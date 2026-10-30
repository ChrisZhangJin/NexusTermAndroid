---
name: android-mlock-rlimit
description: "russh-cryptovec's mlock panics on Android because RLIMIT_MEMLOCK is 64KB and unraisable; vendored patch fixes it"
metadata: 
  node_type: memory
  type: project
  originSessionId: 323d9a20-4dfb-4370-95a6-f6b681a66926
  modified: 2026-10-30T06:36:32.722Z
---

On Android, `RLIMIT_MEMLOCK` for an app process is **65536 bytes, soft == hard**
— a non-root process cannot raise the soft limit above the hard limit, so
`setrlimit` at startup is a dead end. Measured on HONOR PTP-AN00 (Android 16,
kernel 6.6.118) via `run-as com.nexusterm.android cat /proc/<pid>/limits`.

`russh-cryptovec` 0.48.0 `src/platform/unix.rs` calls `panic!("Failed to lock
memory")` when `libc::mlock` returns non-zero, with **no feature flag to disable
it**. `CryptoVec::resize` is on the hot receive path
(`client_read_authenticated` → `from_slice` → `resize`), so once the 64 KB
budget is exhausted the russh client task panics and dies. Downstream chain:
russh task dies → `channel.wait()` returns `None` → read loop breaks with
`reason="lost"` → `data_rx` dropped → next `write()` returns
`SessionError::Channel("输入通道已关闭")` → UniFFI throws into Kotlin.

Fixed 2026-10-30 by vendoring the crate at `nexus-core/vendor/russh-cryptovec`
(same `[patch.crates-io]` pattern as the pre-existing `vendor/russh` and
`vendor/russh-sftp`): `mlock`/`munlock` warn once via `std::sync::Once` + `log`
instead of panicking. `munlock` must be non-fatal too — unlocking memory that
was never locked always fails, and a panic in `Drop` aborts the process.

**Timing is budget-dependent, not data-dependent.** The same APK on the same
phone failed at connect (`alive_ms=51`, 5/5 attempts) in one run and only at a
`tmux attach` burst in the next. A large burst is merely the likeliest single
allocation to push past the budget — which is why it long *looked* like
"tmux attach triggers it". Don't chase data volume.

**Why:** cost a multi-session investigation that chased burst size, the
`.await`-in-`select!`-arm defect, and a missing Kotlin try/catch before the Rust
panic hook made it visible in one run. See [[rust-logging-on-android]].

**How to apply:** if an SSH session dies with `reason="lost"` and no obvious
cause, check logcat tag `NexusRust` for the mlock warning first. The Xiaomi
MI 8 SE's limit was never measured — the device difference is explained but not
independently confirmed. See [[test-devices]].
