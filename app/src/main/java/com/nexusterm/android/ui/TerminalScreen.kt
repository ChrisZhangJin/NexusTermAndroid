package com.nexusterm.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "NexusTermUI"

/**
 * Hosts a WebView running the xterm.js bundle in
 * app/src/main/assets/terminal/index.html. Two one-way channels:
 *
 *  Kotlin -> JS (data arriving from nexus-core's `on_data` callback):
 *    `webView.evaluateJavascript("window.NexusTerm.writeBase64('...')", null)`
 *
 *  JS -> Kotlin (keystrokes / resize events from the xterm.js instance):
 *    `window.NexusAndroid.onTermInput(text)` / `onResize(cols, rows)`,
 *    delivered via the `@JavascriptInterface`-annotated bridge below and
 *    forwarded to [onInput]/[onResize].
 *
 * This Composable owns no SSH state itself — [TerminalHandle] is the escape
 * hatch the caller (MainActivity) uses to push bytes in without having to
 * reach into WebView internals.
 */
class TerminalHandle internal constructor(internal val webView: WebView) {
    // `window.NexusTerm` only exists once index.html's inline <script> has
    // actually run — which is strictly after onPageFinished, not right
    // after loadUrl() returns. Any bytes arriving before that (very likely,
    // since the shell banner/prompt lands almost immediately after connect)
    // would previously vanish silently behind the `window.NexusTerm && ...`
    // guard. We buffer until [markPageReady] flips this, then flush in order.
    @Volatile private var pageReady = false
    private val pending = mutableListOf<ByteArray>()

    internal fun markPageReady() {
        synchronized(pending) {
            pageReady = true
            Log.d(TAG, "page ready, flushing ${pending.size} buffered chunk(s)")
            pending.forEach { sendNow(it) }
            pending.clear()
        }
    }

    fun writeBase64(bytes: ByteArray) {
        Log.d(TAG, "writeBase64: ${bytes.size} bytes, pageReady=$pageReady")
        synchronized(pending) {
            if (!pageReady) {
                pending.add(bytes)
                return
            }
        }
        sendNow(bytes)
    }

    private fun sendNow(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        // Single-quoted JS string literal: base64's alphabet never contains
        // a `'`, `\`, or newline, so no escaping is needed here.
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.writeBase64('$b64');") { result ->
            Log.d(TAG, "writeBase64 JS result=$result")
        }
    }

    fun notifyClosed(reason: String) {
        val safeReason = reason.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.notifyClosed('$safeReason');", null)
    }

    /**
     * Writes an app-generated status line (auto-reconnect progress, give-up
     * notices) into the terminal itself.
     *
     * The terminal is the only channel that survives a drop: the WebView and
     * its scrollback stay alive across a reconnect, while the switcher card is
     * only on screen during a two-finger drag. Deliberately routed through
     * [writeBase64] rather than a new `window.NexusTerm.*` entry point —
     * assets/terminal/index.html is shared with other work, and going through
     * the normal output path also means these lines inherit the pre-page-ready
     * buffering above and so interleave with the remote's own output in the
     * order they were produced, instead of jumping ahead of it.
     *
     * [sgr] is a raw SGR parameter string (33 = yellow, 31 = red, 32 = green).
     */
    fun writeNotice(text: String, sgr: String = "33") {
        writeBase64("\r\n\u001B[${sgr}m[$text]\u001B[0m\r\n".toByteArray(Charsets.UTF_8))
    }

    /**
     * Re-runs xterm's fit addon, which reports `cols`/`rows` back through
     * `NexusAndroid.onResize`. Needed after a reconnect: nexus-core opens every
     * new pty at its hardcoded 120x32 (see `connect` in nexus-core/src/lib.rs)
     * and nothing else would ever tell the re-dialled shell the real size,
     * since the WebView never fired a `resize` event — it was never detached.
     */
    fun refit() {
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.fit();", null)
    }

    /**
     * Tears the terminal down for good. Detaches before destroying: a
     * WebView destroyed while still attached to a window logs a hard error
     * and can take the process down, and the Composable showing it is only
     * disposed on the *next* frame after its tab leaves `liveSessions`.
     */
    fun destroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }
}

/**
 * Builds the WebView + [TerminalHandle] for one live SSH session.
 *
 * Deliberately NOT a Composable, and deliberately not owned by the
 * composition: a terminal has to outlive the screen that shows it. Backing
 * out to the session list or switching to another tab disposes the
 * Composable — when the WebView went with it, xterm.js's scrollback died
 * with it and every byte that nexus-core delivered for a backgrounded
 * session got written into a destroyed WebView and silently vanished
 * (`evaluateJavascript` on a dead WebView is a no-op, not an error). The
 * caller — MainActivity's `LiveSession` — owns this for the connection's
 * whole lifetime and calls [TerminalHandle.destroy] when it disconnects.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createTerminal(
    context: Context,
    onInput: (String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
): TerminalHandle {
    val webView = WebView(context).apply {
        // Without explicit layoutParams, a WebView attached by an
        // AndroidView can end up measured at a near-zero height — Blink's
        // CSS `height: 100%` is relative to this native View's measured
        // size, not to Compose's Modifier.fillMaxSize() on the AndroidView
        // wrapper. This was the actual cause of the "blank" terminal: JS
        // executed and term.write() succeeded with zero errors (confirmed
        // via logcat), but the painted surface had collapsed to nothing to
        // paint into.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Keystrokes/resize come back through this bridge object,
        // matching the `window.NexusAndroid.*` calls in index.html.
        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onTermInput(data: String) {
                    CoroutineScope(Dispatchers.Main).launch { onInput(data) }
                }

                @JavascriptInterface
                fun onResize(cols: Int, rows: Int) {
                    CoroutineScope(Dispatchers.Main).launch { onResize(cols, rows) }
                }
            },
            "NexusAndroid",
        )
    }

    val handle = TerminalHandle(webView)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            Log.d(TAG, "onPageFinished url=$url")
            handle.markPageReady()
        }
    }
    // Without this, any console.log/error/exception thrown by index.html's
    // inline script (e.g. xterm.js failing to parse/load) is completely
    // invisible in logcat — this was the single biggest blind spot while
    // diagnosing the blank terminal.
    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "console[${message.messageLevel()}] ${message.message()} " +
                "(${message.sourceId()}:${message.lineNumber()})")
            return true
        }
    }
    webView.loadUrl("file:///android_asset/terminal/index.html")
    return handle
}

/**
 * Shows an already-running terminal. Re-attaching the same WebView to a
 * fresh [AndroidView] is the whole point — the view is detached (not
 * destroyed) while its tab is backgrounded, so xterm.js keeps its buffer
 * and scrollback across tab switches and trips back to the session list.
 */
@Composable
fun TerminalScreen(handle: TerminalHandle, modifier: Modifier = Modifier) {
    // AndroidView runs `factory` exactly once per composable instance and
    // never again — recomposing it with a different [handle] would leave the
    // *previous* session's WebView attached, so switching tabs moved the
    // selection indicator while the terminal underneath stayed put. Keying on
    // the handle forces a genuinely new AndroidView (and so a fresh factory
    // call) whenever the active session changes.
    key(handle) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            // Compose detaches the WebView when this composable is disposed,
            // but leaves `parent` pointing at the (now orphaned) holder —
            // re-adding it without this would throw "the specified child
            // already has a parent".
            factory = { handle.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
        )
    }
}
