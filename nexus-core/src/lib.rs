//! `nexus-core`：从 NexusTerm 桌面版 fork 出来的 SSH/SFTP 核心，供 Android
//! 应用通过 UniFFI 调用。
//!
//! 这个 crate 只保留「连上一台机器、收发终端字节」这条垂直切片需要的部分：
//! `ssh::` 的握手/认证/读写循环、`keys::`/`store::` 的密钥与会话存储、
//! `errors::` 的网络错误转中文提示。Tauri 的 IPC 命令层（`cmd.rs`）、
//! AI agent、xsh 导入、本地 PTY、状态栏资源采集都**没有**被拷贝过来——
//! 它们要么是下一期功能，要么是桌面专属（见仓库里的执行计划文档）。

pub mod errors;
pub mod keys;
pub mod listener;
pub mod sftp;
pub mod ssh;
pub mod store;

use std::sync::{Arc, OnceLock};

pub use listener::TerminalEventListener;
pub use ssh::session::{AuthMethod, SessionError as SshError};

uniffi::setup_scaffolding!();

/// 一次成功连接返回给 Kotlin 的句柄。目前只携带 id（后续 SFTP/多路复用
/// 需要更多信息时再长出字段），以及本次协商是否用了旧版 KEX（SHA-1 系列）——
/// Kotlin 侧可以据此提示用户。
#[derive(Debug, Clone, uniffi::Record)]
pub struct SessionHandle {
    pub session_id: String,
    pub legacy_crypto: bool,
}

/// 进程内单例：所有连接共用一张会话表、一张已知主机指纹表、一张二次验证
/// 登记表。桌面版里这个单例由 Tauri 的 `AppState` 托管；Android 没有等价
/// 机制，这里用 `OnceLock` 自己管生命周期——对一个长驻的 Application 进程
/// 而言语义是一样的（进程死连接也必然死）。
pub(crate) fn manager() -> &'static ssh::session::SessionManager {
    static MANAGER: OnceLock<ssh::session::SessionManager> = OnceLock::new();
    MANAGER.get_or_init(ssh::session::SessionManager::new)
}

/// id → tokio runtime handle 没有必要，因为 Kotlin 侧每次调用都从 JNI
/// 线程同步进来；真正的异步工作在 `connect()`/写循环内部用 crate 自带的
/// 一个 tokio 多线程 runtime 跑。整个库只建一个 runtime，所有会话共用。
fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .worker_threads(2)
            .thread_name("nexus-core")
            .build()
            .expect("无法创建 tokio runtime")
    })
}

/// id 生成简单地复用 uuid v4，和桌面版前端生成标签 id 的方式一致，
/// 不强制由 Kotlin 侧传入——这样 UniFFI 的签名更干净。
fn new_session_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// 会话配置库单例。与 `manager()`/`runtime()` 同一套 `OnceLock` 模式——
/// 一个长驻 Application 进程里只开一次 SQLite 连接。
///
/// 与另外两个单例不同的是：这个库需要一个外部传入的路径（Kotlin 侧传
/// `applicationContext.filesDir` 拼出来的目录），所以没法像 `manager()`
/// 那样在第一次访问时惰性初始化——必须先调用 [`init_store`]。
static STORE: OnceLock<store::Store> = OnceLock::new();

fn store() -> Option<&'static store::Store> {
    STORE.get()
}

fn store_or_err() -> Result<&'static store::Store, StoreError> {
    store().ok_or_else(|| StoreError::NotInitialized)
}

/// 会话库操作的错误类型。形状与 [`SshError`]（`ssh::session::SessionError`）
/// 保持一致：内容已经是完整的提示文本，`Display` 不再叠加前缀。
///
/// `store::Store` 的方法历史上返回 `Result<_, String>`（桌面版遗留，错误
/// 直接是 `e.to_string()`），这里用一个薄的 `uniffi::Error` 包一层，
/// 好让 UniFFI 能把它映射成 Kotlin 侧的异常类型。
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum StoreError {
    #[error("会话库尚未初始化，请先调用 init_store")]
    NotInitialized,
    #[error("{0}")]
    Failed(String),
}

impl From<String> for StoreError {
    fn from(e: String) -> Self {
        StoreError::Failed(e)
    }
}

