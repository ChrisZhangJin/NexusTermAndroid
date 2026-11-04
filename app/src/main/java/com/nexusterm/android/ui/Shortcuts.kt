package com.nexusterm.android.ui

import android.util.Log
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

private const val SHORTCUT_TAG = "NexusTermShortcut"

// 外接物理键盘时的快捷键 —— 数据模型与持久化，和手势（GestureConfig.kt）是平行的两套输入，
// 动作集合也不同，所以这里是一个**独立的** enum（[ShortcutAction]），而不是往
// GestureAction 里塞：GestureAction 的每个成员都带着「能放在哪种手势槽位上」的类型
// 约束，而且 TerminalTabsPager 对它有穷尽的 when；快捷键没有槽位这个概念（一个键位组合
// 就是一个键位组合），硬塞进去只会让两边的约束互相污染。
//
// 持久化却是**搭车**的：[ShortcutBindings] 是 [GestureConfig.shortcuts] 字段，随整份
// GestureConfig 走同一个 SharedPreferences key（见 GesturePrefs）。MainActivity 持久化的
// 就是整份 GestureConfig、设置页编辑的也是它，这样现有的接线一行不用加。
//
// ## 为什么默认键位几乎全是 Ctrl+Shift+字母
//
// 终端里按键是**两家抢**的：app 和远端程序。xterm.js 会把 Ctrl+字母 变成控制字符发给
// 远端（^A 行首、^C 中断、^D EOF、^W 删词、^R 反向搜索……readline / vim / tmux 全靠它），
// Alt+字母 变成 ESC 前缀（readline 的 M-b / M-f）。app 一旦把 Ctrl+T 之类抢走，远端
// 程序就永远收不到它 —— 这是不可接受的，所以「Ctrl+字母」「Alt+字母」整族归远端。
// 而 xterm.js 对 Ctrl+Shift+字母 不产生任何数据（浏览器类终端的惯例也是用这一族做
// 终端自己的功能，比如复制粘贴），所以 app 级别的快捷键就放在 Ctrl+Shift+字母 上。
// 非字母键（数字、Tab、=、-、End……）没有这个冲突，Ctrl+数字 / Ctrl+Tab 这类可以直接用。
// 具体判定规则见 [isReservedChord]。
//
// **Meta（Win/Cmd 键）永远不参与**：Android 在事件到达 app 之前就把 Meta 组合键吃掉了
// （Meta+Enter、Meta+Tab 等是系统快捷键），app 层面绑了也收不到。所以 [KeyChord] 里
// 根本没有 meta 字段，[ShortcutBindings.actionFor] 看到 meta 一律返回 null。

// ─────────────────────────── 动作 ───────────────────────────

/** 设置页 / 帮助页分段用：快捷键动作的分组。 */
enum class ShortcutGroup(val label: String) {
    TAB("标签"),
    TERMINAL("终端"),
    FONT("字体"),
    OTHER("其它"),
}

/**
 * 可绑定快捷键的动作。
 *
 * enum 的声明顺序就是设置页 / 帮助页里的显示顺序，**同组的成员要挨在一起**
 * （所以 OPEN_MENU 放在「其它」组里，而不是紧跟 RENAME_TAB）。声明顺序同时也是
 * [ShortcutBindings.actionFor] 在极端情况下（手工改坏的存档里同一个键位被两个动作
 * 占着）的裁决顺序：靠前的赢。
 *
 * 动作的语义（调用方实现时的口径）：
 *  - [NEW_TAB]：**复制当前连接**开一个新标签，不是弹出连接页。
 *  - [CLOSE_TAB]：断开当前会话（等同菜单里的「断开连接」）。
 *  - [OPEN_MENU]：打开终端菜单（等同手势里的「右键菜单」）。
 *  - [TOGGLE_WIDE_WINDOW]：开/关虚拟大窗口。和手势那边拆成开、关两个单向动作不同，
 *    键盘没有「方向性槽位天然成对」这回事，一个键位就是一次切换，toggle 更自然。
 *  - [FONT_SIZE_UP] / [FONT_SIZE_DOWN]：按一次步进一档；[FONT_SIZE_RESET] 回默认字号。
 *
 * [tabIndex] 只有 GOTO_TAB_1..9 非空，且是 **0 基**的（GOTO_TAB_1 → 0），直接当标签
 * 列表下标用；超出当前标签数时由调用方忽略。
 *
 * label 放在这里而不是设置页，理由同 [GestureSlot]：新增一个动作只改这一个文件。
 *
 * 用构造参数而不是 getter 存 [tabIndex]：它是写死的常量，不依赖任何别的类的初始化，
 * 没有 GestureAction 注释里说的那个 enum 初始化顺序的坑。
 */
