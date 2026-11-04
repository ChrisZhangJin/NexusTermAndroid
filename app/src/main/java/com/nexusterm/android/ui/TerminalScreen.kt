package com.nexusterm.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

private const val TAG = "NexusTermUI"

/** Font size bounds for pinch-to-zoom, in CSS px (= dp in the WebView). */
const val MIN_FONT_SIZE = 8
const val MAX_FONT_SIZE = 28
const val DEFAULT_FONT_SIZE = 14

/**
 * Width of the virtual window. 160 columns is about 3.3 screens of a portrait
 * phone at 14px — wide enough for `top`, `ps aux` and most log lines without
 * wrapping, yet still a short swipe from one edge to the other.
 */
const val VIRTUAL_WINDOW_COLS = 160

/**
 * The one terminal setting that outlives the app: the font size last pinched
 * to. Global rather than per saved session — it's about the phone and the
 * user's eyes, not about the host — and only read when a terminal is
 * created, so tabs that are already open keep whatever they were zoomed to.
 */
internal object TerminalPrefs {
    private const val FILE = "terminal"
    private const val KEY_FONT_SIZE = "font_size"

    fun fontSize(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    fun setFontSize(context: Context, px: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FONT_SIZE, px).apply()
    }
}

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
class TerminalHandle internal constructor(
    internal val webView: WebView,
    fontSize: Int,
    /** 命令历史按它分开存（已保存会话的 id）；空串 = 不记录。 */
    val historyKey: String = "",
) {
    /** Font size this terminal is showing, in CSS px. */
    var fontSize: Int = fontSize
        private set

    /** Whether this tab is in the [VIRTUAL_WINDOW_COLS]-wide virtual window. Not persisted. */
    var virtualWindow: Boolean = false
        private set

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
        if (focusWhenReady) {
            focusWhenReady = false
            focusNow()
        }
    }

    /**
     * When remote output last arrived, in [SystemClock.elapsedRealtime] ms
     * (monotonic, unaffected by wall-clock changes; 0 = nothing yet). Callers
     * of [waitForPrompt] must take their `sinceMs` from the same clock.
     * Bumped by [writeBase64] only, i.e. by the shell's own bytes; app notices
     * ([writeNotice]) go through [appendNotice] and deliberately don't count,
     * or a "reconnected" line would make a silent screen look like fresh output.
     */
    @Volatile var lastOutputMs: Long = 0L
        private set

    /** Shell output from the remote. Use [writeNotice] for app-generated text. */
    fun writeBase64(bytes: ByteArray) {
        lastOutputMs = SystemClock.elapsedRealtime()
        appendBytes(bytes)
    }

    private fun appendBytes(bytes: ByteArray) {
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

    /** See `resetModes` in index.html: clears tmux/vim's alt screen and mouse modes locally. */
    fun resetModes() {
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.resetModes();", null)
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
        appendBytes("\r\n\u001B[${sgr}m[$text]\u001B[0m\r\n".toByteArray(Charsets.UTF_8))
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
     * Changes the font size, clamped to [MIN_FONT_SIZE]..[MAX_FONT_SIZE].
     * With [commit] false the terminal only reflows locally (a pinch in
     * progress); true also reports the new cols/rows to the remote pty and
     * remembers the size for future terminals. [focusX] is the pinch centre
     * in page px, which the virtual window keeps anchored.
     */
    fun setFontSize(px: Int, commit: Boolean, focusX: Float = 0f) {
        fontSize = px.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        webView.evaluateJavascript(
            "window.NexusTerm && window.NexusTerm.setFontSize($fontSize, $commit, $focusX);",
            null,
        )
        if (commit) TerminalPrefs.setFontSize(webView.context, fontSize)
    }

    fun setVirtualWindow(on: Boolean) {
        virtualWindow = on
        val cols = if (on) VIRTUAL_WINDOW_COLS else 0
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.setVirtualCols($cols);", null)
    }

    fun paste(text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.pasteBase64('$b64');", null)
    }

    /**
     * 从历史面板把 [cmd] 放到命令行上。先 Ctrl+A Ctrl+K 清掉已经敲了一半的内容（和按 ↑ 召回
     * 历史一样是「替换」而不是「追加」），再走粘贴：bash / zsh 的括号粘贴保证它只是落在
     * 行上而不被执行。[run] = true 再补一个回车。
     */
    fun insertCommand(cmd: String, run: Boolean) {
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.sendKeyBase64('AQs=');", null)
        paste(cmd)
        if (run) webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.sendKeyBase64('DQ==');", null)
    }

    fun clearScreen() {
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.clearScreen();", null)
    }

    fun scrollToBottom() {
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.scrollToBottom();", null)
    }

    /**
     * 接着物理键盘时，把按键的去处准备好：WebView 拿到 Android 视图焦点，页面里
     * xterm 的 textarea 拿到 DOM 焦点，两层都到位按键才进得了终端。
     *
     * 只能在确认有物理键盘（见 HardKeyboardMonitor）时调，不要在页面加载 / 切标签
     * 时无条件调：没有硬键盘的手机上，textarea 一聚焦输入法就可能弹出来。
     * 页面还没跑起来（[markPageReady] 之前）时只记下意图，等页面就绪再补做，否则
     * `window.NexusTerm` 还不存在，这次调用会被 `&&` 守卫静默吞掉。
     * WebView 此刻若没挂在窗口上（后台标签）requestFocus 会返回 false，没关系：
     * 重新挂上之后调用方应再调一次。
     */
    fun focusForHardwareKeys() {
        synchronized(pending) {
            if (!pageReady) {
                focusWhenReady = true
                return
            }
        }
        focusNow()
    }

    @Volatile private var focusWhenReady = false

    private fun focusNow() {
        webView.requestFocus()
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.focusForHardwareKeys();", null)
    }

    /**
     * Touch selection: [selectBegin] selects the word at the point, [selectMove]
     * stretches it out to another. Coordinates are page px, i.e. dp from the
     * WebView's top-left.
     */
    fun selectBegin(x: Float, y: Float) {
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.selectBegin($x, $y);", null)
    }

    fun selectMove(x: Float, y: Float) {
        webView.evaluateJavascript("window.NexusTerm && window.NexusTerm.selectMove($x, $y);", null)
    }

    /** Ends the selection and hands back its text ("" when nothing was selected). */
    fun selectEnd(onText: (String) -> Unit) {
        webView.evaluateJavascript("window.NexusTerm ? window.NexusTerm.selectEnd() : ''") { raw ->
            // The result arrives JSON-encoded: a quoted, escaped string literal.
            val text = runCatching { JSONArray("[$raw]").optString(0, "") }.getOrDefault("")
            onText(if (raw == "null") "" else text)
        }
    }

    /**
     * 读出当前选区的文字（没有选区 = ""），不改动任何选区状态——键盘「复制」快捷键用，
     * 和 [selectEnd] 的区别是它不结束触摸选择。结果的解码和 [selectEnd] 是同一套。
     */
    fun readSelection(onText: (String) -> Unit) {
        webView.evaluateJavascript("window.NexusTerm ? window.NexusTerm.getSelection() : ''") { raw ->
            val text = runCatching { JSONArray("[$raw]").optString(0, "") }.getOrDefault("")
            onText(if (raw == "null") "" else text)
        }
    }

    /**
     * Reads what is on the cursor's line before the cursor (see
     * NexusTerm.getPromptState in index.html); null when the page isn't up yet
     * or the result can't be decoded. Safe to call from any thread: the
     * evaluateJavascript call is posted to the WebView's UI thread.
     */
    fun readPromptState(onResult: (PromptState?) -> Unit) {
        webView.post {
            webView.evaluateJavascript("window.NexusTerm ? window.NexusTerm.getPromptState() : null") { raw ->
                onResult(parsePromptState(raw))
            }
        }
    }

    /**
     * Waits for the remote shell to sit at a prompt: output quiet for >350 ms
     * AND the text before the cursor ends in a prompt character
     * ([looksLikePrompt]). Returns that [PromptState], or null on timeout
     * (callers should then not type anything into an uncertain screen).
     *
     * [sinceMs] is a [SystemClock.elapsedRealtime] value (same clock as
     * [lastOutputMs]); at least one remote output chunk must have arrived at or
     * after it, so a stale prompt left on the screen from before a reconnect
     * isn't mistaken for the new shell's. Cancellation-cooperative.
     */
    suspend fun waitForPrompt(sinceMs: Long, timeoutMs: Long = 10_000): PromptState? {
        val t0 = SystemClock.elapsedRealtime()
        while (true) {
            delay(PROMPT_POLL_MS)
            val now = SystemClock.elapsedRealtime()
            val last = lastOutputMs
            if (last >= sinceMs && now - last > PROMPT_QUIET_MS) {
                val state = suspendCancellableCoroutine<PromptState?> { cont ->
                    readPromptState { if (cont.isActive) cont.resume(it) }
                }
                if (state != null && looksLikePrompt(state.textBeforeCursor)) return state
            }
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) return null
        }
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

private const val PROMPT_POLL_MS = 150L
private const val PROMPT_QUIET_MS = 350L

/** Snapshot of the cursor line: [textBeforeCursor] is the line's text up to the cursor. */
data class PromptState(val textBeforeCursor: String, val cursorX: Int, val cols: Int)

private fun parsePromptState(raw: String?): PromptState? {
    if (raw == null || raw == "null") return null
    return runCatching {
        // evaluateJavascript JSON-encodes the returned string: unwrap the literal first.
        val json = JSONArray("[$raw]").optString(0, "")
        val o = JSONObject(json)
        PromptState(o.optString("text", ""), o.optInt("cursorX", 0), o.optInt("cols", 0))
    }.getOrNull()
}

/**
 * Port of termRegistry.ts's PROMPT_TAIL (desktop app): the line ends in a common
 * prompt character -- $ # % > or the fancy-prompt glyphs a heavy zsh/starship
 * theme uses (❯ ➜ » λ) -- optionally followed by one whitespace char (a space, or
 * U+00A0, which JS's \s covers but Java's doesn't). Only the tail matters, so
 * `(base) chris@host:~$ `, `# `, `> `, `❯ ` match; `➜  ~ ` (oh-my-zsh
 * robbyrussell, glyph first and the cwd after it) does not, same as on desktop.
 */
private val PROMPT_TAIL = Regex("[$#%>❯➜»λ][\\s\u00A0]?$")

/** True when [textBeforeCursor] (the cursor line up to the cursor) looks like a shell prompt. */
internal fun looksLikePrompt(textBeforeCursor: String): Boolean =
    PROMPT_TAIL.containsMatchIn(textBeforeCursor)

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
    historyKey: String = "",
): TerminalHandle {
    val startFontSize = TerminalPrefs.fontSize(context)
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
        // A long press is the terminal's own "start selecting" gesture,
        // recognised in TerminalTabsPager. Left to Chromium, the same press
        // would buzz and try its own selection/context menu at ~0.5s, well
        // before ours fires.
        setOnLongClickListener { true }
        isHapticFeedbackEnabled = false
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

                // 回车那一刻页面从屏幕上读出的命令，见 index.html 的 captureCommand。
                @JavascriptInterface
                fun onCommand(cmd: String) {
                    CommandHistory.record(context, historyKey, cmd)
                }

                // Read synchronously by index.html before its first fit.
                @JavascriptInterface
                fun initialFontSize(): Int = startFontSize
            },
            "NexusAndroid",
        )
    }

    val handle = TerminalHandle(webView, startFontSize, historyKey)
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
