package com.nexusterm.android.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 终端里的「查找」和「标记」，对应桌面版的 Ctrl+F 查找条和「标记…」窗口。
 * 页面那头的实现见 index.html（addon-search 和移植过来的 Highlighter）。
 */

/** 标记的三种底色，和桌面版 MARK_COLORS 一致（xterm 的装饰只认 #RRGGBB）。 */
private val MARK_SWATCHES = listOf(
    "高亮黄" to "#ffd84d",
    "高亮绿" to "#6fdc8c",
    "高亮蓝" to "#6cb6ff",
)

/** 输入停顿多久再去查：每个字符都查一次整个滚动历史没有意义。 */
private const val FIND_DEBOUNCE_MS = 120L

/**
 * 终端顶部的查找条。[seed] 是打开时终端里选中的文字，自动填进输入框。
 * 条里的状态（关键字、选项）只活到关闭为止，关闭时撤掉页面上的高亮。
 */
@Composable
internal fun FindBar(
    handle: TerminalHandle,
    seed: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf(seed.lineSequence().firstOrNull().orEmpty()) }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var bad by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun step(mode: String) {
        if (query.isNotEmpty() && !bad && handle.searchCount >= 0) handle.find(query, caseSensitive, wholeWord, regex, mode)
    }

    fun close() {
        keyboard?.hide()
        onClose()
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // 关键字或选项变了：增量查找（当前命中还匹配就不动）。首次进来有 seed 时也会查一次。
    LaunchedEffect(query, caseSensitive, wholeWord, regex) {
        if (query.isNotEmpty()) delay(FIND_DEBOUNCE_MS)
        handle.find(query, caseSensitive, wholeWord, regex, "incr") { valid -> bad = !valid }
    }
    DisposableEffect(handle) { onDispose { handle.clearFind() } }
    BackHandler { close() }

    val status = when {
        handle.searchCount < 0 -> "查找不可用"
        query.isEmpty() -> ""
        bad -> "正则无效"
        handle.searchCount == 0 -> "无结果"
        handle.searchIndex < 0 -> "${handle.searchCount}+ 处"
        else -> "${handle.searchIndex + 1} / ${handle.searchCount}"
    }
    val miss = query.isNotEmpty() && (bad || handle.searchCount == 0)

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    if (query.isEmpty()) {
                        Text("查找关键字", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (miss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { step("next") }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            // 硬键盘：Enter 下一个、Shift+Enter 上一个、Esc 关。
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.Enter, Key.NumPadEnter -> { step(if (e.isShiftPressed) "prev" else "next"); true }
                                    Key.Escape -> { close(); true }
                                    else -> false
                                }
                            },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = status,
                    fontSize = 12.sp,
                    color = if (miss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(enabled = handle.searchCount >= 0, onClick = { step("prev") }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("↑") }
                TextButton(enabled = handle.searchCount >= 0, onClick = { step("next") }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("↓") }
                TextButton(onClick = { close() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("×") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = caseSensitive, onClick = { caseSensitive = !caseSensitive }, label = { Text("Aa 大小写") })
                FilterChip(selected = wholeWord, onClick = { wholeWord = !wholeWord }, label = { Text("全词") })
                FilterChip(selected = regex, onClick = { regex = !regex }, label = { Text("正则") })
            }
        }
    }
}

/**
 * 「标记关键字」：三种颜色各填一个要高亮的文字，留空 = 不用。规则存在页面里，
 * 新输出会自动继续高亮。[seed] 是打开时的选中文字，填进第一个空位。
 */
@Composable
internal fun MarkDialog(handle: TerminalHandle, seed: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val texts = remember { mutableStateListOf("", "", "") }
    var caseSensitive by remember { mutableStateOf(false) }

    LaunchedEffect(handle) {
        handle.readMarks { rules, cs ->
            MARK_SWATCHES.forEachIndexed { i, (_, color) ->
                texts[i] = rules.firstOrNull { it.color == color }?.text.orEmpty()
            }
            caseSensitive = cs
            val s = seed.lineSequence().firstOrNull()?.trim().orEmpty()
            if (s.isNotEmpty() && s !in texts) {
                val free = texts.indexOfFirst { it.isEmpty() }
                if (free >= 0) texts[free] = s
            }
        }
    }

    fun submit() {
        val rules = MARK_SWATCHES.mapIndexed { i, (_, color) -> TerminalHandle.MarkRule(color, texts[i]) }
        val any = rules.any { it.text.isNotEmpty() }
        handle.applyMarks(rules, caseSensitive) { hits ->
            if (any) Toast.makeText(context, "标记完成：共高亮 $hits 处", Toast.LENGTH_SHORT).show()
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标记关键字") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "为每种颜色输入要高亮的文本，留空则不使用。新输出会自动按规则继续高亮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MARK_SWATCHES.forEachIndexed { i, (label, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(color))),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = texts[i],
                            onValueChange = { texts[i] = it },
                            singleLine = true,
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                    Text("大小写敏感")
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { handle.clearMarks(); onDismiss() }) { Text("清除全部") }
                TextButton(onClick = { submit() }) { Text("确定") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
