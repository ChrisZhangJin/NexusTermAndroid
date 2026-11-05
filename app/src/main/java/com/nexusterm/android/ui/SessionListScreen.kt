package com.nexusterm.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
    /** 回到指定的那一条已连接标签（一条配置有多条标签时由用户选）。 */
    onResumeTab: (LiveTabInfo) -> Unit,
    /**
     * 当前所有活跃标签（连上了的，不一定在屏幕上），MainActivity 的
     * `liveSessions` 的投影。取代了早先的 `connectedSessionIds: Set<String>`：
     * 一条配置可以被拨出多条连接，只给一个 id 集合就只够画个圆点，没法让
     * 用户指定断哪一条。
     */
    liveTabs: List<LiveTabInfo> = emptyList(),
    /** Why the last connection attempt failed, if it did. Without this a failed dial is completely silent. */
    connectError: String? = null,
    /** 用户关掉（或超时自动收起）连接失败提示。 */
    onDismissError: () -> Unit = {},
    /** 正在拨号的保存配置 id：这些行的「连接」置灰并播放进度动画。 */
    connectingIds: Set<String> = emptySet(),
    /**
     * 打开手势设置页。入口放在首页而不是终端菜单里：在荣耀上终端菜单（双指
     * 长按）就是被系统抢走、打不开的那个，设置入口藏在里面等于锁在门里。
     */
    onOpenSettings: () -> Unit = {},
    /** 「操作说明」里键盘快捷键一节按它生成，这样用户改绑之后帮助页不会过时。 */
    shortcuts: ShortcutBindings = ShortcutBindings.DEFAULT,
) {
    var sessions by remember { mutableStateOf<List<SessionRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableStateOf(0) }
    // Saveable so an open help dialog survives rotation.
    var showHelp by rememberSaveable { mutableStateOf(false) }
    // 右下角按钮组是否展开。不用 rememberSaveable：转屏后收起更符合直觉。
    var fabExpanded by remember { mutableStateOf(false) }
    // 当前向左滑开的那一行（同一时刻最多一行），和等待确认删除的那条配置。
    var openRowId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SessionRecord?>(null) }
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            // 顺序沿用 liveTabs 的顺序（= 标签被打开的先后），
                            // 菜单里的「标签 1/2/3」才和用户的心理模型对得上。
                            liveTabs = liveTabs.filter { it.savedSessionId == session.id },
                            connecting = session.id in connectingIds,
                            revealed = openRowId == session.id,
                            onRevealedChange = { open ->
                                openRowId = if (open) session.id else openRowId.takeIf { it != session.id }
                            },
                            onDisconnectTab = onDisconnectTab,
                            onResumeTab = onResumeTab,
                            onConnect = { onConnect(session) },
                            onNewTab = { onNewTab(session) },
                            onFiles = { onFiles(session) },
                            onEdit = { onEdit(session) },
                            // 只是请求确认；真正的删除在下面的对话框里。
                            onDelete = { pendingDelete = session },
                            modifier = Modifier.animateItem(),
                        )
                        // 浅色细线：只靠和背景的一点点色差把行分开，不抢眼。
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            }

            ConnectErrorBanner(
                message = connectError,
                onDismiss = onDismissError,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            HomeActionFab(
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                onOpenSettings = onOpenSettings,
                onAddNew = onAddNew,
                onOpenHelp = { showHelp = true },
            )
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个连接？") },
            text = {
                Text(
                    "「${sessionDisplayName(session)}」（${session.username}@${session.host}）" +
                        "的配置和保存的密码会被永久删除，无法恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        openRowId = null
                        // 提示多半是在说这台主机连不上；主机都删了，提示留着没意义。
                        onDismissError()
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
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false }, shortcuts = shortcuts)
    }
}

/** 左滑露出的每个操作格宽度。 */
private val ACTION_WIDTH = 68.dp

/** 抬手时的速度（px/s）超过它就按「甩」处理，不看位置。 */
private const val FLING_VELOCITY = 800f

