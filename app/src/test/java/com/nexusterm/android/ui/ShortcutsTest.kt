package com.nexusterm.android.ui

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shortcuts.kt 的 JVM 单测。运行环境（真 org.json 盖掉 mockable android.jar 的桩、
 * `isReturnDefaultValues` 让 Log 不抛）和为什么要有金丝雀，见 GestureConfigTest 的类注释 ——
 * 这里的 JSON 用例依赖同一套前提，金丝雀在那边守着。
 *
 * `KeyEvent.KEYCODE_*` 是编译期常量，被内联进测试，JVM 上可以直接用；
 * 唯一碰到桩的是 `KeyEvent.keyCodeToString`（describe 的兜底），它在这里返回 null，
 * 所以 describe 的兜底分支只断言「非空」，不断言具体文案。
 */
class ShortcutsTest {

    private fun chord(keyCode: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        KeyChord(keyCode, ctrl, shift, alt)

    private val d = ShortcutBindings.DEFAULT

    private fun fire(
        b: ShortcutBindings,
        keyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ) = b.actionFor(keyCode, ctrl, shift, alt, meta)

    // ───────────── 动作枚举 ─────────────

    @Test
    fun everyActionHasAChineseLabelAndAGroup() {
        ShortcutAction.entries.forEach {
            assertTrue("$it 的 label 为空", it.label.isNotBlank())
            assertNotNull(it.group)
        }
        assertEquals("下一个标签", ShortcutAction.NEXT_TAB.label)
        assertEquals(
            listOf("标签", "终端", "字体", "其它"),
            ShortcutGroup.entries.map { it.label },
        )
    }

    @Test
    fun gotoTabActionsExposeZeroBasedTabIndex() {
        val gotos = ShortcutAction.entries.filter { it.tabIndex != null }
        assertEquals(9, gotos.size)
        gotos.forEachIndexed { i, action ->
            assertEquals("$action", i, action.tabIndex)
            assertEquals("$action", "GOTO_TAB_${i + 1}", action.name)
            assertEquals(action, ShortcutAction.forTabIndex(i))
        }
        assertNull(ShortcutAction.forTabIndex(9))
        assertNull(ShortcutAction.forTabIndex(-1))
        assertNull(ShortcutAction.NEXT_TAB.tabIndex)
    }

    @Test
    fun groupsAreContiguousInDeclarationOrder() {
        // 设置页按声明顺序分段：同组成员必须挨在一起，否则同一个标题会出现两次。
        val groups = ShortcutAction.entries.map { it.group }
        val collapsed = groups.filterIndexed { i, g -> i == 0 || groups[i - 1] != g }
        assertEquals("有分组被拆成了几段", collapsed.distinct(), collapsed)
        assertEquals(
            listOf(ShortcutAction.COPY, ShortcutAction.PASTE, ShortcutAction.CLEAR_SCREEN, ShortcutAction.SCROLL_TO_BOTTOM),
            ShortcutAction.inGroup(ShortcutGroup.TERMINAL),
        )
        assertEquals(
            listOf(ShortcutAction.FONT_SIZE_UP, ShortcutAction.FONT_SIZE_DOWN, ShortcutAction.FONT_SIZE_RESET),
            ShortcutAction.inGroup(ShortcutGroup.FONT),
        )
    }

    // ───────────── 默认键位表 ─────────────

    /** 默认表的回归锁：期望值在这里手写第二份，两份对不上才叫锁。 */
    @Test
    fun defaultsMatchTheAgreedTable() {
        fun cs(k: Int) = chord(k, ctrl = true, shift = true)
        fun c(k: Int) = chord(k, ctrl = true)
        val expected: Map<ShortcutAction, List<KeyChord>> = buildMap {
            put(ShortcutAction.NEXT_TAB, listOf(c(KeyEvent.KEYCODE_TAB)))
            put(ShortcutAction.PREV_TAB, listOf(cs(KeyEvent.KEYCODE_TAB)))
            val digits = listOf(
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6,
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
            )
            digits.forEachIndexed { i, k ->
                put(ShortcutAction.forTabIndex(i)!!, listOf(chord(k, ctrl = true, alt = true)))
            }
            put(ShortcutAction.OPEN_SWITCHER, listOf(cs(KeyEvent.KEYCODE_A)))
            put(ShortcutAction.NEW_TAB, listOf(cs(KeyEvent.KEYCODE_T)))
            put(ShortcutAction.CLOSE_TAB, listOf(cs(KeyEvent.KEYCODE_W)))
            put(ShortcutAction.RENAME_TAB, listOf(cs(KeyEvent.KEYCODE_R)))
            put(ShortcutAction.OPEN_MENU, listOf(cs(KeyEvent.KEYCODE_M)))
            put(ShortcutAction.COPY, listOf(cs(KeyEvent.KEYCODE_C)))
            put(ShortcutAction.PASTE, listOf(cs(KeyEvent.KEYCODE_V)))
            put(ShortcutAction.CLEAR_SCREEN, listOf(cs(KeyEvent.KEYCODE_L)))
            put(ShortcutAction.SCROLL_TO_BOTTOM, listOf(cs(KeyEvent.KEYCODE_MOVE_END)))
            put(ShortcutAction.TOGGLE_KEY_BAR, listOf(cs(KeyEvent.KEYCODE_K)))
            put(ShortcutAction.TOGGLE_WIDE_WINDOW, listOf(cs(KeyEvent.KEYCODE_E)))
            put(ShortcutAction.OPEN_SFTP, listOf(cs(KeyEvent.KEYCODE_S)))
            put(
                ShortcutAction.FONT_SIZE_UP,
                listOf(
                    c(KeyEvent.KEYCODE_EQUALS), cs(KeyEvent.KEYCODE_EQUALS),
                    c(KeyEvent.KEYCODE_PLUS), c(KeyEvent.KEYCODE_NUMPAD_ADD),
                ),
            )
            put(ShortcutAction.FONT_SIZE_DOWN, listOf(c(KeyEvent.KEYCODE_MINUS), c(KeyEvent.KEYCODE_NUMPAD_SUBTRACT)))
            put(ShortcutAction.FONT_SIZE_RESET, listOf(c(KeyEvent.KEYCODE_0), c(KeyEvent.KEYCODE_NUMPAD_0)))
        }
        // 全集：新增动作忘了补默认键位会在这里红，而不是悄悄变成「未绑定」。
        assertEquals("期望表漏了动作", ShortcutAction.entries.toSet(), expected.keys)
        expected.forEach { (action, chords) ->
            assertEquals("$action 的默认键位", chords, d.chordsFor(action))
        }
        assertEquals(expected, d.map)
    }

    @Test
    fun noChordIsSharedBetweenActionsByDefault() {
        val all = ShortcutAction.entries.flatMap { a -> d.chordsFor(a).map { it to a } }
        val byChord = all.groupBy({ it.first }, { it.second })
        byChord.forEach { (chord, actions) ->
            assertEquals("${chord.describe()} 被多个动作占用：$actions", 1, actions.size)
        }
        // 同一个动作内也不该重复。
        ShortcutAction.entries.forEach { a ->
            assertEquals("$a", d.chordsFor(a).distinct(), d.chordsFor(a))
        }
    }

    /** 由 isReservedChord 的规则保证：默认表里一项都不被拒绝。 */
    @Test
    fun noDefaultChordIsReserved() {
        ShortcutAction.entries.forEach { a ->
            d.chordsFor(a).forEach { c ->
                assertNull("${a}: ${c.describe()} 被判为保留：${isReservedChord(c)?.message}", isReservedChord(c))
            }
        }
    }

    @Test
    fun everyDefaultActionHasAtLeastOneChord() {
        ShortcutAction.entries.forEach { assertTrue("$it", d.chordsFor(it).isNotEmpty()) }
    }

    // ───────────── actionFor：精确匹配 ─────────────

    @Test
    fun actionForMatchesModifiersExactly() {
        assertEquals(
            ShortcutAction.GOTO_TAB_1,
            fire(d, KeyEvent.KEYCODE_1, ctrl = true, alt = true),
        )
        // Ctrl+1 没绑任何东西：不能因为 Ctrl+Alt+1 绑了就「差不多」命中。
        assertNull(fire(d, KeyEvent.KEYCODE_1, ctrl = true))
        assertNull(fire(d, KeyEvent.KEYCODE_1, alt = true))
        assertNull(fire(d, KeyEvent.KEYCODE_1, ctrl = true, shift = true, alt = true))

        assertEquals(ShortcutAction.NEW_TAB, fire(d, KeyEvent.KEYCODE_T, ctrl = true, shift = true))
        // 裸 Ctrl+T 归远端（tmux/readline 的 ^T）：绝不命中。
        assertNull(fire(d, KeyEvent.KEYCODE_T, ctrl = true))
        assertNull(fire(d, KeyEvent.KEYCODE_T, shift = true))
        assertNull(fire(d, KeyEvent.KEYCODE_T))
        assertNull(fire(d, KeyEvent.KEYCODE_T, ctrl = true, shift = true, alt = true))

        assertEquals(ShortcutAction.NEXT_TAB, fire(d, KeyEvent.KEYCODE_TAB, ctrl = true))
        assertEquals(ShortcutAction.PREV_TAB, fire(d, KeyEvent.KEYCODE_TAB, ctrl = true, shift = true))
        assertNull(fire(d, KeyEvent.KEYCODE_TAB))
    }

    @Test
    fun everyDefaultChordFiresItsOwnAction() {
        ShortcutAction.entries.forEach { a ->
            d.chordsFor(a).forEach { c ->
                assertEquals("${c.describe()}", a, fire(d, c.keyCode, c.ctrl, c.shift, c.alt))
            }
        }
    }

    @Test
    fun gotoTabChordsMapToTheirOwnTab() {
        val keys = listOf(
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
        )
        keys.forEachIndexed { i, k ->
            val action = fire(d, k, ctrl = true, alt = true)
            assertEquals(i, action?.tabIndex)
        }
        // 0 没有对应的标签。
        assertNull(fire(d, KeyEvent.KEYCODE_0, ctrl = true, alt = true))
    }

    @Test
    fun metaNeverMatches() {
        ShortcutAction.entries.forEach { a ->
            d.chordsFor(a).forEach { c ->
                assertNull(
                    "Meta+${c.describe()} 不该命中",
                    fire(d, c.keyCode, c.ctrl, c.shift, c.alt, meta = true),
                )
            }
        }
        assertNull(fire(d, KeyEvent.KEYCODE_T, ctrl = true, shift = true, meta = true))
        assertNull(fire(d, KeyEvent.KEYCODE_A, meta = true))
    }

    @Test
    fun fontSizeAliases() {
        val up = ShortcutAction.FONT_SIZE_UP
        assertEquals(up, fire(d, KeyEvent.KEYCODE_EQUALS, ctrl = true))
        assertEquals(up, fire(d, KeyEvent.KEYCODE_EQUALS, ctrl = true, shift = true))
        assertEquals(up, fire(d, KeyEvent.KEYCODE_PLUS, ctrl = true))
        assertEquals(up, fire(d, KeyEvent.KEYCODE_NUMPAD_ADD, ctrl = true))

        val down = ShortcutAction.FONT_SIZE_DOWN
        assertEquals(down, fire(d, KeyEvent.KEYCODE_MINUS, ctrl = true))
        assertEquals(down, fire(d, KeyEvent.KEYCODE_NUMPAD_SUBTRACT, ctrl = true))
        // Ctrl+Shift+- 是 xterm.js 的 ^_，不能占。
        assertNull(fire(d, KeyEvent.KEYCODE_MINUS, ctrl = true, shift = true))

        val reset = ShortcutAction.FONT_SIZE_RESET
        assertEquals(reset, fire(d, KeyEvent.KEYCODE_0, ctrl = true))
        assertEquals(reset, fire(d, KeyEvent.KEYCODE_NUMPAD_0, ctrl = true))
    }

    @Test
    fun chordsForFallsBackToDefaultForMissingKeysButHonoursEmptyLists() {
        val sparse = ShortcutBindings(mapOf(ShortcutAction.COPY to emptyList()))
        // 键缺失：回默认。
        assertEquals(d.chordsFor(ShortcutAction.PASTE), sparse.chordsFor(ShortcutAction.PASTE))
        assertEquals(ShortcutAction.PASTE, fire(sparse, KeyEvent.KEYCODE_V, ctrl = true, shift = true))
        // 键存在、列表为空：已解绑，不回默认。
        assertTrue(sparse.chordsFor(ShortcutAction.COPY).isEmpty())
        assertNull(fire(sparse, KeyEvent.KEYCODE_C, ctrl = true, shift = true))
    }

    // ───────────── describe ─────────────

    @Test
    fun describeStrings() {
        assertEquals("Ctrl+Shift+T", chord(KeyEvent.KEYCODE_T, ctrl = true, shift = true).describe())
        assertEquals("Ctrl+Alt+1", chord(KeyEvent.KEYCODE_1, ctrl = true, alt = true).describe())
        assertEquals("Ctrl+=", chord(KeyEvent.KEYCODE_EQUALS, ctrl = true).describe())
        assertEquals("Ctrl+Shift+=", chord(KeyEvent.KEYCODE_EQUALS, ctrl = true, shift = true).describe())
        assertEquals("Ctrl+Tab", chord(KeyEvent.KEYCODE_TAB, ctrl = true).describe())
        assertEquals("Ctrl+Shift+Tab", chord(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true).describe())
        assertEquals("Ctrl+Shift+End", chord(KeyEvent.KEYCODE_MOVE_END, ctrl = true, shift = true).describe())
        assertEquals("Ctrl+-", chord(KeyEvent.KEYCODE_MINUS, ctrl = true).describe())
        assertEquals("Ctrl+0", chord(KeyEvent.KEYCODE_0, ctrl = true).describe())
        assertEquals("Ctrl+Num+", chord(KeyEvent.KEYCODE_NUMPAD_ADD, ctrl = true).describe())
        assertEquals("Ctrl+Num0", chord(KeyEvent.KEYCODE_NUMPAD_0, ctrl = true).describe())
        assertEquals("Ctrl+F5", chord(KeyEvent.KEYCODE_F5, ctrl = true).describe())
        // 修饰键顺序固定：Ctrl、Alt、Shift。
        assertEquals("Ctrl+Alt+Shift+Z", chord(KeyEvent.KEYCODE_Z, ctrl = true, shift = true, alt = true).describe())
    }

    @Test
    fun describeNeverReturnsBlankEvenForUnknownKeys() {
        // 表外的键走 keyCodeToString 兜底；JVM 桩里它返回 null，最终兜底也得给出非空串。
        assertTrue(chord(KeyEvent.KEYCODE_VOLUME_UP, ctrl = true).describe().isNotBlank())
        assertTrue(chord(9999, ctrl = true).describe().length > "Ctrl+".length)
    }

    @Test
    fun describeActionJoinsAliasesAndShowsUnbound() {
        assertEquals("Ctrl+Shift+T", d.describe(ShortcutAction.NEW_TAB))
        assertEquals("Ctrl+= / Ctrl+Shift+= / Ctrl++ / Ctrl+Num+", d.describe(ShortcutAction.FONT_SIZE_UP))
        assertEquals("未设置", d.unbind(ShortcutAction.NEW_TAB).describe(ShortcutAction.NEW_TAB))
    }

    // ───────────── isReservedChord ─────────────

    private fun reserved(keyCode: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        isReservedChord(chord(keyCode, ctrl, shift, alt))

    @Test
    fun noModifierOrShiftOnlyIsReserved() {
        assertEquals(ReservedReason.NO_COMMAND_MODIFIER, reserved(KeyEvent.KEYCODE_T))
        assertEquals(ReservedReason.NO_COMMAND_MODIFIER, reserved(KeyEvent.KEYCODE_T, shift = true))
        assertEquals(ReservedReason.NO_COMMAND_MODIFIER, reserved(KeyEvent.KEYCODE_F5))
    }

    @Test
    fun modifierOnlyKeysAndEscapeAndUnknownAreReserved() {
        listOf(
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK,
        ).forEach {
            // 修饰键按下时事件里自带它自己的修饰位，所以带不带位都得拒绝。
            assertEquals("$it", ReservedReason.MODIFIER_ONLY, reserved(it))
            assertEquals("$it", ReservedReason.MODIFIER_ONLY, reserved(it, ctrl = true, shift = true))
        }
        assertEquals(ReservedReason.ESCAPE, reserved(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(ReservedReason.ESCAPE, reserved(KeyEvent.KEYCODE_ESCAPE, ctrl = true, shift = true))
        assertEquals(ReservedReason.UNSUPPORTED_KEY, reserved(KeyEvent.KEYCODE_UNKNOWN, ctrl = true))
        assertEquals(ReservedReason.UNSUPPORTED_KEY, reserved(-3, ctrl = true))
    }

    /** 整个 A..Z：Ctrl+字母、Alt+字母、Ctrl+Alt+字母、Alt+Shift+字母 都归远端，只有 Ctrl+Shift+字母放行。 */
    @Test
    fun lettersNeedCtrlShift() {
        for (k in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            if (k == KeyEvent.KEYCODE_Z) continue // Ctrl+Alt+Z 的原因是系统占用，单独测。
            assertEquals("Ctrl+$k", ReservedReason.REMOTE_LETTER, reserved(k, ctrl = true))
            assertEquals("Alt+$k", ReservedReason.REMOTE_LETTER, reserved(k, alt = true))
            assertEquals("Ctrl+Alt+$k", ReservedReason.REMOTE_LETTER, reserved(k, ctrl = true, alt = true))
            assertEquals("Alt+Shift+$k", ReservedReason.REMOTE_LETTER, reserved(k, shift = true, alt = true))
            assertNull("Ctrl+Shift+$k", reserved(k, ctrl = true, shift = true))
            assertNull("Ctrl+Alt+Shift+$k", reserved(k, ctrl = true, shift = true, alt = true))
        }
        assertEquals(ReservedReason.REMOTE_LETTER, reserved(KeyEvent.KEYCODE_Z, ctrl = true))
        assertEquals(ReservedReason.REMOTE_LETTER, reserved(KeyEvent.KEYCODE_Z, alt = true))
        assertNull(reserved(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))
    }

    @Test
    fun systemChordsAreReserved() {
        assertEquals(ReservedReason.SYSTEM_RESERVED, reserved(KeyEvent.KEYCODE_TAB, alt = true))
        assertEquals(ReservedReason.SYSTEM_RESERVED, reserved(KeyEvent.KEYCODE_TAB, alt = true, shift = true))
        assertEquals(ReservedReason.IME_SWITCH, reserved(KeyEvent.KEYCODE_SPACE, ctrl = true))
        assertEquals(ReservedReason.IME_SWITCH, reserved(KeyEvent.KEYCODE_SPACE, ctrl = true, shift = true))
        assertEquals(ReservedReason.SYSTEM_RESERVED, reserved(KeyEvent.KEYCODE_Z, ctrl = true, alt = true))
        // Ctrl+Tab / Ctrl+Alt+Tab 不是 Alt+Tab。
        assertNull(reserved(KeyEvent.KEYCODE_TAB, ctrl = true))
    }

    @Test
    fun ctrlShiftMinusIsReservedButCtrlMinusIsNot() {
        assertEquals(ReservedReason.SENDS_CONTROL_BYTE, reserved(KeyEvent.KEYCODE_MINUS, ctrl = true, shift = true))
        assertNull(reserved(KeyEvent.KEYCODE_MINUS, ctrl = true))
    }

    @Test
    fun altWithNonLetterIsReserved() {
        assertEquals(ReservedReason.REMOTE_ALT, reserved(KeyEvent.KEYCODE_1, alt = true))
        assertEquals(ReservedReason.REMOTE_ALT, reserved(KeyEvent.KEYCODE_EQUALS, alt = true, shift = true))
        assertEquals(ReservedReason.REMOTE_ALT, reserved(KeyEvent.KEYCODE_DPAD_LEFT, alt = true))
    }

    @Test
    fun ctrlOnlyNavigationAndControlKeysAreReserved() {
        listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH,
        ).forEach {
            assertEquals("Ctrl+$it", ReservedReason.REMOTE_KEY, reserved(it, ctrl = true))
        }
        // 加了 Shift 就放行（默认的 Ctrl+Shift+End 就靠这条）。
        assertNull(reserved(KeyEvent.KEYCODE_MOVE_END, ctrl = true, shift = true))
    }

    @Test
    fun ctrlDigitsAndSymbolsAreAllowedBecauseDefaultsUseThem() {
        assertNull(reserved(KeyEvent.KEYCODE_1, ctrl = true))
        assertNull(reserved(KeyEvent.KEYCODE_1, ctrl = true, alt = true))
        assertNull(reserved(KeyEvent.KEYCODE_EQUALS, ctrl = true))
        assertNull(reserved(KeyEvent.KEYCODE_NUMPAD_ADD, ctrl = true))
        assertNull(reserved(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true))
    }

    @Test
    fun everyReservedReasonHasAUserFacingMessage() {
        ReservedReason.entries.forEach { assertTrue("$it", it.message.isNotBlank()) }
    }

    // ───────────── rebind / 冲突 ─────────────

    @Test
    fun rebindReplacesTheActionsChordList() {
        val c = chord(KeyEvent.KEYCODE_J, ctrl = true, shift = true)
        val b = d.rebind(ShortcutAction.FONT_SIZE_UP, c)
        // 四个别名都被唯一的新键位顶掉。
        assertEquals(listOf(c), b.chordsFor(ShortcutAction.FONT_SIZE_UP))
        assertEquals(ShortcutAction.FONT_SIZE_UP, fire(b, KeyEvent.KEYCODE_J, ctrl = true, shift = true))
        assertNull(fire(b, KeyEvent.KEYCODE_EQUALS, ctrl = true))
        // 其余动作不受影响。
        ShortcutAction.entries.filter { it != ShortcutAction.FONT_SIZE_UP }.forEach {
            assertEquals("$it", d.chordsFor(it), b.chordsFor(it))
        }
        // 键集仍然完整：== 才能当语义相等用。
        assertEquals(ShortcutAction.entries.toSet(), b.map.keys)
        assertNotEquals(d, b)
    }

    @Test
    fun rebindStealsTheChordFromTheCurrentHolder() {
        val paste = d.chordsFor(ShortcutAction.PASTE).single()
        assertEquals(listOf(ShortcutAction.PASTE), d.conflictsFor(paste))
        // 排除自己：不算冲突。
        assertEquals(emptyList<ShortcutAction>(), d.conflictsFor(paste, except = ShortcutAction.PASTE))

        val b = d.rebind(ShortcutAction.COPY, paste)
        assertEquals(listOf(paste), b.chordsFor(ShortcutAction.COPY))
        // 被抢的一方变成未绑定（空列表），而不是回默认 —— 否则抢占就白做了。
        assertTrue(b.chordsFor(ShortcutAction.PASTE).isEmpty())
        assertEquals(ShortcutAction.COPY, fire(b, paste.keyCode, paste.ctrl, paste.shift, paste.alt))
        // 抢占之后一个键位至多属于一个动作。
        val owners = ShortcutAction.entries.filter { paste in b.chordsFor(it) }
        assertEquals(listOf(ShortcutAction.COPY), owners)
    }

    @Test
    fun rebindStealOnlyRemovesTheOneChordFromAMultiChordHolder() {
        // 把 Ctrl+= 抢给 NEW_TAB：字号增大还剩另外三个别名。
        val eq = chord(KeyEvent.KEYCODE_EQUALS, ctrl = true)
        val b = d.rebind(ShortcutAction.NEW_TAB, eq)
        assertEquals(
            d.chordsFor(ShortcutAction.FONT_SIZE_UP).filter { it != eq },
            b.chordsFor(ShortcutAction.FONT_SIZE_UP),
        )
        assertEquals(ShortcutAction.NEW_TAB, fire(b, KeyEvent.KEYCODE_EQUALS, ctrl = true))
    }

    @Test
    fun conflictsForReportsEveryHolder() {
        assertEquals(emptyList<ShortcutAction>(), d.conflictsFor(chord(KeyEvent.KEYCODE_J, ctrl = true, shift = true)))
        // 手搓一个两个动作占同一键位的实例：两个都要报。
        val shared = chord(KeyEvent.KEYCODE_J, ctrl = true, shift = true)
        val b = ShortcutBindings(
            d.map + (ShortcutAction.COPY to listOf(shared)) + (ShortcutAction.PASTE to listOf(shared)),
        )
        assertEquals(listOf(ShortcutAction.COPY, ShortcutAction.PASTE), b.conflictsFor(shared))
        assertEquals(listOf(ShortcutAction.PASTE), b.conflictsFor(shared, except = ShortcutAction.COPY))
        // 反查表在这种手搓的重复里按枚举顺序靠前的赢，且不抛。
        assertEquals(ShortcutAction.COPY, fire(b, shared.keyCode, shared.ctrl, shared.shift, shared.alt))
    }

    @Test
    fun rebindToAReservedChordIsIgnored() {
        listOf(
            chord(KeyEvent.KEYCODE_C, ctrl = true),                       // 远端的 ^C
            chord(KeyEvent.KEYCODE_T),                                    // 无修饰
            chord(KeyEvent.KEYCODE_TAB, alt = true),                      // 系统
            chord(KeyEvent.KEYCODE_SPACE, ctrl = true),                   // 输入法
            chord(KeyEvent.KEYCODE_ESCAPE, ctrl = true),                  // Esc
            chord(KeyEvent.KEYCODE_SHIFT_LEFT, ctrl = true, shift = true), // 只按了修饰键
        ).forEach { assertEquals(it.describe(), d, d.rebind(ShortcutAction.COPY, it)) }
    }

    @Test
    fun unbindAndResetAction() {
        val u = d.unbind(ShortcutAction.CLEAR_SCREEN)
        assertTrue(u.chordsFor(ShortcutAction.CLEAR_SCREEN).isEmpty())
        assertNull(fire(u, KeyEvent.KEYCODE_L, ctrl = true, shift = true))
        assertEquals(d, u.reset(ShortcutAction.CLEAR_SCREEN))

        val custom = d.rebind(ShortcutAction.CLEAR_SCREEN, chord(KeyEvent.KEYCODE_J, ctrl = true, shift = true))
        assertEquals(d, custom.reset(ShortcutAction.CLEAR_SCREEN))
        assertEquals(d, custom.reset())
    }

    @Test
    fun resetActionStealsBackItsDefaultChordFromWhoeverTookIt() {
        val clear = d.chordsFor(ShortcutAction.CLEAR_SCREEN).single()
        val stolen = d.rebind(ShortcutAction.COPY, clear)
        val back = stolen.reset(ShortcutAction.CLEAR_SCREEN)
        assertEquals(listOf(clear), back.chordsFor(ShortcutAction.CLEAR_SCREEN))
        assertTrue(back.chordsFor(ShortcutAction.COPY).isEmpty())
    }

    // ───────────── JSON ─────────────

    @Test
    fun defaultSurvivesRoundTrip() {
        assertEquals(d, ShortcutBindings.fromJsonObject(d.toJsonObject()))
        assertEquals(d, ShortcutBindings.fromJsonObject(null))
    }

    @Test
    fun customBindingsSurviveRoundTrip() {
        val saved = d
            .rebind(ShortcutAction.NEW_TAB, chord(KeyEvent.KEYCODE_N, ctrl = true, shift = true, alt = true))
            .rebind(ShortcutAction.COPY, d.chordsFor(ShortcutAction.PASTE).single()) // 抢占，PASTE 变空
            .unbind(ShortcutAction.OPEN_SFTP)
        val loaded = ShortcutBindings.fromJsonObject(JSONObject(saved.toJsonObject().toString()))
        assertEquals(saved, loaded)
        // 空列表必须写出并读回：解绑不能在重启后变回默认。
        assertTrue(loaded.chordsFor(ShortcutAction.OPEN_SFTP).isEmpty())
        assertTrue(loaded.chordsFor(ShortcutAction.PASTE).isEmpty())
    }

    @Test
    fun toJsonWritesEveryActionAsAnArray() {
        val o = d.toJsonObject()
        ShortcutAction.entries.forEach { a ->
            val arr = o.optJSONArray(a.name)
            assertNotNull("${a.name} 没写出", arr)
            assertEquals(d.chordsFor(a).size, arr!!.length())
        }
        val first = o.getJSONArray("NEXT_TAB").getJSONObject(0)
        assertEquals(KeyEvent.KEYCODE_TAB, first.getInt("key"))
        assertTrue(first.getBoolean("ctrl"))
        assertFalse(first.getBoolean("shift"))
        assertFalse(first.getBoolean("alt"))
    }

    @Test
    fun nonObjectOrGarbageBlockGivesDefaultsPerAction() {
        // 整块里每个动作的值都不是数组：全部回默认，不抛。
        val allGarbage = JSONObject().apply {
            ShortcutAction.entries.forEach { put(it.name, "nope") }
        }
        assertEquals(d, ShortcutBindings.fromJsonObject(allGarbage))
        assertEquals(d, ShortcutBindings.fromJsonObject(JSONObject()))
    }

    @Test
    fun garbageOnlyResetsThatAction() {
        val json = JSONObject(
            """
            {
              "NEXT_TAB": "oops",
              "PREV_TAB": 42,
              "NEW_TAB": [1, "x", null, {"nokey":true}, {"key":-5}, {"key":0,"ctrl":true}],
              "CLOSE_TAB": [{"key":${KeyEvent.KEYCODE_J},"ctrl":true,"shift":true}],
              "COPY": [{"key":"${KeyEvent.KEYCODE_J + 1}","ctrl":"true","shift":true}],
              "LAUNCH_ROCKET": [{"key":${KeyEvent.KEYCODE_K},"ctrl":true,"shift":true}]
            }
            """.trimIndent(),
        )
        val b = ShortcutBindings.fromJsonObject(json)
        // 坏的回默认。
        assertEquals(d.chordsFor(ShortcutAction.NEXT_TAB), b.chordsFor(ShortcutAction.NEXT_TAB))
        assertEquals(d.chordsFor(ShortcutAction.PREV_TAB), b.chordsFor(ShortcutAction.PREV_TAB))
        // 非空数组但一项合法的都没有：回默认。
        assertEquals(d.chordsFor(ShortcutAction.NEW_TAB), b.chordsFor(ShortcutAction.NEW_TAB))
        // 好的原样保留。
        assertEquals(
            listOf(chord(KeyEvent.KEYCODE_J, ctrl = true, shift = true)),
            b.chordsFor(ShortcutAction.CLOSE_TAB),
        )
        // 数字字符串这类「类型松散但意思明确」的值被容忍（org.json 的 optInt/optBoolean 语义）。
        assertEquals(
            listOf(chord(KeyEvent.KEYCODE_J + 1, ctrl = true, shift = true)),
            b.chordsFor(ShortcutAction.COPY),
        )
        // 没写的动作回默认；不认识的动作名被忽略。
        assertEquals(d.chordsFor(ShortcutAction.PASTE), b.chordsFor(ShortcutAction.PASTE))
        assertEquals(ShortcutAction.entries.toSet(), b.map.keys)
    }

    @Test
    fun badEntriesInsideAnArrayAreDroppedButGoodOnesKept() {
        val good = chord(KeyEvent.KEYCODE_J, ctrl = true, shift = true)
        val arr = JSONArray()
            .put("junk")
            .put(good.toJsonObject())
            .put(JSONObject().put("key", "abc"))
        val b = ShortcutBindings.fromJsonObject(JSONObject().put("NEW_TAB", arr))
        assertEquals(listOf(good), b.chordsFor(ShortcutAction.NEW_TAB))
    }

    @Test
    fun reservedChordsInTheArchiveAreDropped() {
        val ctrlC = chord(KeyEvent.KEYCODE_C, ctrl = true)
        val ok = chord(KeyEvent.KEYCODE_J, ctrl = true, shift = true)
        val b = ShortcutBindings.fromJsonObject(
            JSONObject()
                .put("COPY", JSONArray().put(ctrlC.toJsonObject()).put(ok.toJsonObject()))
                // 只有保留键位 → 等同全是垃圾 → 回默认。
                .put("PASTE", JSONArray().put(ctrlC.toJsonObject())),
        )
        assertEquals(listOf(ok), b.chordsFor(ShortcutAction.COPY))
        assertEquals(d.chordsFor(ShortcutAction.PASTE), b.chordsFor(ShortcutAction.PASTE))
        assertNull(fire(b, KeyEvent.KEYCODE_C, ctrl = true))
    }

    @Test
    fun duplicateChordsInTheArchiveAreResolvedToOneOwner() {
        val shared = chord(KeyEvent.KEYCODE_J, ctrl = true, shift = true)
        val b = ShortcutBindings.fromJsonObject(
            JSONObject()
                .put("COPY", JSONArray().put(shared.toJsonObject()).put(shared.toJsonObject()))
                .put("PASTE", JSONArray().put(shared.toJsonObject())),
        )
        // 枚举靠前的（COPY）拿到，且同一动作里的重复也被去掉；PASTE 因此成了未绑定。
        assertEquals(listOf(shared), b.chordsFor(ShortcutAction.COPY))
        assertTrue(b.chordsFor(ShortcutAction.PASTE).isEmpty())
        assertEquals(listOf(ShortcutAction.COPY), b.conflictsFor(shared))
    }

    @Test
    fun explicitArchiveBindingsBeatDefaultsOfActionsMissingFromTheArchive() {
        // 模拟「升级后新增了动作 / 存档缺这个动作」：用户把 NEW_TAB 的出厂键位显式给了 COPY，
        // 存档里没有 NEW_TAB —— NEW_TAB 回默认时不能把用户明确的选择顶掉。
        val newTabDefault = d.chordsFor(ShortcutAction.NEW_TAB).single()
        val b = ShortcutBindings.fromJsonObject(
            JSONObject().put("COPY", JSONArray().put(newTabDefault.toJsonObject())),
        )
        assertEquals(listOf(newTabDefault), b.chordsFor(ShortcutAction.COPY))
        assertTrue(b.chordsFor(ShortcutAction.NEW_TAB).isEmpty())
        assertEquals(ShortcutAction.COPY, fire(b, newTabDefault.keyCode, newTabDefault.ctrl, newTabDefault.shift, newTabDefault.alt))
    }
}
