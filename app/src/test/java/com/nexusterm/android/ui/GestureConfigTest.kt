package com.nexusterm.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GestureConfig.kt 的 JVM 单测。
 *
 * ## 这些用例靠什么跑起来
 *
 * AGP 给 JVM 单测用的是 "mockable" android.jar：里面每个方法体都是
 * `throw RuntimeException("Method ... not mocked")`。而被测代码用到两个本属于
 * android.jar 的东西 —— `org.json.JSONObject`（序列化）和 `android.util.Log`
 * （容错时记一条 warn）—— 原样跑会当场抛。app/build.gradle.kts 里为此配了两样：
 *
 *     testImplementation("org.json:json:20231013")            // 真实现，盖掉桩
 *     testOptions { unitTests.isReturnDefaultValues = true }  // 只为了 Log 不抛
 *
 * 分工是：真实的 org.json 把桩盖掉，所以那个开关只剩下让 `Log.w` 返回 0 的作用。
 * 没上 Robolectric 是因为这里只缺这两样东西，为它引一整套 Android 运行时太重。
 *
 * ## 为什么必须有金丝雀
 *
 * 上面那套**建立在「测试依赖排在 mockable android.jar 之前」这个 classpath 顺序
 * 假设上**。假设一旦不成立（或者将来有人把那条 org.json 依赖删了），
 * `isReturnDefaultValues = true` 会让 JSONObject 的每个方法安静地返回 0/null/""
 * 而不是抛 —— 失败模式不是红，而是**部分用例静默变绿**：
 *
 *  - 「坏存档 → 回退默认」那几条会**假通过**。桩既不抛异常也解析不出任何东西，
 *    结果当然等于默认值，断言照样成立，但 fromJson 里的容错逻辑一行都没被走到。
 *  - 只有正向用例（真的读出某个值）才会红，而且红得莫名其妙。
 *
 * 所以本类开头有两条 `environmentCanary_*`。**它们守的不是 GestureConfig 的逻辑，
 * 而是测试环境本身的有效性**：直接断言真实 org.json 的行为，不经过被测代码。
 * 桩一旦生效，它们必然红，并且报错信息直接说明原因 —— 而不是留给下一个人去猜
 * 为什么其余用例悄悄退化成了空跑。
 *
 * ## 不覆盖的部分
 *
 * 本文件不碰 SharedPreferences，所以 GesturePrefs 的四个函数没有覆盖：那层只是
 * getString/putString 的薄封装，真正的逻辑全在 toJson/fromJson 里，而测它需要的
 * Context 得靠 Robolectric —— 为这点收益不值得。
 */
class GestureConfigTest {

    // ───── 金丝雀：守的是测试环境的有效性，不是 GestureConfig 的逻辑 ─────
    //
    // 放在最前面是因为它们一旦红，下面所有用例的结论都不作数 —— 先看这两条。

    /**
     * 断言测试 classpath 上的 org.json 是**真实现**而不是 mockable android.jar
     * 里的桩。故意一行都不经过 GestureConfig：被测代码出不出错是另一回事，这条
     * 只回答「解析 JSON 这件事在这个进程里到底能不能做」。
     *
     * 挑的四个语义正好是 fromJson 全部容错逻辑的地基，缺一条就有用例变成空跑：
     * optInt/optString 决定绑定读不读得出来，optLong 的 fallback 决定阈值兜底，
     * optJSONObject 决定 bindings/thresholds 两个块取不取得到、以及块类型不符时
     * 是不是老老实实返回 null。
     *
     * 桩 + `isReturnDefaultValues = true` 的话这些会返回 0/""/null，当场红。
     */
    @Test
    fun environmentCanary_realJsonImplementationIsOnTheClasspath() {
        val o = JSONObject("""{"a":1,"b":"x","nested":{"k":2}}""")

        assertEquals("optInt 读不出 1 —— 拿到的是 mockable android.jar 的桩，不是真 org.json", 1, o.optInt("a"))
        assertEquals("optString 读不出 \"x\" —— 同上", "x", o.optString("b"))
        // fromJson 的阈值兜底整个压在这条语义上：key 缺失时返回 fallback，不是 0。
        assertEquals("optLong 的 fallback 失效 —— 阈值容错的用例等于没测", 7L, o.optLong("missing", 7L))
        assertNotNull("optJSONObject 返回 null —— bindings/thresholds 两个块永远读不出来", o.optJSONObject("nested"))
        // 块类型不符返回 null —— wronglyTypedSectionsGiveDefaults 靠它才有意义。
        assertNull("optJSONObject 对非对象值没返回 null", o.optJSONObject("b"))
        // toJson() 依赖 put + toString 真的拼得出 JSON。
        assertTrue("put/toString 没拼出 JSON", JSONObject().put("k", 1L).toString().contains("\"k\""))
    }

    /**
     * 断言畸形输入**确实会抛**。
     *
     * 这条抓的是比上一条更阴的失败模式：下面那些「坏存档 → 回退默认」的用例在桩
     * 环境里会**静默变绿**。桩既不抛异常、也解析不出任何东西，于是结果当然等于
     * 默认值，断言照样成立 —— 但 fromJson 里那条 catch 一次都没被走到，容错逻辑
     * 一行都没被验证。全绿的测试报告比红的更骗人，所以把「必须抛」直接钉死在
     * 这里：桩不抛，这条就红。
     */
    @Test
    fun environmentCanary_malformedJsonActuallyThrows() {
        // 三种形状：根本不是 JSON、截断、根是数组而不是对象。
        listOf("not json at all", "{", "[1,2,3]").forEach { bad ->
            var threw = false
            try {
                JSONObject(bad)
            } catch (e: Exception) {
                threw = true
            }
            assertTrue(
                "JSONObject(\"$bad\") 没有抛异常 —— 说明走的是桩，容错路径根本没被验证到",
                threw,
            )
        }
    }

    // ───────────── 默认配置（用户有意设定的新默认，不再等于改造前的行为） ─────────────

