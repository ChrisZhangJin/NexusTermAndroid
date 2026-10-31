package com.nexusterm.android.ui

import android.content.Context
import android.util.Log
import org.json.JSONObject

private const val TAG = "NexusTermGesture"

// 终端手势的「哪个手势做哪件事」—— 数据模型与持久化。识别器本身（见
// TerminalTabsPager.kt 的 `terminalGestures`）不在这里，这里只回答它两个
// 问题：某个槽位绑了什么动作、某个判定阈值是多少毫秒。
//
// 之所以要可配置，不是为了「可定制」本身，而是因为硬编码在某些 ROM 上
// 根本跑不起来：荣耀 MagicOS 把「双指长按」整个绑给了系统的 YOYO 问屏幕，
// 事件在到达 app 之前就被系统层截走，app 侧无论怎么写识别器都永远收不到
// 这个手势。而双指长按曾经是唯一打开终端菜单的入口 —— 菜单里有粘贴、
// SFTP、断开。也就是说在那台设备上，终端进去之后有一半功能是不可达的。
// 能改绑是唯一的出路，这不是锦上添花。（现在默认的单指双击也能打开菜单，
// 算是一个现成的备用入口，但改绑的能力仍然是根本。）

// ─────────────────────────── 槽位与动作 ───────────────────────────
//
// ## 类型约束的设计（识别器按这个写）
//
// 槽位按「能提供什么输入」分四种 [SlotKind]，动作按「需要什么输入」声明自己
// 接受哪几种（[GestureAction.isAllowedOn]）：
//
//  - DISCRETE   长按、双击：只有「发生了」。
//  - CONTINUOUS 双指左右拖动：只有过程量（pan.x），方向是双向的，没有「一次性」
//               的合理语义，所以只收 OPEN_SWITCHER（跟手）和 NONE。
//  - SWIPE      上滑 / 下滑（两指、三指）：**两种动作都行**。
//  - PINCH      缩小 / 放大（两指、三指）：**两种动作都行**，并且是唯一能给出
//               「幅度」的槽位，所以字号增减只能放这里。
//
// SWIPE / PINCH 槽位上的动作再分两类（[GestureAction.tracksGesture]）：
//
//  - 跟手动作（tracksGesture = true）：OPEN_SWITCHER（卡片随手指拉出）、
//    FONT_SIZE_UP / FONT_SIZE_DOWN（随捏合幅度连续步进）。识别器要在手势进行
//    中持续喂位移量，松手收尾。
//  - 一次性动作（其余）：清屏、滑至底部、开关虚拟大窗口、菜单、粘贴……识别器
//    在「方向判定出来的那一刻」触发一次，之后这次手势剩余部分一律消费掉、
//    不再做别的事（不要等松手才触发，也不要重复触发）。
//
// 位移量一律取「沿该槽位方向」的量（≥0 才有意义）：上滑 = -pan.y，下滑 = pan.y，
// 放大 = span - startSpan，缩小 = startSpan - span。反方向的位移被 0 下限吃掉。
// 同一次手势里槽位在判定时就定死了（先过 slop 的方向），不会在上滑/下滑、
// 缩小/放大之间来回切换。
//
// 合法性表（○ 合法）：
//                          DISCRETE CONTINUOUS SWIPE PINCH
//   NONE                      ○        ○         ○     ○
//   OPEN_SWITCHER             ○        ○         ○     ○   （离散=直接弹出；其余=跟手）
//   FONT_SIZE_UP / DOWN                                  ○   （要幅度，只能捏合）
//   START_SELECTION           ○                              （选区要靠后续单指拖动接续）
//   其余一次性动作            ○                  ○     ○

/**
 * 槽位能提供什么样的输入 —— 这是本文件里类型约束的根。见上面的总述和
 * [GestureAction.isAllowedOn]。
 */
enum class SlotKind {
    /** 一次性触发：长按到时、双击命中。没有过程量。 */
    DISCRETE,

    /** 手势进行中持续产出位移、且方向是双向的：双指左右拖动。 */
    CONTINUOUS,

    /** 单方向的滑动（上滑 / 下滑）：既能触发一次性动作，也能跟手。 */
    SWIPE,

    /** 单方向的捏合（缩小 / 放大）：既能触发一次性动作，也能跟手，还能给出幅度。 */
    PINCH,
}

/** 设置页分段用：按手指数分组。 */
enum class SlotGroup(val label: String, val fingers: Int) {
    ONE_FINGER("单指", 1),
    TWO_FINGER("双指", 2),
    THREE_FINGER("三指", 3),
}

/**
 * 可改绑的手势槽位。
 *
 * **方向语义（识别器以此为准）**，坐标系是 Android 屏幕坐标，y 向下为正：
 *  - 「上滑」：手指向屏幕**上方**移动，pan.y < 0；「下滑」：pan.y > 0。
 *    判定为滑动还是水平拖动，沿用现有的 `abs(pan.y) >= abs(pan.x)`。
 *  - 「放大」：手指**分开**，span 增大（zoom > 1）；「缩小」：手指**靠拢**，
 *    span 变小（zoom < 1）。span 是各指到质心的平均距离。
 *
 * **单指轻点和单指滑动故意不在这里，它们不可配置。** 终端底下是一个跑着
 * xterm.js 的 WebView：单指轻点要落到页面上做光标定位、唤起软键盘，以及
 * vim/tmux 开了鼠标上报时把点击位置报给远端；单指滑动是 scrollback 唯一
 * 的滚动方式。识别器对这两者的处理是「不消费、原样放行」，一旦把它们做成
 * 槽位就意味着要先消费再判断，WebView 会收到 ACTION_CANCEL，滚动和鼠标
 * 上报当场失效。把它们排除在 enum 之外，设置页就不可能把用户引到这个坑里。
 *
 * [label] 是设置页直接显示的名字，放在这里而不是设置页，是为了让「新增一个
 * 槽位」只需要改这一个文件。enum 的声明顺序就是设置页里的显示顺序，同组的
 * 槽位要挨在一起。
 */
