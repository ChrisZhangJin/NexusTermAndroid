//! 把 Rust 侧「看不见的死亡」接到 logcat 上。
//!
//! 背景：这个 crate 在 `ssh::` 里到处写 `tracing::info!/warn!/trace!`，其中
//! `ssh/session.rs` 读循环末尾那句
//! `tracing::info!(session, reason, code, "会话结束")` 正是「会话为什么断」
//! 的唯一记录。但在 app 里**没有任何 subscriber**（`tracing_subscriber::fmt()`
//! 只出现在 `ssh/mod.rs` 的 `#[cfg(test)]` 里），于是这些事件全部被丢弃；
//! `ssh/session.rs` 顶部那条「宿主会装好 tracing-android」的注释，一直没有
//! 人去兑现。同样地，`Cargo.toml` 里 `panic = "unwind"`，tokio 任务（包括
//! russh 的那条）panic 之后只会把 `JoinError` 塞回 `JoinHandle`，默认 panic
//! hook 往 stderr 写一行——而 Android app 进程的 stderr 等于 `/dev/null`。
//! 结果就是：russh 任务一死，读循环 `break`，`data_rx` 被 drop，下一次
//! `write()` 撞上关闭的队列返回 `Channel("输入通道已关闭")`，Kotlin 侧抛出
//! 未捕获异常把进程带走——而**死因一个字都没留下**。
//!
//! 这个模块补上两件事，并且都做成幂等（`Once`），因为 Activity 重建、
//! 进程内多次进入都可能重复调用：
//!
//! 1. [`install_panic_hook`]：panic 时把 payload、线程名、`#[track_caller]`
//!    位置和 backtrace 直接写进 logcat。
//! 2. [`install_tracing`]：把 `tracing` 事件桥到 logcat。
//!
//! ## 为什么不用 `tracing-android`
//!
//! `tracing-android` 最后一次发布是 0.2.0（2021），它自己也只是
//! 「`android_log-sys` + 一个 `MakeWriter`」这么薄的一层。引它要多拉两个
//! crate（`tracing-android`、`android_log-sys`），而 `tracing-subscriber`
//! 的 `fmt` 层**已经是本 crate 的依赖**（`Cargo.toml` 里带 `env-filter`，
//! 默认 features 把 `fmt`/`registry` 都打开了）。所以这里直接实现一个
//! `MakeWriter`，用 `extern "C"` 声明 NDK 的 `__android_log_write`——
//! **新增依赖数为 0**，同时还能通过 `make_writer_for(&Metadata)` 把
//! `tracing` 的 Level 精确映射到 logcat 的优先级（`tracing-android` 也是
//! 这么做的）。唯一的代价是多了下面这几十行，换来的是不再引入一个四年
//! 没人维护的 crate。

use std::sync::Once;

/// logcat 里统一的 tag。取一个在整机日志里绝不会撞车、好 grep 的名字。
pub const LOG_TAG: &str = "NexusRust";

/// 没有 `RUST_LOG` 时的默认过滤器。
///
/// `info` 打底保证第三方 crate（russh 等）只出关键信息，`nexus_core=debug`
/// 让本 crate 的会话生命周期日志（含「会话结束」那条）必定可见。调到
/// `nexus_core=trace` 可以额外看到读循环里未处理的通道消息。
const DEFAULT_FILTER: &str = "info,nexus_core=debug";

static INIT: Once = Once::new();

/// 幂等地装好 panic hook + tracing subscriber。
///
/// 可以重复调用任意多次：`Once` 保证真正的安装只发生一次，后续调用是空操作
/// （这点很重要——`lib.rs` 既在导出的 `init_logging()` 里调它，也在
/// `runtime()` 的惰性初始化里兜底调一次）。
///
/// `filter` 为 `None` 时按 `RUST_LOG` → [`DEFAULT_FILTER`] 的顺序取值。
pub fn init(filter: Option<String>) {
    INIT.call_once(|| {
        install_panic_hook();
        install_tracing(filter);
        tracing::info!(
            target: "nexus_core",
            tag = LOG_TAG,
            "nexus-core 日志已接入（panic hook + tracing subscriber 均已安装）"
        );
    });
}

// ───────────────────────────── panic hook ─────────────────────────────

fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        // payload 的两种常见形态：`panic!("literal")` 给 &str，
        // `panic!("{}", x)` 给 String。两个都取不到就只能认命。
        let payload = info
            .payload()
            .downcast_ref::<&str>()
            .map(|s| (*s).to_string())
            .or_else(|| info.payload().downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "<无法识别的 panic payload>".to_string());

        let location = info
            .location()
            .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
            .unwrap_or_else(|| "<未知位置>".to_string());

        let thread = std::thread::current();
        let thread_name = thread.name().unwrap_or("<未命名线程>").to_string();

        // `force_capture()` 而不是 `capture()`：后者要看 `RUST_BACKTRACE`，
        // 而 Android app 进程里没人能设这个环境变量。std 自带，不引新依赖。
        //
        // 注意：release profile 原本是 `strip = true`，那样抓到的栈只有裸
        // 地址，毫无用处——为此 Cargo.toml 已改成 `strip = "debuginfo"`
        // （保留符号表、丢掉 DWARF），见那里的注释。
        let backtrace = std::backtrace::Backtrace::force_capture();

        let message = format!(
            "☠️ Rust panic\n  线程: {thread_name}\n  位置: {location}\n  信息: {payload}\n  backtrace:\n{backtrace}"
        );

        // 故意**不**走 `tracing::error!`：panic 有可能就发生在 subscriber
        // 自己身上（或者 hook 比 subscriber 先装好），那时再进 tracing 会
        // 二次 panic / 递归。直接怼 liblog 是这里唯一不会反咬一口的路径。
        write_raw(Priority::Fatal, &message);
    }));
}

// ──────────────────────── tracing → logcat 桥 ────────────────────────

fn install_tracing(filter: Option<String>) {
    use tracing_subscriber::EnvFilter;

    let env_filter = match filter {
        Some(directives) => EnvFilter::new(directives),
        None => EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(DEFAULT_FILTER)),
    };

    // `try_init` 而不是 `init`：进程里可能已经有别人（比如 `cargo test`
    // 里的某个用例）装过全局 subscriber，那不该 panic。
    let _ = tracing_subscriber::fmt()
        .with_env_filter(env_filter)
        // 时间戳、颜色、线程 id 全关：logcat 自己就带时间、pid/tid 和
        // 优先级着色，再打一遍只是把每行撑长、把 4K 上限提前吃掉。
        .with_ansi(false)
        .without_time()
        .with_level(false)
        .with_target(true)
        .with_writer(LogcatMakeWriter)
        .try_init();
}

/// 每条事件现做一个 writer；真正的落地发生在 `Drop`。
///
/// `tracing_subscriber::fmt` 的调用约定是「为一条事件取一个 writer → 把
/// 整行格式化进去 → 丢掉它」，所以 `Drop` 恰好就是「这条事件写完了」的
/// 时刻，也是把累积的字节一次性交给 `__android_log_write` 的地方——
/// 逐次 `write()` 直接转发会把一行事件拆成好几条 logcat 记录。
struct LogcatMakeWriter;

impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for LogcatMakeWriter {
    type Writer = LogcatWriter;

    fn make_writer(&'a self) -> Self::Writer {
        LogcatWriter::new(Priority::Info)
    }

    /// 有 metadata 就按事件自己的 Level 定 logcat 优先级，这样
    /// `adb logcat NexusRust:W` 这类按级别过滤才真的管用。
    fn make_writer_for(&'a self, meta: &tracing::Metadata<'_>) -> Self::Writer {
        LogcatWriter::new(Priority::from_level(*meta.level()))
    }
}

struct LogcatWriter {
    priority: Priority,
    buf: Vec<u8>,
}

impl LogcatWriter {
    fn new(priority: Priority) -> Self {
        Self {
            priority,
            buf: Vec::with_capacity(256),
        }
    }
}