enum class ShortcutAction(
    val label: String,
    val group: ShortcutGroup,
    val tabIndex: Int? = null,
) {
    // 标签
    NEXT_TAB("下一个标签", ShortcutGroup.TAB),
    PREV_TAB("上一个标签", ShortcutGroup.TAB),
    GOTO_TAB_1("切换到标签 1", ShortcutGroup.TAB, 0),
    GOTO_TAB_2("切换到标签 2", ShortcutGroup.TAB, 1),
    GOTO_TAB_3("切换到标签 3", ShortcutGroup.TAB, 2),
    GOTO_TAB_4("切换到标签 4", ShortcutGroup.TAB, 3),
    GOTO_TAB_5("切换到标签 5", ShortcutGroup.TAB, 4),
    GOTO_TAB_6("切换到标签 6", ShortcutGroup.TAB, 5),
    GOTO_TAB_7("切换到标签 7", ShortcutGroup.TAB, 6),
    GOTO_TAB_8("切换到标签 8", ShortcutGroup.TAB, 7),
    GOTO_TAB_9("切换到标签 9", ShortcutGroup.TAB, 8),
    OPEN_SWITCHER("会话切换器", ShortcutGroup.TAB),
    NEW_TAB("新建标签（复制当前连接）", ShortcutGroup.TAB),
    CLOSE_TAB("关闭标签（断开连接）", ShortcutGroup.TAB),
    RENAME_TAB("重命名标签", ShortcutGroup.TAB),

    // 终端
    COPY("复制", ShortcutGroup.TERMINAL),
    PASTE("粘贴", ShortcutGroup.TERMINAL),
    CLEAR_SCREEN("清屏", ShortcutGroup.TERMINAL),
    SCROLL_TO_BOTTOM("滑至底部", ShortcutGroup.TERMINAL),

    // 字体
    FONT_SIZE_UP("字号增大", ShortcutGroup.FONT),
    FONT_SIZE_DOWN("字号减小", ShortcutGroup.FONT),
    FONT_SIZE_RESET("字号还原", ShortcutGroup.FONT),

    // 其它
    OPEN_MENU("终端菜单", ShortcutGroup.OTHER),
    TOGGLE_KEY_BAR("显示/隐藏按键栏", ShortcutGroup.OTHER),
    TOGGLE_WIDE_WINDOW("开关虚拟大窗口", ShortcutGroup.OTHER),
    OPEN_SFTP("SFTP 文件管理", ShortcutGroup.OTHER);

    companion object {
        /** [group] 里的动作，按声明顺序。设置页 / 帮助页分段用。 */
        fun inGroup(group: ShortcutGroup): List<ShortcutAction> =
            entries.filter { it.group == group }

        /** 0 基的标签下标 → 对应的 GOTO_TAB_n；没有（下标不在 0..8）返回 null。 */
        fun forTabIndex(index: Int): ShortcutAction? =
            entries.firstOrNull { it.tabIndex == index }
    }
}

// ─────────────────────────── 键位组合 ───────────────────────────

/**
 * 一个键位组合：一个主键 + 修饰键状态。
 *
 * [keyCode] 是 `android.view.KeyEvent.KEYCODE_*`。三个修饰键是**精确匹配**的语义：
 * 绑 Ctrl+1 的动作不会被 Ctrl+Alt+1 触发（见 [ShortcutBindings.actionFor]）。
 * **没有 Meta 字段**，理由见文件头。
 *
 * 构造器不做合法性校验（data class 的 copy 绕不过去，理由同 [GestureThresholds]）；
 * 「能不能当快捷键」由 [isReservedChord] 回答，在真正的入口（[ShortcutBindings.rebind]、
 * [ShortcutBindings.fromJsonObject]）把关。
 */
