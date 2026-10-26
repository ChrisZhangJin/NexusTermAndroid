package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import uniffi.nexus_core.AuthMethod

/** What the connect form hands back to the caller when the user taps Connect. */
data class ConnectRequest(
    val host: String,
    val port: Int,
    val username: String,
    val auth: AuthMethod,
)

/**
 * Host/port/username/password form — the vertical slice's only supported
 * auth method is password (private-key browsing/import UI is out of scope
 * per the plan; `AuthMethod.PrivateKey`/`Agent` already exist on the Rust
 * side for when that UI is built).
 */
@Composable
fun ConnectScreen(
    isConnecting: Boolean,
    errorMessage: String?,
    onConnect: (ConnectRequest) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "NexusTerm", style = MaterialTheme.typography.headlineMedium)
        Text(text = "SSH 连接", style = MaterialTheme.typography.bodyMedium)

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

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
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )

        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 16.dp))

        if (errorMessage != null) {
            Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
        }

        Button(
            enabled = !isConnecting && host.isNotBlank() && username.isNotBlank(),
            onClick = {
                val portNumber = port.toIntOrNull() ?: 22
                onConnect(
                    ConnectRequest(
                        host = host.trim(),
                        port = portNumber,
                        username = username.trim(),
                        auth = AuthMethod.Password(password = password),
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text(if (isConnecting) "连接中…" else "连接")
        }
    }
}
