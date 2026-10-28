package com.nexusterm.android.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.nexus_core.AuthMethod
import uniffi.nexus_core.RemoteEntry
import uniffi.nexus_core.SftpException
import uniffi.nexus_core.SftpHandle
import uniffi.nexus_core.SftpProgressListener
import uniffi.nexus_core.TerminalEventListener
import uniffi.nexus_core.sftpCancelTransfer as uniffiSftpCancelTransfer
import uniffi.nexus_core.sftpClose as uniffiSftpClose
import uniffi.nexus_core.sftpConnect as uniffiSftpConnect
import uniffi.nexus_core.sftpDownload as uniffiSftpDownload
import uniffi.nexus_core.sftpHome as uniffiSftpHome
import uniffi.nexus_core.sftpList as uniffiSftpList
import uniffi.nexus_core.sftpMkdir as uniffiSftpMkdir
import uniffi.nexus_core.sftpRead as uniffiSftpRead
import uniffi.nexus_core.sftpRemove as uniffiSftpRemove
import uniffi.nexus_core.sftpRename as uniffiSftpRename
import uniffi.nexus_core.sftpUpload as uniffiSftpUpload

/**
 * Thin Kotlin-side wrapper over the generated UniFFI bindings for
 * nexus-core's `sftp::` exports (nexus-core/src/sftp/mod.rs). Same shape as
 * [SshClient]: an object with no state, a `suspend fun` per export, and
 * `Dispatchers.IO` to keep the blocking JNI calls off the main thread.
 *
 * `connect()` dials an entirely independent SSH connection — this milestone
 * does not reuse an already-open terminal session (see the module doc in
 * nexus-core/src/sftp/mod.rs), so opening the file browser for a session
 * that's also open in a terminal tab will ask for credentials/2FA again.
 *
 * `upload`/`download` return a `transferId` as soon as the background copy
 * task starts; progress/completion/failure arrive asynchronously through the
 * [SftpProgressListener] passed in (see [SftpProgressListenerImpl]) — the
 * suspend function itself does **not** wait for the transfer to finish.
 */
object SftpClient {

    /**
     * @throws SftpException on handshake/auth/subsystem failure — nexus-core
     *   already translates russh's raw errors into Chinese human-readable
     *   text (see nexus-core/src/errors.rs).
     */
    /**
     * @param configId the saved session id. If a terminal tab for the same
     *   config/host/port/user is still connected, nexus-core opens the sftp
     *   subsystem on *that* connection — no second handshake, and no second
     *   one-time password. Falls back to dialling independently otherwise.
     */
    suspend fun connect(
        configId: String,
        host: String,
        port: Int,
        username: String,
        auth: AuthMethod,
        listener: TerminalEventListener,
    ): SftpHandle = withContext(Dispatchers.IO) {
        uniffiSftpConnect(configId, host, port.toUShort(), username, auth, listener)
    }

    suspend fun list(handleId: String, path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        uniffiSftpList(handleId, path)
    }

    suspend fun mkdir(handleId: String, path: String) = withContext(Dispatchers.IO) {
        uniffiSftpMkdir(handleId, path)
    }

    suspend fun remove(handleId: String, path: String) = withContext(Dispatchers.IO) {
        uniffiSftpRemove(handleId, path)
    }

    suspend fun rename(handleId: String, from: String, to: String) = withContext(Dispatchers.IO) {
        uniffiSftpRename(handleId, from, to)
    }

    suspend fun home(handleId: String): String = withContext(Dispatchers.IO) {
        uniffiSftpHome(handleId)
    }

    /** Capped whole-file read, for small-file previews only — not for transfers. */
    suspend fun read(handleId: String, path: String, max: Long): ByteArray = withContext(Dispatchers.IO) {
        uniffiSftpRead(handleId, path, max.toULong())
    }

    /** Returns the `transferId` immediately; progress/result arrive via [progressListener]. */
    suspend fun upload(
        handleId: String,
        localPath: String,
        remotePath: String,
        progressListener: SftpProgressListener,
    ): String = withContext(Dispatchers.IO) {
        uniffiSftpUpload(handleId, localPath, remotePath, progressListener)
    }

    /** Mirror of [upload]: remote -> local. */
    suspend fun download(
        handleId: String,
        remotePath: String,
        localPath: String,
        progressListener: SftpProgressListener,
    ): String = withContext(Dispatchers.IO) {
        uniffiSftpDownload(handleId, remotePath, localPath, progressListener)
    }

    suspend fun cancelTransfer(transferId: String) = withContext(Dispatchers.IO) {
        uniffiSftpCancelTransfer(transferId)
    }

    suspend fun close(handleId: String) = withContext(Dispatchers.IO) {
        uniffiSftpClose(handleId)
    }
}
