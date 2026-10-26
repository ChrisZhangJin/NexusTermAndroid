//! SFTP：从 NexusTerm 桌面版 `src-tauri/src/sftp/mod.rs` port 过来，但做了
//! 两处刻意的改动（都在本模块注释里标出）：
//!
//! 1. **不复用终端连接**。桌面版的 `SftpManager::connect` 会先尝试在同一配置下
//!    已认证的终端连接上开一条 `sftp` 子系统通道，省掉对 OTP 服务器的二次验证。
//!    这一期 Android 版本没有那张"已认证连接"登记表（见 `ssh::session::SharedConns`
//!    从未在这里被引用），`sftp_connect` 永远独立拨号、独立认证——复用是后续
//!    里程碑的工作（需要先把 `connect()` 的句柄也登记进一张共享表）。
//! 2. **上传/下载不再用 `tokio::io::copy`**。原来一行 `io::copy(&mut src, &mut file)`
//!    拿不到进度、也没法取消。这里换成一个显式的 64KB 分片循环，每片之后检查
//!    取消标记、回调一次进度——`SftpProgressListener` 是本模块新增的
//!    callback_interface，终端那条路径没有等价物。

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex, OnceLock};
use std::time::Duration;

use russh_sftp::client::SftpSession;
use serde::Serialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::Mutex as AsyncMutex;

use crate::errors;
use crate::listener::TerminalEventListener;
use crate::ssh::session::{AuthContext, AuthMethod};
use crate::ssh::{self, KnownHosts, LegacyPolicy, OpenError};

/// 单个远端目录项。与桌面版的 `RemoteEntry` 同构，字段名/形状不变——
/// Kotlin 侧没有现成前端约定需要对齐，但保持同构方便将来对照桌面版排障。
#[derive(Debug, Clone, Serialize, uniffi::Record)]
pub struct RemoteEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub size: u64,
    pub mtime: Option<u64>,
    pub permissions: Option<u32>,
}

/// 一次 `sftp_connect` 成功后返回给 Kotlin 的句柄。
#[derive(Debug, Clone, uniffi::Record)]
pub struct SftpHandle {
    pub handle_id: String,
    /// 是否协商到了 SHA-1 系列的旧 KEX，语义同 `SessionHandle::legacy_crypto`。
    pub legacy_crypto: bool,
}

/// SFTP 操作的错误类型。与 `SessionError`（`SshError`）同构：内容已是完整的
///中文提示，`Display` 不再叠加前缀。
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SftpError {
    #[error("{0}")]
    Connect(String),
    #[error("{0}")]
    Auth(String),
    #[error("服务器未启用 SFTP 子系统: {0}")]
    Subsystem(String),
    #[error("远端操作失败: {0}")]
    Remote(String),
    #[error("连接不存在: {0}")]
    NotFound(String),
    #[error("传输已取消")]
    Cancelled,
}

/// Kotlin 侧需要实现的上传/下载进度回调。
///
/// 方法同步调用（与 `TerminalEventListener` 一样是 UniFFI 回调接口的通用约束），
/// 从 Rust 的 tokio 任务里直接触发——Kotlin 实现不应在这几个方法里做耗时操作。
#[uniffi::export(callback_interface)]
pub trait SftpProgressListener: Send + Sync {
    fn on_progress(&self, transfer_id: String, bytes_done: u64, bytes_total: u64);
    fn on_complete(&self, transfer_id: String);
    fn on_error(&self, transfer_id: String, message: String);
}

/// 每条 SFTP 连接的登记表：句柄 id → 已协商好的 SFTP 会话。
///
/// 用 `tokio::sync::Mutex`（不是 `parking_lot`）是因为持锁期间要跨 `.await`
/// 调用 russh-sftp 的异步方法（`list`/`mkdir`/...）；`parking_lot::Mutex` 的
/// guard 不是 `Send`，没法跨 await point 持有。
struct Registry {
    sessions: AsyncMutex<HashMap<String, SftpSession>>,
}

fn registry() -> &'static Registry {
    static REGISTRY: OnceLock<Registry> = OnceLock::new();
    REGISTRY.get_or_init(|| Registry {
        sessions: AsyncMutex::new(HashMap::new()),
    })
}

