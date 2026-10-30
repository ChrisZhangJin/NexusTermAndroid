# 本目录为什么存在

`vendor/russh-cryptovec` 是 **russh-cryptovec 0.48.0 的原样拷贝** + 一处修补
（`src/platform/unix.rs`：`mlock`/`munlock` 失败不再 panic）。
通过 `nexus-core/Cargo.toml` 的 `[patch.crates-io]` 生效。

**版本号必须保持 0.48.0**：`russh` 0.49.2 和 `russh-keys` 0.49.2 都声明
`russh-cryptovec = "0.48.0"`（即 `>=0.48.0, <0.49.0`）。`[patch]` 要求本地副本
满足依赖方的版本要求，改成 0.50+ 会直接「patch did not resolve to any versions」。

对照文件：`src/platform/unix.rs`（唯一改动的文件）。

## 为什么不是改 `vendor/russh` 的依赖声明

`russh-cryptovec` 有**两个**依赖方：`vendor/russh`（已 vendor）和
`russh-keys` 0.49.2（仍来自 crates.io）。`CryptoVec` 在两边都用，
只改 `vendor/russh` 的依赖声明会漏掉 `russh-keys` 那条路径
（认证阶段的密钥缓冲区正好走它），等于只堵了一半。
`[patch.crates-io]` 是**按 crate 名全局替换**的，一次覆盖所有依赖方——
所以这里新 vendor 一份，而不是动 `vendor/russh`。

## 补丁 1：`mlock` 失败不再是致命的

### 现象

HONOR PTP-AN00（Android 16 / 内核 6.6.118 / API 36）上，SSH 连上约 **51 毫秒**
后必断。Rust 侧 panic：

```
位置: russh-cryptovec-0.48.0/src/platform/unix.rs:17:13
信息: Failed to lock memory
   6: russh_cryptovec::platform::unix::mlock
   7: <russh_cryptovec::cryptovec::CryptoVec>::resize
   8: <russh_cryptovec::cryptovec::CryptoVec>::from_slice
   9: <russh::client::Session>::client_read_authenticated::<nexus_core::ssh::ClientHandler>
```

panic 杀死 russh 的 client 任务 → 读循环的 `channel.wait()` 返回 `None` →
会话以 `reason="lost"` 结束 → 后续 `write()` 报「输入通道已关闭」。

### 根因

该机 `/proc/<pid>/limits` 实测 `Max locked memory  65536  65536  bytes`：
**软硬限制都是 64 KB**。`CryptoVec` 每次扩容都对新缓冲区 `mlock`，
SSH 数据流稍微一跑就越过 64 KB，`libc::mlock` 返回 `ENOMEM`，
上游在非 0 时**无条件 `panic!`**，且没有任何 feature 开关能关掉。

**「启动时提高 rlimit」这条路是死的**：非 root 进程的 `setrlimit` 无法把软限制
抬到硬限制之上，而这里硬限制就是 64 KB。只能改这个 crate。

### 改动

`mlock`/`munlock` 在 libc 返回非 0 时，由 `panic!` 改为 `warn_once(..)` 后正常返回。
函数签名不变（仍返回 `()`），因此 `cryptovec.rs` 的调用点一行都不用动，diff 最小。

`munlock` **必须一起改**：`mlock` 既然可能从未成功，对一块从未被锁住的内存调
`munlock` 自然也会失败。留着那个 panic 等于把问题从 `resize` 搬到 `Drop` 和
realloc 的 `munlock(old_ptr, ..)` 上——而 `Drop` 里 panic 比原来的毛病**更糟**
（unwind 途中二次 panic 直接 abort 整个进程，连 logcat 都来不及留话）。

### 安全权衡（必须明确写下来，这不是零成本）

`mlock` 的用途是**防止密钥材料被换页到交换区**，落到持久化存储上。失败意味着
这条保证没了。实际影响评估如下，但请注意这是**降级，不是等价替换**：

- **仍然成立的防御**：`CryptoVec` 的清零逻辑与 `mlock` 完全无关——
  `resize` 的截断分支 `memset` 抹掉多余部分，realloc 分支对旧缓冲区逐字节
  `write_volatile(0)`，`Drop` 同样逐字节清零后才 `dealloc`。
  也就是说「释放时不留残渣」这一层**一点没动**，补丁只影响「驻留期间能否被换出」。
- **Android 没有磁盘交换分区**，所以不存在「密钥被写进 swap 分区、关机后仍可取证」
  这个经典场景。
- **但 Android 普遍启用 zram**（压缩交换），技术上确实是 swap。区别在于 zram
  **背后还是 RAM**，页永远不落盘；且页被换回后由上面的清零逻辑照常抹掉。
  所以残留面是「一块 RAM 里的压缩副本」，而不是持久化介质上的明文。
- **残余风险**：能读到本进程 RAM / zram 的攻击者（root、内核漏洞、冷启动攻击）
  本来就能直接读未加锁的堆内存——`mlock` 对这类攻击者从来就没提供过防护。

结论：换来的是「**这台机器上 SSH 能用**」，代价是一个在 Android 上本就近乎
不可兑现的保证。但代价不是零，别假装没有。

### 日志策略：`Once` + `log`，只警告一次

`CryptoVec::resize` 是**每个 SSH 包都可能命中**的热路径，限制卡死时失败是
**每包一次**。每次都打日志会把一条真实告警淹在几千条同样的噪声里，还会反过来
拖慢传输。所以用 `std::sync::Once`：第一次失败把原因（带 errno，
`os error 12` 基本就等于 RLIMIT_MEMLOCK 不够）和后果一次讲清，之后彻底闭嘴。

用 `log` 而不是 `tracing`：本 crate 原本只依赖 `libc`，而 `log` 是兄弟 crate
（`russh` / `russh-keys`）既有的门面，宿主的 `tracing_subscriber::fmt().try_init()`
已经把 `tracing-log` 桥装上了（见 `nexus-core/src/logging.rs`），
`log::warn!` 会直接出现在 logcat 的 `NexusRust` tag 下。`log` 0.4 本来就在依赖树里，
**不引入任何新 crate**。

## 上游已经这么修了（升级路径，供将来参考）

russh-cryptovec **0.62.0** 的 `unix.rs` 把 `mlock`/`munlock` 改成返回
`Result<(), MemoryLockError>`，调用点一律 `let _ = mlock(..)` 直接忽略失败，
并在 `MemoryLockError::new` 里用 `AtomicBool` + `log::warn!` **只警告一次**——
和本补丁的做法完全一致（本补丁等于把上游的修复向后移植到 0.48.0）。
0.50.0 还只是把 panic 信息补得更详细（带 errno 和「检查 RLIMIT_MEMLOCK」提示），
**仍然 panic**，所以升到 0.50/0.51 没用。

想靠升级彻底甩掉本目录，得把 `russh` 整条链从 0.49.2 抬到 0.6x
（`russh-cryptovec` 的版本号是跟着 `russh` 走的），那会连带重做
`vendor/russh` 的 7 处补丁——是一件独立的大工程，不要顺手做。

## 不要做的事

- 不要试图用 `setrlimit` 在启动时抬高 `RLIMIT_MEMLOCK` 绕开本补丁：
  硬限制就是 64 KB，非 root 进程抬不动（已实测）。
- 不要把 `warn_once` 改成每次都打：见上面的日志策略，那是热路径。
- 不要「顺手」把 `windows.rs` 一起改了。那条路径本项目根本不编译，
  改了只会让和上游的 diff 变大、将来对比更费劲。
