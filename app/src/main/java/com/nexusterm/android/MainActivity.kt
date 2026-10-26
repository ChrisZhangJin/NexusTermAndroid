package com.nexusterm.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.nexusterm.android.ui.TerminalScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.nexus_core.AuthMethod
import uniffi.nexus_core.AuthPrompt
import uniffi.nexus_core.NewSession
import uniffi.nexus_core.SessionRecord
import uniffi.nexus_core.SessionException
import uniffi.nexus_core.StoreException

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
 * - `terminal/{liveSessionId}` — unchanged [TerminalScreen], just reached by
 *   navigating instead of a `when` branch. `liveSessionId` is the id
 *   `SshClient.connect` handed back, *not* the saved session's own id (a
 *   saved session can be connected to more than once, each with its own
 *   live SSH session id).
 * - `sftp/{sessionId}` — [SftpScreen], reached from a saved session's
 *   "文件" action; dials its own independent SFTP connection (see
 *   nexus-core/src/sftp/mod.rs's module doc on why it doesn't reuse the
 *   terminal connection yet).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val navController = rememberNavController()

            // Shared across the lifetime of a single terminal screen visit:
            // the listener is constructed *before* the WebView exists (it has
            // to be, since `SshClient.connect` needs it up front), so
            // `onData`/`onClosed` write into this var and `TerminalScreen`'s
            // `onReady` callback fills it in once the WebView is mounted —
            // same chicken/egg resolution the vertical slice used.
            var terminalHandle by remember { mutableStateOf<TerminalHandle?>(null) }
            var closedReason by remember { mutableStateOf<String?>(null) }
            // Bytes can (and routinely do — the shell banner/prompt lands
            // almost immediately after connect) arrive before the
            // TerminalScreen composable has even run its factory() and
            // produced a TerminalHandle. Without this buffer they were
            // silently dropped via `terminalHandle?.writeBase64(...)`.
            val pendingTerminalData = remember { mutableListOf<ByteArray>() }

            MaterialTheme {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    NavHost(navController = navController, startDestination = "sessionList") {
                        composable("sessionList") {
                            var connectError by remember { mutableStateOf<String?>(null) }

                            SessionListScreen(
                                onAddNew = { navController.navigate("editSession") },
                                onEdit = { session -> navController.navigate("editSession/${session.id}") },
                                onFiles = { session -> navController.navigate("sftp/${session.id}") },
                                onDeleteRequested = { session ->
                                    SessionStore.delete(session.id)
                                    SecretsStore.clear(this@MainActivity, session.id)
                                },
                                onConnect = { session ->
                                    connectError = null
                                    lifecycleScope.launch {
                                        try {
                                            val auth = authMethodFor(session)
                                            val listener = SessionListenerImpl(
                                                onDataCallback = { _, bytes ->
                                                    // UniFFI calls this back on a tokio/JNI
                                                    // background thread (see SessionListenerImpl),
                                                    // but WebView.evaluateJavascript() is
                                                    // main-thread-only — hop before writing.
                                                    lifecycleScope.launch(Dispatchers.Main) {
                                                        val handle = terminalHandle
                                                        if (handle == null) {
                                                            pendingTerminalData.add(bytes)
                                                        } else {
                                                            handle.writeBase64(bytes)
                                                        }
                                                    }
                                                },
                                                onPulseCallback = { /* hook up a heartbeat indicator later */ },
                                                onClosedCallback = { _, reason ->
                                                    // Compose state + notifyClosed are both
                                                    // main-thread-only too.
                                                    lifecycleScope.launch(Dispatchers.Main) {
                                                        closedReason = reason
                                                        terminalHandle?.notifyClosed(reason)
                                                    }
                                                },
                                                onAuthPromptCallback = { prompt -> promptUserForAnswer(prompt) },
                                            )
                                            Log.d("NexusTermConnect", "connecting to ${session.host}:${session.port}")
                                            val handle = SshClient.connect(
                                                host = session.host,
                                                port = session.port.toInt(),
                                                username = session.username,
                                                auth = auth,
                                                listener = listener,
                                            )
                                            Log.d("NexusTermConnect", "connected, sessionId=${handle.sessionId}")
                                            terminalHandle = null
                                            pendingTerminalData.clear()
                                            closedReason = null
                                            navController.navigate("terminal/${handle.sessionId}")
                                        } catch (e: SessionException) {
                                            Log.e("NexusTermConnect", "connect failed", e)
                                            connectError = e.message ?: "连接失败"
                                        }
                                    }
                                },
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

                        composable(
                            route = "terminal/{liveSessionId}",
                            arguments = listOf(navArgument("liveSessionId") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val liveSessionId = backStackEntry.arguments?.getString("liveSessionId") ?: ""

                            TerminalScreen(
                                onReady = { handle ->
                                    terminalHandle = handle
                                    pendingTerminalData.forEach { handle.writeBase64(it) }
                                    pendingTerminalData.clear()
                                },
                                onInput = { text ->
                                    lifecycleScope.launch {
                                        SshClient.write(liveSessionId, text.toByteArray(Charsets.UTF_8))
                                    }
                                },
                                onResize = { cols, rows ->
                                    lifecycleScope.launch {
                                        SshClient.resize(liveSessionId, cols, rows)
                                    }
                                },
                            )

                            if (closedReason != null) {
                                lifecycleScope.launch {
                                    // Give the WebView a moment to render the
                                    // "connection closed" banner before we
                                    // drop back to the session list.
                                    delay(1500)
                                    closedReason = null
                                    terminalHandle = null
                                    navController.popBackStack("sessionList", inclusive = false)
                                }
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