/// 进行中传输的取消标记登记表：transfer_id → 共享的取消旗标。
///
/// 用普通 `std::sync::Mutex` 就够——这里只做"插入/查找/删除一个 Arc"，
/// 不持锁跨 await。
fn transfers() -> &'static StdMutex<HashMap<String, Arc<AtomicBool>>> {
    static TRANSFERS: OnceLock<StdMutex<HashMap<String, Arc<AtomicBool>>>> = OnceLock::new();
    TRANSFERS.get_or_init(|| StdMutex::new(HashMap::new()))
}

fn new_handle_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

fn new_transfer_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// 把握手/认证失败翻译成 `SftpError`，与 `ssh::session::SessionManager::connect`
/// 内联的那份 match 同构（没有抽公共函数：两边各自的错误枚举不同，硬共享反而
/// 要再包一层转换，不值得）。
fn map_open_err(host: &str, port: u16, timeout: Duration, e: OpenError) -> SftpError {
    match e {
        OpenError::Timeout => SftpError::Connect(errors::compose(
            &errors::timeout_message(host, port, timeout),
            &format!("等待握手超过 {} 秒", timeout.as_secs()),
        )),
        OpenError::HostKeyMismatch => {
            SftpError::Auth("主机公钥指纹不匹配，疑似中间人攻击".into())
        }
        OpenError::Handshake { err, legacy_tried } => {
            SftpError::Connect(errors::handshake_error(host, port, &err, legacy_tried))
        }
        OpenError::Auth(msg) => SftpError::Auth(msg),
    }
}

/// 独立拨号 + 认证，再在同一条连接上开 `sftp` 子系统。
///
/// 不复用任何既有终端连接（见模块顶部说明①），`listener` 仅用于二次验证/私钥
/// 口令问询——SFTP 操作本身没有"终端输出"这个概念，`on_data`/`on_pulse` 不会
/// 被这条路径触发。
#[uniffi::export]
pub fn sftp_connect(
    host: String,
    port: u16,
    username: String,
    auth: AuthMethod,
    listener: Box<dyn TerminalEventListener>,
) -> Result<SftpHandle, SftpError> {
    let listener: Arc<dyn TerminalEventListener> = Arc::from(listener);
    let id = new_handle_id();
    let timeout = errors::resolve_timeout(None);
    let known_hosts = Arc::new(KnownHosts::new());
    let prompts = Arc::new(ssh::prompt::AuthPrompts::new());

    let opened = crate::runtime()
        .block_on(ssh::open_authenticated(
            &host,
            port,
            known_hosts,
            timeout,
            LegacyPolicy::Auto,
            None, // SFTP 是短交互，探活包反而可能干扰老服务器——与桌面版一致
            &username,
            &auth,
            &id,
            AuthContext {
                listener: listener.clone(),
                prompts,
            },
        ))
        .map_err(|e| map_open_err(&host, port, timeout, e))?;

    let legacy_crypto = opened.legacy;
    let session = opened.session;

    let sftp = crate::runtime().block_on(async {
        let channel = session
            .channel_open_session()
            .await
            .map_err(|e| SftpError::Connect(e.to_string()))?;
        channel
            .request_subsystem(true, "sftp")
            .await
            .map_err(|e| SftpError::Subsystem(e.to_string()))?;
        SftpSession::new(channel.into_stream())
            .await
            .map_err(|e| SftpError::Subsystem(e.to_string()))
    })?;

    crate::runtime()
        .block_on(registry().sessions.lock())
        .insert(id.clone(), sftp);

    Ok(SftpHandle {
        handle_id: id,
        legacy_crypto,
    })
}

/// 列出远端目录。目录在前、名称升序——与桌面版 `SftpManager::list` 同构。
#[uniffi::export]
pub fn sftp_list(handle_id: String, path: String) -> Result<Vec<RemoteEntry>, SftpError> {
    crate::runtime().block_on(async {
        let mut entries = {
            let map = registry().sessions.lock().await;
            let s = map
                .get(&handle_id)
                .ok_or_else(|| SftpError::NotFound(handle_id.clone()))?;
            let dir = s
                .read_dir(path.clone())
                .await
                .map_err(|e| SftpError::Remote(e.to_string()))?;
            dir.map(|e| {
                let meta = e.metadata();
                RemoteEntry {
                    name: e.file_name(),
                    path: e.path(),
                    is_dir: meta.is_dir(),
                    size: meta.size.unwrap_or(0),
                    mtime: meta.mtime.map(|t| t as u64),
                    permissions: meta.permissions,
                }
            })
            .collect::<Vec<_>>()
        };
        entries.sort_by(|a, b| {
            b.is_dir
                .cmp(&a.is_dir)
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        Ok(entries)
    })
}

#[uniffi::export]
pub fn sftp_mkdir(handle_id: String, path: String) -> Result<(), SftpError> {
    crate::runtime().block_on(async {
        let map = registry().sessions.lock().await;
        let s = map
            .get(&handle_id)
            .ok_or_else(|| SftpError::NotFound(handle_id.clone()))?;
        s.create_dir(path)
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))
    })
}