enum class GestureSlot(val group: SlotGroup, val kind: SlotKind, val label: String) {
    /** 单指长按，持续 [GestureThresholds.oneFingerHoldMs]。 */
    ONE_FINGER_HOLD(SlotGroup.ONE_FINGER, SlotKind.DISCRETE, "单指长按"),

    /**
     * 单指双击。**识别器要先查 [GestureConfig.isOneFingerDoubleTapEnabled] 再决定要不要起这套逻辑。**
     *
     * 默认启用，绑「右键菜单」，窗口 200ms（用户有意推翻了此前「默认关闭」的
     * 决定）。但代价没有变，只是现在默认就在付：单指轻点原本是「不消费、
     * 立即放行」给 WebView 的；要识别单指双击，就必须在第一次抬手之后压住
     * 这个 tap 不放行，等满 [GestureThresholds.oneFingerDoubleTapWindowMs] 确认没有第二
     * 下，才能把它补发给 WebView。于是终端里**每一次**单指点击 —— 光标定位、
     * 唤起软键盘、vim/tmux 的鼠标上报 —— 都要迟钝一个窗口的时间（默认 200ms）。
     * 这是全局的、对所有点击都生效的手感损失，不是只在用户真的双击时才付的。
     * 觉得迟钝的人把它改成「无」就整段关掉，一行额外代码都不跑。
     *
     * 双指双击没有这个不对称：现有状态机里，两根手指快速按下再抬起（没达到
     * 长按时限、位移没过 slop）走的是 UNDECIDED → down.size < 2 → IGNORED，
     * 本来就不触发任何动作、也本来就被消费掉了。多等一个窗口等于不耽误任何
     * 既有行为，白拿一个槽位。
     */
    ONE_FINGER_DOUBLE_TAP(SlotGroup.ONE_FINGER, SlotKind.DISCRETE, "单指双击"),

    /**
     * 双指长按，持续 [GestureThresholds.twoFingerHoldMs]。
     *
     * 就是被荣耀 MagicOS 的 YOYO 问屏幕抢走的那个。默认仍然绑菜单（见
     * [GestureConfig.DEFAULT]），受影响的用户把菜单改绑到别的槽位即可
     * （默认的单指双击已经是一个现成的备用入口）。
     */
    TWO_FINGER_HOLD(SlotGroup.TWO_FINGER, SlotKind.DISCRETE, "双指长按"),

    /** 双指双击，两次抬手间隔不超过 [GestureThresholds.twoFingerDoubleTapWindowMs]。 */
    TWO_FINGER_DOUBLE_TAP(SlotGroup.TWO_FINGER, SlotKind.DISCRETE, "双指双击"),

    /** 双指上滑：两指一起向屏幕**上方**移动（pan.y < 0），位移量取 -pan.y。 */
    TWO_FINGER_SWIPE_UP(SlotGroup.TWO_FINGER, SlotKind.SWIPE, "双指上滑"),

    /** 双指下滑：两指一起向屏幕**下方**移动（pan.y > 0），位移量取 pan.y。 */
    TWO_FINGER_SWIPE_DOWN(SlotGroup.TWO_FINGER, SlotKind.SWIPE, "双指下滑"),

    /** 双指水平拖拽。现有识别器判定为 IGNORED（"Horizontal two-finger drags mean nothing"），是个空槽。 */
    TWO_FINGER_DRAG_HORIZONTAL(SlotGroup.TWO_FINGER, SlotKind.CONTINUOUS, "双指左右拖动"),

    /** 双指缩小：两指**靠拢**（span 变小），幅度取 startSpan - span。 */
    TWO_FINGER_PINCH_IN(SlotGroup.TWO_FINGER, SlotKind.PINCH, "双指缩小"),

    /** 双指放大：两指**分开**（span 增大），幅度取 span - startSpan。 */
    TWO_FINGER_PINCH_OUT(SlotGroup.TWO_FINGER, SlotKind.PINCH, "双指放大"),

    /**
     * 三指长按，持续 [GestureThresholds.threeFingerHoldMs]。
     *
     * 注意三指在 MIUI 上是系统截屏手势（这也是历史上三指滑动选择被废掉的原因），
     * 三指系列的槽位在那类 ROM 上可能整个收不到事件：能用的人可以用，用不了的
     * 人不受影响，也可以改绑。
     */
    THREE_FINGER_HOLD(SlotGroup.THREE_FINGER, SlotKind.DISCRETE, "三指长按"),

    /** 三指上滑：三指一起向屏幕**上方**移动（pan.y < 0）。 */
    THREE_FINGER_SWIPE_UP(SlotGroup.THREE_FINGER, SlotKind.SWIPE, "三指上滑"),

    /** 三指下滑：三指一起向屏幕**下方**移动（pan.y > 0）。 */
    THREE_FINGER_SWIPE_DOWN(SlotGroup.THREE_FINGER, SlotKind.SWIPE, "三指下滑"),

