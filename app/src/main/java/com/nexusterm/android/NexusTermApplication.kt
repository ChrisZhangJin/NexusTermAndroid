package com.nexusterm.android

import android.app.Application
import java.io.File
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
        val dbDir = File(filesDir, "nexus-store")
        initStore(dbDir.absolutePath)
    }
}
