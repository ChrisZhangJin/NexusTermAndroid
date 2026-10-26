package com.nexusterm.android.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.nexusterm.android.ssh.SecretsStore
import com.nexusterm.android.ssh.SessionListenerImpl
import com.nexusterm.android.ssh.SessionStore
import com.nexusterm.android.ssh.SftpClient
import com.nexusterm.android.ssh.SftpProgressListenerImpl
import java.io.File
import kotlinx.coroutines.launch
import uniffi.nexus_core.AuthMethod
import uniffi.nexus_core.RemoteEntry
import uniffi.nexus_core.SessionRecord
import uniffi.nexus_core.SftpException
import uniffi.nexus_core.StoreException

private enum class SftpSide { LOCAL, REMOTE }

private data class TransferProgress(
    val transferId: String,
    val label: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val error: String? = null,
    val done: Boolean = false,
)

/**
 * Single-pane SFTP file browser: a tab toggles between the "local" tree
 * (Storage Access Framework — see below) and the "remote" tree (nexus-core's
 * `sftp::` exports via [SftpClient]). There's no desktop-app analog for the
 * local side: NexusTerm's desktop build just uses native file dialogs over
 * the real filesystem, which Android's scoped storage (API 30+) forbids for
 * anything outside the app's own sandbox — so local browsing here goes
 * through [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree]
 * + [DocumentFile], never `java.io.File` paths picked by the user.
 *
 * nexus-core's `sftp_upload`/`sftp_download` take plain filesystem paths
 * (see nexus-core/src/sftp/mod.rs — they open local files with
 * `tokio::fs::File`, which can't read a `content://` SAF `Uri` directly).
 * This screen bridges that gap itself: uploads first copy the SAF document
 * into a private cache file via `ContentResolver`, then hand nexus-core that
 * cache file's real path; downloads land in a cache file first, then get
 * copied into the user-chosen SAF destination document. Cache files are
 * deleted as soon as the transfer finishes (success or failure).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<SessionRecord?>(null) }
    var handleId by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var side by remember { mutableStateOf(SftpSide.REMOTE) }

    var remotePath by remember { mutableStateOf("/") }
    var remoteEntries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var selectedRemote by remember { mutableStateOf<RemoteEntry?>(null) }

    var localStack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var localEntries by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var selectedLocal by remember { mutableStateOf<DocumentFile?>(null) }

    var transfer by remember { mutableStateOf<TransferProgress?>(null) }

    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val root = DocumentFile.fromTreeUri(context, uri)
            if (root != null) {
                localStack = listOf(root)
                localEntries = root.listFiles().toList()
                selectedLocal = null
            }
        }
    }

    fun refreshLocal() {
        localStack.lastOrNull()?.let { localEntries = it.listFiles().toList() }
    }

    fun refreshRemote(path: String) {
        val hid = handleId ?: return
        scope.launch {
            try {
                remoteEntries = SftpClient.list(hid, path)
                remotePath = path
                selectedRemote = null
            } catch (e: SftpException) {
                errorMessage = e.message ?: "列目录失败"
            }
        }
    }

    LaunchedEffect(sessionId) {
        connecting = true
        errorMessage = null
        try {
            val record = SessionStore.list().firstOrNull { it.id == sessionId }
            if (record == null) {
                errorMessage = "会话不存在"
                return@LaunchedEffect
            }
            session = record
            val auth = buildAuthMethod(context, record)
            val listener = SessionListenerImpl(
                onDataCallback = { _, _ -> },
                onPulseCallback = { _ -> },
                onClosedCallback = { _, _ -> },
                onAuthPromptCallback = { "" },
            )
            val handle = SftpClient.connect(record.host, record.port.toInt(), record.username, auth, listener)
            handleId = handle.handleId
            val home = SftpClient.home(handle.handleId)
            remotePath = home
            remoteEntries = SftpClient.list(handle.handleId, home)
        } catch (e: SftpException) {
            errorMessage = e.message ?: "连接失败"
        } catch (e: StoreException) {
            errorMessage = e.message ?: "无法读取会话配置"
        } finally {
            connecting = false
        }
    }

    DisposableEffect(sessionId) {
        onDispose {
            val hid = handleId
            if (hid != null) {
                // best-effort；Activity 可能已经在销毁，拿不到协程作用域也无妨——
                // 远端会在 TCP 层自然超时收尾，不会泄漏到下一次打开。
                scope.launch { runCatching { SftpClient.close(hid) } }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.name ?: "文件") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = if (side == SftpSide.LOCAL) 0 else 1) {
                Tab(selected = side == SftpSide.LOCAL, onClick = { side = SftpSide.LOCAL }, text = { Text("本地") })
                Tab(selected = side == SftpSide.REMOTE, onClick = { side = SftpSide.REMOTE }, text = { Text("远程") })
            }

            when {
                connecting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                errorMessage != null -> Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                side == SftpSide.LOCAL -> LocalPane(
                    rootChosen = localStack.isNotEmpty(),
                    canGoUp = localStack.size > 1,
                    entries = localEntries,
                    selected = selectedLocal,
                    onChooseRoot = { openTree.launch(null) },
                    onGoUp = {
                        localStack = localStack.dropLast(1)
                        refreshLocal()
                        selectedLocal = null
                    },
                    onOpenDir = { doc ->
                        localStack = localStack + doc
                        refreshLocal()
                        selectedLocal = null
                    },
                    onSelectFile = { selectedLocal = it },
                    onUpload = { src ->
                        val hid = handleId
                        if (hid == null) {
                            errorMessage = "尚未连接"
                        } else {
                            val dest = joinRemotePath(remotePath, src.name ?: "file")
                            scope.launch {
                                uploadViaTempFile(context, src, hid, dest) { transfer = it }
                            }
                        }
                    },
                )
                else -> RemotePane(
                    path = remotePath,
                    entries = remoteEntries,
                    selected = selectedRemote,
                    onGoUp = {
                        val parent = remotePath.substringBeforeLast('/').ifEmpty { "/" }
                        refreshRemote(parent)
                    },
                    onOpenDir = { refreshRemote(it.path) },
                    onSelectFile = { selectedRemote = it },
                    onDownload = { entry ->
                        val hid = handleId
                        val destDir = localStack.lastOrNull()
                        when {
                            hid == null -> errorMessage = "尚未连接"
                            destDir == null -> errorMessage = "请先在「本地」标签选择一个文件夹"
                            else -> scope.launch {
                                downloadViaTempFile(context, hid, entry, destDir) { transfer = it }
                            }
                        }
                    },
                    onDelete = { entry ->
                        val hid = handleId ?: return@RemotePane
                        scope.launch {
                            try {
                                SftpClient.remove(hid, entry.path)
                                refreshRemote(remotePath)
                            } catch (e: SftpException) {
                                errorMessage = e.message ?: "删除失败"
                            }
                        }
                    },
                )
            }
        }
    }

    transfer?.let { t ->
        AlertDialog(
            onDismissRequest = { if (t.done) transfer = null },
            title = { Text(t.label) },
            text = {
                Column {
                    when {
                        t.error != null -> Text(t.error, color = MaterialTheme.colorScheme.error)
                        t.done -> Text("完成")
                        else -> {
                            val fraction = if (t.bytesTotal > 0) {
                                (t.bytesDone.toFloat() / t.bytesTotal.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Text("${t.bytesDone} / ${t.bytesTotal} 字节")
                        }
                    }
                }
            },
            confirmButton = {
                if (t.done) {
                    TextButton(onClick = {
                        transfer = null
                        refreshRemote(remotePath)
                    }) { Text("关闭") }
                } else {
                    TextButton(onClick = {
                        scope.launch { runCatching { SftpClient.cancelTransfer(t.transferId) } }
                    }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun LocalPane(
    rootChosen: Boolean,
    canGoUp: Boolean,
    entries: List<DocumentFile>,
    selected: DocumentFile?,
    onChooseRoot: () -> Unit,
    onGoUp: () -> Unit,
    onOpenDir: (DocumentFile) -> Unit,
    onSelectFile: (DocumentFile) -> Unit,
    onUpload: (DocumentFile) -> Unit,
) {
    if (!rootChosen) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = onChooseRoot) { Text("选择本地文件夹") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (canGoUp) {
            TextButton(onClick = onGoUp) { Text("上一级") }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(entries, key = { it.uri.toString() }) { doc ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (doc.isDirectory) onOpenDir(doc) else onSelectFile(doc)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = (if (doc.isDirectory) "📁 " else "📄 ") + (doc.name ?: "?"),
                        color = if (doc == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Divider()
            }
        }
        if (selected != null) {
            Button(onClick = { onUpload(selected) }, modifier = Modifier.padding(16.dp)) {
                Text("上传到远程当前目录")
            }
        }
    }
}

@Composable
private fun RemotePane(
    path: String,
    entries: List<RemoteEntry>,
    selected: RemoteEntry?,
    onGoUp: () -> Unit,
    onOpenDir: (RemoteEntry) -> Unit,
    onSelectFile: (RemoteEntry) -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onDelete: (RemoteEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (path != "/") {
            TextButton(onClick = onGoUp) { Text("上一级") }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(entries, key = { it.path }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDir) onOpenDir(entry) else onSelectFile(entry)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = (if (entry.isDir) "📁 " else "📄 ") + entry.name,
                        color = if (entry == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Divider()
            }
        }
        if (selected != null) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDownload(selected) }) { Text("下载到本地当前目录") }
                TextButton(onClick = { onDelete(selected) }) { Text("删除") }
            }
        }
    }
}

private fun joinRemotePath(dir: String, name: String): String {
    val base = if (dir.endsWith("/")) dir.dropLast(1) else dir
    return "$base/$name"
}

/** Mirrors `ConnectScreen`'s [AuthKind] choices onto `uniffi.nexus_core.AuthMethod`, pulling secrets from [SecretsStore]. */
private fun buildAuthMethod(context: Context, record: SessionRecord): AuthMethod {
    return when (record.authType) {
        "key" -> AuthMethod.PrivateKey(
            path = record.keyPath ?: "",
            passphrase = SecretsStore.getPassphrase(context, record.id),
        )
        "agent" -> AuthMethod.Agent
        else -> AuthMethod.Password(password = SecretsStore.getPassword(context, record.id) ?: "")
    }
}