    /** 三指缩小：三指**靠拢**（span 变小）。 */
    THREE_FINGER_PINCH_IN(SlotGroup.THREE_FINGER, SlotKind.PINCH, "三指缩小"),

    /** 三指放大：三指**分开**（span 增大）。 */
    THREE_FINGER_PINCH_OUT(SlotGroup.THREE_FINGER, SlotKind.PINCH, "三指放大"),
}

/**
 * 槽位能触发的动作。
 *
 * 构造参数里的 [SlotKind] 列表不是防呆，是语义约束：见 [isAllowedOn] 和上面的
 * 总述。**一次性动作**（识别到就触发一次）和**跟手动作**（[tracksGesture]）的
 * 区别决定识别器要不要在手势进行中持续喂位移量。
 *
 * 写成 vararg 而不是引用几个 `DISCRETE_ONLY` 之类的共享常量，是躲一个类
 * 初始化顺序的坑：顶层 val 属于文件类，而 enum 的 <clinit> 可能先于它跑
 * （DEFAULT_BINDINGS 初始化时就会引用本 enum，反过来把文件类的初始化卡在
 * 半途），那时读到的会是 null。vararg 没有这个跨类依赖。代价是「一次性动作」
 * 那一长串重复了几遍，换来初始化顺序上不用动脑子。
 */
enum class GestureAction(val label: String, private vararg val allowedKinds: SlotKind) {
    /** 空：该槽位不做任何事，识别器应当完全跳过它。 */
    NONE("无", SlotKind.DISCRETE, SlotKind.CONTINUOUS, SlotKind.SWIPE, SlotKind.PINCH),

    /**
     * 右键菜单 = 现有的终端菜单（粘贴、清屏、滑至底部、字体还原、虚拟大窗口、
     * SFTP、断开）。label 写成「右键菜单（终端菜单）」是因为用户把它绑到单指
     * 双击上时，心智模型是「双击 = 右键」，而不是「打开某个叫终端菜单的东西」。
     * 菜单在 [at] 手势落点弹出。
     */
    OPEN_MENU("右键菜单（终端菜单）", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH),

    /**
     * 会话切换器。**四类槽位都允许**：绑在方向性槽位（上滑/下滑/缩小/放大）和
     * 左右拖动上是跟手把卡片拉出来（连续进度，[tracksGesture]），绑在长按/
     * 双击上是直接弹到底（离散）。两种都是说得通的交互，不该因为「它现在是个
     * 拖拽」就把离散槽位挡掉 —— 双指手势在某些 ROM 上同样可能被抢，那时用户
     * 需要能把它挪到一个离散槽位上。
     */
    OPEN_SWITCHER("会话切换器", SlotKind.DISCRETE, SlotKind.CONTINUOUS, SlotKind.SWIPE, SlotKind.PINCH),

    /**
     * 开始选择文本。**只能绑离散槽位**：选区开始之后要靠同一次手势里剩下的
     * 单指拖动来延伸（见识别器的 SELECTING），长按、双击都是单指落点后接着
     * 拖，而多指滑动/捏合结束时手指已经散了，没有「接着拖」可言。
     */
    START_SELECTION("开始选择文本", SlotKind.DISCRETE),

    /**
     * 字号增大。**只能绑捏合槽位**（缩小/放大四个都行）：字号是随捏合幅度
     * 一档一档步进的（见识别器的 PinchStepPerFontSize），需要持续的 span 变化量。
     * 离散槽位给不出幅度，滑动槽位给的是 pan 而不是 span，没有可用的输入。
     *
     * 方向由**动作**决定而不是由手势方向决定：FONT_SIZE_UP 永远是字号变大，
     * 幅度取「沿所在槽位方向」的位移量。放在「放大」槽位上是常规手感（张开
     * = 变大）；放在「缩小」槽位上就是「捏合 = 变大」，合法，用户自己选的。
     * （想一键恢复默认字号的话那是菜单里的「字体还原」，不是这个动作。）
     */
    FONT_SIZE_UP("字号增大", SlotKind.PINCH),

    /** 字号减小。约束和语义同 [FONT_SIZE_UP]。 */
    FONT_SIZE_DOWN("字号减小", SlotKind.PINCH),

    PASTE("粘贴", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH),

    /** 清屏。语义同菜单里的「清屏」（`handle.clearScreen()`）。 */
    CLEAR_SCREEN("清屏", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH),

    SCROLL_TO_BOTTOM("滑至底部", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH),

    /**
     * 开启虚拟大窗口（`handle.setVirtualWindow(true)`，[VIRTUAL_WINDOW_COLS] 列）。
     * 和菜单里那个**切换**项不同，这是单向的、幂等的：已经开着再触发什么都
     * 不发生。拆成开/关两个动作而不是一个 toggle，是因为方向性槽位天然成对
     * （放大/缩小），用户要的是「放大 = 开、缩小 = 关」，toggle 在这里会让
     * 连做两次同方向手势得到相反的结果。
     */
    ENABLE_WIDE_WINDOW("开启虚拟大窗口", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH),

    /** 关闭虚拟大窗口（`handle.setVirtualWindow(false)`）。单向、幂等，理由同 [ENABLE_WIDE_WINDOW]。 */
    DISABLE_WIDE_WINDOW("关闭虚拟大窗口", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH),

    TOGGLE_KEY_BAR("显示/隐藏按键栏", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH),

    OPEN_SFTP("SFTP 文件管理", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH),