/**
 * 一行连接。整行（标签区 + 主按钮）作为一个整体可以向左滑，露出背后的
 * 「文件 / 编辑 / 删除」（已连接时多一个「新标签」）；向右滑、点这一行、或者
 * 滑开别的行都会收回。同一时刻只有一行是滑开的，由 [revealed] 从外面控制。
 *
 * 主操作留在行上：没连时是「连接」；已连接时是「断开」「回到会话」——
 * 一条配置开了多个标签时，这两个都弹出菜单让用户选哪一个，见 [TabPicker]。
 *
 * 连接中（[connecting]）：「连接」置灰成「连接中」，整行背景上有同色系的斜条纹
 * 持续向右流动，见 [ConnectingStripes]。
 */
@Composable
private fun SessionRow(
    session: SessionRecord,
    /** 只包含这条配置拨出来的活跃标签，可能有好几条。 */
    liveTabs: List<LiveTabInfo>,
    connecting: Boolean,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onDisconnectTab: (LiveTabInfo) -> Unit,
    onResumeTab: (LiveTabInfo) -> Unit,
    onConnect: () -> Unit,
    onNewTab: () -> Unit,
    onFiles: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = liveTabs.isNotEmpty()
    // 「这条配置开了不止一个标签，选哪个？」的下拉菜单是否展开。标签全断完时
    // 整个按钮（连同它的 Box）会离开组合，菜单也就跟着消失，不需要额外收尾。
    var disconnectPickerOpen by remember { mutableStateOf(false) }
    var resumePickerOpen by remember { mutableStateOf(false) }

    val colors = MaterialTheme.colorScheme
    val actionCount = if (isConnected) 4 else 3
    val panelPx = with(LocalDensity.current) { (ACTION_WIDTH * actionCount).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 外部改了 revealed（别的行滑开了、删除后收起）或操作格个数变了（连上 / 断开）
    // → 动画到对应位置。自己拖动的收尾在 onDragStopped 里直接做。
    LaunchedEffect(revealed, panelPx) {
        offset.animateTo(if (revealed) -panelPx else 0f, tween(180))
    }

    Box(modifier = modifier.fillMaxWidth().clipToBounds()) {
        // 背后的操作格。前景完全盖住它，所以不滑开时看不见。
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
        ) {
            val close = { onRevealedChange(false) }
            if (isConnected) {
                ActionCell("新标签", colors.secondaryContainer, colors.onSecondaryContainer) { close(); onNewTab() }
            }
            ActionCell("文件", colors.tertiaryContainer, colors.onTertiaryContainer) { close(); onFiles() }
            ActionCell("编辑", colors.primaryContainer, colors.onPrimaryContainer) { close(); onEdit() }
            ActionCell("删除", colors.error, colors.onError) { close(); onDelete() }
        }

        // 前景：整行。背景必须不透明，否则背后的操作格会透出来。
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(colors.background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-panelPx, 0f)) }
                    },
                    onDragStopped = { velocity ->
                        val open = when {
                            velocity < -FLING_VELOCITY -> true
                            velocity > FLING_VELOCITY -> false
                            else -> offset.value < -panelPx / 2
                        }
                        onRevealedChange(open)
                        scope.launch { offset.animateTo(if (open) -panelPx else 0f, tween(180)) }
                    },
                )
                // 滑开时点这一行 = 收回。不画涟漪，免得盖在条纹上很脏。
                .clickable(
                    enabled = revealed,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onRevealedChange(false) },
        ) {
            AnimatedVisibility(
                visible = connecting,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.matchParentSize(),
            ) { ConnectingStripes() }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = sessionDisplayName(session),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 单行：地址折成两行很难看。标签区现在能拿到除主按钮外的整行宽度。
                    Text(
                        text = "${session.username}@${session.host}:${session.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val status = when {
                        connecting -> "连接中…"
                        // 开了不止一个标签时把条数也写出来：否则用户按「断开」
                        // 突然弹出一个选择菜单会很突兀——这里先说明了「有两条」。
                        isConnected -> "● " + liveTabs.first().status.label() +
                            if (liveTabs.size > 1) " ×${liveTabs.size}" else ""
                        // 空闲时也占一行：状态行只在连接中才出现的话，点「连接」
                        // 那一刻这一行变高，下面所有行跟着跳一下。
                        else -> "未连接"
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isConnected || connecting) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                if (isConnected) {
                    Box {
                        TextButton(
                            onClick = {
                                // 只有一条就直接断；有好几条时不替用户猜——产品上
                                // 这个按钮的语义是「断一条」，不是「断掉这台机器上
                                // 的全部标签」，猜错了就是把用户正在跑的活给杀了。
                                val only = liveTabs.singleOrNull()
                                if (only != null) onDisconnectTab(only) else disconnectPickerOpen = true
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) { Text("断开") }
                        TabPicker(
                            expanded = disconnectPickerOpen,
                            onDismiss = { disconnectPickerOpen = false },
                            title = "断开哪个标签？",
                            session = session,
                            liveTabs = liveTabs,
                            onPick = onDisconnectTab,
                        )
                    }
                }
                Box {
                    Button(
                        onClick = {
                            // 和「断开」同一个规则：多个标签时让用户选回到哪一个。
                            val only = liveTabs.singleOrNull()
                            when {
                                liveTabs.size > 1 -> resumePickerOpen = true
                                only != null -> onResumeTab(only)
                                else -> onConnect()
                            }
                        },
                        enabled = !connecting,
                        colors = ButtonDefaults.buttonColors(
                            // 默认的禁用色是灰的，叠在绿色条纹上像坏了；
                            // 保持品牌色、只是淡一点，一眼能看出「在忙」而不是「不可用」。
                            disabledContainerColor = colors.primary.copy(alpha = 0.45f),
                            disabledContentColor = colors.onPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = colors.onPrimary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("连接中")
                        } else {
                            Text(if (isConnected) "回到会话" else "连接")
                        }
                    }
                    TabPicker(
                        expanded = resumePickerOpen,
                        onDismiss = { resumePickerOpen = false },
                        title = "回到哪个标签？",
                        session = session,
                        liveTabs = liveTabs,
                        onPick = onResumeTab,
                    )
                }
            }
        }
    }
}