/// 删除远端条目。桌面版的 `remove` 要调用方额外传 `is_dir`；这里的 UniFFI
/// 签名里没有这个参数（里程碑计划定死的形状），所以先 `metadata` 探测一次
/// 类型，再决定走 `remove_dir` 还是 `remove_file`——多一次往返，换来一个
/// 更不容易被 Kotlin 调用方传错的签名。
#[uniffi::export]
pub fn sftp_remove(handle_id: String, path: String) -> Result<(), SftpError> {
    crate::runtime().block_on(async {
        let map = registry().sessions.lock().await;
        let s = map
            .get(&handle_id)
            .ok_or_else(|| SftpError::NotFound(handle_id.clone()))?;
        let meta = s
            .metadata(path.clone())
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))?;
        if meta.is_dir() {
            s.remove_dir(path).await
        } else {
            s.remove_file(path).await
        }
        .map_err(|e| SftpError::Remote(e.to_string()))
    })
}

#[uniffi::export]
pub fn sftp_rename(handle_id: String, from: String, to: String) -> Result<(), SftpError> {
    crate::runtime().block_on(async {
        let map = registry().sessions.lock().await;
        let s = map
            .get(&handle_id)
            .ok_or_else(|| SftpError::NotFound(handle_id.clone()))?;
        s.rename(from, to)
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))
    })
}

/// 解析远端家目录，用作文件浏览器的起始路径。
#[uniffi::export]
pub fn sftp_home(handle_id: String) -> Result<String, SftpError> {
    crate::runtime().block_on(async {
        let map = registry().sessions.lock().await;
        let s = map
            .get(&handle_id)
            .ok_or_else(|| SftpError::NotFound(handle_id.clone()))?;
        s.canonicalize(".".to_string())
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))
    })
}

/// 把远端文件整个读进内存（小文件预览用），超过 `max` 字节直接拒绝。
/// 与桌面版 `SftpManager::read` 基本原样保留。
#[uniffi::export]
pub fn sftp_read(handle_id: String, path: String, max: u64) -> Result<Vec<u8>, SftpError> {
    crate::runtime().block_on(async {
        let mut file = {
            let map = registry().sessions.lock().await;
            let s = map
                .get(&handle_id)
                .ok_or_else(|| SftpError::NotFound(handle_id.clone()))?;
            let meta = s
                .metadata(path.clone())
                .await
                .map_err(|e| SftpError::Remote(e.to_string()))?;
            if meta.is_dir() {
                return Err(SftpError::Remote(format!("「{path}」是目录")));
            }
            if meta.size.unwrap_or(0) > max {
                return Err(SftpError::Remote(format!(
                    "文件超过 {} MB，不适合阅读",
                    max / 1024 / 1024
                )));
            }
            s.open(path.clone())
                .await
                .map_err(|e| SftpError::Remote(e.to_string()))?
        };
        let mut buf = Vec::new();
        (&mut file)
            .take(max + 1)
            .read_to_end(&mut buf)
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))?;
        let _ = file.close().await;
        if buf.len() as u64 > max {
            return Err(SftpError::Remote("文件过大，不适合阅读".into()));
        }
        Ok(buf)
    })
}

/// 分片拷贝用的缓冲区大小。桌面版用 `tokio::io::copy` 内部的 8KB 默认值；
/// 这里按计划要求放大到 64KB——移动网络下一次系统调用能带更多数据，
/// 同时取消/进度检查粒度仍然足够细（64KB 在 Wi-Fi 下通常 <50ms 一片）。
const CHUNK_SIZE: usize = 64 * 1024;