    DISCONNECT("断开连接", SlotKind.DISCRETE, SlotKind.SWIPE, SlotKind.PINCH);

    /**
     * 这个动作能不能绑到 [slot] 上。
     *
     * 三处都要用它，缺一不可：设置页用它过滤下拉选项（用户压根看不到非法
     * 组合）、识别器用它做防御性校验、[GestureConfig.fromJson] 用它过滤存档
     * （旧版本写入的或被手工篡改的非法组合，加载时一律退回该槽位的默认值，
     * 不能原样吃进来）。
     */
    fun isAllowedOn(slot: GestureSlot): Boolean = slot.kind in allowedKinds

    /**
     * 这个动作在非离散槽位上是不是「跟手」的：要在手势进行中持续喂位移量。
     * 为 false 的动作在 SWIPE / PINCH 槽位上是一次性的 —— 方向一判定出来就
     * 触发一次。
     *
     * 在离散槽位（长按、双击）上本来就没有过程量，OPEN_SWITCHER 绑在那里是
     * 直接弹出，识别器应当按一次性处理，不看这个属性。
     *
     * 用 getter 而不是构造参数：同样是躲 enum 初始化顺序的坑，也让「哪些动作
     * 跟手」在一处列全。
     */
    val tracksGesture: Boolean
        get() = this == OPEN_SWITCHER || this == FONT_SIZE_UP || this == FONT_SIZE_DOWN

    companion object {
        /** 设置页列选项用：[slot] 上合法的动作，按 enum 声明顺序（NONE 在最前）。 */
        fun allowedActionsFor(slot: GestureSlot): List<GestureAction> =
            GestureAction.entries.filter { it.isAllowedOn(slot) }
    }
}

// ─────────────────────────── 阈值 ───────────────────────────

/** 对应现有的 `SELECT_HOLD_MS`。 */
const val DEFAULT_ONE_FINGER_HOLD_MS = 800L

/** 对应现有的 `MENU_HOLD_MS`。 */
const val DEFAULT_TWO_FINGER_HOLD_MS = 800L

const val DEFAULT_THREE_FINGER_HOLD_MS = 800L

// 单指/双指双击窗口各有各的默认值。拆成两个常量而不是共用一个：两者是独立的阈值。
// 单指的是 200ms：它同时是启用后每次单指轻点的延迟预算（见 GestureSlot.ONE_FINGER_DOUBLE_TAP），
// 而单指双击现在默认启用，所以默认值取舒适连点里偏紧的一端，把常驻的手感损失压低；
// 想要宽松的人在设置页往大拖。双指没有这个代价，仍是改造前的 300ms。
const val DEFAULT_ONE_FINGER_DOUBLE_TAP_WINDOW_MS = 200L

const val DEFAULT_TWO_FINGER_DOUBLE_TAP_WINDOW_MS = 300L

/**
 * 长按时限的可调区间，设置页的滑块直接用它当 valueRange。
 *
 * 下界 200ms：再短就和「按下去马上滑动」区分不开，滚动会频繁误判成长按。
 * 上界 3000ms：再长用户会以为手势坏了而不是没按够。
 */
val GESTURE_HOLD_MS_RANGE: LongRange = 200L..3000L

/**
 * 双击窗口的可调区间，单指、双指两个窗口都用它（只是各自独立取值）。
 *
 * 下界 150ms 是人类能稳定连点的下限；上界 600ms 之所以压得这么死，是因为
 * 单指双击窗口同时就是开启单指双击后每次点击的延迟预算（见
 * [GestureSlot.ONE_FINGER_DOUBLE_TAP]），再大终端就没法用了。双指窗口没有
 * 这个代价，但没必要为它单开一个更宽的区间，两个滑块保持同样的手感范围。
 */
val GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE: LongRange = 150L..600L

/**
 * 五个可调时间阈值。单位毫秒，和识别器里的 `SystemClock.uptimeMillis()` 同基。
 *
 * 构造器不做钳位（data class 的 copy 绕不过去，钳在这里只是假的保证），
 * 钳位发生在两个真正的入口：[coerced] 和 [GestureConfig.fromJson]。
 */
data class GestureThresholds(
    val oneFingerHoldMs: Long = DEFAULT_ONE_FINGER_HOLD_MS,
    val twoFingerHoldMs: Long = DEFAULT_TWO_FINGER_HOLD_MS,
    val threeFingerHoldMs: Long = DEFAULT_THREE_FINGER_HOLD_MS,
    // 单指、双指双击各有独立的窗口：之前共用一个值，设置页里拖一个另一个跟着动，
    // 用户认为这是 bug。单指窗口还兼任开启单指双击后每次点击的延迟，和双指的
    // 取舍并不相同，本来就不该绑在一起。
    val oneFingerDoubleTapWindowMs: Long = DEFAULT_ONE_FINGER_DOUBLE_TAP_WINDOW_MS,
    val twoFingerDoubleTapWindowMs: Long = DEFAULT_TWO_FINGER_DOUBLE_TAP_WINDOW_MS,
) {
    /** 全部钳进合法区间。设置页保存前调一次，省得把滑块的边界条件重复一遍。 */
    fun coerced(): GestureThresholds = GestureThresholds(
        oneFingerHoldMs = oneFingerHoldMs.coerceIn(GESTURE_HOLD_MS_RANGE),
        twoFingerHoldMs = twoFingerHoldMs.coerceIn(GESTURE_HOLD_MS_RANGE),
        threeFingerHoldMs = threeFingerHoldMs.coerceIn(GESTURE_HOLD_MS_RANGE),
        oneFingerDoubleTapWindowMs = oneFingerDoubleTapWindowMs.coerceIn(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE),
        twoFingerDoubleTapWindowMs = twoFingerDoubleTapWindowMs.coerceIn(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE),
    )
}

