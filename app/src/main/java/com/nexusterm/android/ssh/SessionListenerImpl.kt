package com.nexusterm.android.ssh

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.nexus_core.AuthPrompt
import uniffi.nexus_core.TerminalEventListener

private const val TAG = "NexusTermListener"

/**
 * Implements the Rust-defined [TerminalEventListener] callback interface
 * (nexus-core/src/listener.rs) and forwards every event to whatever the UI
 * currently cares about via plain Kotlin callbacks — [onData]/[onPulse]/
 * [onClosed] fire-and-forget, [onAuthPrompt] blocks the calling Rust/JNI
 * thread until the UI supplies an answer (or the internal timeout below
 * fires), mirroring nexus-core's own `PROMPT_TIMEOUT` so neither side waits
 * forever if the other never responds.
 *
 * UniFFI invokes callback-interface methods on whatever thread the Rust
 * side used to call them (a tokio `spawn_blocking` worker, per
 * ssh/session.rs / ssh/prompt.rs) — never the Android main thread. The UI
 * layer is responsible for hopping back to the main thread itself (Compose
 * state updates must happen there).
 */
class SessionListenerImpl(
    private val onDataCallback: (sessionId: String, bytes: ByteArray) -> Unit,
    private val onPulseCallback: (sessionId: String) -> Unit,
    private val onClosedCallback: (sessionId: String, reason: String) -> Unit,
    /**
     * Must return the field answers joined by "\n" (same convention as the
     * Rust side's [AuthPrompt] decoding in nexus-core/src/ssh/prompt.rs),
     * or an empty string to mean "user cancelled". Suspends the UI-facing
     * coroutine while this runs; the JNI thread blocks on [runBlocking]
     * underneath until it completes or times out.
     */
    private val onAuthPromptCallback: suspend (AuthPrompt) -> String,
) : TerminalEventListener {

    override fun onData(sessionId: String, bytes: ByteArray) {
        Log.d(TAG, "onData session=$sessionId bytes=${bytes.size} thread=${Thread.currentThread().name}")
        onDataCallback(sessionId, bytes)
    }

    override fun onPulse(sessionId: String) {
        onPulseCallback(sessionId)
    }

    override fun onClosed(sessionId: String, reason: String) {
        Log.d(TAG, "onClosed session=$sessionId reason=$reason")
        onClosedCallback(sessionId, reason)
    }

    override fun onAuthPrompt(prompt: AuthPrompt): String {
        // The UniFFI callback contract here is synchronous (see the plan's
        // rationale in nexus-core/src/listener.rs), so we have no choice but
        // to block this JNI thread while the suspend callback resolves.
        // 180s mirrors nexus-core's own PROMPT_TIMEOUT (ssh/prompt.rs).
        return runBlocking {
            withTimeoutOrNull(PROMPT_TIMEOUT_MS) {
                onAuthPromptCallback(prompt)
            } ?: ""
        }
    }

    companion object {
        private const val PROMPT_TIMEOUT_MS = 180_000L
    }
}

/** Small helper for call sites that need a one-shot "wait for an answer from the UI" bridge. */
class PendingAnswer {
    private val deferred = CompletableDeferred<String>()
    suspend fun await(): String = deferred.await()
    fun answer(value: String) {
        deferred.complete(value)
    }
}