data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean,
    val shift: Boolean,
    val alt: Boolean,
) {
    /**
     * 给人看的写法，修饰键固定按 Ctrl、Alt、Shift 的顺序：
     * "Ctrl+Shift+T"、"Ctrl+Alt+1"、"Ctrl+="、"Ctrl+Tab"、"Ctrl+Num+"。
     */
    fun describe(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(keyName(keyCode))
    }

    /** 存档里的一项。字段名短而稳定；不写 describe() 的结果，那是给人看的、可能改措辞。 */
    internal fun toJsonObject(): JSONObject = JSONObject()
        .put(KEY_CHORD_KEY, keyCode)
        .put(KEY_CHORD_CTRL, ctrl)
        .put(KEY_CHORD_SHIFT, shift)
        .put(KEY_CHORD_ALT, alt)

    companion object {
        /**
         * 从存档里的一项读回来。不是对象 / 没有正整数的 key / 数值不是数字，都返回 null
         * （由调用方决定那一整项怎么回退）；修饰键缺失或类型不对按 false 算。
         * 不查 [isReservedChord]，那是 [ShortcutBindings.fromJsonObject] 的事。
         */
        internal fun fromJsonObject(o: JSONObject?): KeyChord? {
            if (o == null) return null
            val key = o.optInt(KEY_CHORD_KEY, 0)
            if (key <= 0) return null
            return KeyChord(
                keyCode = key,
                ctrl = o.optBoolean(KEY_CHORD_CTRL, false),
                shift = o.optBoolean(KEY_CHORD_SHIFT, false),
                alt = o.optBoolean(KEY_CHORD_ALT, false),
            )
        }
    }
}

private const val KEY_CHORD_KEY = "key"
private const val KEY_CHORD_CTRL = "ctrl"
private const val KEY_CHORD_SHIFT = "shift"
private const val KEY_CHORD_ALT = "alt"

/**
 * 主键的显示名。常用的写死一张表，剩下的退回 `KeyEvent.keyCodeToString` 去掉
 * `KEYCODE_` 前缀。
 *
 * 那个兜底在 JVM 单测的 mockable android.jar 里返回 null（`isReturnDefaultValues`），
 * 所以也得有最终兜底 "Key<码>"，保证 [KeyChord.describe] 永远给出非空字符串。
 */
private fun keyName(keyCode: Int): String = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> "Num${keyCode - KeyEvent.KEYCODE_NUMPAD_0}"
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
    KeyEvent.KEYCODE_EQUALS -> "="
    KeyEvent.KEYCODE_MINUS -> "-"
    KeyEvent.KEYCODE_PLUS -> "+"
    KeyEvent.KEYCODE_NUMPAD_ADD -> "Num+"
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "Num-"
    KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "Num*"
    KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "Num/"
    KeyEvent.KEYCODE_NUMPAD_DOT -> "Num."
    KeyEvent.KEYCODE_NUMPAD_ENTER -> "NumEnter"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_ENTER -> "Enter"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    KeyEvent.KEYCODE_INSERT -> "Insert"
    KeyEvent.KEYCODE_MOVE_HOME -> "Home"
    KeyEvent.KEYCODE_MOVE_END -> "End"
    KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
    KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
    KeyEvent.KEYCODE_DPAD_UP -> "Up"
    KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
    KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
    KeyEvent.KEYCODE_COMMA -> ","
    KeyEvent.KEYCODE_PERIOD -> "."
    KeyEvent.KEYCODE_SLASH -> "/"
    KeyEvent.KEYCODE_BACKSLASH -> "\\"
    KeyEvent.KEYCODE_SEMICOLON -> ";"
    KeyEvent.KEYCODE_APOSTROPHE -> "'"
    KeyEvent.KEYCODE_GRAVE -> "`"
    KeyEvent.KEYCODE_LEFT_BRACKET -> "["
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
    else -> KeyEvent.keyCodeToString(keyCode)?.removePrefix("KEYCODE_")?.takeIf { it.isNotEmpty() }
        ?: "Key$keyCode"
}

// ─────────────────────────── 不许绑的键位 ───────────────────────────

/**
 * [isReservedChord] 拒绝一个键位的原因。[message] 是「按键组合」捕获对话框里直接
 * 展示给用户的一句话。
 */
enum class ReservedReason(val message: String) {
    /** 没有 Ctrl 也没有 Alt（含只有 Shift）：那是在打字。 */
    NO_COMMAND_MODIFIER("需要同时按住 Ctrl 或 Alt，否则就是普通输入"),

    /** 按下的只是 Shift/Ctrl/Alt/Meta/CapsLock 这类修饰键本身。 */
    MODIFIER_ONLY("请再按一个主键，不能只按修饰键"),

    /** 键码无效（KEYCODE_UNKNOWN 等）。 */
    UNSUPPORTED_KEY("无法识别这个按键"),

