package com.nexusterm.android.ssh

import uniffi.nexus_core.SftpProgressListener

/**
 * Implements the Rust-defined [SftpProgressListener] callback interface
 * (nexus-core/src/sftp/mod.rs) and forwards every event to plain Kotlin
 * callbacks, mirroring how [SessionListenerImpl] bridges
 * `TerminalEventListener`. Unlike `onAuthPrompt`, none of these three
 * methods need to return anything or block the calling thread — they're
 * fire-and-forget progress pings from the upload/download task running on
 * nexus-core's own tokio runtime (see `sftp::start_transfer`), so the
 * implementations here must stay cheap and non-blocking (hop to the main
 * thread yourself if you need to touch Compose state).
 */
class SftpProgressListenerImpl(
    private val onProgressCallback: (transferId: String, bytesDone: Long, bytesTotal: Long) -> Unit,
    private val onCompleteCallback: (transferId: String) -> Unit,
    private val onErrorCallback: (transferId: String, message: String) -> Unit,
) : SftpProgressListener {

    override fun onProgress(transferId: String, bytesDone: ULong, bytesTotal: ULong) {
        onProgressCallback(transferId, bytesDone.toLong(), bytesTotal.toLong())
    }

    override fun onComplete(transferId: String) {
        onCompleteCallback(transferId)
    }

    override fun onError(transferId: String, message: String) {
        onErrorCallback(transferId, message)
    }
}
