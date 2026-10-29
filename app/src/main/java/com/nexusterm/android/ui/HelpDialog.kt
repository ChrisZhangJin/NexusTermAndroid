package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Section header → bullet lines. Kept as data rather than inline composables so
 * the wording can be checked against the code it describes at a glance: the
 * menu entries mirror [TerminalTabsPager]'s DropdownMenu and the key-bar lines
 * mirror [TerminalKeyBar]'s PAGES and sticky-modifier handling.
 */
private val HELP_SECTIONS = listOf(
    "终端手势" to listOf(
        "双指捏合：缩放字体（8–28 号），每捏开或捏拢一段距离跳一号",
        "双指按住不动 0.8 秒后抬起：打开菜单（粘贴、清屏、滑至底部、字体还原、虚拟大窗口、SFTP 文件管理、断开）；震动后若继续捏合或拖动，仍按捏合 / 切换卡片处理",
        "双指上下拖动：会话切换卡片；左右滑选择，点击进入，向上甩出断开该会话",
        "单指上下滑动：滚动历史，松手带惯性（远端开启鼠标模式或运行 vim 等全屏程序时交给程序处理）",
        "虚拟大窗口下单指左右滑动：横向平移",
    ),
    "文本选择" to listOf(
        "单指长按 0.8 秒：选中手指下的词（震动提示），按住继续拖动可扩展",
        "选中状态下单指拖动：选区终点跟到手指处，拖到上下边缘自动滚动",
        "单指轻点：结束选择并复制到剪贴板",
        "选中期间单指无法滚动屏幕，先轻点结束（同时完成复制）再滚",
    ),
    "常用键栏" to listOf(
        "闲置 6 秒自动隐藏，终端随之伸缩",
        "软键盘弹出时键栏常驻，键盘收起后才重新开始计时",
        "在终端底部继续向上推（过度滚动）呼出键栏",
        "在键栏上单指左右滑动：切换页面（常用键 / 符号 / F1–F12）；点一下仍是打字，横向滑开一小段才算翻页并取消该次按键",
        "CTRL / ALT 点一下待用，作用于下一个按键（含软键盘输入），再点一下取消",
    ),
    "会话" to listOf(
        "通知栏显示已连接会话数，点击返回应用",
    ),
)

/**
 * The "?" button's 操作说明: almost every terminal interaction is a gesture
 * with no on-screen affordance, so this is the only place they are discoverable.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作说明") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HELP_SECTIONS.forEachIndexed { index, (header, lines) ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp, bottom = 4.dp),
                    )
                    lines.forEach { line ->
                        Text(
                            text = "• $line",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