    /** Esc：远端程序（vim、readline）要用，而且是对话框里「取消」的通用手势。 */
    ESCAPE("Esc 留给远端程序，也用来取消输入"),

    /** 字母键必须是 Ctrl+Shift+字母；Ctrl+字母 / Alt+字母 属于远端程序。 */
    REMOTE_LETTER("字母键必须同时按住 Ctrl+Shift；Ctrl+字母、Alt+字母 属于远端程序（readline、vim、tmux）"),

    /** 非字母键只按了 Alt（可带 Shift）：终端会把它当 ESC 前缀发给远端。 */
    REMOTE_ALT("Alt 组合键会被终端当作 ESC 前缀发给远端程序"),

    /** Ctrl+光标键 / Home / End / 功能键 / Enter / Backspace / Ctrl+[ ] \：终端原样发给远端。 */
    REMOTE_KEY("这个组合会被终端原样发给远端程序（按词移动、Esc、功能键等）"),

    /** Android 系统自己要用（Alt+Tab、无障碍快捷键 Ctrl+Alt+Z）。 */
    SYSTEM_RESERVED("这个组合被 Android 系统占用"),

    /** 输入法切换（Ctrl+Space / Ctrl+Shift+Space）。 */
    IME_SWITCH("这个组合用于切换输入法"),

    /** Ctrl+Shift+-：xterm.js 会把它发成 ^_（readline 的撤销）。 */
    SENDS_CONTROL_BYTE("这个组合会被终端发成控制字符 ^_（撤销）"),
}

/** 不带 Shift 的 Ctrl 组合里，终端会原样发给远端的非字母键。见 [isReservedChord] 第 9 条。 */
private val REMOTE_OWNED_CTRL_KEYS: Set<Int> = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
    KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL,
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH,
) + (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).toSet()

private val MODIFIER_KEYS: Set<Int> = setOf(
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
    KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
    KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK,
    KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM,
)

/**
 * 这个键位**不能**用作快捷键吗？可以用返回 null，不能用返回原因。
 *
 * 给「按下一个组合键来绑定」的捕获对话框用来**拒绝**；[ShortcutBindings.rebind] 和
 * [ShortcutBindings.fromJsonObject] 也会兜一道（存档被手工改成 Ctrl+C 不能把终端弄坏）。
 *
 * ## 规则（原则：终端里归远端程序的键，app 不许抢）
 *
 * 一个键位要合法，必须通过下面所有检查，按顺序，第一个命中的就是返回的原因：
 *  1. 键码无效 / 只是修饰键 / 是 Esc。
 *  2. 没有 Ctrl 也没有 Alt（含只有 Shift）：普通输入。
 *  3. Alt+Tab（任意 Shift，不带 Ctrl）：系统切应用。
 *  4. Ctrl+Space、Ctrl+Shift+Space（不带 Alt）：输入法切换。
 *  5. Ctrl+Alt+Z（不带 Shift）：无障碍快捷键。
 *  6. Ctrl+Shift+-（不带 Alt）：xterm.js 发 ^_。
 *  7. **字母键必须是 Ctrl+Shift+字母**（Alt 可加可不加）：Ctrl+字母是控制字符、
 *     Alt+字母是 ESC 前缀、Ctrl+Alt+字母是 ESC+控制字符，全是远端程序的；只有
 *     Ctrl+Shift+字母 xterm.js 不发数据。
 *  8. 非字母键只有 Alt（没有 Ctrl）：ESC 前缀。
 *  9. 非字母键 Ctrl-only（不带 Shift、不带 Alt）且是光标键、Home/End/PgUp/PgDn、
 *     Insert/Delete/Backspace、Enter、F1..F12、`[` `]` `\`：终端发 CSI 序列或控制字符
 *     （Ctrl+左右 = 远端 shell 按词移动，Ctrl+[ = Esc）。加上 Shift 或 Alt 之后
 *     xterm.js 虽然也会发修饰过的序列，但这些组合在实践里没有程序依赖，所以放行
 *     （默认的 Ctrl+Shift+End 就是这一类）。
 *
 * 其余全部放行。也就是说 Ctrl+数字、Ctrl+Alt+数字、Ctrl+=、Ctrl+-、Ctrl+Tab、
 * Ctrl+Shift+Tab、Ctrl+小键盘键 都合法 —— 默认键位表用到它们，
 * 单测锁住「默认表里没有任何一项被判为保留」。
 *
 * Meta 在设计上就被排除（[KeyChord] 没有 Meta 字段，见文件头）。
 */
