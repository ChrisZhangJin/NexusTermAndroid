package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexusterm.android.ssh.SessionStore
import kotlinx.coroutines.launch
import uniffi.nexus_core.SessionRecord
import uniffi.nexus_core.StoreException

/**
 * The app's home screen: every saved [SessionRecord], tap-to-connect, plus
 * per-row "connect" / "files" / "edit" / "delete" actions. Replaces the
 * vertical slice's single always-shown [ConnectScreen] (that composable is
 * now the add/edit form, reachable from the "+" button and the "edit"
 * action here).
 *
 * Keeps its own session-list state and reloads via [SessionStore.list] —
 * callers only need to supply what happens *after* an action (connect/edit/
 * files navigation, or performing the actual delete). This mirrors the
 * project's existing convention of screens owning their own transient UI
 * state (see [ConnectScreen]'s local `remember`s) while the activity owns
 * navigation and anything that crosses screen boundaries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onAddNew: () -> Unit,
    onEdit: (SessionRecord) -> Unit,
    onConnect: (SessionRecord) -> Unit,
    onFiles: (SessionRecord) -> Unit,
    /** Caller is responsible for `SessionStore.delete` + clearing [com.nexusterm.android.ssh.SecretsStore]. */
    onDeleteRequested: suspend (SessionRecord) -> Unit,
) {
    var sessions by remember { mutableStateOf<List<SessionRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadTrigger) {
        isLoading = true
        errorMessage = null
        try {
            sessions = SessionStore.list()
        } catch (e: StoreException) {
            errorMessage = e.message ?: "无法加载会话列表"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NexusTerm") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNew) { Text("+") }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                errorMessage != null -> Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                sessions.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Text("还没有保存的连接")
                    Button(onClick = onAddNew, modifier = Modifier.padding(top = 12.dp)) { Text("新建连接") }
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            onConnect = { onConnect(session) },
                            onFiles = { onFiles(session) },
                            onEdit = { onEdit(session) },
                            onDelete = {
                                scope.launch {
                                    onDeleteRequested(session)
                                    reloadTrigger++
                                }
                            },
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionRecord,
    onConnect: () -> Unit,
    onFiles: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = session.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "${session.username}@${session.host}:${session.port}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDelete) { Text("删除") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onFiles) { Text("文件") }
            Button(onClick = onConnect) { Text("连接") }
        }
    }
}
