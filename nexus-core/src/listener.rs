//! Rust → Kotlin 的事件回调边界。
//!
//! 桌面版用 `tauri::AppHandle::emit(...)` 把四类事件（终端数据、keepalive
//! 脉搏、会话关闭、二次验证问询）丢给前端；Android 版没有 Tauri 的事件总线，
//! 改用 UniFFI 的 `callback_interface`——Kotlin 侧实现这个 trait 并在
//! `connect()` 时把实例传进来，Rust 核心直接同步调用。

use std::sync::Arc;

use crate::ssh::prompt::AuthPrompt;

/// Kotlin 侧需要实现的监听者。四个方法分别对应原来的四个 `app.emit` 调用点：
/// - `on_data`      ← `ssh/session.rs` 的 `nx://term/data/<id>`
/// - `on_pulse`     ← `ssh/mod.rs` 的 `nx://term/pulse/<id>`（服务端 keepalive）
/// - `on_closed`    ← `ssh/session.rs` 的 `nx://term/closed/<id>`
/// - `on_auth_prompt` ← `ssh/prompt.rs` 的 `nx://auth/prompt`
///
/// UniFFI 的回调接口方法是同步的，因此这些调用都从 Rust 的 tokio 任务中
/// 直接触发（必要时经 `spawn_blocking`），Kotlin 侧的实现不应长时间阻塞。
#[uniffi::export(callback_interface)]
pub trait TerminalEventListener: Send + Sync {
    /// 收到一段终端输出。`bytes` 是原始字节（未做 UTF-8 校验，可能切断多字节
    /// 字符），Kotlin 侧负责按 UTF-8/宽松解码喂给 xterm.js。
    fn on_data(&self, session_id: String, bytes: Vec<u8>);

    /// 服务端 keepalive 到达，前端据此画一次"心跳"动效。
    fn on_pulse(&self, session_id: String);

    /// 会话结束（正常退出 / 连接丢失 / 用户主动断开）。
    fn on_closed(&self, session_id: String, reason: String);

    /// 二次验证 / 私钥口令问询。要求**同步**返回答案：
    /// - 多个字段用 `\n` 连接，顺序与 `prompt.fields` 一致；
    /// - 空字符串表示用户取消。
    fn on_auth_prompt(&self, prompt: AuthPrompt) -> String;
}

/// 一条连接运行期间所需的宿主上下文：就是监听者的一个共享引用。
/// 单独包一层是为了后续扩展（例如附带会话 id、日志目录）不必改所有签名。
#[derive(Clone)]
pub struct SessionContext {
    pub listener: Arc<dyn TerminalEventListener>,
}

impl SessionContext {
    pub fn new(listener: Arc<dyn TerminalEventListener>) -> Self {
        Self { listener }
    }
}