fun isReservedChord(chord: KeyChord): ReservedReason? {
    val k = chord.keyCode
    val ctrl = chord.ctrl
    val alt = chord.alt
    val shift = chord.shift

    if (k <= 0) return ReservedReason.UNSUPPORTED_KEY
    if (k in MODIFIER_KEYS) return ReservedReason.MODIFIER_ONLY
    if (k == KeyEvent.KEYCODE_ESCAPE) return ReservedReason.ESCAPE

    if (!ctrl && !alt) return ReservedReason.NO_COMMAND_MODIFIER

    if (alt && !ctrl && k == KeyEvent.KEYCODE_TAB) return ReservedReason.SYSTEM_RESERVED
    if (ctrl && !alt && k == KeyEvent.KEYCODE_SPACE) return ReservedReason.IME_SWITCH
    if (ctrl && alt && !shift && k == KeyEvent.KEYCODE_Z) return ReservedReason.SYSTEM_RESERVED
    if (ctrl && shift && !alt && k == KeyEvent.KEYCODE_MINUS) return ReservedReason.SENDS_CONTROL_BYTE

    if (k in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
        return if (ctrl && shift) null else ReservedReason.REMOTE_LETTER
    }

    if (!ctrl) return ReservedReason.REMOTE_ALT
    if (!shift && !alt && k in REMOTE_OWNED_CTRL_KEYS) return ReservedReason.REMOTE_KEY
    return null
}

// ─────────────────────────── 默认键位 ───────────────────────────

private fun ctrlShift(keyCode: Int) = KeyChord(keyCode, ctrl = true, shift = true, alt = false)

private fun ctrlOnly(keyCode: Int) = KeyChord(keyCode, ctrl = true, shift = false, alt = false)

/**
 * 默认键位表。**全集**：每个 [ShortcutAction] 都有一项，[ShortcutBindings.DEFAULT]
 * 和 [ShortcutBindings.fromJsonObject] 产出的 map 键集一致，两者用 `==` 比较才有意义
 * （设置页判断「是否已是默认」靠 GestureConfig 的 `==`，会连带比到这里）。
 *
 * 为什么是 Ctrl+Shift+字母，见文件头；表里没有任何一项落在 [isReservedChord] 的拒绝
 * 范围内（单测锁定）。
 *
 * 多键位的动作：
 *  - 字号增大：Ctrl+=、Ctrl+Shift+=（也就是按 Ctrl 和 '+'）、Ctrl+小键盘 +、
 *    Ctrl+KEYCODE_PLUS（'+' 有独立键的布局，比如德语）—— 不同键盘/布局上「放大」
 *    的按法不一样，都认。
 *  - 字号减小：Ctrl+-、Ctrl+小键盘 -。**不含 Ctrl+Shift+-**：xterm.js 把它发成 ^_，
 *    属于远端（见 [ReservedReason.SENDS_CONTROL_BYTE]）。
 *  - 字号还原：Ctrl+0、Ctrl+小键盘 0。
 *  - 切到第 n 个标签：Ctrl+Alt+n。不用 Alt+n（归远端，见 [ReservedReason.REMOTE_ALT]），
 *    也不用 Ctrl+n（和 Ctrl+Alt+n 靠精确匹配区分得开，但留给 Ctrl+数字 做别的将来更灵活）。
 *
 * 这是顶层 private val 而不是写在 [ShortcutBindings] 的 companion 里：和
 * GestureConfig.kt 的 DEFAULT_BINDINGS 同样的结构，并且这里只引用 [ShortcutAction] 和
 * [KeyChord]、不引用 [ShortcutBindings]，所以文件类和 ShortcutBindings 之间没有循环的
 * 类初始化依赖。
 */
