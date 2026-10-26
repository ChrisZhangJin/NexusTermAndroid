package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** The three auth methods nexus-core's `AuthMethod` enum supports (see nexus-core/src/ssh/session.rs). */
enum class AuthKind { PASSWORD, PRIVATE_KEY, AGENT }

/**
 * What the form hands back when the user taps Save. Deliberately *not* a
 * `uniffi.nexus_core.NewSession` — this also carries the plaintext secret(s),
 * which the caller writes to [com.nexusterm.android.ssh.SecretsStore]
 * (never to `NewSession`/Rust — see that object's doc for why).
 */
data class SessionFormResult(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authKind: AuthKind,
    val keyPath: String,
    val password: String,
    val passphrase: String,
)

/** Pre-fills the form when editing an existing session; `null` means "new session". */
data class SessionFormInitial(
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authKind: AuthKind = AuthKind.PASSWORD,
    val keyPath: String = "",
    val password: String = "",
    val passphrase: String = "",
)

/**
 * Add/edit session form. Was the vertical slice's only screen (immediate
 * "fill in host/port/user/password, tap Connect"); now it only *saves* a
 * session configuration via [SessionFormResult] — actually connecting
 * happens from [SessionListScreen]'s "连接" button, using whatever was last
 * saved here.
 *
 * Private-key browsing/import UI is still out of scope for this milestone
 * (same note the original vertical-slice version had) — the private-key
 * path is a plain text field for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    initial: SessionFormInitial,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (SessionFormResult) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var authKind by remember { mutableStateOf(initial.authKind) }
    var keyPath by remember { mutableStateOf(initial.keyPath) }
    var password by remember { mutableStateOf(initial.password) }
    var passphrase by remember { mutableStateOf(initial.passphrase) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.name.isEmpty()) "新建连接" else "编辑连接") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("取消") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("主机") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))
            Text(text = "认证方式", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(
                    selected = authKind == AuthKind.PASSWORD,
                    onClick = { authKind = AuthKind.PASSWORD },
                    label = { Text("密码") },
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
                FilterChip(
                    selected = authKind == AuthKind.PRIVATE_KEY,
                    onClick = { authKind = AuthKind.PRIVATE_KEY },
                    label = { Text("私钥") },
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
                FilterChip(
                    selected = authKind == AuthKind.AGENT,
                    onClick = { authKind = AuthKind.AGENT },
                    label = { Text("无凭据") },
                )
            }

            when (authKind) {
                AuthKind.PASSWORD -> {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                }
                AuthKind.PRIVATE_KEY -> {
                    OutlinedTextField(
                        value = keyPath,
                        onValueChange = { keyPath = it },
                        label = { Text("私钥路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("私钥口令（可留空）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                }
                AuthKind.AGENT -> {
                    Text(
                        text = "不提供凭据，仅适用于接受无密码登录的服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

            if (errorMessage != null) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
            }

            Button(
                enabled = !isSaving && name.isNotBlank() && host.isNotBlank() && username.isNotBlank(),
                onClick = {
                    val portNumber = port.toIntOrNull() ?: 22
                    onSave(
                        SessionFormResult(
                            name = name.trim(),
                            host = host.trim(),
                            port = portNumber,
                            username = username.trim(),
                            authKind = authKind,
                            keyPath = keyPath.trim(),
                            password = password,
                            passphrase = passphrase,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (isSaving) "保存中…" else "保存")
            }
        }
    }
}
