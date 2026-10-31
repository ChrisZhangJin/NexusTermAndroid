package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexusterm.android.ssh.SessionStore
import kotlinx.coroutines.launch
import uniffi.nexus_core.SessionRecord
import uniffi.nexus_core.StoreException
import java.text.DateFormat
import java.util.Date

/** How a live tab's connection is doing, as far as the session list needs to care. */
enum class LiveTabStatus { CONNECTED, RECONNECTING, CLOSED }

/**
 * 一条活跃标签在首页里的**只读投影**。
 *
 * 首页列的是保存下来的会话配置（[SessionRecord]），活跃连接的真身是
 * MainActivity 里的 `LiveSession`。那个类持有 WebView 和 TerminalHandle ——
 * 都是有生命周期的 UI 资源，交给一个只想画几个按钮的 Composable 迟早会被
 * 意外持有、意外销毁，所以这里只搬运「认出是哪条标签」需要的字段。
 *
 * 不带 `liveSessionId`：断开走的是 [tabId]。重连会把底层 SSH 会话换成新的
 * （`liveSessionId` 跟着变），但标签还是那个标签，用 [tabId] 回指才不会在
 * 「用户正盯着菜单、这条标签刚好重连成功」时指到空处。
 */
data class LiveTabInfo(
    val tabId: String,
    /** 这条标签是从哪个保存的会话配置拨出来的。 */
    val savedSessionId: String,
    /** 已经带上了「（重连中 3/10）」之类后缀的标签标题，和标签切换器上看到的是同一个。 */
    val title: String,
    val status: LiveTabStatus,
    /**
     * 这条标签是什么时候打开的（epoch 毫秒）。`<= 0` 表示投影处没填，菜单就不显示
     * 时间、退回只有「序号 + 状态」的写法。
     *
     * 为什么需要它：[title] 其实只是「配置名 + 状态后缀」，同一条配置开出来的
     * 标签 title 必然一样；终端切换器里靠缩略图区分，而首页不能去读终端内容，
     * 所以「几点开的」是首页唯一拿得到、又对用户有区分度的信息。带默认值，
     * 不破坏现有构造。
     */
    val openedAtMillis: Long = 0L,
)

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
    /** Hangs up exactly the one tab handed back — never "everything on this host"（首页的断开按一下只断一条，多条时由下面的菜单让用户选）。 */
    onDisconnectTab: (LiveTabInfo) -> Unit,
    /**
     * 当前所有活跃标签（连上了的，不一定在屏幕上），MainActivity 的
     * `liveSessions` 的投影。取代了早先的 `connectedSessionIds: Set<String>`：
     * 一条配置可以被拨出多条连接，只给一个 id 集合就只够画个圆点，没法让
     * 用户指定断哪一条。
     */
    liveTabs: List<LiveTabInfo> = emptyList(),
    /** Why the last connection attempt failed, if it did. Without this a failed dial is completely silent. */
    connectError: String? = null,
    /**
     * 打开手势设置页。入口放在首页而不是终端菜单里：在荣耀上终端菜单（双指
     * 长按）就是被系统抢走、打不开的那个，设置入口藏在里面等于锁在门里。
     */
    onOpenSettings: () -> Unit = {},
) {
    var sessions by remember { mutableStateOf<List<SessionRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableStateOf(0) }
    // Saveable so an open help dialog survives rotation.
    var showHelp by rememberSaveable { mutableStateOf(false) }
    // 右下角按钮组是否展开。不用 rememberSaveable：转屏后收起更符合直觉。
    var fabExpanded by remember { mutableStateOf(false) }
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
        // 设置入口不再放顶栏：和「+」「?」一起收进右下角的 HomeActionFab 里，
        // 顶栏只剩标题。
        topBar = { TopAppBar(title = { Text("NexusTerm") }) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 先画列表再画按钮组：后者要叠在列表之上（含展开时的 scrim）。
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
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底部留白：收起态的把手（48dp 命中区 + 16dp 边距）浮在列表右下角，
                    // 不留的话最后一条的「连接」按钮滚到底也会被它压住点不到。
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
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
                            // 顺序沿用 liveTabs 的顺序（= 标签被打开的先后），
                            // 菜单里的「标签 1/2/3」才和用户的心理模型对得上。
                            liveTabs = liveTabs.filter { it.savedSessionId == session.id },
                            onDisconnectTab = onDisconnectTab,
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

            HomeActionFab(
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                onOpenSettings = onOpenSettings,
                onAddNew = onAddNew,
                onOpenHelp = { showHelp = true },
            )
        }
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(
    session: SessionRecord,
    /** 只包含这条配置拨出来的活跃标签，可能有好几条。 */
    liveTabs: List<LiveTabInfo>,
    onDisconnectTab: (LiveTabInfo) -> Unit,
    onConnect: () -> Unit,
    onNewTab: () -> Unit,
    onFiles: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isConnected = liveTabs.isNotEmpty()
    // 「这条配置开了不止一个标签，断哪个？」的下拉菜单是否展开。标签全断完时
    // 整个按钮（连同它的 Box）会离开组合，菜单也就跟着消失，不需要额外收尾。
    var tabPickerOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isConnected) {
                Text(
                    // 开了不止一个标签时把条数也写出来：否则用户按「断开」
                    // 突然弹出一个选择菜单会很突兀——圆点先说明了「这里有两条」。
                    text = if (liveTabs.size > 1) "● ×${liveTabs.size} " else "● ",
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
        // FlowRow 而不是 Row：连上之后这一行会变成六个按钮（删除/编辑/文件/
        // 断开/新标签/回到会话），在一台 360dp 宽的手机上放不下，而 Row 不会
        // 换行——它会把多出来的部分直接顶出左边界裁掉。
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDelete) { Text("删除") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onFiles) { Text("文件") }
            if (isConnected) {
                Box {
                    TextButton(
                        onClick = {
                            // 只有一条就直接断；有好几条时不替用户猜——产品上
                            // 这个按钮的语义是「断一条」，不是「断掉这台机器上
                            // 的全部标签」，猜错了就是把用户正在跑的活给杀了。
                            val only = liveTabs.singleOrNull()
                            if (only != null) onDisconnectTab(only) else tabPickerOpen = true
                        },
                    ) { Text("断开") }

                    DropdownMenu(
                        expanded = tabPickerOpen,
                        onDismissRequest = { tabPickerOpen = false },
                    ) {
                        // 没有这行标题，一串标签名看起来像「切换到哪个标签」。
                        Text(
                            text = "断开哪个标签？",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        liveTabs.forEachIndexed { index, tab ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        // 同一条配置开出来的标签标题是一模一样的
                                        // （都用配置名），只给标题用户根本分不清
                                        // 哪个是哪个，所以补上打开顺序、状态和
                                        // 打开时间。不露 tabId：那是一串 uuid，
                                        // 对用户没有任何意义。
                                        Text("标签 ${index + 1}：${tabDistinctTitle(tab, session) ?: sessionDisplayName(session)}")
                                        Text(
                                            text = tabDetailLine(tab),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    tabPickerOpen = false
                                    onDisconnectTab(tab)
                                },
                            )
                        }
                    }
                }
                // Only worth showing once there's something to be a *second* tab
                // alongside — before that "连接" already opens the first one.
                TextButton(onClick = onNewTab) { Text("新标签") }
            }
            Button(onClick = onConnect) { Text(if (isConnected) "回到会话" else "连接") }
        }
    }
}