/// 上传：本地 → 远端。
///
/// **不阻塞调用方**：立刻返回 `transfer_id`，真正的拷贝在 crate 自带的 tokio
/// runtime 上异步跑（与终端读写循环用的是同一个 runtime，见 `lib.rs::runtime()`），
/// Kotlin 侧通过 `progress_listener` 的回调轮询进度/完成/出错。
#[uniffi::export]
pub fn sftp_upload(
    handle_id: String,
    local_path: String,
    remote_path: String,
    progress_listener: Box<dyn SftpProgressListener>,
) -> Result<String, SftpError> {
    let progress_listener: Arc<dyn SftpProgressListener> = Arc::from(progress_listener);
    start_transfer(progress_listener, move |transfer_id, cancel, listener| {
        Box::pin(async move {
            do_upload(&handle_id, &local_path, &remote_path, &transfer_id, &cancel, &listener).await
        })
    })
}

/// 下载：远端 → 本地。形状与 [`sftp_upload`] 镜像对称。
#[uniffi::export]
pub fn sftp_download(
    handle_id: String,
    remote_path: String,
    local_path: String,
    progress_listener: Box<dyn SftpProgressListener>,
) -> Result<String, SftpError> {
    let progress_listener: Arc<dyn SftpProgressListener> = Arc::from(progress_listener);
    start_transfer(progress_listener, move |transfer_id, cancel, listener| {
        Box::pin(async move {
            do_download(&handle_id, &remote_path, &local_path, &transfer_id, &cancel, &listener).await
        })
    })
}

type TransferFut = std::pin::Pin<Box<dyn std::future::Future<Output = Result<(), SftpError>> + Send>>;

/// 公共的"分配 id、登记取消标记、丢进 runtime 后台跑、跑完回调 complete/error、
/// 收尾摘掉登记表"骨架——upload 和 download 的唯一区别是分片循环里谁读谁写，
/// 其余（id 分配、取消登记、回调分发）完全一样，抽出来避免两份拷贝漂移。
fn start_transfer<F>(
    progress_listener: Arc<dyn SftpProgressListener>,
    run: F,
) -> Result<String, SftpError>
where
    F: FnOnce(String, Arc<AtomicBool>, Arc<dyn SftpProgressListener>) -> TransferFut + Send + 'static,
{
    let transfer_id = new_transfer_id();
    let cancel = Arc::new(AtomicBool::new(false));
    transfers()
        .lock()
        .unwrap()
        .insert(transfer_id.clone(), cancel.clone());

    let tid = transfer_id.clone();
    let listener_for_task = progress_listener.clone();
    crate::runtime().spawn(async move {
        let result = run(tid.clone(), cancel, listener_for_task.clone()).await;
        transfers().lock().unwrap().remove(&tid);
        match result {
            Ok(()) => listener_for_task.on_complete(tid),
            Err(SftpError::Cancelled) => listener_for_task.on_error(tid, "传输已取消".into()),
            Err(e) => listener_for_task.on_error(tid, e.to_string()),
        }
    });

    Ok(transfer_id)
}

async fn do_upload(
    handle_id: &str,
    local_path: &str,
    remote_path: &str,
    transfer_id: &str,
    cancel: &Arc<AtomicBool>,
    listener: &Arc<dyn SftpProgressListener>,
) -> Result<(), SftpError> {
    let mut remote_file = {
        let map = registry().sessions.lock().await;
        let s = map
            .get(handle_id)
            .ok_or_else(|| SftpError::NotFound(handle_id.to_string()))?;
        s.create(remote_path.to_string())
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))?
    };

    let mut local = tokio::fs::File::open(local_path)
        .await
        .map_err(|e| SftpError::Remote(format!("无法读取本地文件 {}: {}", local_path, e)))?;
    let total = local.metadata().await.map(|m| m.len()).unwrap_or(0);

    let mut buf = vec![0u8; CHUNK_SIZE];
    let mut done: u64 = 0;
    loop {
        if cancel.load(Ordering::Relaxed) {
            let _ = remote_file.close().await;
            return Err(SftpError::Cancelled);
        }
        let n = local
            .read(&mut buf)
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))?;
        if n == 0 {
            break;
        }
        remote_file
            .write_all(&buf[..n])
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))?;
        done += n as u64;
        listener.on_progress(transfer_id.to_string(), done, total);
    }

    remote_file
        .flush()
        .await
        .map_err(|e| SftpError::Remote(e.to_string()))?;
    remote_file
        .close()
        .await
        .map_err(|e| SftpError::Remote(e.to_string()))?;
    Ok(())
}