// ─────────────────────────── 配置本体 ───────────────────────────

/**
 * 默认绑定。**这张表不再是「改造前的行为」**：用户有意推翻了此前「默认等于
 * 现状」的决定，要的是一套开箱即用的新默认（单指双击=右键菜单、双指下滑=
 * 切换器、三指放大/缩小=开/关虚拟大窗口……）。单测里有一条逐槽位的回归锁
 * 住这张表，改默认值必须同步改测试 —— 这是故意的摩擦。
 *
 * 已有存档的用户：存档是全量快照，里面已有的槽位**原样保留**，只有存档里
 * 没有的新槽位才取这张表（见 [GestureConfig.fromJson]）。
 *
 * 默认里 OPEN_MENU 绑了两处（单指双击、双指长按）。这是有意的：双指长按在
 * 荣耀上会被系统抢走，单指双击就是现成的备用入口，两处互不影响。
 *
 * 空槽也显式写出 NONE 而不是靠「查不到就是 NONE」兜底：这样这张表是全集，
 * [GestureConfig.DEFAULT] 和 [GestureConfig.fromJson] 产出的 map 键集一致，
 * 两者用 `==` 比较才有意义（设置页判断「是否已是默认」要靠它）。
 */
private val DEFAULT_BINDINGS: Map<GestureSlot, GestureAction> = mapOf(
    // 单指
    GestureSlot.ONE_FINGER_HOLD to GestureAction.START_SELECTION,
    GestureSlot.ONE_FINGER_DOUBLE_TAP to GestureAction.OPEN_MENU,
    // 双指
    GestureSlot.TWO_FINGER_HOLD to GestureAction.OPEN_MENU,
    GestureSlot.TWO_FINGER_DOUBLE_TAP to GestureAction.NONE,
    GestureSlot.TWO_FINGER_SWIPE_UP to GestureAction.NONE,
    GestureSlot.TWO_FINGER_SWIPE_DOWN to GestureAction.OPEN_SWITCHER,
    GestureSlot.TWO_FINGER_DRAG_HORIZONTAL to GestureAction.NONE,
    GestureSlot.TWO_FINGER_PINCH_IN to GestureAction.FONT_SIZE_DOWN,
    GestureSlot.TWO_FINGER_PINCH_OUT to GestureAction.FONT_SIZE_UP,
    // 三指
    GestureSlot.THREE_FINGER_HOLD to GestureAction.NONE,
    GestureSlot.THREE_FINGER_SWIPE_UP to GestureAction.CLEAR_SCREEN,
    GestureSlot.THREE_FINGER_SWIPE_DOWN to GestureAction.SCROLL_TO_BOTTOM,
    GestureSlot.THREE_FINGER_PINCH_IN to GestureAction.DISABLE_WIDE_WINDOW,
    GestureSlot.THREE_FINGER_PINCH_OUT to GestureAction.ENABLE_WIDE_WINDOW,
)

/** [slot] 的出厂动作。存档里该槽位缺失或非法时回退到它。 */
fun defaultActionFor(slot: GestureSlot): GestureAction =
    DEFAULT_BINDINGS[slot] ?: GestureAction.NONE

/**
 * 一整份手势配置。不可变，改绑用 [rebind] 生成新实例。
 *
 * [bindings] 允许是残缺的 —— 读取一律走 [actionFor]，缺失和非法都在读的那一刻
 * 兜回默认值。这样无论这个实例是从存档解析来的、是 data class `copy()` 出来的、
 * 还是别处手搓的，识别器拿到手的永远是一个合法的动作，不需要在调用点重复校验。
 *
 * 但 data class 的 `==` 比的是 map 本身而不是 [actionFor] 的结果，所以两条正经
 * 的构造路径（[DEFAULT] 和 [fromJson]）都产出键集完整的 map，`==` 才能当「语义
 * 相等」用。手搓残缺 map 的实例不保证这一点 —— 想比就先 `GestureConfig(
 * GestureSlot.entries.associateWith(it::actionFor), it.thresholds)` 补全。
 */
