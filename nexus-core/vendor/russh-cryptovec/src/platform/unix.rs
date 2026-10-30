use std::sync::Once;

use libc::c_void;

/// 整个进程只警告一次。
///
/// `CryptoVec::resize` 在数据流里是**每个 SSH 包都可能命中**的热路径
/// （`client_read_authenticated` → `CryptoVec::from_slice` → `resize`），
/// 一旦 `RLIMIT_MEMLOCK` 卡死，失败就是**每包一次**。若每次都打日志，
/// 一条真实告警会被几千条同样的噪声淹掉，还会反过来拖慢传输。
/// 所以这里用 `Once`：第一次失败讲清楚原因与后果，之后彻底闭嘴。
///
/// 用 `log` 而不是 `tracing`：本 crate 原本就只依赖 `libc`，而 `log` 是
/// 兄弟 crate（`russh` / `russh-keys`）既有的日志门面，宿主已经通过
/// `tracing_subscriber::fmt().try_init()` 装了 `tracing-log` 桥
/// （见 `nexus-core/src/logging.rs`），所以 `log::warn!` 会原样出现在
/// logcat 的 `NexusRust` tag 下。换成 `tracing` 只会多拉一个依赖、
/// 并和上游后续版本的做法分叉（上游 0.62 用的也是 `log`）。
static LOCK_WARNING: Once = Once::new();

/// 第一次 mlock/munlock 失败时，把**原因**和**安全权衡**一次性讲清楚。
///
/// 带上 errno：`Cannot allocate memory (os error 12)` 基本就等于
/// 「`RLIMIT_MEMLOCK` 不够」，这是排障时唯一需要的信息。
fn warn_once(op: &str) {
    let err = std::io::Error::last_os_error();
    LOCK_WARNING.call_once(|| {
        log::warn!(
            "安全提示：操作系统拒绝为密钥缓冲区 {op} 内存（{err}）。\
             密钥材料仍会在释放时被清零，但不再有「禁止换出」的保证。"
        );
        log::warn!(
            "常见原因是 RLIMIT_MEMLOCK 过低（Android 普遍是 64 KB 软硬限制，\
             非 root 进程无法抬高）。该警告只打印一次。"
        );
    });
}

/// Unlock memory on drop for Unix-based systems.
pub fn munlock(ptr: *const u8, len: usize) {
    unsafe {
        if libc::munlock(ptr as *const c_void, len) != 0 {
            // 必须和 `mlock` 一样是非致命的：既然 `mlock` 可能从未成功，
            // 对一块**从未被锁住**的内存调 `munlock` 自然也会失败。
            // 这里要是还 panic，就等于把 `mlock` 的 panic 原样搬到了
            // `Drop` 和 realloc 的 `munlock(old_ptr, ..)` 上——而 `Drop`
            // 里 panic 比原来的毛病更糟（双重 panic 直接 abort 整个进程）。
            warn_once("解锁");
        }
    }
}

pub fn mlock(ptr: *const u8, len: usize) {
    unsafe {
        if libc::mlock(ptr as *const c_void, len) != 0 {
            warn_once("锁定");
        }
    }
}

pub fn memset(ptr: *mut u8, value: i32, size: usize) {
    unsafe {
        libc::memset(ptr as *mut c_void, value, size);
    }
}
