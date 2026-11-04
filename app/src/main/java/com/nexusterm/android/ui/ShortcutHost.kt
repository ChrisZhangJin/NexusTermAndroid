package com.nexusterm.android.ui

/**
 * 物理键盘快捷键的「动作入口」：Activity 层的按键拦截（匹配键位 → [ShortcutAction]）
 * 和 Compose 里真正持有终端状态的 [TerminalTabsPager] 之间的桥。
 *
 * 为什么要这一层：动作要读写的东西（分页器状态、切换器进度、菜单/对话框的开关、
 * 当前标签的 TerminalHandle）全部是 Compose 状态，活在 [TerminalTabsPager] 的组合里；
 * 而按键是在 `Activity.dispatchKeyEvent` 里收到的，那里够不着它们。所以让 Pager
 * 在组合期间把自己的实现**登记**进来，Activity 只认这个小对象。
 *
 * ## 登记 / 注销的约定
 *
 * [handler] 和 [switcherKeyHandler] 只在「终端页正在屏幕上」时非空：Pager 用
 * DisposableEffect 登记，离开组合（回首页、进 SFTP、进设置页）时注销。所以
 * **两个 handler 都是 null ⇔ 现在不该拦截任何快捷键**，调用方（T4）可以直接用
 * [perform] 的返回值决定吃不吃这次按键：返回 false = 没人处理，按原样交给系统 / 远端。
 * （导航转场的几百毫秒里旧页面可能还没从组合里撤掉，那一小段时间内 handler 仍然
 * 登记着，这是可接受的：此时按键只会作用在还能看见的终端上。）
 *
 * ## 返回值的口径
 *
 * - [perform]：**只要终端页在屏幕上，匹配到的快捷键就一律返回 true（消费掉）**，
 *   哪怕这次什么都没做（单标签时的 NEXT_TAB、没选中文字时的 COPY、标签号超出范围的
 *   GOTO_TAB_n）。理由：快捷键默认是 Ctrl+Shift+字母，xterm.js 对它们不产生数据，
 *   但落到系统 / WebView 手里可能触发别的东西；「按了就是 app 的」比「有时是 app 的、
 *   有时漏给远端」好预测。没有 handler 登记时返回 false。
 * - [onSwitcherKey]：只有会话切换器打开着、并且这个键是切换器认的键
 *   （左右方向键、Enter、Esc、Backspace/Delete）时才返回 true；其它情况（包括切换器
 *   没开）返回 false，让按键照常走。
 *
 * 纯 Kotlin，不依赖 Android / Compose，所以可以直接在单测里用。
 * 只应在主线程使用（handler 里会写 Compose 状态）。
 */
class ShortcutHost {
    /** 终端页登记的动作实现；null = 终端页不在屏幕上。 */
    var handler: ((ShortcutAction) -> Boolean)? = null

    /**
     * 终端页登记的「切换器内按键」实现，参数是 `android.view.KeyEvent.KEYCODE_*`；
     * null = 终端页不在屏幕上。
     */
    var switcherKeyHandler: ((keyCode: Int) -> Boolean)? = null

    /** 执行 [action]。没有 handler 登记返回 false，否则返回 handler 的结果（见类注释）。 */
    fun perform(action: ShortcutAction): Boolean = handler?.invoke(action) ?: false

    /** 切换器打开时处理一次按键。没有 handler 登记、或切换器没开 / 不认这个键，返回 false。 */
    fun onSwitcherKey(keyCode: Int): Boolean = switcherKeyHandler?.invoke(keyCode) ?: false
}
