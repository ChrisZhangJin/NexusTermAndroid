package com.nexusterm.android.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.nexus_core.NewSession
import uniffi.nexus_core.SessionRecord
import uniffi.nexus_core.StoreException
import uniffi.nexus_core.createSession as uniffiCreateSession
import uniffi.nexus_core.deleteSession as uniffiDeleteSession
import uniffi.nexus_core.listSessions as uniffiListSessions
import uniffi.nexus_core.updateSession as uniffiUpdateSession

/**
 * Thin Kotlin-side wrapper over the generated UniFFI bindings for
 * nexus-core's session-store exports (`init_store`/`list_sessions`/
 * `create_session`/`update_session`/`delete_session`, see
 * nexus-core/src/lib.rs). Same shape as [SshClient]: no state of its own,
 * just type-safe entry points plus `Dispatchers.IO` dispatch to keep the
 * blocking JNI calls off the main thread.
 *
 * `initStore` itself is **not** wrapped here — it's called exactly once at
 * process startup from [com.nexusterm.android.NexusTermApplication], before
 * any Compose screen that would call into this object exists.
 *
 * Deliberately out of scope for this milestone (per the plan): macros and
 * command history. `SessionRecord`/`NewSession` never carry a password or
 * private-key passphrase field with an actual secret in it — those live in
 * [SecretsStore], keyed by session id, and are never round-tripped through
 * Rust/SQLite.
 */
object SessionStore {

    /** @throws StoreException if the store hasn't been initialized, or on a SQLite error. */
    suspend fun list(): List<SessionRecord> = withContext(Dispatchers.IO) {
        uniffiListSessions()
    }

    suspend fun create(session: NewSession): SessionRecord = withContext(Dispatchers.IO) {
        uniffiCreateSession(session)
    }

    suspend fun update(id: String, session: NewSession): SessionRecord = withContext(Dispatchers.IO) {
        uniffiUpdateSession(id, session)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        uniffiDeleteSession(id)
    }
}
