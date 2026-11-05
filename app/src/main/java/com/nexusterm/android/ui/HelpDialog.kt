package com.nexusterm.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    "连接列表" to listOf(
        "连接列表里把一整行向左滑：露出「文件 / 编辑 / 删除」（已连接时还有「新标签」），向右滑或点这一行收回",
        "删除要再确认一次；点「连接」后按钮置灰、整行有条纹动画，表示正在连接；一条连接开了多个标签时，「断开」「回到会话」都会让你选哪一个",
    ),
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
 * 以此开头的行是分段内的小标题（不加「• 」、加粗显示），不是 bullet。用一个前缀约定而不是
 * 另起一个数据类型，是为了让 [HELP_SECTIONS] 保持「标题 → 文字行」这个一眼能对照的形状。
 */
private const val SUBHEADER_PREFIX = "## "

/**
 * 「外接键盘快捷键」一节。键位**不写死**，由 [shortcuts] 现场生成：用户在设置里改绑之后，
 * 帮助页必须跟着变，否则这份说明比没有还糟（照着它按不出效果）。所以它不能像
 * [HELP_SECTIONS] 那样是顶层常量，而是 [shortcuts] 的函数。
 *
 * 分组、顺序、文字全部来自 [ShortcutAction] / [ShortcutGroup]，新增动作不用改这里。
 * 未绑定的动作直接略去（帮助页列一个按不出来的「未设置」没有意义）。
 *
 * 不论当前有没有接键盘都照常显示：用户是在接键盘**之前**来翻帮助的。
 */
private fun keyboardShortcutSection(shortcuts: ShortcutBindings): Pair<String, List<String>> {
    val lines = mutableListOf("大部分手势都有对应的键盘操作，下面是当前的键位")

    ShortcutGroup.entries.forEach { group ->
        val groupLines = mutableListOf<String>()
        ShortcutAction.inGroup(group).forEach { action ->
            if (action.tabIndex != null) {
                // GOTO_TAB_1..9 合并成一行放在 GOTO_TAB_1 的位置，其余 2..9 在下面被吞掉。
                if (action.tabIndex == 0) {
                    val merged = gotoTabLine(shortcuts)
                    if (merged != null) {
                        groupLines += merged
                        return@forEach
                    }
                } else if (gotoTabLine(shortcuts) != null) {
                    return@forEach
                }
                // 合并不了（用户改绑过）就落回通用路径，每个动作各占一行，保证不漏不错。
            }
            if (shortcuts.chordsFor(action).isEmpty()) return@forEach
            groupLines += "${shortcuts.describe(action)}：${action.label}"
        }
        if (groupLines.isNotEmpty()) {
            lines += SUBHEADER_PREFIX + group.label
            lines += groupLines
        }
    }

    // 下面几条是固定文字，不来自绑定表：它们描述的是行为约定，不随改绑变化。
    lines += SUBHEADER_PREFIX + "其它说明"
    lines += "会话切换器打开时：←/→ 选卡，Enter 进入，Esc 取消，Delete 断开该会话（连按两下确认）"
    lines += "Ctrl+C、Ctrl+Z、Ctrl+字母和 Alt+字母原样发给远端，不会被应用占用"
    lines += "在系统层 Meta+/ 可以打开 Android 的键盘快捷键面板，里面同样列出这些快捷键"
    lines += "快捷键可在 首页 → 设置 → 手势设置 → 键盘快捷键 里修改"
    return "外接键盘快捷键" to lines
}

/**
 * 「切换到标签 1..9」合并成一行，如「Ctrl+Alt+1…9：直达第 N 个标签」。
 *
 * 只有在九个动作**各自恰好一个键位、修饰键相同、主键正好是数字 1..9**时才合并 ——
 * 这是默认键位的形状（Ctrl+Alt+数字）。用户把其中任何一个改绑、解绑或加了别名，
 * 「…1–9」这个写法就不再成立，返回 null 让调用方逐条列出真实键位，宁可啰嗦也不能错。
 */
private fun gotoTabLine(shortcuts: ShortcutBindings): String? {
    val first = shortcuts.chordsFor(ShortcutAction.GOTO_TAB_1).singleOrNull() ?: return null
    if (first.keyCode != KeyEvent.KEYCODE_1) return null
    for (index in 1..8) {
        val action = ShortcutAction.forTabIndex(index) ?: return null
        val chord = shortcuts.chordsFor(action).singleOrNull() ?: return null
        if (chord != first.copy(keyCode = KeyEvent.KEYCODE_1 + index)) return null
    }
    // describe() 以主键名结尾，数字键的名字就是 "1"，去掉它得到修饰键前缀（"Ctrl+Alt+"）。
    return "${first.describe().removeSuffix("1")}1…9：直达第 N 个标签"
}

/**
 * The "?" button's 操作说明: almost every terminal interaction is a gesture
 * with no on-screen affordance, so this is the only place they are discoverable.
 *
 * 所有输入都走参数、不依赖 SessionListScreen，终端内的帮助入口也可以直接复用。
 * [shortcuts] 默认是出厂键位，只是为了让不关心键盘的调用方照常编译；真正的调用方
 * 应传 `gestureConfig.shortcuts`，帮助页才会显示用户改绑后的键位。
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit, shortcuts: ShortcutBindings = ShortcutBindings.DEFAULT) {
    // 键位表不变就不重算：对话框每次重组都重新拼几十行字符串没有必要。
    val sections = remember(shortcuts) { HELP_SECTIONS + keyboardShortcutSection(shortcuts) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作说明") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                sections.forEachIndexed { index, (header, lines) ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp, bottom = 4.dp),
                    )
                    lines.forEach { line ->
                        if (line.startsWith(SUBHEADER_PREFIX)) {
                            Text(
                                text = line.removePrefix(SUBHEADER_PREFIX),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                            )
                        } else {
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
