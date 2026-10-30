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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
    /** Connects, or resumes the existing live session if there already is one. */
    onConnect: (SessionRecord) -> Unit,
    /** Always dials a *new* connection, even when one is already live — a second tab onto the same host. */
    onNewTab: (SessionRecord) -> Unit,
    onFiles: (SessionRecord) -> Unit,
    /** Caller is responsible for `SessionStore.delete` + clearing [com.nexusterm.android.ssh.SecretsStore]. */
    onDeleteRequested: suspend (SessionRecord) -> Unit,
    /** Saved session ids that currently have a live (connected, not necessarily on-screen) SSH session — see MainActivity's `liveSessions`. Renders a small indicator so re-tapping "连接" is understood to resume rather than reconnect. */
    connectedSessionIds: Set<String> = emptySet(),
    /** Why the last connection attempt failed, if it did. Without this a failed dial is completely silent. */
    connectError: String? = null,
) {
    var sessions by remember { mutableStateOf<List<SessionRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableStateOf(0) }
    // Saveable so an open help dialog survives rotation.
    var showHelp by rememberSaveable { mutableStateOf(false) }
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
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // One green family, three weights, so the screen reads as a single
                // palette instead of three unrelated greens:
                //   "?"   → primaryContainer (NexusMint)  — same hue, clearly subordinate
                //   "+"   → primary (NexusGreen)          — the screen's real action, strongest
                //   连接  → Button's default primary       — matches "+" by construction
                // Previously "?" borrowed the *secondary* family (pale #CDEBDF) and "+"
                // took FAB's default primaryContainer, which left the row buttons as a
                // third, darker green with no relationship to either.
                SmallFloatingActionButton(
                    onClick = { showHelp = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) { Text("?") }
                FloatingActionButton(
                    onClick = onAddNew,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text("+") }
            }
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
                    if (connectError != null) {
                        item {
                            Text(
                                text = connectError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            isConnected = session.id in connectedSessionIds,
                            onConnect = { onConnect(session) },
                            onNewTab = { onNewTab(session) },
                            onFiles = { onFiles(session) },
                            onEdit = { onEdit(session) },
                            onDelete = {
                                scope.launch {
                                    // onDeleteRequested 最终落到 SessionStore.delete →
                                    // UniFFI deleteSession，会抛 StoreException。这里原本
                                    // 是裸 launch：SQLite 一报错（库被占、磁盘满）异常
                                    // 就从 rememberCoroutineScope 里逃出去，没有任何
                                    // handler，直接带走进程 —— 和 MainActivity 里按键
                                    // 写入那条崩溃链是同一个形状。复用本屏已有的
                                    // errorMessage 呈现，不另造 UI。
                                    try {
                                        onDeleteRequested(session)
                                        reloadTrigger++
                                    } catch (e: StoreException) {
                                        // 失败时**不**bump reloadTrigger：上面那个
                                        // LaunchedEffect(reloadTrigger) 开头就会把
                                        // errorMessage 清成 null，刚写进去的提示会被
                                        // 它当场抹掉，用户只看到一闪。删除失败意味着
                                        // 库里什么都没变，本来也不需要重读。
                                        errorMessage = e.message ?: "删除失败"
                                    }
                                }
                            },
                        )
                        Divider()
                    }
                }
            }
        }
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
}

@Composable
private fun SessionRow(
    session: SessionRecord,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onNewTab: () -> Unit,
    onFiles: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isConnected) {
                Text(
                    text = "● ",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(text = session.name, style = MaterialTheme.typography.titleMedium)
        }
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
            // Only worth showing once there's something to be a *second* tab
            // alongside — before that "连接" already opens the first one.
            if (isConnected) {
                TextButton(onClick = onNewTab) { Text("新标签") }
            }
            Button(onClick = onConnect) { Text(if (isConnected) "回到会话" else "连接") }
        }
    }
}