data class GestureConfig(
    val bindings: Map<GestureSlot, GestureAction> = DEFAULT_BINDINGS,
    val thresholds: GestureThresholds = GestureThresholds(),
) {
    /** [slot] 当前绑定的动作。缺失或非法组合都回退到 [defaultActionFor]。 */
    fun actionFor(slot: GestureSlot): GestureAction =
        bindings[slot]?.takeIf { it.isAllowedOn(slot) } ?: defaultActionFor(slot)

    /**
     * 单指双击这个槽位到底有没有启用（默认是：绑着右键菜单）。
     *
     * 写成构造时算好的字段而不是 getter，是因为识别器要在**每一次**单指抬手
     * 的路径上查它来决定要不要压住这个 tap 等第二下 —— 那是终端最热的输入
     * 路径，这里必须退化成一次字段读取，不能是一次 map 查找。
     * 代价为什么值得在意，见 [GestureSlot.ONE_FINGER_DOUBLE_TAP]。
     */
    val isOneFingerDoubleTapEnabled: Boolean =
        actionFor(GestureSlot.ONE_FINGER_DOUBLE_TAP) != GestureAction.NONE

    /**
     * 把 [slot] 改绑到 [action]。
     *
     * [action] 在该槽位上非法时**原样返回 this 而不是抛异常** —— 设置页本来就
     * 用 [GestureAction.allowedActionsFor] 过滤过选项，走到这里说明是调用方的
     * bug，而手势是进终端之后唯一的交互方式，为了一个 UI 层的 bug 把 app 崩掉
     * 得不偿失。真有问题 logcat 里有记录。
     */
    fun rebind(slot: GestureSlot, action: GestureAction): GestureConfig {
        if (!action.isAllowedOn(slot)) {
            Log.w(TAG, "忽略非法改绑：$action 不能绑到 $slot（${slot.kind}）")
            return this
        }
        return copy(bindings = bindings + (slot to action))
    }

    /** 绑了 [action] 的所有槽位。设置页用来提示「这个动作已经绑在别处了」。 */
    fun slotsFor(action: GestureAction): List<GestureSlot> =
        GestureSlot.entries.filter { actionFor(it) == action }

    /** 序列化。全量写出（含默认值），这样存档本身就是一份可读的快照。 */
    fun toJson(): String = JSONObject().apply {
        put(KEY_VERSION, JSON_VERSION)
        put(KEY_BINDINGS, JSONObject().apply {
            GestureSlot.entries.forEach { put(it.name, actionFor(it).name) }
        })
        put(KEY_THRESHOLDS, JSONObject().apply {
            put(KEY_ONE_FINGER_HOLD_MS, thresholds.oneFingerHoldMs)
            put(KEY_TWO_FINGER_HOLD_MS, thresholds.twoFingerHoldMs)
            put(KEY_THREE_FINGER_HOLD_MS, thresholds.threeFingerHoldMs)
            // 只写新 key，不再写旧的 doubleTapWindowMs：两个值可能不同，旧 key
            // 表达不了；旧 key 只在 fromJson 里作为读取兜底（见 KEY_LEGACY_DOUBLE_TAP_WINDOW_MS）。
            put(KEY_ONE_FINGER_DOUBLE_TAP_WINDOW_MS, thresholds.oneFingerDoubleTapWindowMs)
            put(KEY_TWO_FINGER_DOUBLE_TAP_WINDOW_MS, thresholds.twoFingerDoubleTapWindowMs)
        })
    }.toString()

    companion object {
        /** 出厂配置，见 [DEFAULT_BINDINGS]。 */
        val DEFAULT = GestureConfig()

        /**
         * 反序列化。**任何输入都不会抛异常**，最坏退到 [DEFAULT]。
         *
         * 这条是硬要求：进了终端之后，手势就是唯一的交互方式，一条写坏的存档
         * 不能把 app 变成砖 —— 连「打开设置页把它改回来」都做不到。
         *
         * 容错是**逐字段**的，不是整体 try/catch 一刀切回全默认：用户改了七个
         * 设置、第八个字段坏了，前七个不该跟着一起没。只有连 JSON 都解析不出来
         * （整体损坏）才会全量回退，因为那时确实一个字段都读不出。
         *
         * 具体规则：
         *  - key 缺失 / 值类型不对 → 该字段用默认值，其余字段照读；
         *  - enum 名字不认识（旧版本写的、将来版本写的、手工改的）→ 该槽位回默认；
         *  - 动作在该槽位上非法（[GestureAction.isAllowedOn] 不过）→ 该槽位回默认；
         *  - 不认识的槽位名（已被删除的旧槽位、将来版本新增的）→ 直接忽略，不抛；
         *  - 旧槽位迁移（只在对应的新槽位在存档里**缺失**时才做，新 key 存在就
         *    以新 key 为准；旧值为 NONE 或迁过去不合法则不迁移，该槽位走新默认）：
         *      · `TWO_FINGER_DRAG_VERTICAL` 的动作 → [GestureSlot.TWO_FINGER_SWIPE_DOWN]
         *        （旧「上下拖动」实际只有下拖有效）；
         *      · `TWO_FINGER_PINCH`：`ADJUST_FONT_SIZE` → 放大槽 FONT_SIZE_UP、
         *        缩小槽 FONT_SIZE_DOWN；`OPEN_SWITCHER` → 只迁到缩小槽（旧实现里
         *        是捏紧才拉出切换器），放大槽走新默认；
         *    迁移之后仍缺失的新槽位一律走新默认值；
         *  - 旧存档迁移：只有旧的 `doubleTapWindowMs`、没有两个新双击 key 时，
         *    旧值同时作为单指和双指窗口的初值；新 key 存在则优先用新 key；
         *  - 数值越界 → 钳到区间边界，而不是丢弃成默认值（用户设过 5000ms 这个
         *    意图是「要很长」，钳成 3000 比退回 800 更接近他要的）。
         */
        fun fromJson(json: String?): GestureConfig {
            if (json.isNullOrBlank()) return DEFAULT

            // 捕 Exception 而不是只捕 JSONException：org.json 在不同 ROM 上
            // 的实现并不完全一致，对畸形输入抛过 StringIndexOutOfBounds 之类
            // 的 RuntimeException。「绝不抛」是这里的硬要求，不能赌实现细节。
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                Log.w(TAG, "手势配置存档无法解析为 JSON，整份回退默认：${e.message}")
                return DEFAULT
            }

            // 版本号只写不分支：逐字段容错 + 下面的旧槽位迁移已经能吃下 v1 和 v2。
            // v2 是「双指拖动/捏合拆成方向性槽位」那次改动，写出来是为了将来再
            // 出现无法用「字段缺失=默认」表达的迁移时，有个能分辨的依据。
            val bindingsJson = root.optJSONObject(KEY_BINDINGS)
            val bindings = GestureSlot.entries.associateWith { slot ->
                val raw = bindingsJson?.optString(slot.name)?.takeIf { it.isNotEmpty() }
                val parsed = raw?.let(::parseAction)
                when {
                    raw == null -> migratedLegacyBinding(slot, bindingsJson) ?: defaultActionFor(slot)
                    parsed == null -> {
                        Log.w(TAG, "存档里 $slot 绑了无法识别的动作 \"$raw\"，回退默认")
                        defaultActionFor(slot)
                    }
                    !parsed.isAllowedOn(slot) -> {
                        Log.w(TAG, "存档里 $slot 绑了非法动作 $parsed（${slot.kind}），回退默认")
                        defaultActionFor(slot)
                    }
                    else -> parsed
                }
            }

            val t = root.optJSONObject(KEY_THRESHOLDS)

            // 旧版本只有一个共用的 doubleTapWindowMs。升级用户存档里的就是它，
            // 如果直接忽略，调过双击间隔的人会被悄悄丢回默认值；所以把它当作
            // 两个新阈值各自缺失时的「默认值」。逐个 key 判断而不是整体判断：
            // 新 key 存在（哪怕只有一个）就用新 key，另一个缺失的才回落到旧值。
            // 旧值越界同样被钳。
            //
            // legacyWindow 是可空的、而不是给个默认数：单指/双指的出厂默认现在
            // 不同了（200 / 300），旧 key 压根不存在（或不是数字）时，两个窗口
            // 必须各回各的默认值，不能共用同一个兜底。
            val legacyWindow: Long? = if (t != null && t.has(KEY_LEGACY_DOUBLE_TAP_WINDOW_MS)) {
                t.optLong(KEY_LEGACY_DOUBLE_TAP_WINDOW_MS, Long.MIN_VALUE)
                    .takeIf { it != Long.MIN_VALUE }
                    ?.coerceIn(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE)
            } else {
                null
            }
            val oneFingerWindow = t.optMs(
                KEY_ONE_FINGER_DOUBLE_TAP_WINDOW_MS,
                legacyWindow ?: DEFAULT_ONE_FINGER_DOUBLE_TAP_WINDOW_MS,
                GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE,
            )
            val twoFingerWindow = t.optMs(
                KEY_TWO_FINGER_DOUBLE_TAP_WINDOW_MS,
                legacyWindow ?: DEFAULT_TWO_FINGER_DOUBLE_TAP_WINDOW_MS,
                GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE,
            )
            val thresholds = GestureThresholds(
                oneFingerHoldMs = t.optMs(KEY_ONE_FINGER_HOLD_MS, DEFAULT_ONE_FINGER_HOLD_MS, GESTURE_HOLD_MS_RANGE),
                twoFingerHoldMs = t.optMs(KEY_TWO_FINGER_HOLD_MS, DEFAULT_TWO_FINGER_HOLD_MS, GESTURE_HOLD_MS_RANGE),
                threeFingerHoldMs = t.optMs(KEY_THREE_FINGER_HOLD_MS, DEFAULT_THREE_FINGER_HOLD_MS, GESTURE_HOLD_MS_RANGE),
                oneFingerDoubleTapWindowMs = oneFingerWindow,
                twoFingerDoubleTapWindowMs = twoFingerWindow,
            )

            return GestureConfig(bindings, thresholds)
        }
    }
}

