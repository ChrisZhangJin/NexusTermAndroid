package com.nexusterm.android.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
    fun writeBase64(bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        // Single-quoted JS string literal: base64's alphabet never contains
        // a `'`, `\`, or newline, so no escaping is needed here.
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.writeBase64('$b64');", null)
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
                webViewClient = android.webkit.WebViewClient()
                loadUrl("file:///android_asset/terminal/index.html")
                onReady(TerminalHandle(this))
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose { }
    }
}
