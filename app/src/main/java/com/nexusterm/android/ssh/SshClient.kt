package com.nexusterm.android.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.nexus_core.AuthMethod
import uniffi.nexus_core.SessionHandle
import uniffi.nexus_core.SessionException
import uniffi.nexus_core.TerminalEventListener
import uniffi.nexus_core.connect as uniffiConnect
import uniffi.nexus_core.disconnect as uniffiDisconnect
import uniffi.nexus_core.resize as uniffiResize
import uniffi.nexus_core.write as uniffiWrite

/**
 * Thin Kotlin-side wrapper over the generated UniFFI bindings for
 * `nexus-core` (see nexus-core/src/lib.rs for the Rust side of this
 * contract: `connect`/`write`/`resize`/`disconnect`).
 *
 * Kept deliberately dumb: no state, no caching, just type-safe entry points
 * plus the coroutine dispatch needed to keep the (currently blocking, JNI)
 * calls off the main thread. `connect()` in particular runs a full SSH
 * handshake + auth on the Rust side via a blocking JNI call, so it must
 * never be invoked from Dispatchers.Main.
 */
object SshClient {

    /**
     * Establish a connection and open an interactive shell channel.
     * `listener` receives every byte of terminal output plus lifecycle
     * events (pulse/closed) and must answer any keyboard-interactive / key
     * passphrase prompts synchronously (see [TerminalEventListener.onAuthPrompt]).
     *
     * @throws SessionException on handshake/auth/channel failure — callers
     *   should catch this and surface `message` to the user (nexus-core
     *   already translates russh's raw errors into Chinese human-readable
     *   text, see nexus-core/src/errors.rs).
     */
    /**
     * @param configId the saved [uniffi.nexus_core.SessionRecord] id this dial
     *   came from. nexus-core registers the authenticated connection under it,
     *   which is what lets [SftpClient.connect] borrow this connection instead
     *   of handshaking and authenticating all over again.
     */
    suspend fun connect(
        configId: String,
        host: String,
        port: Int,
        username: String,
        auth: AuthMethod,
        listener: TerminalEventListener,
    ): SessionHandle = withContext(Dispatchers.IO) {
        uniffiConnect(configId, host, port.toUShort(), username, auth, listener)
    }

    suspend fun write(sessionId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        uniffiWrite(sessionId, bytes)
    }

    suspend fun resize(sessionId: String, cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        uniffiResize(sessionId, cols.toUShort(), rows.toUShort())
    }

    suspend fun disconnect(sessionId: String) = withContext(Dispatchers.IO) {
        uniffiDisconnect(sessionId)
    }
}
