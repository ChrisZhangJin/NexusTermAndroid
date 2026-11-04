package com.nexusterm.android.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.nexusterm.android.rememberHasHardKeyboard

// 手势设置页里的「键盘快捷键」一节：列出每个动作当前的键位，点一行弹出捕获对话框改绑。
// 单独成文件是为了不把 GestureSettingsScreen.kt 撑得更长，那边只留一行调用。
//
// 状态同手势部分：config 由上层持有，这里只通过 onConfigChange 回写，不碰 SharedPreferences。
// 键位的合法性判定（[isReservedChord]）、冲突查询（[ShortcutBindings.conflictsFor]）、改绑（抢占
// 语义）全在 Shortcuts.kt，这里只负责把它们串成交互。

/**
 * 「键盘快捷键」一节。**始终显示**：用户常常是先配置、后接键盘，不能因为现在没检测到键盘
 * 就把入口藏起来。状态行只是信息，不影响任何按钮是否可用。
 */
@Composable
fun ShortcutSettingsSection(
    config: GestureConfig,
    onConfigChange: (GestureConfig) -> Unit,
) {
    val hasKeyboard by rememberHasHardKeyboard()
    val bindings = config.shortcuts

    // 当前正在捕获哪个动作的键位（null = 对话框关着）。存枚举而不是 Boolean + 另一个状态，
    // 旋转屏幕后对话框还在、还知道是给谁改。enum 是 Serializable，rememberSaveable 能直接存。
    var capturing by rememberSaveable { mutableStateOf<ShortcutAction?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    HorizontalDivider()

    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(text = "键盘快捷键", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (hasKeyboard) "已检测到外接键盘" else "未检测到外接键盘（接上后即可使用）",
            style = MaterialTheme.typography.bodySmall,
            color = if (hasKeyboard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "应用级快捷键默认使用 Ctrl+Shift+字母，这样远端 shell 里的 Ctrl+C、Ctrl+Z，" +
                "以及 vim、tmux 的按键都不会被抢走。Meta（Win/Cmd）组合键由 Android 系统保留，不能绑定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // 只重置快捷键，不动手势；已经是出厂键位时灰掉（同顶栏「恢复默认」的做法）。
            TextButton(
                onClick = { confirmReset = true },
                enabled = bindings != ShortcutBindings.DEFAULT,
            ) { Text("恢复默认快捷键") }
        }
    }

    ShortcutGroup.entries.forEach { group ->
        ShortcutGroupHeader(group)
        ShortcutAction.inGroup(group).forEach { action ->
            when {
                // GOTO_TAB_1..9 九行几乎一样，折成一行，挂在第一个 GOTO 的位置上。
                action.tabIndex == null -> {
                    ShortcutRow(
                        label = action.label,
                        binding = bindings.describe(action),
                        onClick = { capturing = action },
                    )
                    HorizontalDivider()
                }
                action.tabIndex == 0 -> {
                    ShortcutRow(
                        label = "直达第 1–9 个标签",
                        binding = gotoTabSummary(bindings),
                        caption = "固定的一组键位，不能在这里逐个改绑",
                        onClick = null,
                    )
                    HorizontalDivider()
                }
                else -> Unit
            }
        }
    }

    val target = capturing
    if (target != null) {
        // 对话框是模态的，关掉才能点别的行，所以它的冲突 / 提示状态不会串到下一个动作上。
        ShortcutCaptureDialog(
            action = target,
            bindings = bindings,
            hasKeyboard = hasKeyboard,
            onApply = { newBindings ->
                capturing = null
                onConfigChange(config.copy(shortcuts = newBindings))
            },
            onDismiss = { capturing = null },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认快捷键？") },
            text = { Text("所有键盘快捷键都会回到出厂键位，手势设置不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onConfigChange(config.copy(shortcuts = ShortcutBindings.DEFAULT))
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

/** 分段小标题，样式同手势部分的 GroupHeader（那个是 private 的，这里照着写一份）。 */
@Composable
private fun ShortcutGroupHeader(group: ShortcutGroup) {
    Text(
        text = group.label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** 一行：左边动作名（下面一行小字是键位），[onClick] 为 null 表示只读行。保持紧凑，全表有约 18 行。 */
@Composable
private fun ShortcutRow(
    label: String,
    binding: String,
    caption: String? = null,
    onClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = binding,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 折叠行里显示的键位。默认是「Ctrl+Alt+1…9」；如果九个动作不再是「同一组修饰键 + 数字 1..9」
 * （比如有人把 Ctrl+Alt+3 抢去绑了别的动作，标签 3 就变成未设置），就老老实实逐个列出来，
 * 不能继续假装是一整组。
 */
private fun gotoTabSummary(bindings: ShortcutBindings): String {
    val gotos = ShortcutAction.entries.filter { it.tabIndex != null }
    val first = bindings.chordsFor(gotos.first()).singleOrNull()
    val uniform = first != null && first.keyCode == AndroidKeyEvent.KEYCODE_1 && gotos.all { a ->
        bindings.chordsFor(a).singleOrNull() == first.copy(keyCode = first.keyCode + a.tabIndex!!)
    }
    if (uniform) return first!!.describe().dropLast(1) + "1…9"
    return gotos.joinToString("、") { "${it.tabIndex!! + 1}: ${bindings.describe(it)}" }
}

/**
 * 捕获键位的对话框。
 *
 * **触屏也要能完整操作**（用户可能还没接键盘，或者键盘被系统吃了某个键）：取消 / 清除 /
 * 恢复默认都是按钮，不依赖 Esc。物理 Esc 在这里反而是「被保留的键」，会提示而不是关窗口。
 *
 * 按键捕获靠一个拿着焦点的 Box：`focusRequester + focusable + onPreviewKeyEvent`，对**每一个**
 * 键事件（含 KeyUp、重复、被拒绝的）都返回 true。否则 Tab / 方向键会让 Compose 焦点跑到别的按钮上，
 * Enter 会点中被聚焦的按钮，Esc 会触发对话框的「返回」把它关掉 —— 用户想绑的恰恰就是这些键。
 */
@Composable
private fun ShortcutCaptureDialog(
    action: ShortcutAction,
    bindings: ShortcutBindings,
    hasKeyboard: Boolean,
    onApply: (ShortcutBindings) -> Unit,
    onDismiss: () -> Unit,
) {
    // 被拒绝时的提示（保持捕获，用户直接再按一次）。
    var message by remember { mutableStateOf<String?>(null) }

    // 合法但已被别的动作占着的键位，等用户确认「覆盖」。
    var pending by remember { mutableStateOf<KeyChord?>(null) }

    val focusRequester = remember { FocusRequester() }

    // 进入、以及状态变化后（比如点了「重按」按钮，焦点可能被按钮拿走）都把焦点要回来。
    // 对话框窗口刚建好时 requestFocus 偶尔会赶在节点挂载之前，所以兜一层 runCatching。
    LaunchedEffect(pending, message) {
        runCatching { focusRequester.requestFocus() }
    }

    val conflicts = pending?.let { bindings.conflictsFor(it, except = action) }.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.label) },
        text = {
            Column {
                Text(
                    text = "请按下新的组合键",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (hasKeyboard) "请在键盘上按下想要的组合键" else "请在外接键盘上按下组合键",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Box(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .focusRequester(focusRequester)
                        .focusable()
                        // 放在 focusable 之后：键事件先到焦点节点，再逐级往上，这里是最内层。
                        .onPreviewKeyEvent { event ->
                            handleCaptureKey(
                                event = event,
                                action = action,
                                bindings = bindings,
                                onMessage = { m ->
                                    message = m
                                    pending = null
                                },
                                onConflict = { chord ->
                                    message = null
                                    pending = chord
                                },
                                onApply = onApply,
                            )
                        }
                        .padding(12.dp),
                ) {
                    Column {
                        Text(
                            text = "当前：${bindings.describe(action)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (bindings.chordsFor(action).size > 1) {
                            // rebind 是「替换成唯一的键位」，字号增大的四个别名会被一并顶掉，先说清楚。
                            Text(
                                text = "改绑后只保留新的组合键，其余别名会被移除。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                val m = message
                if (m != null) {
                    Text(
                        text = m,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                val p = pending
                if (p != null) {
                    // 抢占语义：键位从原动作的列表里摘掉；只剩这一个键位的动作会变成「未设置」。
                    val becomeUnset = conflicts.filter { c -> bindings.chordsFor(c).all { it == p } }
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = "${p.describe()} 已绑定在：${conflicts.joinToString("、") { it.label }}，确定覆盖？",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (becomeUnset.isNotEmpty()) {
                            Text(
                                text = "覆盖后「${becomeUnset.joinToString("、") { it.label }}」将变为「未设置」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { pending = null }) { Text("重按") }
                            TextButton(onClick = { onApply(bindings.rebind(action, p)) }) { Text("覆盖") }
                        }
                    }
                }
            }
        },
        // 三个按钮都放在 confirmButton 槽里：AlertDialog 的按钮区会自己换行，不需要再占 dismissButton。
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onApply(bindings.unbind(action)) }) { Text("清除") }
                TextButton(onClick = { onApply(bindings.reset(action)) }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 处理捕获框收到的一个键事件，**永远返回 true**（理由见 [ShortcutCaptureDialog]）。
 *
 * 只看 KeyDown 且忽略按住不放的重复；KeyUp 直接吞掉。
 */
private fun handleCaptureKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    action: ShortcutAction,
    bindings: ShortcutBindings,
    onMessage: (String) -> Unit,
    onConflict: (KeyChord) -> Unit,
    onApply: (ShortcutBindings) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return true
    if (event.nativeKeyEvent.repeatCount > 0) return true

    val chord = KeyChord(
        keyCode = event.key.nativeKeyCode,
        ctrl = event.isCtrlPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    )
    val reason = isReservedChord(chord)

    // 先放过纯修饰键（用户正在按下 Ctrl+Shift 的路上，等真正的主键）。必须排在 Meta 检查之前：
    // 按下 Meta 键本身时事件的 meta 状态已经是 true，不排前面就会一按 Win 键就报错。
    if (reason == ReservedReason.MODIFIER_ONLY) return true

    if (event.isMetaPressed) {
        onMessage("Meta（Win/Cmd）组合键被系统保留，不能用")
        return true
    }
    if (reason != null) {
        onMessage(reason.message)
        return true
    }

    if (bindings.conflictsFor(chord, except = action).isNotEmpty()) {
        onConflict(chord)
    } else {
        onApply(bindings.rebind(action, chord))
    }
    return true
}
