package com.nexusterm.android.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
class TerminalHandle(private val webView: WebView) {
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(
    onReady: (TerminalHandle) -> Unit,
    onInput: (String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
) {
    val context = LocalContext.current

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                // Without explicit layoutParams, a WebView created inside an
                // AndroidView factory can end up measured at a near-zero
                // height — Blink's CSS `height: 100%` is relative to this
                // native View's measured size, not to Compose's
                // Modifier.fillMaxSize() on the AndroidView wrapper. This
                // was the actual cause of the "blank" terminal: JS executed
                // and term.write() succeeded with zero errors (confirmed
                // via logcat), but the painted surface had collapsed to
                // nothing to paint into.
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
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
                val handle = TerminalHandle(this)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "onPageFinished url=$url")
                        handle.markPageReady()
                    }
                }
                // Without this, any console.log/error/exception thrown by
                // index.html's inline script (e.g. xterm.js failing to
                // parse/load) is completely invisible in logcat — this was
                // the single biggest blind spot while diagnosing the blank
                // terminal.
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d(TAG, "console[${message.messageLevel()}] ${message.message()} " +
                            "(${message.sourceId()}:${message.lineNumber()})")
                        return true
                    }
                }
                loadUrl("file:///android_asset/terminal/index.html")
                onReady(handle)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose { }
    }
}