private suspend fun uploadViaTempFile(
    context: Context,
    src: DocumentFile,
    handleId: String,
    remotePath: String,
    onUpdate: (TransferProgress) -> Unit,
) {
    val label = "上传 ${src.name}"
    val temp = File.createTempFile("nexus-upload", ".tmp", context.cacheDir)
    try {
        context.contentResolver.openInputStream(src.uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: run {
            onUpdate(TransferProgress("", label, 0, 0, error = "无法读取所选文件", done = true))
            temp.delete()
            return
        }
    } catch (e: Exception) {
        onUpdate(TransferProgress("", label, 0, 0, error = e.message ?: "读取本地文件失败", done = true))
        temp.delete()
        return
    }

    val listener = SftpProgressListenerImpl(
        onProgressCallback = { id, done, total -> onUpdate(TransferProgress(id, label, done, total)) },
        onCompleteCallback = { id ->
            temp.delete()
            onUpdate(TransferProgress(id, label, 0, 0, done = true))
        },
        onErrorCallback = { id, msg ->
            temp.delete()
            onUpdate(TransferProgress(id, label, 0, 0, error = msg, done = true))
        },
    )
    try {
        val id = SftpClient.upload(handleId, temp.absolutePath, remotePath, listener)
        onUpdate(TransferProgress(id, label, 0, 0))
    } catch (e: SftpException) {
        temp.delete()
        onUpdate(TransferProgress("", label, 0, 0, error = e.message ?: "启动上传失败", done = true))
    }
}

private suspend fun downloadViaTempFile(
    context: Context,
    handleId: String,
    entry: RemoteEntry,
    destDir: DocumentFile,
    onUpdate: (TransferProgress) -> Unit,
) {
    val label = "下载 ${entry.name}"
    val temp = File.createTempFile("nexus-download", ".tmp", context.cacheDir)

    val listener = SftpProgressListenerImpl(
        onProgressCallback = { id, done, total -> onUpdate(TransferProgress(id, label, done, total)) },
        onCompleteCallback = { id ->
            try {
                val dest = destDir.createFile("application/octet-stream", entry.name)
                if (dest == null) {
                    onUpdate(TransferProgress(id, label, 0, 0, error = "无法在本地创建文件", done = true))
                } else {
                    context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                        temp.inputStream().use { input -> input.copyTo(output) }
                    }
                    onUpdate(TransferProgress(id, label, 0, 0, done = true))
                }
            } catch (e: Exception) {
                onUpdate(TransferProgress(id, label, 0, 0, error = e.message ?: "写入本地文件失败", done = true))
            } finally {
                temp.delete()
            }
        },
        onErrorCallback = { id, msg ->
            temp.delete()
            onUpdate(TransferProgress(id, label, 0, 0, error = msg, done = true))
        },
    )
    try {
        val id = SftpClient.download(handleId, entry.path, temp.absolutePath, listener)
        onUpdate(TransferProgress(id, label, 0, 0))
    } catch (e: SftpException) {
        temp.delete()
        onUpdate(TransferProgress("", label, 0, 0, error = e.message ?: "启动下载失败", done = true))
    }
}
