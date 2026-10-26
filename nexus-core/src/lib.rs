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
fn manager() -> &'static ssh::session::SessionManager {
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

/// 建立一条 SSH 连接并开一个交互式 shell 通道。
///
/// 对应桌面版 `cmd.rs` 里 `ssh_connect` 命令 + `SessionManager::connect`：
/// 真正的握手、算法协商、二次验证（keyboard-interactive）、pty/shell 请求
/// 全部原样保留在 `ssh::session::SessionManager::connect` 里，这里只是把
/// UniFFI 的参数形状适配过去，并把读写循环丢进专属 runtime。
#[uniffi::export]
pub fn connect(
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
        config_id: None,
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
}
