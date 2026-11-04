package com.nexusterm.android

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nexusterm.android.ssh.SecretsStore
import com.nexusterm.android.ssh.SessionListenerImpl
import com.nexusterm.android.ssh.SessionStore
import com.nexusterm.android.ssh.SidecarPrefs
import com.nexusterm.android.ssh.SshClient
import com.nexusterm.android.ssh.TermContext
import com.nexusterm.android.ssh.cellWidth
import com.nexusterm.android.ssh.parseProbe
import com.nexusterm.android.ssh.restoreCommand
import com.nexusterm.android.ssh.restoreNotice
import com.nexusterm.android.ssh.stealthInput
import com.nexusterm.android.ui.AuthKind
import com.nexusterm.android.ui.ConnectScreen
import com.nexusterm.android.ui.GesturePrefs
import com.nexusterm.android.ui.GestureSettingsScreen
import com.nexusterm.android.ui.KeyChord
import com.nexusterm.android.ui.LiveTabInfo
import com.nexusterm.android.ui.LiveTabStatus
import com.nexusterm.android.ui.SessionFormInitial
import com.nexusterm.android.ui.SessionListScreen
import com.nexusterm.android.ui.SftpScreen
import com.nexusterm.android.ui.ShortcutAction
import com.nexusterm.android.ui.ShortcutBindings
import com.nexusterm.android.ui.ShortcutGroup
import com.nexusterm.android.ui.ShortcutHost
import com.nexusterm.android.ui.TerminalHandle
import com.nexusterm.android.ui.TerminalTab
import com.nexusterm.android.ui.TerminalTabsPager
import com.nexusterm.android.ui.createTerminal
import com.nexusterm.android.ui.theme.NexusTermTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.nexus_core.AuthMethod
import uniffi.nexus_core.AuthPrompt
import uniffi.nexus_core.NewSession
import uniffi.nexus_core.SessionRecord
import uniffi.nexus_core.SessionException
import uniffi.nexus_core.StoreException
import java.util.UUID
import kotlin.math.min

/**
 * Milestone 2 wiring: a real Navigation-Compose graph replaces the vertical
 * slice's two-state `when` block.
 *
 * Routes:
 * - `sessionList` — [SessionListScreen], the home screen (list of saved
 *   sessions from [SessionStore], backed by nexus-core's SQLite store).
 * - `editSession` / `editSession/{sessionId}` — [ConnectScreen], now an
 *   add/edit form that writes a [NewSession] via [SessionStore] and the
 *   plaintext secret(s) via [SecretsStore] (never both to the same place —
 *   see that object's doc).
 * - `terminal` — the active session's terminal, full screen (see
 *   [TerminalTabsPager], which also owns the two-finger card switcher that
 *   is how you reach the other sessions). Deliberately takes no route argument:
 *   which tab is on screen is plain Compose state (`activeTabId`),
 *   not navigation state, so switching tabs never pushes/pops a back stack
 *   entry. Routing it per-session instead would dispose and rebuild the
 *   destination on every switch, and the back button would then walk back
 *   through every tab the user had ever looked at.
 * - `sftp/{sessionId}` — [SftpScreen], reached from a saved session's
 *   "文件" action; dials its own independent SFTP connection (see
 *   nexus-core/src/sftp/mod.rs's module doc on why it doesn't reuse the
 *   terminal connection yet).
 */

private const val TAG = "NexusTermConnect"

/**
 * Auto-reconnect policy, mirroring the desktop sibling (NexusTerm's
 * `src/App.tsx`) except for the ceiling: the desktop's uncapped doubling
 * reaches ~17 minutes by attempt 10, which on a phone — where the usual
 * cause is a tunnel, a lift, or the user toggling wifi — reads as "it never
 * came back". Capped at 30s the whole ladder is 2+4+8+16+30×6 ≈ 3.5 minutes,
 * and [MainActivity.retryNudge] short-circuits the wait whenever the network
 * actually returns, so the cap is only ever the worst case.
 */
private const val RECONNECT_MAX = 10
private const val RECONNECT_BASE_MS = 2_000L
private const val RECONNECT_MAX_DELAY_MS = 30_000L

private fun reconnectDelayMs(attempt: Int): Long =
    min(RECONNECT_BASE_MS shl (attempt - 1), RECONNECT_MAX_DELAY_MS)

/**
 * `ClosedInfo::reason` as nexus-core spells it (ssh/session.rs): `exit` =
 * the remote shell exited, `eof` = the remote closed the channel, `lost` =
 * the connection died under us (TCP reset / keepalive timeout / write
 * failure), `user` = we asked for it. Only `lost` is worth reconnecting:
 * the other three all mean the far end made an orderly decision, and
 * re-dialling after `exit` would make `exit` impossible to type.
 */
private const val CLOSE_REASON_LOST = "lost"
private const val CLOSE_REASON_USER = "user"

private const val SGR_RED = "31"
private const val SGR_GREEN = "32"
private const val SGR_YELLOW = "33"

/**
 * 远端上下文探测节奏：连上 3 秒后探第一次（等 shell 起来、别和登录握手抢），之后每 30 秒一次。
 * 探测只为「断线时知道用户在哪个目录 / tmux 会话」，30 秒的粒度足够，也不给服务器添负担。
 */
private const val PROBE_FIRST_DELAY_MS = 3_000L
private const val PROBE_INTERVAL_MS = 30_000L

/**
 * 按住长按时允许「重复触发」的快捷键动作：切标签、调字号这类「按一次一档、按住连续走」
 * 的动作。其余动作（关闭标签弹确认框、新建标签、打开菜单、粘贴……）必须一次物理按键
 * 只触发一次，否则按住 Ctrl+Shift+W 会反复弹确认框、按住 Ctrl+Shift+T 会连开标签。
 */
private val REPEATABLE_SHORTCUT_ACTIONS: Set<ShortcutAction> = setOf(
    ShortcutAction.NEXT_TAB,
    ShortcutAction.PREV_TAB,
    ShortcutAction.FONT_SIZE_UP,
    ShortcutAction.FONT_SIZE_DOWN,
)

/** 会话切换器里按住可以连续触发的键：只有左右方向键（连续翻卡片）；Enter/Esc/Delete 一次按键一次。 */
private val REPEATABLE_SWITCHER_KEYS: Set<Int> = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

/**
 * 一个「在 ACTION_DOWN 上被我们消费掉」的物理键的登记。用来保证：
 *  - 对应的 ACTION_UP 一定也被吃掉（WebView 不能看到孤儿 key-up），哪怕修饰键已经先松开；
 *  - 长按产生的重复 DOWN 也被吃掉，而且只有 [repeats] 为 true 时才重新执行动作。
 *
 * [action] 非空 = 快捷键动作（长按重复时重新执行同一个动作，不重新按当前修饰键匹配——
 * 用户可能已经松开了 Ctrl）；为 null = 切换器按键。
 */
private class HeldShortcutKey(val action: ShortcutAction?, val repeats: Boolean)