async fn do_download(
    handle_id: &str,
    remote_path: &str,
    local_path: &str,
    transfer_id: &str,
    cancel: &Arc<AtomicBool>,
    listener: &Arc<dyn SftpProgressListener>,
) -> Result<(), SftpError> {
    let (mut remote_file, total) = {
        let map = registry().sessions.lock().await;
        let s = map
            .get(handle_id)
            .ok_or_else(|| SftpError::NotFound(handle_id.to_string()))?;
        let total = s
            .metadata(remote_path.to_string())
            .await
            .map(|m| m.size.unwrap_or(0))
            .unwrap_or(0);
        let file = s
            .open(remote_path.to_string())
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))?;
        (file, total)
    };

    let mut out = tokio::fs::File::create(local_path)
        .await
        .map_err(|e| SftpError::Remote(format!("无法创建本地文件 {}: {}", local_path, e)))?;

    let mut buf = vec![0u8; CHUNK_SIZE];
    let mut done: u64 = 0;
    loop {
        if cancel.load(Ordering::Relaxed) {
            let _ = remote_file.close().await;
            return Err(SftpError::Cancelled);
        }
        let n = remote_file
            .read(&mut buf)
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])
            .await
            .map_err(|e| SftpError::Remote(e.to_string()))?;
        done += n as u64;
        listener.on_progress(transfer_id.to_string(), done, total);
    }

    out.flush()
        .await
        .map_err(|e| SftpError::Remote(e.to_string()))?;
    let _ = remote_file.close().await;
    Ok(())
}

/// 取消一次正在进行的传输。幂等地"找不到也不报错"会比这里更宽松，但传输
/// id 是本模块自己生成、自己派发的，调用方传一个野 id 大概率是编程错误——
/// 报错比静默吞掉更容易在联调时发现问题。
#[uniffi::export]
pub fn sftp_cancel_transfer(transfer_id: String) -> Result<(), SftpError> {
    let map = transfers().lock().unwrap();
    match map.get(&transfer_id) {
        Some(flag) => {
            flag.store(true, Ordering::Relaxed);
            Ok(())
        }
        None => Err(SftpError::NotFound(transfer_id)),
    }
}