/** 「选哪个标签」的下拉菜单，「断开」和「回到会话」共用。 */
@Composable
private fun TabPicker(
    expanded: Boolean,
    onDismiss: () -> Unit,
    title: String,
    session: SessionRecord,
    liveTabs: List<LiveTabInfo>,
    onPick: (LiveTabInfo) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // 没有这行标题，一串标签名看起来像「切换到哪个标签」。
        Text(
            text = title,
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
                    onDismiss()
                    onPick(tab)
                },
            )
        }
    }
}

/** 背后那一格：整格可点，纯文字（项目没有引入 icons 库）。 */
@Composable
private fun ActionCell(label: String, container: Color, content: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(ACTION_WIDTH)
            .fillMaxHeight()
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * 「连接中」的背景：和主题同色系的斜条纹，持续向右流动（参考设计稿里的绿色斜线）。
 * 只在连接中才会被组合出来，所以无限动画不会在静止的列表里空转。
 */
@Composable
private fun ConnectingStripes() {
    val transition = rememberInfiniteTransition(label = "connecting")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "stripePhase",
    )
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val period = 30.dp.toPx()
        val stripe = 14.dp.toPx()
        val slant = size.height * 0.6f
        // phase 在 draw 阶段才读：动画只触发重绘，不触发重组。
        var x = -slant - period + phase * period
        while (x < size.width + period) {
            drawLine(color, Offset(x, size.height), Offset(x + slant, 0f), strokeWidth = stripe)
            x += period
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

/**
 * 连接失败提示：浮在列表上方而不是列表的一项，出现和消失都不会把下面的行推来推去。
 * 可以点「关闭」，也会在 [ERROR_AUTO_DISMISS_MS] 后自己收起。
 */
@Composable
private fun ConnectErrorBanner(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // 记住最后一条非空文案：消失动画播放时 message 已经是 null，不记住的话文字会先没、框后收。
    var lastMessage by remember { mutableStateOf("") }
    if (message != null) lastMessage = message
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(ERROR_AUTO_DISMISS_MS)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(150)) + expandVertically(),
        exit = fadeOut(tween(200)) + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

private const val ERROR_AUTO_DISMISS_MS = 8_000L