private val DEFAULT_CHORDS: Map<ShortcutAction, List<KeyChord>> = buildMap {
    put(ShortcutAction.NEXT_TAB, listOf(ctrlOnly(KeyEvent.KEYCODE_TAB)))
    put(ShortcutAction.PREV_TAB, listOf(ctrlShift(KeyEvent.KEYCODE_TAB)))
    ShortcutAction.entries.forEach { action ->
        val index = action.tabIndex ?: return@forEach
        put(action, listOf(KeyChord(KeyEvent.KEYCODE_1 + index, ctrl = true, shift = false, alt = true)))
    }
    put(ShortcutAction.OPEN_SWITCHER, listOf(ctrlShift(KeyEvent.KEYCODE_A)))
    put(ShortcutAction.NEW_TAB, listOf(ctrlShift(KeyEvent.KEYCODE_T)))
    put(ShortcutAction.CLOSE_TAB, listOf(ctrlShift(KeyEvent.KEYCODE_W)))
    put(ShortcutAction.RENAME_TAB, listOf(ctrlShift(KeyEvent.KEYCODE_R)))
    put(ShortcutAction.COPY, listOf(ctrlShift(KeyEvent.KEYCODE_C)))
    put(ShortcutAction.PASTE, listOf(ctrlShift(KeyEvent.KEYCODE_V)))
    put(ShortcutAction.CLEAR_SCREEN, listOf(ctrlShift(KeyEvent.KEYCODE_L)))
    put(ShortcutAction.SCROLL_TO_BOTTOM, listOf(ctrlShift(KeyEvent.KEYCODE_MOVE_END)))
    put(
        ShortcutAction.FONT_SIZE_UP,
        listOf(
            ctrlOnly(KeyEvent.KEYCODE_EQUALS),
            ctrlShift(KeyEvent.KEYCODE_EQUALS),
            ctrlOnly(KeyEvent.KEYCODE_PLUS),
            ctrlOnly(KeyEvent.KEYCODE_NUMPAD_ADD),
        ),
    )
    put(
        ShortcutAction.FONT_SIZE_DOWN,
        listOf(ctrlOnly(KeyEvent.KEYCODE_MINUS), ctrlOnly(KeyEvent.KEYCODE_NUMPAD_SUBTRACT)),
    )
    put(
        ShortcutAction.FONT_SIZE_RESET,
        listOf(ctrlOnly(KeyEvent.KEYCODE_0), ctrlOnly(KeyEvent.KEYCODE_NUMPAD_0)),
    )
    put(ShortcutAction.OPEN_MENU, listOf(ctrlShift(KeyEvent.KEYCODE_M)))
    put(ShortcutAction.TOGGLE_KEY_BAR, listOf(ctrlShift(KeyEvent.KEYCODE_K)))
    put(ShortcutAction.TOGGLE_WIDE_WINDOW, listOf(ctrlShift(KeyEvent.KEYCODE_E)))
    put(ShortcutAction.OPEN_SFTP, listOf(ctrlShift(KeyEvent.KEYCODE_S)))
}

// ─────────────────────────── 绑定表 ───────────────────────────

/**
 * 一整份快捷键绑定：每个动作绑 0 个或多个 [KeyChord]。不可变，改绑生成新实例。
 *
 * [map] 允许残缺 —— 读取一律走 [chordsFor]，**键缺失**在读的那一刻兜回默认值（和
 * [GestureConfig.actionFor] 同一思路）。注意区分：键**存在但列表为空**是「用户把这个动作
 * 解绑了」，会被尊重，不会回退默认。两条正经的构造路径（[DEFAULT]、[fromJsonObject]）
 * 都产出键集完整的 map，所以 `==` 可以当语义相等用；手搓残缺 map 的实例不保证这一点。
 *
 * ## 冲突策略（选了「抢占」，不是返回错误）
 *
 * 同一个键位只能属于一个动作。[rebind] / [reset] 把键位绑给 A 时，如果 B 已经占着它，
 * **从 B 的列表里摘掉**，B 可能因此变成未绑定（空列表）。选抢占而不是返回冲突结果，
 * 是因为 UI 的流程本来就该是「捕获 → 用 [conflictsFor] 查冲突 → 弹『将从「B」移除，
 * 确定？』→ 确认后调 rebind」：确认之后的 rebind 没有第二种合理的结局，让它再返回一个
 * 要处理的结果类型只会让调用点多一层没用的分支。想拒绝冲突的 UI 自己先查 [conflictsFor]
 * 就行。（这个不变量是：任何经 rebind/reset/fromJsonObject 产出的实例，一个键位至多
 * 属于一个动作。）
 *
 * 命中规则见 [actionFor]：修饰键精确匹配，Meta 一律不命中。
 */
