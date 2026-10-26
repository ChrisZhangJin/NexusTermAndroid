package com.nexusterm.android

import android.os.Bundle
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
import com.nexusterm.android.ssh.SessionListenerImpl
import com.nexusterm.android.ssh.SshClient
import com.nexusterm.android.ui.ConnectScreen
import com.nexusterm.android.ui.TerminalHandle
import com.nexusterm.android.ui.TerminalScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.nexus_core.AuthPrompt
import uniffi.nexus_core.SshException

/**
 * Wires the vertical slice end to end: [ConnectScreen] -> [SshClient.connect]
 * -> [SessionListenerImpl] (UniFFI callback) -> [TerminalScreen]'s WebView,
 * and keystrokes back out through [SshClient.write].
 *
 * State machine is intentionally tiny (enum, not a full Compose navigation
 * graph) since this activity only ever shows one of two screens.
 */
class MainActivity : ComponentActivity() {

    private sealed class Screen {
        data object Connect : Screen()
        data class Terminal(val sessionId: String) : Screen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var screen by remember { mutableStateOf<Screen>(Screen.Connect) }
            var isConnecting by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var terminalHandle by remember { mutableStateOf<TerminalHandle?>(null) }
            var closedReason by remember { mutableStateOf<String?>(null) }

            MaterialTheme {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    when (val current = screen) {
                        is Screen.Connect -> {
                            ConnectScreen(
                                isConnecting = isConnecting,
                                errorMessage = errorMessage,
                                onConnect = { request ->
                                    errorMessage = null
                                    isConnecting = true
                                    lifecycleScope.launch {
                                        try {
                                            val listener = SessionListenerImpl(
                                                onDataCallback = { _, bytes -> terminalHandle?.writeBase64(bytes) },
                                                onPulseCallback = { /* no-op for the slice; hook up a heartbeat indicator later */ },
                                                onClosedCallback = { _, reason ->
                                                    closedReason = reason
                                                    terminalHandle?.notifyClosed(reason)
                                                },
                                                onAuthPromptCallback = { prompt -> promptUserForAnswer(prompt) },
                                            )
                                            val handle = SshClient.connect(
                                                host = request.host,
                                                port = request.port,
                                                username = request.username,
                                                auth = request.auth,
                                                listener = listener,
                                            )
                                            screen = Screen.Terminal(handle.sessionId)
                                        } catch (e: SshException) {
                                            errorMessage = e.message ?: "连接失败"
                                        } finally {
                                            isConnecting = false
                                        }
                                    }
                                },
                            )
                        }

                        is Screen.Terminal -> {
                            TerminalScreen(
                                onReady = { handle -> terminalHandle = handle },
                                onInput = { text ->
                                    lifecycleScope.launch {
                                        SshClient.write(current.sessionId, text.toByteArray(Charsets.UTF_8))
                                    }
                                },
                                onResize = { cols, rows ->
                                    lifecycleScope.launch {
                                        SshClient.resize(current.sessionId, cols, rows)
                                    }
                                },
                            )

                            if (closedReason != null) {
                                lifecycleScope.launch {
                                    // Give the WebView a moment to render the
                                    // "connection closed" banner before we
                                    // drop back to the connect form.
                                    delay(1500)
                                    screen = Screen.Connect
                                    closedReason = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Placeholder UI for keyboard-interactive / private-key-passphrase
     * prompts. The vertical slice only exercises the password auth path, so
     * this never actually fires in practice yet — but [SessionListenerImpl]
     * needs a real suspend function wired up because `on_auth_prompt` is a
     * required method of the UniFFI `TerminalEventListener` trait
     * (nexus-core/src/listener.rs). A full implementation would show a
     * dialog built from `prompt.fields` and resolve this once the user
     * submits it; for now we auto-cancel so a 2FA server fails fast with a
     * clear "not supported yet" message instead of hanging for 180s.
     */
    private suspend fun promptUserForAnswer(prompt: AuthPrompt): String {
        return ""
    }
}
