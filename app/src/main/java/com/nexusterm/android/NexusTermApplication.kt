package com.nexusterm.android

import android.app.Application
import java.io.File
import uniffi.nexus_core.initLogging
import uniffi.nexus_core.initStore

/**
 * Opens the shared session-store SQLite database exactly once per process,
 * mirroring nexus-core's own `manager()`/`runtime()` OnceLock singletons
 * (see nexus-core/src/lib.rs): the Rust side can't guess a sandbox path on
 * its own, so Kotlin is the one deciding where the db lives — under this
 * app's private files dir, which survives app updates but is wiped on
 * uninstall (exactly the lifetime a local SQLite db should have).
 *
 * `initStore` is idempotent on the Rust side (a `OnceLock`), so calling it
 * again from a later `onCreate` (process restarted, but somehow didn't fully
 * die) is harmless.
 */
class NexusTermApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 必须是第一件事，连 initStore 都排在它后面：在 subscriber 和 panic
        // hook 装好之前，Rust 侧的 tracing 事件会被直接丢弃，panic 只往
        // stderr 写一行——而 Android app 进程的 stderr 就是 /dev/null。
        // 之前「会话为什么断」在设备上完全查不到，根因就在这里。
        //
        // 传 null = 用默认过滤器（info,nexus_core=debug）。临时需要更吵时
        // 改成 initLogging("nexus_core=trace")，不用重编 Rust 以外的东西。
        // Rust 侧是 `Once` 保护的，重复调用无副作用。
        //
        // 日志 tag 固定为 NexusRust：`adb logcat -s NexusRust:V`。
        initLogging(null)
        val dbDir = File(filesDir, "nexus-store")
        initStore(dbDir.absolutePath)
    }
}