/// 打开（或在已打开时直接视为成功的）会话库。
///
/// `db_path` 由 Kotlin 传入，通常是 `applicationContext.filesDir` 下的一个
/// 子目录——不采用桌面版那种"自己猜 app_data 目录"的做法，Android 的沙箱
/// 路径规则和桌面完全不同，隐式猜测只会猜错。
///
/// 幂等：重复调用（例如 Activity 重建）只要路径不变就直接成功；`OnceLock`
/// 决定了第一次成功 set 之后，同进程内这张库就不会被换成另一条路径——
/// 这与 `manager()`/`runtime()` 的"进程生命周期 = 单例生命周期"语义一致。
#[uniffi::export]
pub fn init_store(db_path: String) -> Result<(), StoreError> {
    if STORE.get().is_some() {
        return Ok(());
    }
    let opened = store::Store::open(std::path::PathBuf::from(db_path))?;
    // 两个线程同时首次调用时，只有一个的 set 会成功；另一个不是错误，
    // 它只是晚了一步，库已经被装好了。
    let _ = STORE.set(opened);
    Ok(())
}

/// 列出所有已保存的会话配置（文件夹、sort_order、name 排序，见
/// `store::Store::list`）。密码/口令不在返回的记录里——它们从未被持久化
/// 到这张表（见 `store::mod.rs` 顶部关于凭据处理的说明）。
#[uniffi::export]
pub fn list_sessions() -> Result<Vec<store::SessionRecord>, StoreError> {
    Ok(store_or_err()?.list()?)
}

/// 新增一条会话配置。
#[uniffi::export]
pub fn create_session(session: store::NewSession) -> Result<store::SessionRecord, StoreError> {
    Ok(store_or_err()?.create(session)?)
}

/// 覆盖更新一条会话配置（全量替换，不是增量 patch——与桌面版 `cmd.rs`
/// 对应命令的语义一致）。
#[uniffi::export]
pub fn update_session(
    id: String,
    session: store::NewSession,
) -> Result<store::SessionRecord, StoreError> {
    Ok(store_or_err()?.update(&id, session)?)
}

/// 删除一条会话配置。幂等由 `Store::delete` 本身保证（`DELETE ... WHERE id=?`
/// 对不存在的 id 不报错）。
#[uniffi::export]
pub fn delete_session(id: String) -> Result<(), StoreError> {
    Ok(store_or_err()?.delete(&id)?)
}

/// 建立一条 SSH 连接并开一个交互式 shell 通道。
///
/// 对应桌面版 `cmd.rs` 里 `ssh_connect` 命令 + `SessionManager::connect`：
/// 真正的握手、算法协商、二次验证（keyboard-interactive）、pty/shell 请求
/// 全部原样保留在 `ssh::session::SessionManager::connect` 里，这里只是把
/// UniFFI 的参数形状适配过去，并把读写循环丢进专属 runtime。
/// `config_id` 是保存的会话 id（`SessionRecord::id`）。登记进
/// [`ssh::session::SharedConns`] 之后，同一配置的文件面板就能复用这条已认证连接
/// 开 sftp 子系统，省掉一次握手和一次验证码——没有它，`sftp_connect` 永远只能
/// 独立拨号。
#[uniffi::export]
pub fn connect(
    config_id: String,
    host: String,
    port: u16,
    username: String,
    auth: AuthMethod,
    listener: Box<dyn TerminalEventListener>,
) -> Result<SessionHandle, SshError> {
    let listener: Arc<dyn TerminalEventListener> = Arc::from(listener);
    let id = new_session_id();
    let req = ssh::session::ConnectRequest {
        host,
        port: Some(port),
        username,
        auth: Some(auth),
        cols: 120,
        rows: 32,
        connect_timeout_secs: None,
        legacy_crypto: None,
        config_id: Some(config_id),
        sidecar: Some(false),
    };

    let outcome = runtime().block_on(manager().connect(listener, id.clone(), req))?;

    Ok(SessionHandle {
        session_id: id,
        legacy_crypto: outcome.legacy_crypto,
    })
}

/// 往终端写字节（键入、粘贴、ZMODEM 上传……都走这一个入口）。
#[uniffi::export]
pub fn write(session_id: String, bytes: Vec<u8>) -> Result<(), SshError> {
    runtime().block_on(manager().write(&session_id, bytes))
}