private const val JSON_VERSION = 2
private const val KEY_VERSION = "v"
private const val KEY_BINDINGS = "bindings"
private const val KEY_THRESHOLDS = "thresholds"
private const val KEY_ONE_FINGER_HOLD_MS = "oneFingerHoldMs"
private const val KEY_TWO_FINGER_HOLD_MS = "twoFingerHoldMs"
private const val KEY_THREE_FINGER_HOLD_MS = "threeFingerHoldMs"
private const val KEY_ONE_FINGER_DOUBLE_TAP_WINDOW_MS = "oneFingerDoubleTapWindowMs"
private const val KEY_TWO_FINGER_DOUBLE_TAP_WINDOW_MS = "twoFingerDoubleTapWindowMs"

// 仅用于读取：已被拆掉的旧槽位名。存档里可能还留着它们，只在迁移里读，不再写出。
private const val LEGACY_SLOT_DRAG_VERTICAL = "TWO_FINGER_DRAG_VERTICAL"
private const val LEGACY_SLOT_PINCH = "TWO_FINGER_PINCH"

// 旧动作名 ADJUST_FONT_SIZE 已不在 enum 里，parseAction 认不出它，迁移里按字符串比。
private const val LEGACY_ACTION_ADJUST_FONT_SIZE = "ADJUST_FONT_SIZE"

// 仅用于读取：旧版本把单指/双指双击共用的窗口存在这个 key 下。保留它是为了
// 已经装过旧版的用户升级后不丢设置，不要删，也不要再往里写。
private const val KEY_LEGACY_DOUBLE_TAP_WINDOW_MS = "doubleTapWindowMs"