data class ShortcutBindings(
    val map: Map<ShortcutAction, List<KeyChord>> = DEFAULT_CHORDS,
) {
    /**
     * 键位 → 动作的反查表，构造时算好。[actionFor] 在每一次物理按键的路径上都要查，
     * 这里退化成一次 HashMap 读取（和 [GestureConfig.isOneFingerDoubleTapEnabled] 同理）。
     * 同一键位被多个动作占着（只可能来自手搓的 map）时，按枚举声明顺序靠前的赢。
     * 不在主构造器里，所以不参与 data class 的 equals/hashCode/copy。
     */
    private val index: Map<KeyChord, ShortcutAction> = HashMap<KeyChord, ShortcutAction>().also { m ->
        ShortcutAction.entries.forEach { action ->
            chordsFor(action).forEach { chord -> if (chord !in m) m[chord] = action }
        }
    }

    /** [action] 当前绑定的键位，按优先展示的顺序（第一个是主键位）。键缺失回退默认；空列表 = 已解绑。 */
    fun chordsFor(action: ShortcutAction): List<KeyChord> =
        map[action] ?: DEFAULT_CHORDS[action].orEmpty()

    /** [action] 的键位写成「Ctrl+Shift+T / Ctrl+Shift+=」；未绑定返回「未设置」。设置页、帮助页直接显示。 */
    fun describe(action: ShortcutAction): String =
        chordsFor(action).takeIf { it.isNotEmpty() }?.joinToString(" / ") { it.describe() } ?: "未设置"

    /**
     * 一次按键该触发哪个动作，没有返回 null。**分发入口只调这一个函数。**
     *
     *  - [meta] 为 true 一律返回 null：Meta 组合键是系统的（见文件头），而且 [KeyChord]
     *    里没有 Meta，若忽略它，Meta+Ctrl+Shift+T 就会误触发 NEW_TAB。
     *  - 修饰键**精确匹配**：Ctrl+Alt+1 只会命中 GOTO_TAB_1，Ctrl+1 不会，Ctrl+Shift+T
     *    命中 NEW_TAB，Ctrl+T（该归远端）返回 null。
     *  - 命中与否不查 [isReservedChord]：存档/rebind 入口已经把保留键位挡在外面。
     *
     * 调用方只负责传 `KeyEvent.keyCode` 和 `isCtrlPressed/isShiftPressed/isAltPressed/
     * isMetaPressed`，并且只在 ACTION_DOWN 上调（长按重复由调用方决定要不要吃）。
     */
    fun actionFor(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): ShortcutAction? {
        if (meta) return null
        return index[KeyChord(keyCode, ctrl, shift, alt)]
    }

    /**
     * 当前持有 [chord] 的动作，排除 [except]（通常是正在改绑的那个，它自己占着不算冲突）。
     * 捕获对话框确认前用来提示「将从某某移除」。
     */
    fun conflictsFor(chord: KeyChord, except: ShortcutAction? = null): List<ShortcutAction> =
        ShortcutAction.entries.filter { it != except && chord in chordsFor(it) }

    /**
     * 把 [action] 的键位**替换**成唯一的 [chord]（所以字号增大原有的四个别名会被它顶掉）；
     * 其它动作若占着这个键位则被抢占，见类注释的冲突策略。
     *
     * [chord] 被 [isReservedChord] 拒绝时**原样返回 this 而不是抛异常**，理由同
     * [GestureConfig.rebind]：捕获对话框本来就该先拒绝，走到这里是调用方的 bug，
     * 为它崩 app 得不偿失，logcat 里有记录。
     */
    fun rebind(action: ShortcutAction, chord: KeyChord): ShortcutBindings {
        val reason = isReservedChord(chord)
        if (reason != null) {
            Log.w(SHORTCUT_TAG, "忽略非法改绑：${chord.describe()} 不能绑到 $action（$reason）")
            return this
        }
        return withChords(action, listOf(chord))
    }

    /** 解绑：[action] 不再有任何键位（空列表，和「键缺失回退默认」不同）。 */
    fun unbind(action: ShortcutAction): ShortcutBindings = withChords(action, emptyList())

    /**
     * 把 [action] 恢复成出厂键位。其它动作占着这些键位时同样被抢占（用户先把别的动作改绑到
     * 了这个动作的出厂键位上、再点「恢复这一项」，要的就是这个）。
     */
    fun reset(action: ShortcutAction): ShortcutBindings =
        withChords(action, DEFAULT_CHORDS[action].orEmpty())

    /** 全部恢复出厂。就是 [DEFAULT]，留个函数名让调用点读起来对称。 */
    fun reset(): ShortcutBindings = DEFAULT

    private fun withChords(action: ShortcutAction, chords: List<KeyChord>): ShortcutBindings {
        val claimed = chords.toSet()
        return copy(
            map = ShortcutAction.entries.associateWith { a ->
                if (a == action) chords else chordsFor(a).filter { it !in claimed }
            },
        )
    }

    /**
     * 序列化成 `{"NEXT_TAB":[{"key":61,"ctrl":true,"shift":false,"alt":false}], ...}`。
     * 全量写出（含默认值，含空数组），和 GestureConfig 一样让存档本身是一份可读快照；
     * 空数组必须写出，否则「用户解绑了」会在下次加载时被误读成「没设置过」而回退默认。
     */
    fun toJsonObject(): JSONObject = JSONObject().apply {
        ShortcutAction.entries.forEach { action ->
            put(action.name, JSONArray().apply { chordsFor(action).forEach { put(it.toJsonObject()) } })
        }
    }

    companion object {
        /** 出厂键位，见 [DEFAULT_CHORDS]。 */
        val DEFAULT = ShortcutBindings()

        /**
         * 反序列化。**任何输入都不会抛异常**；[obj] 为 null（v2 及更早的存档没有这一块）
         * 就是 [DEFAULT]。逐动作容错，和 [GestureConfig.fromJson] 同一原则：
         *  - 动作名不认识的键（已删除的、将来版本新增的）→ 忽略；
         *  - 某动作的值不是数组 → 该动作回默认；
         *  - 数组为空 → 尊重，该动作已解绑；
         *  - 数组里的项不是对象 / 没有正整数 key → 丢掉这一项，其余保留；
         *  - 项是被 [isReservedChord] 拒绝的键位（手工改的 Ctrl+C）→ 丢掉这一项；
         *  - 数组非空但**一项都没有留下**（全是垃圾）→ 该动作回默认；
         *  - 同一键位在多个动作里出现（手工改的，或将来版本给新动作加了默认键位而用户
         *    已经把它给了别的动作）→ 只有一个动作能拿到：**存档里显式写了的动作优先于
         *    补默认值的动作**，同优先级里枚举声明靠前的先拿；拿不到的那一项被丢弃，
         *    动作因此可能变成未绑定。这样不变量「一个键位至多属于一个动作」在加载后
         *    恒成立，用户明确的选择也不会被后来的默认值顶掉。
         */
        fun fromJsonObject(obj: JSONObject?): ShortcutBindings {
            if (obj == null) return DEFAULT

            val taken = HashSet<KeyChord>()
            val loaded = HashMap<ShortcutAction, List<KeyChord>>()

            // 第一遍：存档里读得出来的动作。
            val needDefault = ArrayList<ShortcutAction>()
            for (action in ShortcutAction.entries) {
                val parsed = parseChords(obj.optJSONArray(action.name), action)
                if (parsed == null) {
                    needDefault += action
                } else {
                    val kept = parsed.filter { taken.add(it) }
                    loaded[action] = kept
                }
            }
            // 第二遍：回退默认的动作，默认键位被别人拿走了就不再给它。
            for (action in needDefault) {
                loaded[action] = DEFAULT_CHORDS[action].orEmpty().filter { taken.add(it) }
            }

            // 按枚举顺序重建，保证 map 的迭代顺序稳定（== 不看顺序，但设置页可能直接遍历）。
            return ShortcutBindings(ShortcutAction.entries.associateWith { loaded.getValue(it) })
        }

        /**
         * 读一个动作的键位数组。返回 null 表示「该动作回默认」（不是数组，或非空但全是垃圾）；
         * 返回空列表表示用户解绑了。
         */
        private fun parseChords(array: JSONArray?, action: ShortcutAction): List<KeyChord>? {
            if (array == null) return null
            if (array.length() == 0) return emptyList()
            val chords = ArrayList<KeyChord>()
            for (i in 0 until array.length()) {
                val chord = KeyChord.fromJsonObject(array.optJSONObject(i))
                if (chord == null) {
                    Log.w(SHORTCUT_TAG, "存档里 $action 的第 $i 项无法识别，丢弃")
                    continue
                }
                val reason = isReservedChord(chord)
                if (reason != null) {
                    Log.w(SHORTCUT_TAG, "存档里 $action 绑了保留键位 ${chord.describe()}（$reason），丢弃")
                    continue
                }
                if (chord !in chords) chords += chord
            }
            return chords.takeIf { it.isNotEmpty() }
        }
    }
}