/** 键位 → 系统快捷键面板（[KeyboardShortcutInfo]）要的 META_*_ON 位掩码。Meta 键不在 [KeyChord] 里。 */
private fun KeyChord.toMetaState(): Int =
    (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
        (if (shift) KeyEvent.META_SHIFT_ON else 0) or
        (if (alt) KeyEvent.META_ALT_ON else 0)

/**
 * 单纯的修饰键 / 锁定键：永远原样放行，不参与快捷键匹配，否则按住 Ctrl 的瞬间就会
 * 被当成「某个键位」吃掉，后面的 Ctrl+字母 就坏了。
 * [KeyEvent.isModifierKey] 管 Shift/Ctrl/Alt/Meta/Sym/Fn，但按文档**不**含 Caps/Num/Scroll Lock，
 * 所以锁定键单列。
 */
private fun isModifierOrLockKey(keyCode: Int): Boolean =
    KeyEvent.isModifierKey(keyCode) ||
        keyCode == KeyEvent.KEYCODE_CAPS_LOCK ||
        keyCode == KeyEvent.KEYCODE_NUM_LOCK ||
        keyCode == KeyEvent.KEYCODE_SCROLL_LOCK

/** How the UI currently believes a [LiveSession]'s connection is doing. */
private enum class LinkState { CONNECTED, RECONNECTING, CLOSED }

/**
 * [LinkState] 是这个文件的私有实现细节，[LiveTabStatus] 是 UI 层的词汇表。
 * 保持两套而不是让 SessionListScreen 直接用 LinkState，是为了不让「首页怎么
 * 画一条活跃标签」这件事反过来把 LiveSession 的内部状态机钉死。
 */
private fun LinkState.toTabStatus(): LiveTabStatus = when (this) {
    LinkState.CONNECTED -> LiveTabStatus.CONNECTED
    LinkState.RECONNECTING -> LiveTabStatus.RECONNECTING
    LinkState.CLOSED -> LiveTabStatus.CLOSED
}

/**
 * One connected-but-not-necessarily-on-screen SSH session. Each live
 * connection owns its own [TerminalHandle]/pending-bytes buffer/close state
 * so switching which tab is active never touches another session's data —
 * replaces the old single-slot `terminalHandle`/`closedReason`/
 * `pendingTerminalData` vars that could only ever back one connection at a
 * time (leaving that route didn't disconnect the SSH session, just discarded
 * the UI's only handle to it, forcing a reconnect-from-scratch to get back
 * in).
 */
private class LiveSession(
    /**
     * Identity of the *tab*, fixed for as long as the tab exists. Distinct
     * from [liveSessionId] because auto-reconnect replaces the SSH session
     * underneath without replacing the tab: if the switcher and
     * `activeTabId` were keyed on the SSH id — as they used to be — every
     * successful reconnect would make the on-screen tab stop matching
     * anything and bounce the user back to the session list.
     */
    val tabId: String,
    var liveSessionId: String,
    val savedSessionId: String,
    val displayName: String,
) {
    /**
     * Created once, right after `connect` returns, and kept alive until this
     * session is disconnected — see [createTerminal] for why the terminal
     * can't be owned by the composition that shows it. Survives a reconnect
     * untouched, which is the whole point: the user keeps their scrollback.
     */
    var terminal: TerminalHandle? = null

    /**
     * Bytes that arrived in the window between `connect` returning and
     * [terminal] existing. [TerminalHandle] does its own buffering after
     * that (until the WebView's page is ready), so this only covers the gap.
     */
    val pendingData = mutableListOf<ByteArray>()

    /**
     * Reused verbatim across re-dials. Its closures only ever touch `this`
     * (never a captured session id), so handing the same instance to a
     * second [SshClient.connect] wires the new connection straight back into
     * the same terminal.
     */
    var listener: SessionListenerImpl? = null

    var state by mutableStateOf(LinkState.CONNECTED)

    /**
     * Incremented on every unexpected drop. The per-session retry effect in
     * [MainActivity] collects this rather than being started imperatively
     * from the close callback, so that a second drop mid-reconnect simply
     * restarts the loop instead of racing a second one against it.
     */
    var dropCount by mutableStateOf(0)

    /**
     * Set by the auth-prompt callback. A host that asks for a 2FA code can't
     * be retried blind (see [MainActivity.reconnectLoop]).
     */
    var sawAuthPrompt = false

    /**
     * Latched by `disconnectLive`. The retry loop's own cancellation can't
     * be observed from inside a blocking JNI dial, so it checks this on the
     * way out instead and hangs up anything it managed to open.
     */
    @Volatile var disposed = false

    /**
     * 最近一次成功探测到的远端上下文（目录 / tmux / docker），由 [MainActivity.probeLoop] 写入，
     * 重连成功后 [MainActivity.restoreContext] 拿**掉线前**的这份去还原现场。探测失败 / 无有用
     * 信息时保留旧值，所以断线瞬间它仍是掉线前最后一次看到的样子。
     */
    @Volatile var ctx: TermContext? = null

    /**
     * 重连后正在还原现场（等提示符、发 cd / tmux attach）。期间探测既不发也不落盘结果：
     * 新登录 shell 此刻还在 $HOME，探到它会把掉线前的真实上下文覆盖掉。
     */
    @Volatile var restoring = false

    /**
     * Appended to the switcher card's title. Status has to reach the UI
     * through the existing [TerminalTab] shape — `title` is the only free
     * text field it has — so this is a suffix rather than a proper state
     * chip.
     */
    var statusSuffix by mutableStateOf("")

    /**
     * 标签创建的时刻。用 `val` 而不是跟着重连刷新：它标识的是「这条标签」，
     * 不是底层 SSH 会话——重连换了新会话，用户眼里仍是同一个标签。首页「断开
     * 哪个标签？」菜单靠它区分同一配置开出的多条标签。
     */
    val openedAtMillis: Long = System.currentTimeMillis()

    /**
     * 用户在切换器里给这条标签起的名字，null = 没起过，用 [displayName]。
     *
     * 只活在内存里、跟着标签走（标签关了就没了），所以放在 [LiveSession] 而不是
     * 存进保存的配置：它标识的是「这一条标签」，同一个主机开两条标签可以各叫各的。
     * 是 Compose 状态，改了之后切换器、首页的「断开哪个标签？」菜单和通知栏的
     * 标题都会跟着读到新值（它们读的都是 [tabTitle]）。
     */
    var customName by mutableStateOf<String?>(null)

    /** 用户看到的标签名，不含状态后缀：自定义名优先，否则是配置名/主机。 */
    val shownName: String get() = customName ?: displayName

    val tabTitle: String get() = shownName + statusSuffix
}

class MainActivity : ComponentActivity() {

    /**
     * "Stop waiting out the backoff and retry now". Bumped when Android
     * reports a usable default network, and when the user taps a
     * still-reconnecting session in the list. Without the first source the
     * app does exactly what was reported — phone's data comes back, and
     * every tab keeps sitting on a timer with up to
     * [RECONNECT_MAX_DELAY_MS] left to run.
     */
    private val retryNudge = MutableStateFlow(0)

    /**
     * 物理键盘快捷键的动作入口。`Activity.dispatchKeyEvent` 匹配出 [ShortcutAction] 之后调
     * `shortcutHost.perform(action)`，切换器打开时的方向键 / Enter / Esc / Delete 调
     * `shortcutHost.onSwitcherKey(keyCode)`。handler 由 `terminal` 路由里的
     * [TerminalTabsPager] 在组合期间登记、离开时注销（见 [ShortcutHost]），所以首页、
     * SFTP、设置页上两个调用都返回 false。是 Activity 成员而不是 setContent 里的
     * remember：dispatchKeyEvent 在组合之外，要够得着它。
     */
    val shortcutHost = ShortcutHost()

    /**
     * 当前生效的快捷键绑定快照。真正的数据源是 Compose 里的 `gestureConfig.shortcuts`
     * （设置页改绑后会变），但 [dispatchKeyEvent] 在组合之外、读不到 Compose 状态，
     * 所以 setContent 里用 SideEffect 把它镜像到这里；初始值在 `gestureConfig` 首次读盘
     * 时同步写入，保证第一次按键之前就是用户存档的键位而不是出厂键位。
     * `@Volatile`：写在组合线程（主线程）、读在主线程，理论上同线程，加上它只是不依赖这个假设。
     */
    @Volatile
    private var shortcutBindings: ShortcutBindings = ShortcutBindings.DEFAULT

    /**
     * 已被我们在 ACTION_DOWN 上消费的物理键（keyCode → 登记），见 [HeldShortcutKey]。
     * 只在主线程（dispatchKeyEvent）里读写，不需要同步。
     */
    private val heldShortcutKeys = HashMap<Int, HeldShortcutKey>()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        watchNetwork()

        setContent {
            val navController = rememberNavController()

            // 手势配置放在 NavHost 之上：设置页写、终端页读，必须是同一份状态，
            // 否则改完设置回到终端还是旧绑定。load 只在首次组合时读一次盘；
            // 之后的修改走下面 settings 路由里的 onConfigChange（更新 state 并落盘）。
            var gestureConfig by remember {
                mutableStateOf(
                    GesturePrefs.load(this@MainActivity).also { shortcutBindings = it.shortcuts },
                )
            }
            // 把最新的快捷键绑定镜像给 dispatchKeyEvent（它在组合之外）。SideEffect 在每次成功
            // 重组之后执行，设置页改绑 → gestureConfig 变 → 这里同步，下一次按键就用新键位。
            SideEffect { shortcutBindings = gestureConfig.shortcuts }

            // All currently-connected sessions, regardless of which (if any)
            // is on screen right now — this is what lets the user switch
            // tabs or go back to the session list without tearing the SSH
            // connection down.
            val liveSessions = remember { mutableStateListOf<LiveSession>() }

            // Which live session the `terminal` route is showing. Held here
            // rather than as a route argument so tab switches are a state
            // change, not a navigation event (see this file's route doc).
            var activeTabId by remember { mutableStateOf<String?>(null) }

            // Surfaced on the session list; until now a failed connect set
            // this and nothing ever rendered it, so dialling a dead host
            // just quietly did nothing.
            var connectError by remember { mutableStateOf<String?>(null) }

            /**
             * The user-initiated close：切换器里上划关掉一张卡、首页卡片上的
             * 「断开」，以及通知栏「全部断开」之后 Rust 回调回来的那条
             * `on_closed(reason="user")`（见 `onClosedCallback`）。
             * "Gone for good" — dropping the session out of [liveSessions]
             * also disposes its retry effect, so this is simultaneously how
             * auto-reconnect gets cancelled.
             *
             * 可以被同一条会话调用两次而不出事（第二次 remove 找不到、
             * terminal 已经是 null、Rust 的 disconnect 本身幂等），不过真正
             * 的去重闸门是调用方检查 [LiveSession.disposed]。
             */
            fun disconnectLive(live: LiveSession) {
                live.disposed = true
                liveSessions.remove(live)
                if (activeTabId == live.tabId) {
                    activeTabId = liveSessions.firstOrNull()?.tabId
                }
                // Detach-then-destroy, before the composition that still
                // shows this tab gets a chance to draw it again.
                live.terminal?.destroy()
                live.terminal = null
                lifecycleScope.launch { runCatching { SshClient.disconnect(live.liveSessionId) } }
            }

            /**
             * Dials a brand-new SSH connection for [session] and opens it in
             * a new tab. A saved session can be connected to as many times as
             * the user likes — each dial gets its own live session id, its own
             * terminal, and its own tab.
             */
            fun connect(session: SessionRecord) {
                connectError = null
                lifecycleScope.launch {
                    try {
                        val auth = authMethodFor(session)
                        val live = LiveSession(
                            tabId = UUID.randomUUID().toString(),
                            liveSessionId = "", // filled in once connect() returns, see below
                            savedSessionId = session.id,
                            displayName = session.name.ifBlank { session.host },
                        )
                        val listener = SessionListenerImpl(
                            onDataCallback = { _, bytes ->
                                // UniFFI calls this back on a tokio/JNI
                                // background thread (see SessionListenerImpl),
                                // but WebView.evaluateJavascript() is
                                // main-thread-only — hop before writing.
                                lifecycleScope.launch(Dispatchers.Main) {
                                    val terminal = live.terminal
                                    if (terminal == null) {
                                        live.pendingData.add(bytes)
                                    } else {
                                        terminal.writeBase64(bytes)
                                    }
                                }
                            },
                            onPulseCallback = { /* hook up a heartbeat indicator later */ },
                            onClosedCallback = { sessionId, reason ->
                                // Compose state + the terminal writes below are
                                // both main-thread-only too.
                                lifecycleScope.launch(Dispatchers.Main) {
                                    // A reconnect reaps the previous SSH session
                                    // (nexus-core only drops the registry entry on
                                    // close), and that reap calls back in here. The
                                    // id check is what stops that stale event from
                                    // declaring the freshly reconnected session dead.
                                    if (sessionId != live.liveSessionId) return@launch
                                    when (reason) {
                                        CLOSE_REASON_USER -> {
                                            // 「用户主动断开」有两个入口，到这里
                                            // 时它们的 UI 状态完全不同：
                                            //
                                            // 1) 标签上划关闭 / 首页「断开」→ 都走
                                            //    disconnectLive，UI 已经被同步清过
                                            //    了，这条回调只是事后通知；
                                            // 2) 通知栏「全部断开」→ Receiver 绕开
                                            //    Activity 直接调 Rust 的 disconnect
                                            //    （它必须这么做：Activity 可能已经
                                            //    没了，没人能替它执行）。这条回调
                                            //    是 UI 唯一一次得知这条连接没了的
                                            //    机会，不清理的话首页会一直亮着
                                            //    「●」、标签也还在，而底下的 SSH
                                            //    早断了。
                                            //
                                            // 幂等靠 disposed：它只由 disconnectLive
                                            // 置位，所以「清过」和「没清过」分得干净
                                            // ——情况 1 直接跳过，不会二次 destroy
                                            // 已经销毁的 WebView，也不会重复 remove。
                                            if (!live.disposed) disconnectLive(live)
                                        }
                                        CLOSE_REASON_LOST -> {
                                            live.state = LinkState.RECONNECTING
                                            live.dropCount++
                                        }
                                        else -> {
                                            // `exit`/`eof`: the far end shut the
                                            // channel down deliberately, so keep the
                                            // old behaviour of letting the banner
                                            // land and then dropping the tab.
                                            live.state = LinkState.CLOSED
                                            live.terminal?.notifyClosed(reason)
                                            delay(1500)
                                            if (!live.disposed) disconnectLive(live)
                                        }
                                    }
                                }
                            },
                            onAuthPromptCallback = { prompt ->
                                live.sawAuthPrompt = true
                                promptUserForAnswer(prompt)
                            },
                        )
                        live.listener = listener
                        Log.d(TAG, "connecting to ${session.host}:${session.port}")
                        val handle = SshClient.connect(
                            configId = session.id,
                            host = session.host,
                            port = session.port.toInt(),
                            username = session.username,
                            auth = auth,
                            listener = listener,
                            sidecar = !SidecarPrefs.isDenied(this@MainActivity, session.id),
                        )
                        Log.d(TAG, "connected, sessionId=${handle.sessionId}")
                        live.liveSessionId = handle.sessionId
                        live.terminal = createTerminal(
                            context = this@MainActivity,
                            // Read `live.liveSessionId` at call time, not
                            // capture time — it's still "" right now, and a
                            // reconnect will replace it again later.
                            // 这两个 lambda 是整个 app 里唯一「每次按键 / 每次
                            // 重排都会跨 FFI」的路径，也是之前进程猝死的现场：
                            // 读循环一旦 break 就把 data_rx drop 掉，下一次
                            // write() 立刻拿到 SessionException.Channel
                            // （"输入通道已关闭"），而裸 lifecycleScope.launch
                            // 里抛出的异常没有任何 handler —— 直接杀进程。
                            // 一次按键不该有这种后果，所以两边都收口到
                            // handleLiveFfiFailure。
                            onInput = { text ->
                                lifecycleScope.launch {
                                    try {
                                        SshClient.write(live.liveSessionId, text.toByteArray(Charsets.UTF_8))
                                    } catch (e: SessionException) {
                                        handleLiveFfiFailure(live, e, "发送按键")
                                    }
                                }
                            },
                            onResize = { cols, rows ->
                                lifecycleScope.launch {
                                    try {
                                        SshClient.resize(live.liveSessionId, cols, rows)
                                    } catch (e: SessionException) {
                                        handleLiveFfiFailure(live, e, "同步窗口尺寸")
                                    }
                                }
                            },
                        )
                        live.pendingData.forEach { live.terminal?.writeBase64(it) }
                        live.pendingData.clear()

                        liveSessions.add(live)
                        activeTabId = live.tabId
                        navController.navigate("terminal") { launchSingleTop = true }
                    } catch (e: SessionException) {
                        Log.e(TAG, "connect failed", e)
                        connectError = e.friendlyText()
                    }
                }
            }

            // Android 13+ hides every notification — the foreground service's
            // included — until this is granted. Not acted on either way: a
            // denial only hides the session count, the service still runs and
            // still keeps the sessions alive in the background.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> Log.d(TAG, "POST_NOTIFICATIONS granted=$granted") }

            // Mirrors the live (non-CLOSED) tabs into SessionService. CLOSED
            // tabs are left out on purpose: a tab whose retry ladder gave up
            // is dead weight the user still has to close, not a session worth
            // holding the process up for. Titles are part of the key so the
            // "（重连中 3/10）" suffix ticks along in the notification too.
            LaunchedEffect(Unit) {
                var askedPermission = false
                snapshotFlow {
                    // liveSessionId 也要带上：通知栏「全部断开」的 PendingIntent
                    // 靠它知道该断哪些会话。它本身不是 Compose 状态（重连时被
                    // 直接赋值），所以这里不会因为它变化而重新发射——但重连成功
                    // 时 state/statusSuffix 必然跟着变，那一次发射读到的就是刚
                    // 写进去的新 id，通知上的 id 列表因此不会过期。
                    liveSessions.filter { it.state != LinkState.CLOSED }
                        .map { Triple(it.state, it.tabTitle, it.liveSessionId) }
                }.distinctUntilChanged().collect { live ->
                    if (live.isEmpty()) {
                        SessionService.stop(this@MainActivity)
                        return@collect
                    }
                    SessionService.update(
                        context = this@MainActivity,
                        titles = live.map { it.second },
                        liveSessionIds = live.map { it.third },
                        reconnecting = live.count { it.first == LinkState.RECONNECTING },
                    )
                    // Asked on the first connect rather than at launch, so the
                    // prompt shows up with an obvious reason attached.
                    if (!askedPermission &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        askedPermission = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            // One retry loop per live session, owned by the composition rather
            // than by `lifecycleScope`: Compose disposes it the moment the
            // session leaves `liveSessions`, so closing a tab cancels its
            // retries for free — no Job to track, and no way to leak one past
            // the tab it belongs to. It sits up here, above the NavHost, and
            // deliberately not inside the `terminal` route: a drop that happens
            // while the user is on the session list or in the file browser
            // still has to reconnect.
            liveSessions.forEach { live ->
                key(live.tabId) {
                    LaunchedEffect(live.tabId) {
                        // collectLatest, so a second drop part-way through a
                        // reconnect restarts the ladder from attempt 1 instead
                        // of leaving two loops dialling the same host.
                        snapshotFlow { live.dropCount }.collectLatest { drops ->
                            if (drops > 0) reconnectLoop(live)
                        }
                    }
                    // 远端上下文探测：同样挂在标签上（标签关闭即取消），并以「是否 CONNECTED +
                    // dropCount」为键——一掉线 collectLatest 立刻取消正在跑的探测，重连成功
                    // （状态回到 CONNECTED）后从头再来一轮（先等 3 秒再探），探的是新会话。
                    LaunchedEffect(live.tabId) {
                        snapshotFlow { (live.state == LinkState.CONNECTED) to live.dropCount }
                            .distinctUntilChanged()
                            .collectLatest { (connected, _) ->
                                if (connected) probeLoop(live)
                            }
                    }
                }
            }

            NexusTermTheme {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    NavHost(navController = navController, startDestination = "sessionList") {
                        composable("sessionList") {
                            SessionListScreen(
                                onAddNew = { navController.navigate("editSession") },
                                onEdit = { session -> navController.navigate("editSession/${session.id}") },
                                onFiles = { session -> navController.navigate("sftp/${session.id}") },
                                onDeleteRequested = { session ->
                                    SessionStore.delete(session.id)
                                    SecretsStore.clear(this@MainActivity, session.id)
                                },
                                // 只投影出首页画得到的字段，LiveSession 本体
                                // （WebView / TerminalHandle）不出这个文件。
                                liveTabs = liveSessions.map { live ->
                                    LiveTabInfo(
                                        tabId = live.tabId,
                                        savedSessionId = live.savedSessionId,
                                        title = live.tabTitle,
                                        status = live.state.toTabStatus(),
                                        openedAtMillis = live.openedAtMillis,
                                    )
                                },
                                // 首页的「断开」只断用户点中的那一条标签，不是
                                // 这条配置下的全部——同一个配置可以开好几个标签，
                                // 一起断掉会误杀另一个正在跑的活。复用上划关闭
                                // 那条路径（disconnectLive），断开语义全 app 只
                                // 有一处实现。tabId 可能已经不在表里（用户在菜单
                                // 开着时让它断了），firstOrNull 直接吞掉。
                                onDisconnectTab = { tab ->
                                    liveSessions.firstOrNull { it.tabId == tab.tabId }
                                        ?.let(::disconnectLive)
                                },
                                connectError = connectError,
                                onConnect = { session ->
                                    // Already connected from this saved session? Jump back into
                                    // the existing live tab instead of dialing a second connection.
                                    // "新标签" (onNewTab) is how the user asks for a *second*
                                    // connection to the same host.
                                    val existing = liveSessions.firstOrNull { it.savedSessionId == session.id }
                                    if (existing != null) {
                                        // Tapping a tab that's sitting out a backoff is as
                                        // clear a "try now" as the user can give us.
                                        if (existing.state == LinkState.RECONNECTING) {
                                            retryNudge.update { it + 1 }
                                        }
                                        activeTabId = existing.tabId
                                        navController.navigate("terminal") { launchSingleTop = true }
                                    } else {
                                        connect(session)
                                    }
                                },
                                onNewTab = { session -> connect(session) },
                                onOpenSettings = { navController.navigate("settings") { launchSingleTop = true } },
                                // 帮助页的「外接键盘快捷键」要按用户当前（可能已改绑）的键位生成。
                                shortcuts = gestureConfig.shortcuts,
                            )
                        }

                        composable("settings") {
                            GestureSettingsScreen(
                                config = gestureConfig,
                                onConfigChange = { new ->
                                    // 先更新 state 再落盘：UI 立刻反映，写盘是 apply() 异步的。
                                    gestureConfig = new
                                    GesturePrefs.save(this@MainActivity, new)
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable("editSession") {
                            EditSessionRoute(sessionId = null, onDone = { navController.popBackStack() })
                        }
                        composable(
                            route = "editSession/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getString("sessionId")
                            EditSessionRoute(sessionId = sessionId, onDone = { navController.popBackStack() })
                        }

                        composable("terminal") {
                            val live = liveSessions.firstOrNull { it.tabId == activeTabId }

                            if (live == null) {
                                // Either every tab is gone, or the active one was just
                                // closed. Fall back to another tab if there is one;
                                // otherwise there's nothing to show.
                                LaunchedEffect(liveSessions.size) {
                                    val next = liveSessions.firstOrNull()
                                    if (next != null) {
                                        activeTabId = next.tabId
                                    } else {
                                        navController.popBackStack("sessionList", inclusive = false)
                                    }
                                }
                            } else {
                                // No tab strip: the switcher's own cards carry the
                                // session name, so a permanent row would just cost
                                // vertical space on a phone. 关闭一条会话现在有三个
                                // 入口（这里的上划、首页卡片上的「断开」、通知栏的
                                // 「全部断开」），但从终端界面本身出发仍然只有
                                // 这一个，所以切换器在只剩一个标签时也要能打开。
                                //
                                // `terminal` is non-null for anything that made it into
                                // `liveSessions` — it's assigned before the session is
                                // added — so mapNotNull drops nothing.
                                //
                                // A dropped session keeps its tab (and its WebView, and
                                // so its scrollback) for the whole retry ladder; reading
                                // `tabTitle` here is what gets "（重连中 3/10）" onto its
                                // switcher card without widening [TerminalTab].
                                TerminalTabsPager(
                                    modifier = Modifier.fillMaxSize(),
                                    tabs = liveSessions.mapNotNull { session ->
                                        session.terminal?.let {
                                            TerminalTab(
                                                id = session.tabId,
                                                title = session.tabTitle,
                                                name = session.shownName,
                                                handle = it,
                                            )
                                        }
                                    },
                                    activeTabId = live.tabId,
                                    gestureConfig = gestureConfig,
                                    onActiveTabChange = { activeTabId = it },
                                    onCloseTab = { id ->
                                        liveSessions.firstOrNull { it.tabId == id }
                                            ?.let(::disconnectLive)
                                    },
                                    onOpenSftp = { id ->
                                        liveSessions.firstOrNull { it.tabId == id }?.let {
                                            navController.navigate("sftp/${it.savedSessionId}")
                                        }
                                    },
                                    // 空白 = 清掉自定义名，回到配置名/主机。
                                    onRenameTab = { id, name ->
                                        liveSessions.firstOrNull { it.tabId == id }?.customName =
                                            name.trim().takeIf { it.isNotEmpty() }
                                    },
                                    // 键盘「新建标签」：复制当前标签的连接，**另开**一条新的。
                                    // 和首页「新标签」同一个 connect(session)——它本来就总是拨
                                    // 新连接，「已连接就跳回去」那段逻辑只在首页的 onConnect 里，
                                    // 所以这里不需要 forceNew 之类的参数。配置要按 savedSessionId
                                    // 从库里读出来（LiveSession 只存了 id）；读不到（配置已被
                                    // 删）就什么都不做，和重连循环对「配置已被删除」的处理一致。
                                    onNewTab = { tabId ->
                                        val savedId = liveSessions.firstOrNull { it.tabId == tabId }?.savedSessionId
                                        if (!savedId.isNullOrBlank()) {
                                            lifecycleScope.launch {
                                                val saved = runCatching {
                                                    SessionStore.list().firstOrNull { it.id == savedId }
                                                }.getOrNull()
                                                if (saved != null) connect(saved)
                                            }
                                        }
                                    },
                                    shortcutHost = shortcutHost,
                                )
                            }
                        }

                        composable(
                            route = "sftp/{sessionId}",
                            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
                            SftpScreen(sessionId = sessionId, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    /**
     * Re-dials one dropped session, with exponential backoff, into the *same*
     * [LiveSession] — same tab, same WebView, same scrollback; only
     * [LiveSession.liveSessionId] changes, which is exactly what the
     * `onInput`/`onResize` closures in `connect` read at call time for.
     *
     * Not cancellable from the inside: the caller runs it in a LaunchedEffect
     * keyed on the tab, and closing the tab disposes that.
     */
    private suspend fun reconnectLoop(live: LiveSession) {
        var seenNudge = retryNudge.value
        var attempt = 1
        // 掉线前最后一次探测到的上下文，循环一开始就定下来。探测在非 CONNECTED 时不写
        // live.ctx，这里再取一份快照只是双保险：还原用的永远是「掉线前」的现场，
        // 不会被重连后新登录 shell 的 $HOME 覆盖。
        val ctxBeforeDrop = live.ctx

        while (attempt <= RECONNECT_MAX) {
            // Re-read the saved session every attempt rather than caching it:
            // "delete the broken host, fix it, re-add it" is a plausible thing
            // to do while a phone is sitting on a 30s backoff, and we'd rather
            // notice the deletion than keep dialling a config that's gone.
            val saved = runCatching {
                SessionStore.list().firstOrNull { it.id == live.savedSessionId }
            }.getOrNull()
            if (saved == null) {
                // runCatching swallows CancellationException too, so a tab
                // closed mid-lookup lands here as well — don't tell the user
                // their config was deleted when it's their own close.
                if (live.disposed) return
                live.state = LinkState.CLOSED
                live.statusSuffix = "（已断开）"
                live.terminal?.writeNotice("连接已断开，且这条会话的配置已被删除，无法自动重连", SGR_RED)
                return
            }

            val waitMs = reconnectDelayMs(attempt)
            live.state = LinkState.RECONNECTING
            live.statusSuffix = "（重连中 $attempt/$RECONNECT_MAX）"
            live.terminal?.writeNotice("连接已断开，${waitMs / 1000} 秒后进行第 $attempt/$RECONNECT_MAX 次重连…")

            // Park on the nudge rather than a plain delay(): once the ladder
            // reaches its 30s ceiling, the backoff *is* the reason the app
            // looks like it never reconnects after the user turns their data
            // back on. Comparing against a value sampled before the previous
            // dial also covers the network returning *during* that dial —
            // first() on a StateFlow sees the current value immediately, so
            // that case skips the wait instead of sleeping through it.
            withTimeoutOrNull(waitMs) { retryNudge.first { it != seenNudge } }
            seenNudge = retryNudge.value

            // The dead session is still registered on the Rust side (its read
            // loop ended; `SessionManager` only drops the entry on close), and
            // nexus-core keys the shared authenticated connection the SFTP
            // panel borrows off `config_id` — leaving a corpse there would hand
            // that panel a connection that can't be used. close() is idempotent.
            runCatching { SshClient.disconnect(live.liveSessionId) }

            live.sawAuthPrompt = false
            live.terminal?.writeNotice("正在重连 ${saved.host}:${saved.port}…")

            // NonCancellable: the handshake is a blocking JNI call that can't
            // be interrupted, so cancelling here would only mean we stop
            // *waiting* for it — the connection would still get established,
            // authenticated, and then orphaned with nobody holding its id.
            // Let it finish and hang up explicitly below instead.
            // 取在拨号之前：waitForPrompt 用它区分「重连前屏幕上残留的旧提示符」和新 shell 的输出。
            val since = SystemClock.elapsedRealtime()
            val dialed = withContext(NonCancellable) {
                runCatching {
                    SshClient.connect(
                        configId = saved.id,
                        host = saved.host,
                        port = saved.port.toInt(),
                        username = saved.username,
                        auth = authMethodFor(saved),
                        listener = live.listener!!,
                        sidecar = !SidecarPrefs.isDenied(this@MainActivity, saved.id),
                    )
                }
            }

            val handle = dialed.getOrNull()
            if (live.disposed) {
                if (handle != null) runCatching { SshClient.disconnect(handle.sessionId) }
                return
            }

            if (handle != null) {
                Log.d(TAG, "reconnected tab=${live.tabId} sessionId=${handle.sessionId}")
                live.liveSessionId = handle.sessionId
                // 先放开状态再做还原：还原要等提示符（最长 10 秒），不能让标签在这段时间里
                // 还显示「重连中」。restoring 与 state 同步置位，探测因此不会抢跑。
                live.restoring = ctxBeforeDrop != null
                live.state = LinkState.CONNECTED
                live.statusSuffix = ""
                live.terminal?.writeNotice("重连成功", SGR_GREEN)
                // nexus-core opens every pty at its hardcoded 120x32 and the
                // WebView never fired a `resize` (it was never detached), so
                // without this the new shell believes it's on a 120-column
                // terminal for the rest of its life.
                live.terminal?.refit()
                // 就在本协程里做（不另开 Job）：再次掉线时 collectLatest 取消整段，还原随之取消，
                // 不会往已经死掉的会话里写；状态机此时已经是 CONNECTED，没有被它卡住的东西。
                restoreContext(live, ctxBeforeDrop, since)
                return
            }

            val error = dialed.exceptionOrNull()
            Log.w(TAG, "reconnect attempt $attempt failed for tab=${live.tabId}", error)
            val text = (error as? SessionException)?.friendlyText() ?: error?.message ?: "未知错误"

            // Credentials don't repair themselves, and a keyboard-interactive
            // host would be re-prompted on every rung of the ladder — with
            // promptUserForAnswer still auto-cancelling that can never succeed,
            // it just burns ten attempts and ten 2FA codes. Stop and say so.
            if (live.sawAuthPrompt ||
                error is SessionException.Auth ||
                error is SessionException.HostKeyMismatch
            ) {
                live.state = LinkState.CLOSED
                live.statusSuffix = "（已断开）"
                live.terminal?.writeNotice("重连失败：$text。请关闭此标签后手动重新连接", SGR_RED)
                return
            }

            live.terminal?.writeNotice("第 $attempt 次重连失败：$text", SGR_RED)
            attempt++
        }

        live.state = LinkState.CLOSED
        live.statusSuffix = "（已断开）"
        live.terminal?.writeNotice(
            "已重试 $RECONNECT_MAX 次仍未连上，停止自动重连。请关闭此标签后手动重新连接",
            SGR_RED,
        )
    }

    /**
     * 每个已连接标签一条的探测循环：先等 [PROBE_FIRST_DELAY_MS]，之后每 [PROBE_INTERVAL_MS]
     * 经旁路 exec 通道探一次远端 cwd / tmux / 前台进程，结果存进 [LiveSession.ctx]，供掉线重连后
     * [restoreContext] 还原现场。由 per-tab 的 LaunchedEffect 以 CONNECTED 状态为键驱动，所以
     * 掉线即被取消、重连成功后重新开始。
     *
     * 探测绝不能打扰用户：所有失败都只记 debug 日志、不碰终端、不弹提示。唯一的例外是服务器明确
     * 拒绝并行通道（堡垒机只允许一条通道）——此时把这个配置记入 [SidecarPrefs]，永远不再探测，
     * 且之后对它的连接都不再开旁路通道。
     */
    private suspend fun probeLoop(live: LiveSession) {
        delay(PROBE_FIRST_DELAY_MS)
        while (true) {
            if (live.disposed || live.state != LinkState.CONNECTED) return
            if (SidecarPrefs.isDenied(this, live.savedSessionId)) return
            // 还原现场期间不探（见 LiveSession.restoring），等下一轮。
            if (!live.restoring) {
                val sessionId = live.liveSessionId
                try {
                    val raw = SshClient.probe(sessionId)
                    // 结果回来时世界可能已变（掉线 / 重连换了会话 / 开始还原）：不再成立就丢弃。
                    if (!live.disposed && live.state == LinkState.CONNECTED && !live.restoring &&
                        sessionId == live.liveSessionId
                    ) {
                        parseProbe(raw, System.currentTimeMillis())?.let { live.ctx = it }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 同时看 message 和 Channel 的载荷：生成代码的 message 形如 "v1=..."，
                    // 文案放在哪个字段上以绑定为准，两处都查最稳。
                    val text = e.message.orEmpty() + ((e as? SessionException.Channel)?.v1.orEmpty())
                    when {
                        text.contains("并行通道") -> {
                            Log.d(TAG, "服务器不支持并行通道，停止探测 config=${live.savedSessionId}")
                            SidecarPrefs.markDenied(this, live.savedSessionId)
                            return
                        }
                        // 本连接是在标记之前以 sidecar=true 拨出的另一条标签等情形：旁路没开，探也白探。
                        text.contains("兼容模式") -> return
                        else -> Log.d(TAG, "探测失败（忽略） tab=${live.tabId}: ${e.message}")
                    }
                }
            }
            delay(PROBE_INTERVAL_MS)
        }
    }

    /**
     * 重连成功后还原远端现场：等新 shell 出提示符，然后「悄悄」输入 `cd <目录>; tmux attach -t <会话>`
     * （前置空格不进 history，并用 [stealthInput] 抹掉回显），再在终端里留一行说明。
     *
     * [ctx] 是掉线**之前**的上下文；null（从未探测成功 / 堡垒机关闭了探测）什么都不做。
     * docker 只提示不重放（见 [restoreCommand]）。取消安全：全程只有可取消的挂起点，
     * 再次掉线时被 collectLatest 取消，`finally` 负责放开探测。
     */
    private suspend fun restoreContext(live: LiveSession, ctx: TermContext?, since: Long) {
        if (ctx == null) return
        try {
            val terminal = live.terminal ?: return
            val body = restoreCommand(ctx)
            if (body == null) {
                // 只剩 docker：没有可执行的还原，只提示。
                restoreNotice(ctx).forEach { (label, text) ->
                    terminal.writeNotice("$label：$text", SGR_YELLOW)
                }
                return
            }

            val prompt = terminal.waitForPrompt(since)
            if (prompt == null) {
                terminal.writeNotice("未能自动还原目录/会话，请手动执行：$body", SGR_YELLOW)
                return
            }
            // 等提示符期间用户可能关了标签 / 又掉线了：别往不对的会话里写。
            if (live.disposed || live.state != LinkState.CONNECTED) return

            val input = stealthInput(body, prompt.cursorX, prompt.cols, ::cellWidth)
            val sentAt = SystemClock.elapsedRealtime()
            try {
                SshClient.write(live.liveSessionId, input.toByteArray(Charsets.UTF_8))
            } catch (e: SessionException) {
                handleLiveFfiFailure(live, e, "还原目录/会话")
                return
            }

            // tmux attach 会切到备用屏幕由 tmux 接管整个画面，这时再往终端里写说明只会变成
            // 画面上抹不掉的残影；而且 tmux 画面本身就是「已还原」最直观的证明——不写。
            if (!ctx.tmux.isNullOrEmpty()) return

            // 说明必须等远端把 stealth 序列的回显和「上移 N 行 + 清屏」都画完再写，否则光标
            // 还没上移，说明先落了地，随后就被那条清屏抹掉。以「发出之后有输出且安静 250ms」为准，
            // 最多等 3 秒；超时了也照写（多半是命令很慢，说明留在原地总比丢了好）。
            withTimeoutOrNull(3_000) {
                while (!(terminal.lastOutputMs >= sentAt &&
                        SystemClock.elapsedRealtime() - terminal.lastOutputMs > 250)
                ) {
                    delay(50)
                }
            }
            if (live.disposed || live.state != LinkState.CONNECTED) return

            val rows = restoreNotice(ctx)
            val done = rows.filter { it.first != "docker" }
            if (done.isNotEmpty()) {
                terminal.writeNotice("已还原：" + done.joinToString(" · ") { "${it.first} ${it.second}" }, SGR_GREEN)
            }
            rows.filter { it.first == "docker" }.forEach { (label, text) ->
                terminal.writeNotice("$label：$text", SGR_YELLOW)
            }
        } finally {
            live.restoring = false
        }
    }

    /**
     * What to do when a *per-interaction* FFI call (keystroke, resize) against
     * a live session throws. Runs on the main dispatcher, so touching Compose
     * state and the WebView from here is safe.
     *
     * The crash this exists for: nexus-core's read loop ends (`reason=lost`),
     * which drops `data_rx`; the very next `write()` gets
     * `SessionError::Channel("输入通道已关闭")` back, UniFFI lifts it into
     * [SessionException.Channel], and an unguarded `lifecycleScope.launch`
     * has nowhere to put it — the process dies on a keystroke. Catching is
     * necessary but not sufficient: swallowing it would leave the user typing
     * into a terminal that silently eats everything, which is arguably worse.
     *
     * So the two variants are treated differently:
     *
     * - [SessionException.Channel] / [SessionException.NotFound] both mean
     *   *this session no longer exists on the Rust side* — Channel because its
     *   input queue is closed, NotFound because `SessionManager` has already
     *   dropped the registry entry. That is exactly the state `onClosedCallback`
     *   reports as [CLOSE_REASON_LOST], so it's routed into the same machinery:
     *   flip to [LinkState.RECONNECTING] and bump [LiveSession.dropCount],
     *   which the per-tab `snapshotFlow` collector turns into a [reconnectLoop].
     *   Not a new UI — the retry ladder writes its own progress notices.
     *
     *   Why this matters even though `on_closed` normally fires too: the two
     *   race, and either can land first. If the write throws first we start
     *   reconnecting ~immediately instead of waiting for the callback; if the
     *   callback won, [LiveSession.state] is already RECONNECTING and the guard
     *   below makes this a no-op rather than a second ladder from attempt 1.
     *
     * - Anything else (Connect/Auth/HostKeyMismatch — not reachable from
     *   write/resize today, but the `when` in [friendlyText] is exhaustive and
     *   this shouldn't start crashing if that changes) is reported in the
     *   terminal and otherwise left alone: we don't know the session is dead,
     *   so tearing it down would be guessing.
     *
     * The CONNECTED guard also debounces: `onResize` fires repeatedly while the
     * IME animates, and without it a dead session would paint a wall of
     * identical red notices over the user's scrollback.
     */
    private fun handleLiveFfiFailure(live: LiveSession, e: SessionException, action: String) {
        val sessionIsGone = e is SessionException.Channel || e is SessionException.NotFound

        if (!sessionIsGone) {
            Log.w(TAG, "$action 失败 tab=${live.tabId}", e)
            live.terminal?.writeNotice("$action 失败：${e.friendlyText()}", SGR_RED)
            return
        }

        // Already reconnecting (or given up)? The existing machinery owns the
        // session from here; staying quiet is the whole point of the guard.
        if (live.state != LinkState.CONNECTED) return

        Log.w(TAG, "$action 时发现会话已失效 tab=${live.tabId}，转入自动重连", e)
        live.terminal?.writeNotice("连接已失效（$action 时发现：${e.friendlyText()}）", SGR_RED)
        live.state = LinkState.RECONNECTING
        live.dropCount++
    }

    /**
     * Wakes every backed-off retry loop when the phone gets a default network
     * again. This is the half the desktop app doesn't need: on a laptop the
     * backoff timer is usually good enough, but on a phone the user *watches*
     * themselves turn wifi back on and expects the sessions to follow
     * immediately, not up to [RECONNECT_MAX_DELAY_MS] later.
     *
     * [ConnectivityManager.registerDefaultNetworkCallback] requires
     * `android.permission.ACCESS_NETWORK_STATE`, which is NOT in
     * AndroidManifest.xml as of writing — without it this throws
     * SecurityException. Caught here so the app degrades to plain backoff
     * instead of dying on startup, but the permission really does need adding.
     */
    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Delivered on a framework handler thread; MutableStateFlow is
                // safe to write from any thread and the collectors are already
                // on the main dispatcher.
                Log.d(TAG, "default network available, nudging reconnects")
                retryNudge.update { it + 1 }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            // SecurityException = ACCESS_NETWORK_STATE missing from the
            // manifest; IllegalArgumentException = the per-uid NetworkRequest
            // limit. Neither is worth crashing over.
            Log.w(TAG, "无法监听网络状态，自动重连只能依赖退避定时器", e)
        }
    }

    /**
     * 物理键盘快捷键的拦截点。Activity 的 dispatchKeyEvent 是 DecorView 把按键交给焦点
     * View（WebView / Compose 输入框）**之前**调用的，所以这里能先于终端看到按键。
     *
     * ## 消费规则
     *  - 只处理 ACTION_DOWN / ACTION_UP；ACTION_MULTIPLE、纯修饰键 / 锁定键一律放行。
     *  - DOWN（repeatCount == 0，即一次新的物理按键）：
     *      1. 用 [ShortcutBindings.actionFor] 匹配键位，命中且 `shortcutHost.perform` 返回 true
     *         → 消费，并把这个 keyCode 登记进 [heldShortcutKeys]；
     *      2. 否则，如果没有任何修饰键，问切换器 `shortcutHost.onSwitcherKey`，返回 true → 消费并登记；
     *      3. 否则放行（Ctrl+C / Ctrl+Z / Ctrl+字母 / Alt+字母 / Tab / 方向键……原样给终端）。
     *  - DOWN（repeatCount > 0，长按重复）：**不重新匹配键位**。登记过 = 这次物理按键起手
     *    就被我们吃了，重复也吃；其中只有 [REPEATABLE_SHORTCUT_ACTIONS]（切标签、字号加减）
     *    和切换器的左右方向键会重新执行，其它动作一次物理按键只触发一次。没登记过 = 放行
     *    （比如先按住 A 再按 Ctrl，A 的重复事件不能变成快捷键）。
     *  - UP：登记过就消费并注销（哪怕 Ctrl 已经先松开了），WebView 永远不会看到孤儿 key-up；
     *    没登记过就放行。
     *  - 起手 DOWN 没被消费时顺手清掉同 keyCode 的旧登记，防止应用失焦时丢了 UP 留下陈旧
     *    条目、误吃以后一次正常按键的 UP。
     *
     * 不要求 `isHardwareKeyEvent(event)`：adb 注入的事件可能报告虚拟/空设备
     * （见 HardKeyboardMonitor 里的说明），要求它会让 `adb shell input keycombination` 测不了。
     *
     * ## 已知风险
     * 部分布局（如德语）里 AltGr 会以 Ctrl+Alt 的形式到达，于是默认的 Ctrl+Alt+数字
     * （直达第 n 个标签）可能和 AltGr+数字 打出的字符（如 `{` `[` `]` `}`）撞车。键位在
     * 设置页里可以改绑，这里不做特殊处理。
     *
     * 终端页不在屏幕上时 shortcutHost 的两个 handler 都是 null，perform / onSwitcherKey 都返回
     * false，于是首页、SFTP、设置页（含文本框）的按键处理完全不受影响。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val type = event.action
        val keyCode = event.keyCode
        if ((type != KeyEvent.ACTION_DOWN && type != KeyEvent.ACTION_UP) || isModifierOrLockKey(keyCode)) {
            return super.dispatchKeyEvent(event)
        }

        // UP：起手被我们吃掉的，UP 也必须吃掉；不看当前修饰键状态。
        if (type == KeyEvent.ACTION_UP) {
            return if (heldShortcutKeys.remove(keyCode) != null) true else super.dispatchKeyEvent(event)
        }

        // DOWN 的长按重复：只看起手时的登记，不重新匹配。
        if (event.repeatCount > 0) {
            val held = heldShortcutKeys[keyCode] ?: return super.dispatchKeyEvent(event)
            if (held.repeats) {
                val action = held.action
                if (action != null) shortcutHost.perform(action) else shortcutHost.onSwitcherKey(keyCode)
            }
            return true
        }

        // 一次新的物理按键。
        heldShortcutKeys.remove(keyCode)

        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val meta = event.isMetaPressed

        val action = shortcutBindings.actionFor(keyCode, ctrl, shift, alt, meta)
        if (action != null && shortcutHost.perform(action)) {
            heldShortcutKeys[keyCode] = HeldShortcutKey(action, action in REPEATABLE_SHORTCUT_ACTIONS)
            return true
        }

        // 会话切换器打开时它自己认的键（左右 / Enter / Esc / Delete）；切换器没开时返回 false，
        // 普通的方向键、Enter、Esc 照常到终端。只在无修饰键时问，Ctrl+方向键等属于远端程序。
        if (!ctrl && !shift && !alt && !meta && shortcutHost.onSwitcherKey(keyCode)) {
            heldShortcutKeys[keyCode] = HeldShortcutKey(null, keyCode in REPEATABLE_SWITCHER_KEYS)
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * Meta+/ 打开的系统「键盘快捷键」面板的内容来源。面板**只是展示**：系统不会替我们派发
     * 面板里列的组合键，真正生效的是上面的 [dispatchKeyEvent]；所以这里列的必须和
     * [shortcutBindings]（含用户改绑）保持一致，由它现场生成，而不是写死一张表。
     *
     * 每个 [ShortcutGroup] 一组；未绑定的动作跳过；GOTO_TAB_1..9 折叠成一行
     * 「直达第 N 个标签（数字键 1–9）」，取 GOTO_TAB_1 的第一个键位当代表（九个键位
     * 只差最后的数字，列九行没有信息量）。
     */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        val bindings = shortcutBindings
        for (group in ShortcutGroup.entries) {
            val infos = ArrayList<KeyboardShortcutInfo>()
            for (action in ShortcutAction.inGroup(group)) {
                if (action.tabIndex != null) {
                    // GOTO_TAB_n 只在 n == 1 处出一行，其余跳过。
                    if (action != ShortcutAction.GOTO_TAB_1) continue
                    val chord = bindings.chordsFor(action).firstOrNull() ?: continue
                    infos += KeyboardShortcutInfo("直达第 N 个标签（数字键 1–9）", chord.keyCode, chord.toMetaState())
                    continue
                }
                for (chord in bindings.chordsFor(action)) {
                    infos += KeyboardShortcutInfo(action.label, chord.keyCode, chord.toMetaState())
                }
            }
            if (infos.isNotEmpty()) data += KeyboardShortcutGroup(group.label, infos)
        }
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            networkCallback = null
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
        // `liveSessions` lives in this activity's composition, so once the
        // activity is finishing nothing can ever reattach to those tabs —
        // keeping the process pinned in the foreground for them would just be
        // a notification lying about sessions the user can't reach. (Not
        // done for a config-change recreate: the new composition's first
        // emission handles that case itself.)
        if (isFinishing) SessionService.stop(this)
        super.onDestroy()
    }

    private fun authMethodFor(session: SessionRecord): AuthMethod = when (session.authType) {
        "key" -> AuthMethod.PrivateKey(
            path = session.keyPath ?: "",
            passphrase = SecretsStore.getPassphrase(this, session.id),
        )
        "agent" -> AuthMethod.Agent
        else -> AuthMethod.Password(password = SecretsStore.getPassword(this, session.id) ?: "")
    }

    /**
     * Placeholder UI for keyboard-interactive / private-key-passphrase
     * prompts, carried over unchanged from the vertical slice: a real dialog
     * built from `prompt.fields` is still future work, so this auto-cancels
     * (empty string) and lets a 2FA server fail fast with a clear message
     * instead of hanging for 180s (see [SessionListenerImpl]'s own timeout).
     */
    private suspend fun promptUserForAnswer(prompt: AuthPrompt): String {
        return ""
    }

    /**
     * Hosts [ConnectScreen] for both "new session" (`sessionId == null`) and
     * "edit session" (loads the existing [SessionRecord] + secrets first).
     * Kept as a private composable function rather than a top-level one
     * since it owns the save/cancel wiring specific to this activity's nav
     * graph (nothing about it is reusable outside this screen's route).
     */
    @androidx.compose.runtime.Composable
    private fun EditSessionRoute(sessionId: String?, onDone: () -> Unit) {
        var initial by remember { mutableStateOf<SessionFormInitial?>(if (sessionId == null) SessionFormInitial() else null) }
        var isSaving by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        androidx.compose.runtime.LaunchedEffect(sessionId) {
            if (sessionId != null) {
                try {
                    val record = SessionStore.list().firstOrNull { it.id == sessionId }
                    initial = if (record == null) {
                        errorMessage = "会话不存在"
                        SessionFormInitial()
                    } else {
                        SessionFormInitial(
                            name = record.name,
                            host = record.host,
                            port = record.port.toInt(),
                            username = record.username,
                            authKind = when (record.authType) {
                                "key" -> AuthKind.PRIVATE_KEY
                                "agent" -> AuthKind.AGENT
                                else -> AuthKind.PASSWORD
                            },
                            keyPath = record.keyPath ?: "",
                            password = SecretsStore.getPassword(this@MainActivity, record.id) ?: "",
                            passphrase = SecretsStore.getPassphrase(this@MainActivity, record.id) ?: "",
                        )
                    }
                } catch (e: StoreException) {
                    errorMessage = e.message ?: "无法加载会话"
                    initial = SessionFormInitial()
                }
            }
        }

        val loaded = initial
        if (loaded == null) {
            return
        }

        ConnectScreen(
            initial = loaded,
            isSaving = isSaving,
            errorMessage = errorMessage,
            onCancel = onDone,
            onSave = { result ->
                isSaving = true
                errorMessage = null
                lifecycleScope.launch {
                    try {
                        val authType = when (result.authKind) {
                            AuthKind.PASSWORD -> "password"
                            AuthKind.PRIVATE_KEY -> "key"
                            AuthKind.AGENT -> "agent"
                        }
                        val newSession = NewSession(
                            name = result.name,
                            folder = null,
                            host = result.host,
                            port = result.port.toUShort(),
                            username = result.username,
                            authType = authType,
                            keyPath = result.keyPath.ifBlank { null },
                            startupCmd = null,
                            theme = null,
                            connectTimeout = null,
                        )
                        val saved = if (sessionId == null) {
                            SessionStore.create(newSession)
                        } else {
                            SessionStore.update(sessionId, newSession)
                        }
                        when (result.authKind) {
                            AuthKind.PASSWORD -> SecretsStore.savePassword(this@MainActivity, saved.id, result.password)
                            AuthKind.PRIVATE_KEY -> SecretsStore.savePassphrase(this@MainActivity, saved.id, result.passphrase)
                            AuthKind.AGENT -> SecretsStore.clear(this@MainActivity, saved.id)
                        }
                        onDone()
                    } catch (e: StoreException) {
                        errorMessage = e.message ?: "保存失败"
                    } finally {
                        isSaving = false
                    }
                }
            },
        )
    }
}

/**
 * UniFFI generates `message` as a debug-shaped `"v1=…"` for every variant
 * carrying a field, which is useless to show a user — nexus-core already put
 * a finished Chinese sentence in that field (see `SessionError` in
 * ssh/session.rs). This unwraps it.
 */
private fun SessionException.friendlyText(): String = when (this) {
    is SessionException.Connect -> v1
    is SessionException.Auth -> v1
    is SessionException.Channel -> v1
    is SessionException.NotFound -> v1
    is SessionException.HostKeyMismatch -> "主机公钥指纹不匹配，疑似中间人攻击"
}
