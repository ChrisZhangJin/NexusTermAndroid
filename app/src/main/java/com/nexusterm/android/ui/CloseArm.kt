package com.nexusterm.android.ui

/** 键盘断开确认的有效时长：第一下 Backspace / Delete 之后，这段时间内再按一下才真的断开。 */
const val CLOSE_ARM_WINDOW_MS = 3000L

/**
 * 切换器里键盘 Backspace / Delete 断开会话的「二次确认」状态机。
 *
 * 一次按键比上划手势更容易误触，所以第一下只「上膛」（[press] 返回 [Result.close] = false，
 * 界面显示提示），同一张卡片上、[windowMs] 以内的第二下才放行。任何别的键、换了选中的卡片、
 * 切换器收起都调 [disarm]；超时由 [isArmedFor] 判断，界面那边另起一个定时器负责把提示收掉。
 *
 * 不可变：[press] / [disarm] 返回新状态，调用方把它放进一个 Compose state，提示的显隐
 * 就自动跟着它走。时间由调用方传入（用单调时钟，如 SystemClock.elapsedRealtime），
 * 所以纯 Kotlin、不依赖 Android，可以直接在 JVM 单测里跑。
 */
data class CloseArm(
    val tabId: String? = null,
    val armedAtMs: Long = 0L,
) {
    /** [press] 的结果：[next] 是新状态，[close] = true 表示这一下是确认、该断开了。 */
    data class Result(val next: CloseArm, val close: Boolean)

    /** [tabId] 这张卡片现在是不是处于「再按一次就断开」的状态。 */
    fun isArmedFor(tabId: String, nowMs: Long, windowMs: Long = CLOSE_ARM_WINDOW_MS): Boolean =
        this.tabId == tabId && nowMs - armedAtMs in 0 until windowMs

    /**
     * 在 [tabId] 上按了一下 Backspace / Delete。已经上膛且没过期 → 确认（状态回到 [IDLE]）；
     * 否则（没上膛 / 过期 / 上膛的是另一张卡片）→ 对这张卡片重新上膛，不断开。
     */
    fun press(tabId: String, nowMs: Long, windowMs: Long = CLOSE_ARM_WINDOW_MS): Result =
        if (isArmedFor(tabId, nowMs, windowMs)) Result(IDLE, close = true)
        else Result(CloseArm(tabId, nowMs), close = false)

    /** 取消上膛。 */
    fun disarm(): CloseArm = IDLE

    companion object {
        val IDLE = CloseArm()
    }
}