    /**
     * 默认表的回归锁，逐槽位断言：改动 DEFAULT_BINDINGS 里任何一条都会让
     * 对应的那一行红掉，报错信息里带着槽位名。
     *
     * 期望值故意在这里手写一遍，而不是从 DEFAULT 反推 —— 测试是第二份独立的
     * 抄本，两份对不上才叫「锁」。
     */
    @Test
    fun defaultsMatchTheAgreedTable() {
        val expected = mapOf(
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
        // 表必须是全集：新增槽位忘了补这张表（或补默认值）会在这里红，而不是悄悄变成 NONE。
        assertEquals("期望表漏了槽位", GestureSlot.entries.toSet(), expected.keys)
        expected.forEach { (slot, action) ->
            assertEquals("$slot 的默认动作", action, GestureConfig.DEFAULT.actionFor(slot))
        }
    }

    /** 上面那张表之外，逐条再单独断言一遍用户点名的几项，红的时候一眼看出是哪条需求。 */
    @Test
    fun defaultsForTheRequestedBehaviours() {
        val d = GestureConfig.DEFAULT
        assertEquals(GestureAction.OPEN_MENU, d.actionFor(GestureSlot.ONE_FINGER_DOUBLE_TAP))
        assertEquals(GestureAction.OPEN_SWITCHER, d.actionFor(GestureSlot.TWO_FINGER_SWIPE_DOWN))
        assertEquals(GestureAction.NONE, d.actionFor(GestureSlot.TWO_FINGER_SWIPE_UP))
        assertEquals(GestureAction.FONT_SIZE_UP, d.actionFor(GestureSlot.TWO_FINGER_PINCH_OUT))
        assertEquals(GestureAction.FONT_SIZE_DOWN, d.actionFor(GestureSlot.TWO_FINGER_PINCH_IN))
        assertEquals(GestureAction.ENABLE_WIDE_WINDOW, d.actionFor(GestureSlot.THREE_FINGER_PINCH_OUT))
        assertEquals(GestureAction.DISABLE_WIDE_WINDOW, d.actionFor(GestureSlot.THREE_FINGER_PINCH_IN))
        assertEquals(GestureAction.SCROLL_TO_BOTTOM, d.actionFor(GestureSlot.THREE_FINGER_SWIPE_DOWN))
        assertEquals(GestureAction.CLEAR_SCREEN, d.actionFor(GestureSlot.THREE_FINGER_SWIPE_UP))
        // 其余保持现状。
        assertEquals(GestureAction.START_SELECTION, d.actionFor(GestureSlot.ONE_FINGER_HOLD))
        assertEquals(GestureAction.OPEN_MENU, d.actionFor(GestureSlot.TWO_FINGER_HOLD))
        assertEquals(GestureAction.NONE, d.actionFor(GestureSlot.TWO_FINGER_DOUBLE_TAP))
        assertEquals(GestureAction.NONE, d.actionFor(GestureSlot.THREE_FINGER_HOLD))
        assertEquals(GestureAction.NONE, d.actionFor(GestureSlot.TWO_FINGER_DRAG_HORIZONTAL))
    }

    /** DEFAULT 里每条绑定都必须通过类型约束 —— 否则 actionFor 会悄悄把它换成 NONE。 */
    @Test
    fun everyDefaultBindingIsLegalOnItsSlot() {
        GestureSlot.entries.forEach { slot ->
            val action = GestureConfig.DEFAULT.bindings[slot]
            assertNotNull("DEFAULT.bindings 缺少 $slot", action)
            assertTrue("$action 不能绑在 $slot", action!!.isAllowedOn(slot))
        }
    }

    @Test
    fun defaultThresholds() {
        val t = GestureConfig.DEFAULT.thresholds
        // 800 是现有的 SELECT_HOLD_MS / MENU_HOLD_MS。
        assertEquals(800L, t.oneFingerHoldMs)
        assertEquals(800L, t.twoFingerHoldMs)
        assertEquals(800L, t.threeFingerHoldMs)
        // 单指双击窗口默认 200（用户指定），双指仍是 300。
        assertEquals(200L, t.oneFingerDoubleTapWindowMs)
        assertEquals(300L, t.twoFingerDoubleTapWindowMs)
        assertEquals(200L, DEFAULT_ONE_FINGER_DOUBLE_TAP_WINDOW_MS)
        assertTrue(DEFAULT_ONE_FINGER_DOUBLE_TAP_WINDOW_MS in GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE)
    }

    /** 单指双击现在默认启用：识别器据此起「压住 tap 等第二下」的逻辑。 */
    @Test
    fun oneFingerDoubleTapIsOnByDefault() {
        assertTrue(GestureConfig.DEFAULT.isOneFingerDoubleTapEnabled)
        assertEquals(200L, GestureConfig.DEFAULT.thresholds.oneFingerDoubleTapWindowMs)
    }

    @Test
    fun oneFingerDoubleTapFlagFollowsTheBinding() {
        val off = GestureConfig.DEFAULT.rebind(GestureSlot.ONE_FINGER_DOUBLE_TAP, GestureAction.NONE)
        assertFalse(off.isOneFingerDoubleTapEnabled)
        val on = off.rebind(GestureSlot.ONE_FINGER_DOUBLE_TAP, GestureAction.PASTE)
        assertTrue(on.isOneFingerDoubleTapEnabled)
    }

    @Test
    fun openMenuLabelExplainsItIsTheRightClickMenu() {
        assertEquals("右键菜单（终端菜单）", GestureAction.OPEN_MENU.label)
    }

    // ───────────── 类型约束 ─────────────

    private val directional = listOf(
        GestureSlot.TWO_FINGER_SWIPE_UP,
        GestureSlot.TWO_FINGER_SWIPE_DOWN,
        GestureSlot.TWO_FINGER_PINCH_IN,
        GestureSlot.TWO_FINGER_PINCH_OUT,
        GestureSlot.THREE_FINGER_SWIPE_UP,
        GestureSlot.THREE_FINGER_SWIPE_DOWN,
        GestureSlot.THREE_FINGER_PINCH_IN,
        GestureSlot.THREE_FINGER_PINCH_OUT,
    )
    private val pinchSlots = directional.filter { it.kind == SlotKind.PINCH }
    private val discreteSlots = listOf(
        GestureSlot.ONE_FINGER_HOLD,
        GestureSlot.ONE_FINGER_DOUBLE_TAP,
        GestureSlot.TWO_FINGER_HOLD,
        GestureSlot.TWO_FINGER_DOUBLE_TAP,
        GestureSlot.THREE_FINGER_HOLD,
    )

    /** 槽位的 kind 本身也锁一下：约束全建立在它上面。 */
    @Test
    fun slotKindsAreAsDesigned() {
        discreteSlots.forEach { assertEquals("$it", SlotKind.DISCRETE, it.kind) }
        assertEquals(SlotKind.CONTINUOUS, GestureSlot.TWO_FINGER_DRAG_HORIZONTAL.kind)
        listOf(
            GestureSlot.TWO_FINGER_SWIPE_UP, GestureSlot.TWO_FINGER_SWIPE_DOWN,
            GestureSlot.THREE_FINGER_SWIPE_UP, GestureSlot.THREE_FINGER_SWIPE_DOWN,
        ).forEach { assertEquals("$it", SlotKind.SWIPE, it.kind) }
        listOf(
            GestureSlot.TWO_FINGER_PINCH_IN, GestureSlot.TWO_FINGER_PINCH_OUT,
            GestureSlot.THREE_FINGER_PINCH_IN, GestureSlot.THREE_FINGER_PINCH_OUT,
        ).forEach { assertEquals("$it", SlotKind.PINCH, it.kind) }
        // 每个槽位都属于一个组，且手指数和名字前缀一致。
        GestureSlot.entries.forEach { slot ->
            val prefix = when (slot.group) {
                SlotGroup.ONE_FINGER -> "ONE_FINGER_"
                SlotGroup.TWO_FINGER -> "TWO_FINGER_"
                SlotGroup.THREE_FINGER -> "THREE_FINGER_"
            }
            assertTrue("$slot 的 group 与名字不符", slot.name.startsWith(prefix))
        }
    }

    /** 字号增减要幅度，只有捏合槽位能给：四个捏合槽位合法，其余一律非法。 */
    @Test
    fun fontSizeActionsOnlyFitPinchSlots() {
        listOf(GestureAction.FONT_SIZE_UP, GestureAction.FONT_SIZE_DOWN).forEach { action ->
            GestureSlot.entries.forEach { slot ->
                assertEquals(
                    "$action on $slot",
                    slot in pinchSlots,
                    action.isAllowedOn(slot),
                )
            }
        }
        // 点名几个最容易被放宽的：滑动、离散、左右拖动。
        assertFalse(GestureAction.FONT_SIZE_UP.isAllowedOn(GestureSlot.TWO_FINGER_SWIPE_UP))
        assertFalse(GestureAction.FONT_SIZE_DOWN.isAllowedOn(GestureSlot.THREE_FINGER_SWIPE_DOWN))
        assertFalse(GestureAction.FONT_SIZE_UP.isAllowedOn(GestureSlot.TWO_FINGER_HOLD))
        assertFalse(GestureAction.FONT_SIZE_DOWN.isAllowedOn(GestureSlot.ONE_FINGER_DOUBLE_TAP))
        assertFalse(GestureAction.FONT_SIZE_UP.isAllowedOn(GestureSlot.TWO_FINGER_DRAG_HORIZONTAL))
    }

    /** 切换器哪儿都能放：离散（直接弹出）、方向性（跟手）、左右拖动（跟手）。 */
    @Test
    fun switcherAndNoneFitEverySlot() {
        GestureSlot.entries.forEach { slot ->
            assertTrue("$slot", GestureAction.NONE.isAllowedOn(slot))
            assertTrue("$slot", GestureAction.OPEN_SWITCHER.isAllowedOn(slot))
        }
        directional.forEach { assertTrue("$it", GestureAction.OPEN_SWITCHER.isAllowedOn(it)) }
        discreteSlots.forEach { assertTrue("$it", GestureAction.OPEN_SWITCHER.isAllowedOn(it)) }
    }

    /** 一次性动作：离散和方向性槽位都行（清屏、滑至底部、开关大窗口……），左右拖动不行。 */
    @Test
    fun oneShotActionsFitDiscreteAndDirectionalButNotHorizontalDrag() {
        val oneShot = listOf(
            GestureAction.OPEN_MENU,
            GestureAction.PASTE,
            GestureAction.CLEAR_SCREEN,
            GestureAction.SCROLL_TO_BOTTOM,
            GestureAction.ENABLE_WIDE_WINDOW,
            GestureAction.DISABLE_WIDE_WINDOW,
            GestureAction.TOGGLE_KEY_BAR,
            GestureAction.OPEN_SFTP,
            GestureAction.DISCONNECT,
        )
        oneShot.forEach { action ->
            (discreteSlots + directional).forEach { slot ->
                assertTrue("$action on $slot", action.isAllowedOn(slot))
            }
            assertFalse("$action on 左右拖动", action.isAllowedOn(GestureSlot.TWO_FINGER_DRAG_HORIZONTAL))
            assertFalse("$action 不该跟手", action.tracksGesture)
        }
    }

    /** 选区要靠同一次手势里后续的单指拖动接续：只能放离散槽位。 */
    @Test
    fun startSelectionIsDiscreteOnly() {
        discreteSlots.forEach { assertTrue("$it", GestureAction.START_SELECTION.isAllowedOn(it)) }
        (directional + GestureSlot.TWO_FINGER_DRAG_HORIZONTAL).forEach {
            assertFalse("$it", GestureAction.START_SELECTION.isAllowedOn(it))
        }
    }

    /** 左右拖动槽位保持原样：只有 NONE 和切换器。 */
    @Test
    fun horizontalDragSlotStillOnlyTakesSwitcher() {
        assertEquals(
            listOf(GestureAction.NONE, GestureAction.OPEN_SWITCHER),
            GestureAction.allowedActionsFor(GestureSlot.TWO_FINGER_DRAG_HORIZONTAL),
        )
    }

    @Test
    fun trackingActionsAreExactlySwitcherAndFontSize() {
        val tracking = GestureAction.entries.filter { it.tracksGesture }.toSet()
        assertEquals(
            setOf(GestureAction.OPEN_SWITCHER, GestureAction.FONT_SIZE_UP, GestureAction.FONT_SIZE_DOWN),
            tracking,
        )
    }

    /** 设置页的下拉选项就是这个列表，所以内容也锁一下。 */
    @Test
    fun allowedActionsForMatchesSlotKind() {
        // 离散槽位：除了字号增减，全部。
        val onDiscrete = GestureAction.allowedActionsFor(GestureSlot.ONE_FINGER_HOLD)
        assertEquals(GestureAction.entries.size - 2, onDiscrete.size)
        assertFalse(GestureAction.FONT_SIZE_UP in onDiscrete)
        assertFalse(GestureAction.FONT_SIZE_DOWN in onDiscrete)
        assertTrue(GestureAction.START_SELECTION in onDiscrete)

        // 滑动槽位：没有字号增减，也没有选择文本。
        val onSwipe = GestureAction.allowedActionsFor(GestureSlot.THREE_FINGER_SWIPE_UP)
        assertEquals(GestureAction.entries.size - 3, onSwipe.size)
        assertTrue(GestureAction.CLEAR_SCREEN in onSwipe)
        assertTrue(GestureAction.OPEN_SWITCHER in onSwipe)
        assertFalse(GestureAction.FONT_SIZE_UP in onSwipe)
        assertFalse(GestureAction.START_SELECTION in onSwipe)

        // 捏合槽位：没有选择文本，字号增减在。
        val onPinch = GestureAction.allowedActionsFor(GestureSlot.TWO_FINGER_PINCH_OUT)
        assertEquals(GestureAction.entries.size - 1, onPinch.size)
        assertTrue(GestureAction.FONT_SIZE_UP in onPinch)
        assertTrue(GestureAction.FONT_SIZE_DOWN in onPinch)
        assertFalse(GestureAction.START_SELECTION in onPinch)
        assertEquals(GestureAction.NONE, onPinch.first())
    }

    @Test
    fun rebindIgnoresIllegalCombinationsInsteadOfThrowing() {
        val before = GestureConfig.DEFAULT
        // 字号放离散槽位、放滑动槽位、选择文本放捏合槽位 —— 三种非法组合都原样返回 this。
        assertEquals(before, before.rebind(GestureSlot.ONE_FINGER_HOLD, GestureAction.FONT_SIZE_UP))
        assertEquals(before, before.rebind(GestureSlot.TWO_FINGER_SWIPE_UP, GestureAction.FONT_SIZE_DOWN))
        assertEquals(before, before.rebind(GestureSlot.TWO_FINGER_PINCH_OUT, GestureAction.START_SELECTION))
        assertEquals(before, before.rebind(GestureSlot.TWO_FINGER_DRAG_HORIZONTAL, GestureAction.CLEAR_SCREEN))
        assertEquals(
            GestureAction.START_SELECTION,
            before.rebind(GestureSlot.ONE_FINGER_HOLD, GestureAction.FONT_SIZE_UP)
                .actionFor(GestureSlot.ONE_FINGER_HOLD),
        )
    }

    @Test
    fun rebindAcceptsLegalCombinations() {
        val c = GestureConfig.DEFAULT.rebind(GestureSlot.THREE_FINGER_HOLD, GestureAction.OPEN_MENU)
        assertEquals(GestureAction.OPEN_MENU, c.actionFor(GestureSlot.THREE_FINGER_HOLD))
        // 其余槽位不受影响。
        assertEquals(GestureAction.OPEN_MENU, c.actionFor(GestureSlot.TWO_FINGER_HOLD))
        assertNotEquals(GestureConfig.DEFAULT, c)

        // 方向性槽位：一次性、跟手、字号都能绑。
        val d = GestureConfig.DEFAULT
            .rebind(GestureSlot.TWO_FINGER_SWIPE_UP, GestureAction.CLEAR_SCREEN)
            .rebind(GestureSlot.THREE_FINGER_SWIPE_DOWN, GestureAction.OPEN_SWITCHER)
            .rebind(GestureSlot.THREE_FINGER_PINCH_OUT, GestureAction.FONT_SIZE_UP)
        assertEquals(GestureAction.CLEAR_SCREEN, d.actionFor(GestureSlot.TWO_FINGER_SWIPE_UP))
        assertEquals(GestureAction.OPEN_SWITCHER, d.actionFor(GestureSlot.THREE_FINGER_SWIPE_DOWN))
        assertEquals(GestureAction.FONT_SIZE_UP, d.actionFor(GestureSlot.THREE_FINGER_PINCH_OUT))
    }

    /** 默认里确实有一处有意的重复：右键菜单同时在单指双击和双指长按上。 */
    @Test
    fun defaultMenuIsBoundTwiceOnPurpose() {
        assertEquals(
            listOf(GestureSlot.ONE_FINGER_DOUBLE_TAP, GestureSlot.TWO_FINGER_HOLD),
            GestureConfig.DEFAULT.slotsFor(GestureAction.OPEN_MENU),
        )
    }

    // ───────────── 往返 ─────────────

    @Test
    fun defaultSurvivesRoundTrip() {
        assertEquals(GestureConfig.DEFAULT, GestureConfig.fromJson(GestureConfig.DEFAULT.toJson()))
    }

    @Test
    fun customConfigSurvivesRoundTrip() {
        // 典型的荣耀用户改法：菜单从被系统抢走的双指长按挪到三指长按。
        val saved = GestureConfig.DEFAULT
            .rebind(GestureSlot.TWO_FINGER_HOLD, GestureAction.NONE)
            .rebind(GestureSlot.THREE_FINGER_HOLD, GestureAction.OPEN_MENU)
            .rebind(GestureSlot.TWO_FINGER_DOUBLE_TAP, GestureAction.PASTE)
            .rebind(GestureSlot.TWO_FINGER_DRAG_HORIZONTAL, GestureAction.OPEN_SWITCHER)
            .copy(
                thresholds = GestureThresholds(
                    oneFingerHoldMs = 450L,
                    twoFingerHoldMs = 1200L,
                    threeFingerHoldMs = 600L,
                    // 故意取两个不同的值：往返后任何一个被串到另一个都会红。
                    oneFingerDoubleTapWindowMs = 250L,
                    twoFingerDoubleTapWindowMs = 400L,
                ),
            )

        val loaded = GestureConfig.fromJson(saved.toJson())
        assertEquals(saved, loaded)
        GestureSlot.entries.forEach { slot ->
            assertEquals("$slot", saved.actionFor(slot), loaded.actionFor(slot))
        }
        assertEquals(saved.thresholds, loaded.thresholds)
    }

    // ───────────── 坏存档容错 ─────────────

    @Test
    fun missingOrEmptyStorageGivesDefaults() {
        assertEquals(GestureConfig.DEFAULT, GestureConfig.fromJson(null))
        assertEquals(GestureConfig.DEFAULT, GestureConfig.fromJson(""))
        assertEquals(GestureConfig.DEFAULT, GestureConfig.fromJson("   "))
    }

    @Test
    fun structurallyBrokenJsonGivesDefaultsInsteadOfThrowing() {
        // 截断、根本不是 JSON、根是数组而不是对象 —— 三种都不许抛。
        listOf("""{"bindings":{"ONE_FINGER_HOLD":""", "not json at all", "[1,2,3]", "null")
            .forEach { assertEquals(it, GestureConfig.DEFAULT, GestureConfig.fromJson(it)) }
    }

    /** 块本身类型不对（不是对象）等同于块缺失，而不是崩掉。 */
    @Test
    fun wronglyTypedSectionsGiveDefaults() {
        assertEquals(
            GestureConfig.DEFAULT,
            GestureConfig.fromJson("""{"v":1,"bindings":"nope","thresholds":42}"""),
        )
    }

    /**
     * 这条是「逐字段容错」的核心断言：第八个字段坏掉不能把前七个一起带走。
     */
    @Test
    fun unknownActionNameOnlyResetsThatSlot() {
        val loaded = GestureConfig.fromJson(
            """
            {"v":1,"bindings":{
              "ONE_FINGER_HOLD":"PASTE",
              "TWO_FINGER_HOLD":"LAUNCH_ROCKET",
              "THREE_FINGER_HOLD":"OPEN_SFTP"
            },"thresholds":{"oneFingerHoldMs":450}}
            """.trimIndent(),
        )
        // 坏的那个回默认……
        assertEquals(GestureAction.OPEN_MENU, loaded.actionFor(GestureSlot.TWO_FINGER_HOLD))
        // ……好的那些原样保留。
        assertEquals(GestureAction.PASTE, loaded.actionFor(GestureSlot.ONE_FINGER_HOLD))
        assertEquals(GestureAction.OPEN_SFTP, loaded.actionFor(GestureSlot.THREE_FINGER_HOLD))
        assertEquals(450L, loaded.thresholds.oneFingerHoldMs)
    }

    @Test
    fun absentSlotsFallBackIndividually() {
        // 只写了一个槽位：其余按默认表补齐，而不是整份回默认。
        val loaded = GestureConfig.fromJson("""{"bindings":{"ONE_FINGER_HOLD":"SCROLL_TO_BOTTOM"}}""")
        assertEquals(GestureAction.SCROLL_TO_BOTTOM, loaded.actionFor(GestureSlot.ONE_FINGER_HOLD))
        assertEquals(GestureAction.OPEN_MENU, loaded.actionFor(GestureSlot.TWO_FINGER_HOLD))
        assertEquals(GestureAction.FONT_SIZE_UP, loaded.actionFor(GestureSlot.TWO_FINGER_PINCH_OUT))
        assertEquals(GestureAction.CLEAR_SCREEN, loaded.actionFor(GestureSlot.THREE_FINGER_SWIPE_UP))
        assertEquals(GestureConfig.DEFAULT.thresholds, loaded.thresholds)
    }

    /**
     * 非法组合（连续动作绑在离散槽位上）必须在加载时就被挡掉。构造的是一份
     * 「旧版本写的 / 被手工改过的」存档：真让它原样加载，识别器会拿着一个
     * 要不到位移量的动作去跑捏合逻辑。
     */
    @Test
    fun illegalCombinationFallsBackToTheSlotDefault() {
        val loaded = GestureConfig.fromJson(
            """{"bindings":{"ONE_FINGER_HOLD":"FONT_SIZE_UP","TWO_FINGER_HOLD":"OPEN_SFTP"}}""",
        )
        assertEquals(GestureAction.START_SELECTION, loaded.actionFor(GestureSlot.ONE_FINGER_HOLD))
        // 同一份存档里合法的那个不受连累。
        assertEquals(GestureAction.OPEN_SFTP, loaded.actionFor(GestureSlot.TWO_FINGER_HOLD))
    }

    /** 字号放滑动槽位、一次性动作放左右拖动槽位、选择文本放捏合槽位：都要被挡掉，各回各的默认。 */
    @Test
    fun illegalCombinationsOnDirectionalSlotsFallBack() {
        val loaded = GestureConfig.fromJson(
            """{"bindings":{
              "TWO_FINGER_SWIPE_DOWN":"FONT_SIZE_UP",
              "THREE_FINGER_SWIPE_UP":"FONT_SIZE_DOWN",
              "TWO_FINGER_DRAG_HORIZONTAL":"DISCONNECT",
              "TWO_FINGER_PINCH_OUT":"START_SELECTION",
              "THREE_FINGER_PINCH_IN":"PASTE"
            }}""",
        )
        assertEquals(GestureAction.OPEN_SWITCHER, loaded.actionFor(GestureSlot.TWO_FINGER_SWIPE_DOWN))
        assertEquals(GestureAction.CLEAR_SCREEN, loaded.actionFor(GestureSlot.THREE_FINGER_SWIPE_UP))
        assertEquals(GestureAction.NONE, loaded.actionFor(GestureSlot.TWO_FINGER_DRAG_HORIZONTAL))
        assertEquals(GestureAction.FONT_SIZE_UP, loaded.actionFor(GestureSlot.TWO_FINGER_PINCH_OUT))
        // 同一份存档里合法的那个（一次性动作放捏合槽位）原样保留。
        assertEquals(GestureAction.PASTE, loaded.actionFor(GestureSlot.THREE_FINGER_PINCH_IN))
    }

    // ───────────── 数值越界 ─────────────

    @Test
    fun outOfRangeThresholdsAreClampedNotDiscarded() {
        val loaded = GestureConfig.fromJson(
            """
            {"thresholds":{
              "oneFingerHoldMs":999999,
              "twoFingerHoldMs":1,
              "threeFingerHoldMs":-5,
              "oneFingerDoubleTapWindowMs":5000,
              "twoFingerDoubleTapWindowMs":10
            }}
            """.trimIndent(),
        )
        // 钳到边界而不是退回默认值：用户设过 999999 的意图是「要很长」。
        assertEquals(GESTURE_HOLD_MS_RANGE.last, loaded.thresholds.oneFingerHoldMs)
        assertEquals(GESTURE_HOLD_MS_RANGE.first, loaded.thresholds.twoFingerHoldMs)
        assertEquals(GESTURE_HOLD_MS_RANGE.first, loaded.thresholds.threeFingerHoldMs)
        assertEquals(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE.last, loaded.thresholds.oneFingerDoubleTapWindowMs)
        assertEquals(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE.first, loaded.thresholds.twoFingerDoubleTapWindowMs)
    }

    /** 值根本不是数字（字符串、布尔、null）→ 该字段用默认值，不抛。 */
    @Test
    fun nonNumericThresholdsFallBackPerField() {
        val loaded = GestureConfig.fromJson(
            """{"thresholds":{"oneFingerHoldMs":"abc","twoFingerHoldMs":true,"threeFingerHoldMs":null,"oneFingerDoubleTapWindowMs":280}}""",
        )
        assertEquals(DEFAULT_ONE_FINGER_HOLD_MS, loaded.thresholds.oneFingerHoldMs)
        assertEquals(DEFAULT_THREE_FINGER_HOLD_MS, loaded.thresholds.threeFingerHoldMs)
        // 合法的那个不受影响。
        assertEquals(280L, loaded.thresholds.oneFingerDoubleTapWindowMs)
    }

    @Test
    fun coercedClampsEveryField() {
        val t = GestureThresholds(
            oneFingerHoldMs = 10L,
            twoFingerHoldMs = 99999L,
            threeFingerHoldMs = 800L,
            oneFingerDoubleTapWindowMs = 0L,
            twoFingerDoubleTapWindowMs = 99999L,
        ).coerced()
        assertEquals(GESTURE_HOLD_MS_RANGE.first, t.oneFingerHoldMs)
        assertEquals(GESTURE_HOLD_MS_RANGE.last, t.twoFingerHoldMs)
        assertEquals(800L, t.threeFingerHoldMs)
        assertEquals(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE.first, t.oneFingerDoubleTapWindowMs)
        assertEquals(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE.last, t.twoFingerDoubleTapWindowMs)
    }

    // ───────────── 单指/双指双击窗口彼此独立 ─────────────

    /**
     * 回归本 bug：设置页里拖「单指双击」的间隔，「双指双击」的滑块跟着动，
     * 因为两者曾共用一个字段。现在改一个，另一个必须原样不动（两个方向都测）。
     */
    @Test
    fun doubleTapWindowsAreIndependent() {
        val base = GestureThresholds()

        val oneChanged = base.copy(oneFingerDoubleTapWindowMs = 450L).coerced()
        assertEquals(450L, oneChanged.oneFingerDoubleTapWindowMs)
        assertEquals(base.twoFingerDoubleTapWindowMs, oneChanged.twoFingerDoubleTapWindowMs)

        // 取 400 而不是 200：200 恰好是单指窗口的出厂默认，取它会把「串了值」掩盖成「碰巧相等」。
        val twoChanged = base.copy(twoFingerDoubleTapWindowMs = 400L).coerced()
        assertEquals(400L, twoChanged.twoFingerDoubleTapWindowMs)
        assertEquals(base.oneFingerDoubleTapWindowMs, twoChanged.oneFingerDoubleTapWindowMs)

        // 落盘再读回来也不串。
        val loaded = GestureConfig.fromJson(
            GestureConfig.DEFAULT.copy(thresholds = oneChanged.copy(twoFingerDoubleTapWindowMs = 400L)).toJson(),
        )
        assertEquals(450L, loaded.thresholds.oneFingerDoubleTapWindowMs)
        assertEquals(400L, loaded.thresholds.twoFingerDoubleTapWindowMs)
    }

    // ───────────── 旧存档迁移：doubleTapWindowMs → 两个新阈值 ─────────────

    /** 旧版本只存一个共用的 doubleTapWindowMs：升级后它同时成为两个新阈值的初值，而不是丢回默认。 */
    @Test
    fun legacyDoubleTapKeySeedsBothNewWindows() {
        val loaded = GestureConfig.fromJson("""{"v":1,"thresholds":{"oneFingerHoldMs":450,"doubleTapWindowMs":220}}""")
        assertEquals(220L, loaded.thresholds.oneFingerDoubleTapWindowMs)
        assertEquals(220L, loaded.thresholds.twoFingerDoubleTapWindowMs)
        // 同一份存档里别的阈值不受影响。
        assertEquals(450L, loaded.thresholds.oneFingerHoldMs)
    }

    /** 旧值越界同样先钳位再作为两个窗口的初值。 */
    @Test
    fun legacyDoubleTapKeyIsClampedToo() {
        val loaded = GestureConfig.fromJson("""{"thresholds":{"doubleTapWindowMs":99999}}""")
        assertEquals(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE.last, loaded.thresholds.oneFingerDoubleTapWindowMs)
        assertEquals(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE.last, loaded.thresholds.twoFingerDoubleTapWindowMs)
    }

    /** 新旧 key 同时存在：新 key 优先，旧 key 只补缺失的那个。 */
    @Test
    fun newKeysTakePrecedenceOverLegacyKey() {
        val both = GestureConfig.fromJson(
            """{"thresholds":{"doubleTapWindowMs":220,"oneFingerDoubleTapWindowMs":350,"twoFingerDoubleTapWindowMs":180}}""",
        )
        assertEquals(350L, both.thresholds.oneFingerDoubleTapWindowMs)
        assertEquals(180L, both.thresholds.twoFingerDoubleTapWindowMs)

        // 只有一个新 key：它自己用新值，缺的那个回落到旧值。
        val partial = GestureConfig.fromJson(
            """{"thresholds":{"doubleTapWindowMs":220,"oneFingerDoubleTapWindowMs":350}}""",
        )
        assertEquals(350L, partial.thresholds.oneFingerDoubleTapWindowMs)
        assertEquals(220L, partial.thresholds.twoFingerDoubleTapWindowMs)
    }

    /** 新旧 key 都没有：两个窗口都是默认值。 */
    @Test
    fun noDoubleTapKeysGiveDefaults() {
        val loaded = GestureConfig.fromJson("""{"thresholds":{"oneFingerHoldMs":450}}""")
        assertEquals(DEFAULT_ONE_FINGER_DOUBLE_TAP_WINDOW_MS, loaded.thresholds.oneFingerDoubleTapWindowMs)
        assertEquals(DEFAULT_TWO_FINGER_DOUBLE_TAP_WINDOW_MS, loaded.thresholds.twoFingerDoubleTapWindowMs)
    }

    /** 两个窗口各自钳到自己的范围，互不影响。 */
    @Test
    fun eachWindowIsClampedIndependently() {
        val loaded = GestureConfig.fromJson(
            """{"thresholds":{"oneFingerDoubleTapWindowMs":1,"twoFingerDoubleTapWindowMs":5000}}""",
        )
        assertEquals(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE.first, loaded.thresholds.oneFingerDoubleTapWindowMs)
        assertEquals(GESTURE_DOUBLE_TAP_WINDOW_MS_RANGE.last, loaded.thresholds.twoFingerDoubleTapWindowMs)
    }

    /** toJson 只写新 key，不再写旧的：否则新旧并存时旧值会成为迷惑的第三个来源。 */
    @Test
    fun toJsonWritesNewKeysOnly() {
        val t = JSONObject(GestureConfig.DEFAULT.toJson()).getJSONObject("thresholds")
        assertTrue(t.has("oneFingerDoubleTapWindowMs"))
        assertTrue(t.has("twoFingerDoubleTapWindowMs"))
        assertFalse(t.has("doubleTapWindowMs"))
    }

    // ───────────── 旧槽位迁移：TWO_FINGER_DRAG_VERTICAL / TWO_FINGER_PINCH 已被拆掉 ─────────────

    /**
     * 升级用户手机上的典型旧存档：全量快照，带着两个已不存在的槽位名和旧动作名。
     * 不抛、旧槽位的绑定迁到新槽位、其余旧槽位原样保留、新增的槽位走新默认。
     */
    @Test
    fun legacyFullSnapshotMigrates() {
        val loaded = GestureConfig.fromJson(
            """
            {"v":1,"bindings":{
              "ONE_FINGER_HOLD":"START_SELECTION",
              "TWO_FINGER_HOLD":"OPEN_MENU",
              "TWO_FINGER_DOUBLE_TAP":"NONE",
              "THREE_FINGER_HOLD":"NONE",
              "ONE_FINGER_DOUBLE_TAP":"NONE",
              "TWO_FINGER_DRAG_VERTICAL":"OPEN_SWITCHER",
              "TWO_FINGER_DRAG_HORIZONTAL":"NONE",
              "TWO_FINGER_PINCH":"ADJUST_FONT_SIZE"
            },"thresholds":{"oneFingerHoldMs":450,"twoFingerHoldMs":800,"threeFingerHoldMs":800,
              "oneFingerDoubleTapWindowMs":300,"twoFingerDoubleTapWindowMs":300}}
            """.trimIndent(),
        )
        // 迁移：上下拖动 → 双指下滑；捏合调字号 → 放大/缩小各一个。
        assertEquals(GestureAction.OPEN_SWITCHER, loaded.actionFor(GestureSlot.TWO_FINGER_SWIPE_DOWN))
        assertEquals(GestureAction.FONT_SIZE_UP, loaded.actionFor(GestureSlot.TWO_FINGER_PINCH_OUT))
        assertEquals(GestureAction.FONT_SIZE_DOWN, loaded.actionFor(GestureSlot.TWO_FINGER_PINCH_IN))
        // 存档里有的旧槽位原样保留 —— 包括显式存下的旧默认「单指双击=无」。
        assertEquals(GestureAction.NONE, loaded.actionFor(GestureSlot.ONE_FINGER_DOUBLE_TAP))
        assertFalse(loaded.isOneFingerDoubleTapEnabled)
        assertEquals(GestureAction.START_SELECTION, loaded.actionFor(GestureSlot.ONE_FINGER_HOLD))
        assertEquals(GestureAction.OPEN_MENU, loaded.actionFor(GestureSlot.TWO_FINGER_HOLD))
        // 存档里没有的新槽位走新默认。
        assertEquals(GestureAction.NONE, loaded.actionFor(GestureSlot.TWO_FINGER_SWIPE_UP))
        assertEquals(GestureAction.CLEAR_SCREEN, loaded.actionFor(GestureSlot.THREE_FINGER_SWIPE_UP))
        assertEquals(GestureAction.SCROLL_TO_BOTTOM, loaded.actionFor(GestureSlot.THREE_FINGER_SWIPE_DOWN))
        assertEquals(GestureAction.ENABLE_WIDE_WINDOW, loaded.actionFor(GestureSlot.THREE_FINGER_PINCH_OUT))
        assertEquals(GestureAction.DISABLE_WIDE_WINDOW, loaded.actionFor(GestureSlot.THREE_FINGER_PINCH_IN))
        // 阈值照旧读（显式存下的 300 不会被新默认 200 覆盖）。
        assertEquals(450L, loaded.thresholds.oneFingerHoldMs)
        assertEquals(300L, loaded.thresholds.oneFingerDoubleTapWindowMs)
        // 迁移结果落盘再读回来是稳定的，且不再带旧槽位名。
        assertEquals(loaded, GestureConfig.fromJson(loaded.toJson()))
        val written = JSONObject(loaded.toJson()).getJSONObject("bindings")
        assertFalse(written.has("TWO_FINGER_DRAG_VERTICAL"))
        assertFalse(written.has("TWO_FINGER_PINCH"))
    }

    /** 存档里**只有**旧槽位名：旧值迁移，其余全部是新默认。 */
    @Test
    fun legacyOnlySlotNamesMigrateAndEverythingElseIsNewDefault() {
        val loaded = GestureConfig.fromJson(
            """{"bindings":{"TWO_FINGER_DRAG_VERTICAL":"OPEN_SWITCHER","TWO_FINGER_PINCH":"ADJUST_FONT_SIZE"}}""",
        )
        assertEquals(GestureConfig.DEFAULT, loaded)
    }

    /** 旧捏合绑的是切换器：只迁到「缩小」（旧实现里是捏紧才拉出切换器），「放大」走新默认。 */
    @Test
    fun legacyPinchSwitcherMigratesToPinchInOnly() {
        val loaded = GestureConfig.fromJson("""{"bindings":{"TWO_FINGER_PINCH":"OPEN_SWITCHER"}}""")
        assertEquals(GestureAction.OPEN_SWITCHER, loaded.actionFor(GestureSlot.TWO_FINGER_PINCH_IN))
        assertEquals(GestureAction.FONT_SIZE_UP, loaded.actionFor(GestureSlot.TWO_FINGER_PINCH_OUT))
    }

    /** 旧上下拖动绑成别的合法动作（构造的）：照迁到双指下滑。 */
    @Test
    fun legacyVerticalDragKeepsOtherLegalActions() {
        // 旧模型里离散动作其实不合法，但这是对「迁移后对新槽位合法才迁」的直接验证：
        // 下滑槽位接受一次性动作，所以这份存档的意图可以被保留。
        val loaded = GestureConfig.fromJson("""{"bindings":{"TWO_FINGER_DRAG_VERTICAL":"CLEAR_SCREEN"}}""")
        assertEquals(GestureAction.CLEAR_SCREEN, loaded.actionFor(GestureSlot.TWO_FINGER_SWIPE_DOWN))
    }

    /** 旧值 NONE、旧动作名不再存在、或迁过去不合法：不迁移，该新槽位走新默认。 */
    @Test
    fun legacyValuesThatCannotMigrateFallToNewDefaults() {
        // 旧上下拖动 = NONE → 不迁移。
        assertEquals(
            GestureAction.OPEN_SWITCHER,
            GestureConfig.fromJson("""{"bindings":{"TWO_FINGER_DRAG_VERTICAL":"NONE"}}""")
                .actionFor(GestureSlot.TWO_FINGER_SWIPE_DOWN),
        )
        // 旧上下拖动 = 调字号：字号不能放滑动槽位 → 不迁移。
        assertEquals(
            GestureAction.OPEN_SWITCHER,
            GestureConfig.fromJson("""{"bindings":{"TWO_FINGER_DRAG_VERTICAL":"ADJUST_FONT_SIZE"}}""")
                .actionFor(GestureSlot.TWO_FINGER_SWIPE_DOWN),
        )
        // 旧捏合 = NONE / 离散动作 / 乱写：缩小、放大都走新默认。
        listOf("NONE", "DISCONNECT", "LAUNCH_ROCKET").forEach { old ->
            val c = GestureConfig.fromJson("""{"bindings":{"TWO_FINGER_PINCH":"$old"}}""")
            assertEquals(old, GestureAction.FONT_SIZE_DOWN, c.actionFor(GestureSlot.TWO_FINGER_PINCH_IN))
            assertEquals(old, GestureAction.FONT_SIZE_UP, c.actionFor(GestureSlot.TWO_FINGER_PINCH_OUT))
        }
    }

    /** 旧动作名 ADJUST_FONT_SIZE 出现在别的槽位上（旧模型允许它在左右拖动上）：当作未知动作，该槽位回默认，不抛。 */
    @Test
    fun removedActionNameInAnotherSlotFallsBackToDefault() {
        val loaded = GestureConfig.fromJson("""{"bindings":{"TWO_FINGER_DRAG_HORIZONTAL":"ADJUST_FONT_SIZE"}}""")
        assertEquals(GestureAction.NONE, loaded.actionFor(GestureSlot.TWO_FINGER_DRAG_HORIZONTAL))
    }

    /** 新旧混合：新槽位 key 存在就以新为准，旧 key 不再覆盖；没有新 key 的才迁移。 */
    @Test
    fun newKeysWinOverLegacyKeysInAMixedSnapshot() {
        val loaded = GestureConfig.fromJson(
            """{"bindings":{
              "TWO_FINGER_SWIPE_DOWN":"PASTE",
              "TWO_FINGER_DRAG_VERTICAL":"OPEN_SWITCHER",
              "TWO_FINGER_PINCH":"OPEN_SWITCHER",
              "TWO_FINGER_PINCH_OUT":"NONE"
            }}""",
        )
        // 新 key 在：旧的 OPEN_SWITCHER 不覆盖它。
        assertEquals(GestureAction.PASTE, loaded.actionFor(GestureSlot.TWO_FINGER_SWIPE_DOWN))
        // 新 key 显式写了 NONE：尊重它，不被默认值或旧值覆盖。
        assertEquals(GestureAction.NONE, loaded.actionFor(GestureSlot.TWO_FINGER_PINCH_OUT))
        // 缩小槽位没有新 key：从旧捏合迁移（旧值取 OPEN_SWITCHER，因为它和新默认 FONT_SIZE_DOWN 不同，迁没迁一眼可辨）。
        assertEquals(GestureAction.OPEN_SWITCHER, loaded.actionFor(GestureSlot.TWO_FINGER_PINCH_IN))
    }

    /** 未知槽位名（已删除的、将来版本才有的）一律忽略，不抛，也不影响已知槽位。 */
    @Test
    fun unknownSlotNamesAreIgnored() {
        val loaded = GestureConfig.fromJson(
            """{"v":99,"bindings":{
              "FOUR_FINGER_TAP":"OPEN_MENU",
              "TWO_FINGER_SHAKE":"DISCONNECT",
              "ONE_FINGER_HOLD":"PASTE"
            }}""",
        )
        assertEquals(GestureAction.PASTE, loaded.actionFor(GestureSlot.ONE_FINGER_HOLD))
        assertEquals(
            GestureConfig.DEFAULT.rebind(GestureSlot.ONE_FINGER_HOLD, GestureAction.PASTE),
            loaded,
        )
    }

    /** 旧存档的 bindings 块损坏成非对象：等同缺失，不抛，全部新默认。 */
    @Test
    fun legacyMigrationToleratesBrokenBindingsBlock() {
        assertEquals(GestureConfig.DEFAULT, GestureConfig.fromJson("""{"bindings":"TWO_FINGER_PINCH"}"""))
    }

    /** 双击窗口：旧 key 缺失时单指/双指各回各的出厂默认（200 / 300），不能共用一个兜底。 */
    @Test
    fun missingDoubleTapKeysGiveEachWindowItsOwnDefault() {
        val t = GestureConfig.fromJson("""{"v":1,"bindings":{}}""").thresholds
        assertEquals(200L, t.oneFingerDoubleTapWindowMs)
        assertEquals(300L, t.twoFingerDoubleTapWindowMs)
        // 旧 key 存在但不是数字：同样各回各的。
        val t2 = GestureConfig.fromJson("""{"thresholds":{"doubleTapWindowMs":"abc"}}""").thresholds
        assertEquals(200L, t2.oneFingerDoubleTapWindowMs)
        assertEquals(300L, t2.twoFingerDoubleTapWindowMs)
    }

    @Test
    fun defaultRoundTripStillEqualsDefault() {
        assertEquals(GestureConfig.DEFAULT, GestureConfig.fromJson(GestureConfig.DEFAULT.toJson()))
    }
}