/** 和 MainActivity 里 `LiveSession.displayName` 同一个规则：没起名就用主机。 */
private fun sessionDisplayName(session: SessionRecord): String =
    session.name.ifBlank { session.host }

/** 远端来的标题可能很长，菜单宽度是按内容撑开的，必须截断。 */
private const val TAB_TITLE_MAX_CHARS = 24

/**
 * 标签标题里「值得单独展示」的部分；没有则返回 null，调用方回退到配置名。
 *
 * 目前 [LiveTabInfo.title] 是「配置名 + 状态后缀」，以配置名开头的都当作没有
 * 额外信息（后缀已经由状态行表达，再写一遍只是重复）。只有标题和配置名毫无
 * 关系时（比如以后接入远端窗口标题）才展示，并按字符数截断。
 */
private fun tabDistinctTitle(tab: LiveTabInfo, session: SessionRecord): String? {
    val title = tab.title.trim()
    if (title.isEmpty()) return null
    if (title.startsWith(sessionDisplayName(session))) return null
    return if (title.length > TAB_TITLE_MAX_CHARS) {
        title.take(TAB_TITLE_MAX_CHARS) + "…"
    } else {
        title
    }
}

/** 第二行：「已连接 · 14:32 打开」；没有打开时间就只剩状态。 */
private fun tabDetailLine(tab: LiveTabInfo): String {
    val opened = if (tab.openedAtMillis > 0L) {
        // 跟随系统的 12/24 小时与本地化习惯，不自己拼格式。用 MEDIUM（带秒）
        // 而不是 SHORT：用户常常连点两次「新标签」，同一分钟内开出两条，
        // 只到分钟的话两项会撞成一样。
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(tab.openedAtMillis)) + " 打开"
    } else {
        null
    }
    return listOfNotNull(tab.status.label(), opened).joinToString(" · ")
}

private fun LiveTabStatus.label(): String = when (this) {
    LiveTabStatus.CONNECTED -> "已连接"
    LiveTabStatus.RECONNECTING -> "重连中"
    LiveTabStatus.CLOSED -> "已断开"
}