/**
 * 旧存档里已被拆掉的槽位 → 新槽位的迁移。[slot] 是新槽位，[bindings] 是存档的
 * bindings 块；返回 null 表示「没有可迁移的旧值」，调用方走新默认。
 *
 * 旧值为 NONE 不迁移：旧版存档是全量快照，NONE 既可能是「用户关了它」也可能
 * 只是默认值的快照，分不出来；而新槽位的默认恰好是旧槽位默认的延续，不迁移
 * 比把 NONE 复制到好几个新槽位上更接近「升级后手势还在」。
 * 迁过去必须对新槽位合法（[GestureAction.isAllowedOn]），否则同样不迁移。
 */
private fun migratedLegacyBinding(slot: GestureSlot, bindings: JSONObject?): GestureAction? {
    if (bindings == null) return null
    fun legacy(key: String): String? = bindings.optString(key).takeIf { it.isNotEmpty() }

    val candidate: GestureAction? = when (slot) {
        GestureSlot.TWO_FINGER_SWIPE_DOWN ->
            legacy(LEGACY_SLOT_DRAG_VERTICAL)?.let(::parseAction)
        GestureSlot.TWO_FINGER_PINCH_OUT -> when (legacy(LEGACY_SLOT_PINCH)) {
            LEGACY_ACTION_ADJUST_FONT_SIZE -> GestureAction.FONT_SIZE_UP
            else -> null
        }
        GestureSlot.TWO_FINGER_PINCH_IN -> when (legacy(LEGACY_SLOT_PINCH)) {
            LEGACY_ACTION_ADJUST_FONT_SIZE -> GestureAction.FONT_SIZE_DOWN
            GestureAction.OPEN_SWITCHER.name -> GestureAction.OPEN_SWITCHER
            else -> null
        }
        else -> null
    }
    return candidate?.takeIf { it != GestureAction.NONE && it.isAllowedOn(slot) }
}

/**
 * 按名字查动作。刻意用遍历而不是 `enumValueOf`：后者遇到不认识的名字是抛
 * [IllegalArgumentException]，而「不认识的名字」在这里是预期内的正常输入
 * （降级安装、手工改存档），不该走异常路径。
 */
private fun parseAction(name: String): GestureAction? =
    GestureAction.entries.firstOrNull { it.name == name }

/**
 * 读一个毫秒阈值并钳进 [range]。
 *
 * receiver 可空，省得每个字段都写一遍 "thresholds 这个对象在不在"：整个
 * thresholds 块缺失和块里某个 key 缺失，结果应该是一样的（全用默认值）。
 * `optLong` 本身已经吃掉了「值是字符串/布尔/null」这些情况（返回 fallback）。
 */
private fun JSONObject?.optMs(key: String, fallback: Long, range: LongRange): Long =
    (this?.optLong(key, fallback) ?: fallback).coerceIn(range)

// ─────────────────────────── 持久化 ───────────────────────────

/**
 * 手势配置的存取。
 *
 * **SharedPreferences 而不是 nexus-core 的 settings 表**：手势绑定是纯前端
 * 行为，Rust 侧的 SSH 会话层对它一无所知也不需要知道；塞进去只会让一个
 * Android 专属的 UI 概念穿透到跨平台核心里。（用户已拍板。）
 *
 * **复用 TerminalScreen.kt 里 TerminalPrefs 的同一个 "terminal" 文件，另起一个
 * key**，而不是新开一个 prefs 文件。理由：SharedPreferences 的粒度本来就是
 * 「一个文件一次整体读进内存」，再开一个文件等于多一次磁盘加载和多一个要在
 * 卸载/清数据/备份规则里照顾到的对象，而这俩本来就是同一类东西 —— 跟着这台
 * 手机走、跟会话无关、只影响终端界面本身的偏好（字号也是手势调出来的）。
 * 代价只是两个 object 共享一个文件名常量字面量，而两边 key 不重名就互不打扰。
 */
object GesturePrefs {
    /** 和 TerminalScreen.kt 的 TerminalPrefs 同一个文件 —— 见上面类注释。 */
    private const val FILE = "terminal"
    private const val KEY_GESTURES = "gestures"

    /** 读出当前配置。任何存档问题都在 [GestureConfig.fromJson] 里被吃掉，这里不会抛。 */
    fun load(context: Context): GestureConfig = GestureConfig.fromJson(readRaw(context))

    fun save(context: Context, config: GestureConfig) {
        prefs(context).edit().putString(KEY_GESTURES, config.toJson()).apply()
    }

    /** 改绑单个槽位并落盘，返回落盘后的配置。非法组合会被 [GestureConfig.rebind] 忽略。 */
    fun rebind(context: Context, slot: GestureSlot, action: GestureAction): GestureConfig =
        load(context).rebind(slot, action).also { save(context, it) }

    /** 恢复出厂。删 key 而不是写一份默认值进去，这样「从没设置过」和「重置过」是同一个状态。 */
    fun reset(context: Context): GestureConfig {
        prefs(context).edit().remove(KEY_GESTURES).apply()
        return GestureConfig.DEFAULT
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * `getString` 在这个 key 下存着非字符串时会抛 ClassCastException —— 不是
     * 假想情况：共用一个 prefs 文件，哪天有人手滑用同名 key putInt 就会碰上。
     * 既然整个解析链都承诺不抛，读取这一步也不能是例外。
     */
    private fun readRaw(context: Context): String? =
        runCatching { prefs(context).getString(KEY_GESTURES, null) }
            .onFailure { Log.w(TAG, "手势配置 key 类型不对，按未设置处理：${it.message}") }
            .getOrNull()
}