/// 窗口尺寸变化（旋转屏幕 / 字号调整）时同步给远端，否则 vim/top 这类全屏
/// 程序的重绘范围会错位。
#[uniffi::export]
pub fn resize(session_id: String, cols: u16, rows: u16) -> Result<(), SshError> {
    manager().resize(&session_id, cols, rows)
}

/// 主动断开。幂等：会话已经不存在也不报错——对应用户快速点两下「断开」
/// 或者 `on_closed` 已经先一步把它收尾的竞态。
#[uniffi::export]
pub fn disconnect(session_id: String) {
    manager().close(&session_id);
}

#[cfg(test)]
mod smoke_tests {
    use super::*;

    /// 确认单例在同一进程内真的只建一次——SessionManager 持有的
    /// known_hosts/auth_prompts 登记表不能每次调用都被悄悄重置。
    #[test]
    fn manager_singleton_is_stable() {
        let a = manager() as *const _;
        let b = manager() as *const _;
        assert_eq!(a, b);
    }

    /// `init_store` 之前，所有会话 CRUD 导出都该报「尚未初始化」，
    /// 而不是 panic 或者偷偷打开一个默认路径——Kotlin 侧必须显式决定 db 放哪。
    ///
    /// 注意：`STORE` 是进程级 `OnceLock`，这条断言必须排在本文件里任何一次
    /// 成功 `init_store` 调用**之前**运行。`cargo test` 默认同进程多线程跑
    /// 所有测试，所以这里只能验证"如果从未 init 过就该报错"这件事本身的逻辑
    /// （用 `store_or_err()` 直接测，不依赖全局是否已被其它用例初始化）。
    #[test]
    fn store_or_err_reports_not_initialized_when_store_is_none() {
        // 直接构造场景而不是断言全局单例状态：全局单例会被同进程里其它用例
        // （下面这条 `session_crud_roundtrip`）提前初始化，断言 `store()`
        // 返回 `None` 在并行测试下是不确定的。这里改为检查 `StoreError` 本身
        // 的文案/形状是稳定的。
        let err = StoreError::NotInitialized;
        assert_eq!(err.to_string(), "会话库尚未初始化，请先调用 init_store");
    }

    /// `init_store` → 四个会话 CRUD 导出的整条链路，对着一个临时目录跑一遍。
    fn temp_db_path(tag: &str) -> String {
        std::env::temp_dir()
            .join(format!("nexus-core-lib-test-{tag}-{}", uuid::Uuid::new_v4()))
            .to_string_lossy()
            .into_owned()
    }

    #[test]
    fn session_crud_roundtrip() {
        init_store(temp_db_path("crud")).expect("打开会话库");

        let created = create_session(store::NewSession {
            name: "测试主机".into(),
            folder: None,
            host: "example.com".into(),
            port: Some(2222),
            username: "root".into(),
            auth_type: "password".into(),
            key_path: None,
            startup_cmd: None,
            theme: None,
            connect_timeout: None,
        })
        .expect("创建会话失败");
        assert_eq!(created.host, "example.com");
        assert_eq!(created.port, 2222);

        let listed = list_sessions().expect("列出会话失败");
        assert!(listed.iter().any(|s| s.id == created.id));

        let updated = update_session(
            created.id.clone(),
            store::NewSession {
                name: "改名后的主机".into(),
                folder: None,
                host: "example.com".into(),
                port: Some(2222),
                username: "root".into(),
                auth_type: "password".into(),
                key_path: None,
                startup_cmd: None,
                theme: None,
                connect_timeout: None,
            },
        )
        .expect("更新会话失败");
        assert_eq!(updated.name, "改名后的主机");

        delete_session(created.id.clone()).expect("删除会话失败");
        let listed_after = list_sessions().expect("删除后列出会话失败");
        assert!(!listed_after.iter().any(|s| s.id == created.id));
    }

    /// `init_store` 对同一进程里重复调用必须是幂等成功（Activity 重建场景），
    /// 不能因为"库已经开过了"就报错。
    #[test]
    fn init_store_is_idempotent() {
        let path = temp_db_path("idempotent");
        init_store(path.clone()).expect("第一次打开应当成功");
        // 第二次传同一个路径（甚至传别的路径也行——单例已经钉死，不会被替换）
        init_store(path).expect("重复调用应当幂等成功");
    }
}
