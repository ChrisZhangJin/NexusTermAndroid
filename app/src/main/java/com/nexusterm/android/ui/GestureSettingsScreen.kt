package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

// 手势设置页。入口在首页而不在终端里，原因见 GestureConfig.kt 开头：在荣耀上
// 终端菜单（双指长按）本身就是打不开的那个，如果设置入口藏在菜单里，最需要
// 改绑的人恰好进不来。
//
// 状态由上层持有（config 入参 + onConfigChange），本页不碰 SharedPreferences：
// 首页、设置页、终端页要共享同一份，持久化放在 MainActivity 里统一做。

// 滑块步长。给 Slider 设 steps 而不是连续拖：用户调的是「大概 0.8 秒还是 1.2 秒」，
// 精确到 1ms 没有意义，而且离散档位拖起来手感更稳、保存下来的数也整齐。
private const val HOLD_STEP_MS = 50L
private const val DOUBLE_TAP_STEP_MS = 25L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureSettingsScreen(
    config: GestureConfig,
    onConfigChange: (GestureConfig) -> Unit,
    onBack: () -> Unit,
) {
    // Saveable：确认框开着时旋转屏幕不该把它丢掉再让用户重点一次。
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手势设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    // 已经是出厂配置时灰掉：点了什么都不会发生的按钮不如不让点。
                    TextButton(
                        onClick = { confirmReset = true },
                        enabled = config != GestureConfig.DEFAULT,
                    ) { Text("恢复默认") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "某些手机会在系统层占用特定手势，应用根本收不到事件：例如荣耀的「YOYO 问屏幕」" +
                    "会占用双指长按，终端菜单就打不开了。遇到这种冲突，在这里把动作改绑到别的手势即可。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()

            // 按手指数分段。enum 声明顺序里同组的槽位本来就挨在一起，groupBy 保持
            // 首次出现的顺序，所以段的顺序和段内顺序都由 GestureSlot 的声明决定。
            GestureSlot.entries.groupBy { it.group }.forEach { (group, slots) ->
                GroupHeader(group)
                slots.forEach { slot ->
                    SlotRow(slot = slot, config = config, onConfigChange = onConfigChange)
                    HorizontalDivider()
                }
            }

            Text(
                text = "单指轻点和单指滑动不可配置：它们要原样交给终端，用于光标定位、唤起键盘、" +
                    "滚动历史，以及 vim / tmux 的鼠标操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认手势？") },
            text = { Text("所有手势绑定和时长都会回到出厂设置。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onConfigChange(GestureConfig.DEFAULT)
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

/** 分段小标题。双指/三指段多带一行方向说明，免得「上滑」「放大」被理解反。 */
@Composable
private fun GroupHeader(group: SlotGroup) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            text = group.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (group.fingers >= 2) {
            Text(
                text = "上滑 / 下滑：手指向屏幕上方 / 下方移动；放大 / 缩小：手指分开 / 靠拢。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SlotRow(
    slot: GestureSlot,
    config: GestureConfig,
    onConfigChange: (GestureConfig) -> Unit,
) {
    val current = config.actionFor(slot)
    val thresholds = config.thresholds
    val enabled = current != GestureAction.NONE

    // 重复绑定的策略：**允许，但明确提示**，不阻止也不自动互换。
    // 不阻止：同一个动作放两个手势上是说得通的（比如想要一个备用入口来绕开被
    // 系统抢走的手势，这恰恰是本页存在的场景 —— 出厂默认里「右键菜单」就同时
    // 绑在单指双击和双指长按上）；不互换：互换对 FONT_SIZE_UP/DOWN 这类只能放
    // 捏合槽位的动作会产生非法组合。NONE 不算重复，空槽本来就可以有很多个。
    // 因为出厂默认就有一处重复，提示用中性色而不是错误色：它是信息，不是出错了。
    val duplicates = otherSlotsBound(config, slot, current)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = slot.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            ActionPicker(slot = slot, config = config, current = current, onConfigChange = onConfigChange)
        }

        if (duplicates.isNotEmpty()) {
            Text(
                text = "「${current.label}」也绑定在：${duplicates.joinToString("、") { it.label }}（任一手势都会触发）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val holdMs = thresholds.holdMsFor(slot)
        if (holdMs != null) {
            MsSlider(
                label = "长按时长",
                valueMs = holdMs,
                range = GESTURE_HOLD_MS_RANGE,
                stepMs = HOLD_STEP_MS,
                enabled = enabled,
                onCommit = { ms ->
                    onConfigChange(config.copy(thresholds = thresholds.withHoldMs(slot, ms).coerced()))
                },
            )
        }

        // 单指/双指双击各有自己的窗口，各读各写：之前共用一个字段，拖一个另一个跟着动。
        val doubleTapMs = thresholds.doubleTapWindowMsFor(slot)
        if (doubleTapMs != null) {
            MsSlider(
                label = "双击间隔",
                valueMs = doubleTapMs,
                range = GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE,
                stepMs = DOUBLE_TAP_STEP_MS,
                enabled = enabled,
                onCommit = { ms ->
                    onConfigChange(config.copy(thresholds = thresholds.withDoubleTapWindowMs(slot, ms).coerced()))
                },
            )
        }

        if (slot == GestureSlot.ONE_FINGER_DOUBLE_TAP) {
            // 这是真实代价，不是提示性质的小字：识别单指双击必须把每次单指轻点
            // 压住、等满窗口确认没有第二下才放行（见 GestureSlot.ONE_FINGER_DOUBLE_TAP）。
            // 现在默认就是启用的，所以默认就是醒目的错误色；用户改成「无」之后降为
            // 灰色，但仍然写出来，让他在重新打开之前就知道代价。
            Text(
                text = "⚠ 启用后，终端里每一次单指轻点都会延迟约 ${thresholds.oneFingerDoubleTapWindowMs} ms" +
                    "（要等是否有第二下），光标定位、唤起键盘都会变慢。",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 槽位当前动作的下拉选择。只列 [GestureAction.allowedActionsFor]，用户看不到非法组合。 */
@Composable
private fun ActionPicker(
    slot: GestureSlot,
    config: GestureConfig,
    current: GestureAction,
    onConfigChange: (GestureConfig) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { open = true }) { Text("${current.label} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GestureAction.allowedActionsFor(slot).forEach { action ->
                val elsewhere = otherSlotsBound(config, slot, action)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text((if (action == current) "✓ " else "") + action.label)
                            // 选之前就看得到，免得选完才在行内看到红字。
                            if (elsewhere.isNotEmpty()) {
                                Text(
                                    text = "已绑定到：${elsewhere.joinToString("、") { it.label }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        open = false
                        // rebind 对非法组合返回 this，这里选项已经过滤过，不会走到。
                        if (action != current) onConfigChange(config.rebind(slot, action))
                    },
                )
            }
        }
    }
}

/**
 * 毫秒数滑块。拖动过程中的值只存在本地，松手才回调 [onCommit]。
 *
 * 否则每个拖动帧都会触发一次 onConfigChange，也就是一次 SharedPreferences 写盘
 * 加整个终端手势识别器的重建。`remember(valueMs)` 让外部值（比如点了「恢复默认」）
 * 变化时本地值跟着重置。
 */
@Composable
private fun MsSlider(
    label: String,
    valueMs: Long,
    range: LongRange,
    stepMs: Long,
    enabled: Boolean,
    onCommit: (Long) -> Unit,
) {
    var dragging by remember(valueMs) { mutableFloatStateOf(valueMs.toFloat()) }

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = "$label：${dragging.roundToLong()} ms",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                // 空槽的时长没有意义，灰掉但保留数值，用户改绑回来时不会丢设置。
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
        )
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onCommit(dragging.roundToLong()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // steps 是区间内部的刻度数，不含两端，所以要减 1。
            steps = ((range.last - range.first) / stepMs - 1).toInt(),
            enabled = enabled,
        )
    }
}

/** 除 [slot] 之外还绑了 [action] 的槽位。NONE 不算重复。 */
private fun otherSlotsBound(config: GestureConfig, slot: GestureSlot, action: GestureAction): List<GestureSlot> =
    if (action == GestureAction.NONE) emptyList() else config.slotsFor(action).filter { it != slot }

/** 长按槽位对应的时限；非长按槽位返回 null。 */
private fun GestureThresholds.holdMsFor(slot: GestureSlot): Long? = when (slot) {
    GestureSlot.ONE_FINGER_HOLD -> oneFingerHoldMs
    GestureSlot.TWO_FINGER_HOLD -> twoFingerHoldMs
    GestureSlot.THREE_FINGER_HOLD -> threeFingerHoldMs
    else -> null
}

private fun GestureThresholds.withHoldMs(slot: GestureSlot, ms: Long): GestureThresholds = when (slot) {
    GestureSlot.ONE_FINGER_HOLD -> copy(oneFingerHoldMs = ms)
    GestureSlot.TWO_FINGER_HOLD -> copy(twoFingerHoldMs = ms)
    GestureSlot.THREE_FINGER_HOLD -> copy(threeFingerHoldMs = ms)
    else -> this
}

/** 双击槽位对应的窗口；非双击槽位返回 null。 */
private fun GestureThresholds.doubleTapWindowMsFor(slot: GestureSlot): Long? = when (slot) {
    GestureSlot.ONE_FINGER_DOUBLE_TAP -> oneFingerDoubleTapWindowMs
    GestureSlot.TWO_FINGER_DOUBLE_TAP -> twoFingerDoubleTapWindowMs
    else -> null
}

private fun GestureThresholds.withDoubleTapWindowMs(slot: GestureSlot, ms: Long): GestureThresholds = when (slot) {
    GestureSlot.ONE_FINGER_DOUBLE_TAP -> copy(oneFingerDoubleTapWindowMs = ms)
    GestureSlot.TWO_FINGER_DOUBLE_TAP -> copy(twoFingerDoubleTapWindowMs = ms)
    else -> this
}