impl std::io::Write for LogcatWriter {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        self.buf.extend_from_slice(data);
        Ok(data.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl Drop for LogcatWriter {
    fn drop(&mut self) {
        if self.buf.is_empty() {
            return;
        }
        // fmt 层每行末尾带 '\n'，logcat 自己会分行，留着只会多出一条空行。
        let text = String::from_utf8_lossy(&self.buf);
        write_raw(self.priority, text.trim_end_matches('\n'));
    }
}

// ─────────────────────────── liblog 直连 ───────────────────────────

/// logcat 的优先级常量（NDK `android/log.h` 里的 `android_LogPriority`）。
#[derive(Clone, Copy)]
enum Priority {
    Trace = 2, // ANDROID_LOG_VERBOSE
    Debug = 3,
    Info = 4,
    Warn = 5,
    Error = 6,
    Fatal = 7,
}

impl Priority {
    fn from_level(level: tracing::Level) -> Self {
        match level {
            tracing::Level::TRACE => Priority::Trace,
            tracing::Level::DEBUG => Priority::Debug,
            tracing::Level::INFO => Priority::Info,
            tracing::Level::WARN => Priority::Warn,
            tracing::Level::ERROR => Priority::Error,
        }
    }
}

/// 单条 logcat 记录的上限约 4068 字节（含 tag 和结尾的 NUL）。超了会被
/// **直接截断**，而 panic 的 backtrace 轻松就能超——所以这里自己分片。
/// 留足余量取 3000。
const MAX_CHUNK: usize = 3000;

/// 把一段文本写进 logcat，必要时分片。
///
/// 分片点必须落在 UTF-8 字符边界上（`CString` 不在乎，但从中间切断多字节
/// 字符会让 logcat 显示成乱码——本 crate 的日志文案全是中文，这不是理论
/// 问题）。另外按行切比按字节硬切好读，所以优先在换行处断开。
fn write_raw(priority: Priority, text: &str) {
    for chunk in split_chunks(text, MAX_CHUNK) {
        emit(priority, chunk);
    }
}

fn split_chunks(text: &str, max: usize) -> Vec<&str> {
    if text.len() <= max {
        return vec![text];
    }
    let mut out = Vec::new();
    let mut rest = text;
    while rest.len() > max {
        // 先找 max 以内最后一个换行；没有就退化成「最后一个合法字符边界」。
        let hard = (0..=max).rev().find(|i| rest.is_char_boundary(*i)).unwrap_or(0);
        let cut = rest[..hard].rfind('\n').map(|i| i + 1).unwrap_or(hard);
        // `cut == 0` 只可能出现在 max 小到连一个字符都放不下时，
        // 那样会死循环，用 hard 兜底。
        let cut = if cut == 0 { hard.max(1) } else { cut };
        out.push(&rest[..cut]);
        rest = &rest[cut..];
    }
    if !rest.is_empty() {
        out.push(rest);
    }
    out
}

#[cfg(target_os = "android")]
fn emit(priority: Priority, text: &str) {
    use std::ffi::CString;

    // 文本里出现内嵌 NUL 的话 `CString::new` 会失败；那就把 NUL 换掉再写，
    // 总比整条日志消失强。
    let tag = match CString::new(LOG_TAG) {
        Ok(t) => t,
        Err(_) => return,
    };
    let msg = CString::new(text.replace('\0', "\u{fffd}")).unwrap_or_else(|_| {
        CString::new("<日志内容无法转成 CString>").expect("字面量里没有 NUL")
    });

    // SAFETY: 两个指针都来自上面还活着的 `CString`，在调用期间有效且以
    // NUL 结尾；`__android_log_write` 不会保留它们（内部同步写进 logd）。
    unsafe {
        __android_log_write(priority as std::os::raw::c_int, tag.as_ptr(), msg.as_ptr());
    }
}

// NDK liblog 的 C 入口。手写 `extern` 声明而不是引 `android_log-sys`：
// 就这一个符号，不值得为它多一个依赖。`#[link(name = "log")]` 负责把
// `-llog` 加进链接命令（cargo-ndk 已经把 NDK 的 sysroot 放在搜索路径里）。
// 用 `//` 而不是 `///`：rustdoc 不给 extern 块生成文档，会报 unused_doc_comment。
#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(
        prio: std::os::raw::c_int,
        tag: *const std::os::raw::c_char,
        text: *const std::os::raw::c_char,
    ) -> std::os::raw::c_int;
}

/// 非 Android 目标（`cargo test` / 宿主上的 `cargo check`）上没有 liblog，
/// 退化成 stderr。保留同样的调用形状，让这个模块能在宿主上被测到。
#[cfg(not(target_os = "android"))]
fn emit(priority: Priority, text: &str) {
    let level = match priority {
        Priority::Trace => "TRACE",
        Priority::Debug => "DEBUG",
        Priority::Info => "INFO",
        Priority::Warn => "WARN",
        Priority::Error => "ERROR",
        Priority::Fatal => "FATAL",
    };
    eprintln!("[{LOG_TAG}/{level}] {text}");
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 分片必须切在字符边界上：本 crate 的日志几乎全是中文（三字节字符），
    /// 按裸字节切会产生乱码。
    #[test]
    fn split_chunks_respects_char_boundaries() {
        let text = "会话结束".repeat(100); // 1200 字节，没有换行
        let chunks = split_chunks(&text, 100);
        assert!(chunks.len() > 1, "应当被切成多片");
        // 能重新拼回原文 => 每一刀都落在合法边界上（否则切片本身就 panic 了）
        assert_eq!(chunks.concat(), text);
        for c in &chunks {
            assert!(c.len() <= 100 || chunks.len() == 1);
        }
    }

    /// 有换行时优先在行尾断开，这样 backtrace 不会被拦腰砍断。
    #[test]
    fn split_chunks_prefers_newlines() {
        let text = (0..50).map(|i| format!("第 {i} 帧\n")).collect::<String>();
        let chunks = split_chunks(&text, 100);
        assert_eq!(chunks.concat(), text);
        for c in chunks.iter().take(chunks.len() - 1) {
            assert!(c.ends_with('\n'), "非末片应当以换行结尾，实际: {c:?}");
        }
    }

    /// 短文本不该被动过。
    #[test]
    fn split_chunks_passes_short_text_through() {
        assert_eq!(split_chunks("短", 3000), vec!["短"]);
    }

    /// 重复 init 必须是空操作，不能 panic（Activity 重建会重复进来）。
    #[test]
    fn init_is_idempotent() {
        init(Some("off".into()));
        init(Some("off".into()));
        init(None);
    }
}
