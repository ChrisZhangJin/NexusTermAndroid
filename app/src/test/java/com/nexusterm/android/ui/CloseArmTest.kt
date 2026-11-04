package com.nexusterm.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CloseArm 的 JVM 单测：切换器里键盘断开会话的二次确认状态机。 */
class CloseArmTest {

    @Test fun firstPressArmsInsteadOfClosing() {
        val r = CloseArm.IDLE.press("a", nowMs = 1000L)
        assertFalse(r.close)
        assertEquals(CloseArm("a", 1000L), r.next)
        assertTrue(r.next.isArmedFor("a", nowMs = 1000L))
    }

    @Test fun secondPressInsideTheWindowCloses() {
        val armed = CloseArm.IDLE.press("a", 1000L).next
        val r = armed.press("a", 1000L + CLOSE_ARM_WINDOW_MS - 1)
        assertTrue(r.close)
        // 确认之后回到空闲：紧接着再按又是重新上膛，不会连断两张。
        assertEquals(CloseArm.IDLE, r.next)
        assertFalse(r.next.press("a", 1000L + CLOSE_ARM_WINDOW_MS).close)
    }

    @Test fun pressAfterTheWindowReArmsInsteadOfClosing() {
        val armed = CloseArm.IDLE.press("a", 1000L).next
        val late = 1000L + CLOSE_ARM_WINDOW_MS
        assertFalse(armed.isArmedFor("a", late))
        val r = armed.press("a", late)
        assertFalse(r.close)
        assertEquals(CloseArm("a", late), r.next)
    }

    @Test fun pressOnAnotherCardReArmsThatCardOnly() {
        val armed = CloseArm.IDLE.press("a", 1000L).next
        assertFalse(armed.isArmedFor("b", 1001L))
        val r = armed.press("b", 1001L)
        assertFalse(r.close)
        assertEquals(CloseArm("b", 1001L), r.next)
        // a 的上膛已经被 b 顶掉了：回到 a 再按一下不能直接断开。
        assertFalse(r.next.press("a", 1002L).close)
    }

    @Test fun disarmCancels() {
        val armed = CloseArm.IDLE.press("a", 1000L).next
        val off = armed.disarm()
        assertEquals(CloseArm.IDLE, off)
        assertFalse(off.isArmedFor("a", 1001L))
        assertFalse(off.press("a", 1001L).close)
    }

    @Test fun idleIsNeverArmed() {
        assertFalse(CloseArm.IDLE.isArmedFor("a", 0L))
        assertFalse(CloseArm.IDLE.isArmedFor("", 0L))
    }

    /** 时钟倒退（不该发生，但别因此放行）：当作没上膛。 */
    @Test fun clockGoingBackwardsDoesNotConfirm() {
        val armed = CloseArm.IDLE.press("a", 5000L).next
        assertFalse(armed.isArmedFor("a", 4000L))
        assertFalse(armed.press("a", 4000L).close)
    }

    @Test fun customWindowIsHonoured() {
        val armed = CloseArm.IDLE.press("a", 0L, windowMs = 500L).next
        assertTrue(armed.press("a", 499L, windowMs = 500L).close)
        assertFalse(armed.press("a", 500L, windowMs = 500L).close)
    }
}