/// 关闭一条 SFTP 连接。幂等：句柄已经不存在也不报错，语义与
/// `ssh::session::SessionManager::close` 一致。
#[uniffi::export]
pub fn sftp_close(handle_id: String) -> Result<(), SftpError> {
    crate::runtime().block_on(async {
        if let Some(s) = registry().sessions.lock().await.remove(&handle_id) {
            let _ = s.close().await;
        }
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 取消标记的登记/查找/摘除是这个模块唯一一段不需要真实网络就能验证的
    /// 逻辑——其余所有导出函数都要一条活的 `SftpSession`，必须对真实 sshd
    /// 才能跑（见下面 `#[ignore]` 的集成测试）。
    #[test]
    fn cancel_unknown_transfer_errors() {
        let err = sftp_cancel_transfer("not-a-real-id".into());
        assert!(matches!(err, Err(SftpError::NotFound(_))));
    }

    #[test]
    fn cancel_known_transfer_flips_flag() {
        let id = "test-transfer".to_string();
        let flag = Arc::new(AtomicBool::new(false));
        transfers().lock().unwrap().insert(id.clone(), flag.clone());

        assert!(sftp_cancel_transfer(id.clone()).is_ok());
        assert!(flag.load(Ordering::Relaxed));

        transfers().lock().unwrap().remove(&id);
    }

    #[test]
    fn operations_on_unknown_handle_report_not_found() {
        let err = sftp_list("not-a-real-handle".into(), "/".into());
        assert!(matches!(err, Err(SftpError::NotFound(_))));

        let err = sftp_home("not-a-real-handle".into());
        assert!(matches!(err, Err(SftpError::NotFound(_))));
    }

    /// 端到端：对一台真实 sshd 完整走一遍 connect → mkdir → upload → list →
    /// download → read → rename → remove → close。
    ///
    /// 默认 `#[ignore]`，与仓库里既有的 ssh 探针测试（`ssh/mod.rs` 的
    /// `probe_*` 系列）同一套约定：环境变量提供目标主机，不在 CI 上发起
    /// 外部连接。
    ///
    /// ```text
    /// NEXUS_SFTP_HOST=1.2.3.4 NEXUS_SFTP_USER=root NEXUS_SFTP_PASSWORD=*** \
    ///   cargo test --lib sftp:: -- --ignored --nocapture
    /// ```
    #[test]
    #[ignore = "需要真实主机：NEXUS_SFTP_HOST / NEXUS_SFTP_USER / NEXUS_SFTP_PASSWORD"]
    fn full_round_trip_against_real_server() {
        use std::sync::mpsc;

        struct NullListener;
        impl TerminalEventListener for NullListener {
            fn on_data(&self, _session_id: String, _bytes: Vec<u8>) {}
            fn on_pulse(&self, _session_id: String) {}
            fn on_closed(&self, _session_id: String, _reason: String) {}
            fn on_auth_prompt(&self, _prompt: crate::ssh::prompt::AuthPrompt) -> String {
                String::new()
            }
        }

        struct ChannelProgress(mpsc::Sender<String>);
        impl SftpProgressListener for ChannelProgress {
            fn on_progress(&self, _transfer_id: String, _bytes_done: u64, _bytes_total: u64) {}
            fn on_complete(&self, transfer_id: String) {
                let _ = self.0.send(format!("complete:{transfer_id}"));
            }
            fn on_error(&self, transfer_id: String, message: String) {
                let _ = self.0.send(format!("error:{transfer_id}:{message}"));
            }
        }

        let host = std::env::var("NEXUS_SFTP_HOST").expect("NEXUS_SFTP_HOST");
        let user = std::env::var("NEXUS_SFTP_USER").expect("NEXUS_SFTP_USER");
        let password = std::env::var("NEXUS_SFTP_PASSWORD").expect("NEXUS_SFTP_PASSWORD");
        let port: u16 = std::env::var("NEXUS_SFTP_PORT")
            .ok()
            .and_then(|p| p.parse().ok())
            .unwrap_or(22);

        let handle = sftp_connect(
            host,
            port,
            user,
            AuthMethod::Password { password },
            Box::new(NullListener),
        )
        .expect("连接失败");

        let dir = format!("/tmp/nexus-sftp-test-{}", uuid::Uuid::new_v4());
        sftp_mkdir(handle.handle_id.clone(), dir.clone()).expect("mkdir 失败");

        let local_src = std::env::temp_dir().join(format!("nexus-sftp-src-{}", uuid::Uuid::new_v4()));
        std::fs::write(&local_src, b"hello from nexus-core sftp test").unwrap();

        let (tx, rx) = mpsc::channel();
        let remote_file = format!("{dir}/hello.txt");
        let transfer_id = sftp_upload(
            handle.handle_id.clone(),
            local_src.to_string_lossy().to_string(),
            remote_file.clone(),
            Box::new(ChannelProgress(tx)),
        )
        .expect("启动上传失败");
        let msg = rx.recv_timeout(Duration::from_secs(10)).expect("上传没有在超时内完成");
        assert_eq!(msg, format!("complete:{transfer_id}"));

        let entries = sftp_list(handle.handle_id.clone(), dir.clone()).expect("list 失败");
        assert!(entries.iter().any(|e| e.name == "hello.txt"));

        let content = sftp_read(handle.handle_id.clone(), remote_file.clone(), 1024).unwrap();
        assert_eq!(content, b"hello from nexus-core sftp test");

        let renamed = format!("{dir}/hello2.txt");
        sftp_rename(handle.handle_id.clone(), remote_file.clone(), renamed.clone())
            .expect("rename 失败");

        let local_dst = std::env::temp_dir().join(format!("nexus-sftp-dst-{}", uuid::Uuid::new_v4()));
        let (tx2, rx2) = mpsc::channel();
        let dl_id = sftp_download(
            handle.handle_id.clone(),
            renamed.clone(),
            local_dst.to_string_lossy().to_string(),
            Box::new(ChannelProgress(tx2)),
        )
        .expect("启动下载失败");
        let msg2 = rx2.recv_timeout(Duration::from_secs(10)).expect("下载没有在超时内完成");
        assert_eq!(msg2, format!("complete:{dl_id}"));
        assert_eq!(std::fs::read(&local_dst).unwrap(), b"hello from nexus-core sftp test");

        sftp_remove(handle.handle_id.clone(), renamed).expect("remove 文件失败");
        sftp_remove(handle.handle_id.clone(), dir).expect("remove 目录失败");
        sftp_close(handle.handle_id).expect("close 失败");

        let _ = std::fs::remove_file(local_src);
        let _ = std::fs::remove_file(local_dst);
    }
}
