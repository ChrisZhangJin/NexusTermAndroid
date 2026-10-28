package com.nexusterm.android

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
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
import com.nexusterm.android.ssh.SshClient
import com.nexusterm.android.ui.AuthKind
import com.nexusterm.android.ui.ConnectScreen
import com.nexusterm.android.ui.SessionFormInitial
import com.nexusterm.android.ui.SessionListScreen
import com.nexusterm.android.ui.SftpScreen
import com.nexusterm.android.ui.TerminalHandle
import com.nexusterm.android.ui.TerminalTab
import com.nexusterm.android.ui.TerminalTabsPager
import com.nexusterm.android.ui.createTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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

/** How the UI currently believes a [LiveSession]'s connection is doing. */
private enum class LinkState { CONNECTED, RECONNECTING, CLOSED }

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
     * Appended to the switcher card's title. Status has to reach the UI
     * through the existing [TerminalTab] shape — `title` is the only free
     * text field it has — so this is a suffix rather than a proper state
     * chip.
     */
    var statusSuffix by mutableStateOf("")

    val tabTitle: String get() = displayName + statusSuffix
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

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        watchNetwork()

        setContent {
            val navController = rememberNavController()

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
             * The user-initiated close (flick a card up in the switcher).
             * "Gone for good" — dropping the session out of [liveSessions]
             * also disposes its retry effect, so this is simultaneously how
             * auto-reconnect gets cancelled.
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
                                        CLOSE_REASON_USER -> Unit
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
                        )
                        Log.d(TAG, "connected, sessionId=${handle.sessionId}")
                        live.liveSessionId = handle.sessionId
                        live.terminal = createTerminal(
                            context = this@MainActivity,
                            // Read `live.liveSessionId` at call time, not
                            // capture time — it's still "" right now, and a
                            // reconnect will replace it again later.
                            onInput = { text ->
                                lifecycleScope.launch {
                                    SshClient.write(live.liveSessionId, text.toByteArray(Charsets.UTF_8))
                                }
                            },
                            onResize = { cols, rows ->
                                lifecycleScope.launch {
                                    SshClient.resize(live.liveSessionId, cols, rows)
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
                }
            }

            MaterialTheme {
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
                                connectedSessionIds = liveSessions.map { it.savedSessionId }.toSet(),
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
                                // vertical space on a phone. It does mean the switcher
                                // is now the only route to closing a session, which is
                                // why it stays reachable at a single tab.
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
                                                handle = it,
                                            )
                                        }
                                    },
                                    activeTabId = live.tabId,
                                    onActiveTabChange = { activeTabId = it },
                                    onCloseTab = { id ->
                                        liveSessions.firstOrNull { it.tabId == id }
                                            ?.let(::disconnectLive)
                                    },
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
            val dialed = withContext(NonCancellable) {
                runCatching {
                    SshClient.connect(
                        configId = saved.id,
                        host = saved.host,
                        port = saved.port.toInt(),
                        username = saved.username,
                        auth = authMethodFor(saved),
                        listener = live.listener!!,
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
                live.state = LinkState.CONNECTED
                live.statusSuffix = ""
                live.terminal?.writeNotice("重连成功", SGR_GREEN)
                // nexus-core opens every pty at its hardcoded 120x32 and the
                // WebView never fired a `resize` (it was never detached), so
                // without this the new shell believes it's on a 120-column
                // terminal for the rest of its life.
                live.terminal?.refit()
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

    override fun onDestroy() {
        networkCallback?.let { callback ->
            networkCallback = null
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
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
