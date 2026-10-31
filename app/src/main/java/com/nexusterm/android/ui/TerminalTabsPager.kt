package com.nexusterm.android.ui

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** One live terminal, as far as this screen is concerned. */
data class TerminalTab(
    val id: String,
    val title: String,
    val handle: TerminalHandle,
)

/** Drag distance that takes the switcher from fully closed to fully open. */
private const val FULL_DRAG_PX = 600f

/** Past this, releasing settles open rather than closed. */
private const val OPEN_THRESHOLD = 0.4f

/**
 * How far the fingers' span has to change to move the font one size.
 *
 * Tracking the span continuously felt twitchy — a pixel of jitter either side
 * of a size boundary flipped the whole terminal back and forth — so a pinch
 * steps instead, and the reference span is reset to the current one at every
 * step. That reset is the hysteresis: whichever way the next step goes, it
 * costs a fresh [PinchStepPerFontSize] from where the last one landed.
 *
 * In dp, not px, because what's being measured is how far a hand physically
 * moved: the same 64px that felt right on the 440dpi phone this was tuned on
 * would be twice as touchy at 280dpi. Span is the mean distance of the fingers
 * from their centroid, i.e. half the separation between two of them, so 22dp
 * of span is ~43dp of actual finger separation per size: four or five sizes
 * out of one comfortable pinch, and the whole MIN..MAX range in four or five
 * of them.
 */
private val PinchStepPerFontSize = 22.dp

/** Card scale when fully open. The 0.28 gap is what lets neighbours peek in. */
private const val OPEN_SCALE = 0.72f

/** Fraction of the screen a card must be flung up before it counts as closed. */
private const val DISMISS_FRACTION = 0.22f

/**
 * A two-finger swipe down anywhere in the terminal (out of the box; see
 * [gestureConfig]) shrinks it into a card and reveals the other live sessions
 * either side of it; swipe left/right to choose one, tap it to switch, flick it
 * up to disconnect it, or two-finger drag back up to cancel.
 *
 * There is no permanent tab strip — the cards carry the session names — so the
 * gesture stays live even at a single tab, alongside the menu's "断开".
 *
 * The terminal's other gestures live here too, because they share the
 * switcher's opening move and have to be told apart from it (see
 * [terminalGestures]): two-finger pinch to change the font size, a one-finger
 * double tap or two-finger hold for the terminal menu, three-finger swipes and
 * pinches (clear screen, scroll to bottom, virtual wide window), and selection
 * — one-finger long press, drag to stretch, tap to copy.
 *
 * 哪个手势做哪件事由 [gestureConfig] 决定，上面写的只是出厂绑定；识别器认的
 * 是槽位，动作是查表查出来的（见 GestureConfig.kt 里为什么非可配不可）。
 *
 * ## Why the pages are always full-screen
 *
 * app/src/main/assets/terminal/index.html listens for `resize` and reports the
 * new `cols`/`rows` straight back through to `SshClient.resize`, i.e. an SSH
 * window-change on the *remote* shell. So a card must never be implemented by
 * measuring a terminal smaller: that would reflow the real shell to card
 * dimensions and leave it wrong (vim redrawn at ~24x8) until the user switched
 * back. Instead every page is laid out at full screen and shrunk purely with
 * [graphicsLayer], a draw-time transform that leaves measurement — and
 * therefore `cols`/`rows`, and therefore the remote pty — untouched.
 *
 * The same property is why there's no separate "overlay" composition: the
 * closed state is just this pager at scale 1. A terminal's WebView is never
 * re-parented by opening or closing the switcher, so there's no flicker and no
 * chance of a stray re-layout mid-gesture.
 *
 * The extra-keys row ([TerminalKeyBar]) is the one thing here that genuinely
 * is allowed to re-measure the terminal — it occupies real rows, so the shell
 * has to be told. It earns that by changing this pager's bottom inset exactly
 * twice per appearance (once when it arrives, once when it's gone) and sliding
 * on a [graphicsLayer] in between, rather than animating the inset itself.
 *
 * ## Why neighbours only exist while it's open
 *
 * Each page holds a real WebView (a full Chromium view). `beyondViewportPageCount`
 * is 0 while closed, so exactly one is attached — identical to not having this
 * screen at all — and goes to 1 only once the gesture starts. At most three are
 * ever live at once, which bounds the cost no matter how many tabs are open.
 */
@Composable
fun TerminalTabsPager(
    tabs: List<TerminalTab>,
    activeTabId: String,
    onActiveTabChange: (String) -> Unit,
    /** Flicking a card up disconnects that session; the caller does the teardown. */
    onCloseTab: (String) -> Unit,
    /** "SFTP 文件管理" in the terminal menu, for the tab with this id. */
    onOpenSftp: (String) -> Unit,
    /**
     * 手势绑定表。有默认值，所以调用点不传也还是改造前的那套手势。
     *
     * 故意是参数而不是在识别器里自己 `GesturePrefs.load()`：那是一次性读，
     * 用户在设置页改完回到终端不会生效。状态归上层持有，这里只负责用。
     */
    gestureConfig: GestureConfig = GestureConfig.DEFAULT,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    val scope = rememberCoroutineScope()
    val activeIndex = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = activeIndex) { tabs.size }

    // 0 = a plain full-screen terminal, 1 = cards. Everything visual is a
    // lerp over this, so the switcher tracks the fingers instead of snapping.
    val progress = remember { Animatable(0f) }
    val isOpen = progress.value > 0f

    suspend fun settle(target: Float) {
        progress.animateTo(target, tween(durationMillis = 220))
    }

    /** Commit the carousel's choice and collapse back to a single terminal. */
    fun chooseAndClose(page: Int) {
        scope.launch {
            if (pagerState.currentPage != page) pagerState.animateScrollToPage(page)
            onActiveTabChange(tabs[page].id)
            settle(0f)
        }
    }

    // The tab row (or a tab closing) can change the active session while the
    // switcher is shut — keep the pager in step without animating, so it looks
    // like a straight swap rather than a carousel scroll nobody asked for.
    LaunchedEffect(activeTabId, tabs.size) {
        val target = tabs.indexOfFirst { it.id == activeTabId }
        if (target >= 0 && target != pagerState.currentPage && progress.value == 0f) {
            pagerState.scrollToPage(target)
        }
    }

    // Swiping the carousel is itself a choice: adopt whatever it settles on,
    // so closing by any route keeps the tab row and the terminal agreeing.
    LaunchedEffect(pagerState, tabs) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            tabs.getOrNull(page)?.let { if (it.id != activeTabId) onActiveTabChange(it.id) }
        }
    }

    // The terminal every single-session gesture (pinch, menu, selection) acts
    // on: those are only recognised while the switcher is shut, when this is
    // the one on screen. Read through state because the gesture recogniser is
    // set up once and would otherwise keep acting on the first tab forever.
    val activeHandle by rememberUpdatedState(tabs[activeIndex].handle)

    // 同理：SFTP / 断开这两个动作现在也能从手势直接触发，而手势的回调是在
    // 识别器建立的那一刻捕获的，直接闭包 `tabs[activeIndex].id` 会永远指向
    // 第一次组合时的那个标签页。菜单里那两项不受影响（菜单每次都是重新组合
    // 出来的），这里才需要穿过 state 读。
    val activeTabIdNow by rememberUpdatedState(tabs[activeIndex].id)

    val density = LocalDensity.current.density
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Where the terminal menu is anchored, or null when it's closed.
    var menuAt by remember { mutableStateOf<Offset?>(null) }

    // The span the pinch last stepped the font at; see PinchStepPerFontSize.
    var pinchRefSpan by remember { mutableFloatStateOf(0f) }

    // The terminal a long press started selecting in, until a tap ends it.
    var selecting by remember { mutableStateOf<TerminalHandle?>(null) }

    // Keys go to whichever session is actually on screen, and nowhere at
    // all while the cards are up — a tap there means "pick this terminal",
    // not "type into it".
    val keyBar = rememberTerminalKeyBarState(
        handle = tabs[activeIndex].handle,
        enabled = !isOpen,
    )
    val keyBarHeightPx = with(LocalDensity.current) { TerminalKeyBarHeight.toPx() }
    val pinchStepPx = with(LocalDensity.current) { PinchStepPerFontSize.toPx() }

    /** Puts the moving end of the selection under [to], in this Box's px. */
    fun moveSelectionEnd(to: Offset, bounds: Offset) {
        // Allowed a little past the top and bottom: that's how a selection is
        // dragged on into text that's scrolled out of view (see selectMove).
        val margin = keyBarHeightPx / 2
        val x = to.x.coerceIn(0f, bounds.x)
        val y = to.y.coerceIn(-margin, bounds.y + margin)
        selecting?.selectMove(x / density, y / density)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .terminalGestures(
                config = gestureConfig,
                // Starting a two-finger drag at the very top is Android's
                // quick-settings gesture and the system will take it from us,
                // so the switcher ignores anything that begins up there.
                topExclusionFraction = 0.12f,
                // The key bar handles its own touches (including the
                // one-finger page swipe); nothing that starts on it is ours.
                barTop = { height -> if (keyBar.onScreen) height - keyBarHeightPx else Float.MAX_VALUE },
                isSelecting = { selecting != null },
                progressAtStart = { progress.value },
                onSwitcherDrag = { startProgress, dy ->
                    scope.launch {
                        progress.snapTo((startProgress + dy / FULL_DRAG_PX).coerceIn(0f, 1f))
                    }
                },
                onSwitcherRelease = {
                    scope.launch { settle(if (progress.value > OPEN_THRESHOLD) 1f else 0f) }
                },
                // 切换器绑到离散槽位（长按/双击）时走这里：那种槽位给不出
                // 位移量，没有「跟手」可言，只能直接弹到完全打开。复用的还是
                // 同一个 settle，所以落地方式和拖拽松手后是一模一样的动画。
                onSwitcherOpen = { scope.launch { settle(1f) } },
                onPinchStart = { span -> pinchRefSpan = span },
                onPinch = { span, centroid ->
                    // One size per PinchStepPerFontSize of span, not a size per
                    // however many px the span happens to be worth: see that
                    // constant for why continuous tracking had to go. A fling
                    // of a pinch that crosses several thresholds in one event
                    // still moves one size per threshold, hence the division
                    // rather than a ±1.
                    val steps = ((span - pinchRefSpan) / pinchStepPx).toInt()
                    if (steps != 0) {
                        // Reset even when the clamp eats the step, so coming
                        // back off MIN/MAX costs one threshold and not all the
                        // ones spent pushing past it.
                        pinchRefSpan = span
                        val target = (activeHandle.fontSize + steps).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                        if (target != activeHandle.fontSize) {
                            activeHandle.setFontSize(target, commit = false, focusX = centroid.x / density)
                        }
                    }
                },
                onPinchEnd = { activeHandle.setFontSize(activeHandle.fontSize, commit = true) },
                // The buzz comes at the end of the hold, the action only at the
                // lift: see Touch.HOLD_ARMED for why the two are split.
                onHoldArmed = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                onMenu = { menuAt = it },
                // Pointer positions are relative to this Box, whose top-left is
                // also the WebView's (the pager is only inset at the bottom),
                // and a page px in the WebView is one dp.
                onSelectStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    selecting = activeHandle
                    activeHandle.selectBegin(it.x / density, it.y / density)
                },
                onSelectDrag = { at, bounds -> moveSelectionEnd(at, bounds) },
                onSelectEnd = {
                    val handle = selecting
                    selecting = null
                    handle?.selectEnd { text ->
                        if (text.isEmpty()) return@selectEnd
                        clipboard.setText(AnnotatedString(text))
                        // Android 13+ confirms clipboard writes itself.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, "已复制 ${text.length} 个字符", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                // 下面这几个动作在菜单里本来就有对应项（见 [TerminalMenu]），
                // 走的是同一条路：手势只是菜单之外的第二个入口，没有另起一套。
                onPaste = {
                    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let(activeHandle::paste)
                },
                onClearScreen = { activeHandle.clearScreen() },
                onScrollToBottom = { activeHandle.scrollToBottom() },
                // 状态来源和菜单里「虚拟大窗口」那一项是同一份：
                // TerminalHandle.virtualWindow（菜单每次打开都重新组合、读的也是
                // activeHandle）。菜单那项是切换，这里是单向幂等 —— 已经是目标
                // 状态就什么都不做，连 JS 都不发，所以连做两次同方向手势不会翻回去。
                onSetVirtualWindow = { on ->
                    if (activeHandle.virtualWindow != on) activeHandle.setVirtualWindow(on)
                },
                onOpenSftp = { onOpenSftp(activeTabIdNow) },
                onDisconnect = { onCloseTab(activeTabIdNow) },
                // 按键栏没有对应的菜单项，它的「在不在」就是 summoned 这一个
                // 旗标（见 rememberTerminalKeyBarState）。只翻这个旗标、不绕过
                // 它旁边的那几个条件：页面不在缓冲区底部、或软键盘开着的时候，
                // 按键栏的去留本来就不归用户的意愿管。
                onToggleKeyBar = {
                    keyBar.summoned = !keyBar.summoned
                    keyBar.noteActivity()
                },
                onReplayTap = { at -> replayTap(activeHandle.webView, at) },
            ),
    ) {
        val p = progress.value
        val scale = 1f - (1f - OPEN_SCALE) * p

        HorizontalPager(
            state = pagerState,
            // The one place the key bar is allowed to cost the terminal rows.
            // Inset rather than overlaid, because a bar drawn on top of live
            // output would hide the line the user is typing on — and inset
            // only at the two ends of its slide, never per frame, since this
            // page's doc explains what a mid-animation re-measure would do to
            // the remote pty.
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = keyBar.reservedHeight),
            beyondViewportPageCount = if (isOpen) 1 else 0,
            // Horizontal swipes belong to the terminal until the cards are
            // actually showing.
            userScrollEnabled = p > 0.5f,
        ) { page ->
            val tab = tabs[page]

            // How far this card has been flicked up, in pixels (always <= 0).
            // Keyed on the tab so a card keeps its own drag while the pager
            // recycles pages around it.
            val dismissY = remember(tab.id) { Animatable(0f) }

            // Cancelling the switcher mid-flick shouldn't leave the card parked
            // halfway off-screen when it reopens.
            LaunchedEffect(isOpen) { if (!isOpen) dismissY.snapTo(0f) }

            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationY = dismissY.value
                            // Fade out as it leaves, so a dismissal reads as the
                            // session going away rather than just scrolling off.
                            alpha = 1f - (abs(dismissY.value) / size.height).coerceIn(0f, 1f)
                            shape = RoundedCornerShape((16f * p).dp)
                            clip = true
                        },
                ) {
                    TerminalScreen(handle = tab.handle)
                }

                if (isOpen) {
                    // Swallow everything headed for the WebView while the cards
                    // are up: in card mode a touch means "pick this terminal",
                    // not "type into it".
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(page) {
                                detectTapGestures { chooseAndClose(page) }
                            }
                            .pointerInput(tab.id) {
                                // One finger up = disconnect this session. Down is
                                // ignored: that direction already means "cancel the
                                // switcher" via the two-finger gesture, and a card
                                // that slid downwards would have nowhere to go.
                                val dismissAt = size.height * DISMISS_FRACTION
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        scope.launch {
                                            dismissY.snapTo((dismissY.value + dragAmount).coerceAtMost(0f))
                                        }
                                    },
                                    onDragCancel = { scope.launch { dismissY.animateTo(0f) } },
                                    onDragEnd = {
                                        scope.launch {
                                            if (abs(dismissY.value) > dismissAt) {
                                                // Finish the throw before tearing the
                                                // session down, so the card isn't
                                                // yanked out from under the animation.
                                                dismissY.animateTo(
                                                    -size.height.toFloat(),
                                                    tween(durationMillis = 160),
                                                )
                                                onCloseTab(tab.id)
                                            } else {
                                                dismissY.animateTo(0f, tween(durationMillis = 180))
                                            }
                                        }
                                    },
                                )
                            },
                    )

                    // Every tab onto the same host carries the same name, so
                    // the label earns its place here in a way it doesn't in
                    // the tab row.
                    Text(
                        text = tab.title,
                        color = Color.White.copy(alpha = p),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                    )
                }
            }
        }

        // A sibling of the pager, not a child of it: the bar belongs to the
        // screen rather than to any one page, so it neither scales with the
        // cards nor scrolls away with a horizontal swipe.
        TerminalKeyBar(
            state = keyBar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        menuAt?.let { at ->
            TerminalMenu(
                anchor = at,
                handle = activeHandle,
                onDismiss = { menuAt = null },
                onPaste = { clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let(activeHandle::paste) },
                onOpenSftp = { onOpenSftp(tabs[activeIndex].id) },
                onDisconnect = { onCloseTab(tabs[activeIndex].id) },
            )
        }
    }
}

/**
 * The terminal menu (single-finger double tap / two-finger hold): the phone's stand-in for the desktop app's
 * right-click menu, trimmed to what makes sense on a touch screen. Copy isn't
 * here because selecting is its own gesture (long press, drag, tap) and
 * copies as it finishes.
 */
@Composable
private fun TerminalMenu(
    anchor: Offset,
    handle: TerminalHandle,
    onDismiss: () -> Unit,
    onPaste: () -> Unit,
    onOpenSftp: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // A zero-size box at the touch point, so the menu opens where the
    // fingers were rather than at the corner of the screen.
    Box(Modifier.offset { IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text("粘贴") },
                onClick = { onDismiss(); onPaste() },
            )
            DropdownMenuItem(
                text = { Text("清屏") },
                onClick = { onDismiss(); handle.clearScreen() },
            )
            DropdownMenuItem(
                text = { Text("滑至底部") },
                onClick = { onDismiss(); handle.scrollToBottom() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("字体还原") },
                trailingIcon = {
                    if (handle.fontSize != DEFAULT_FONT_SIZE) Text("${handle.fontSize} → $DEFAULT_FONT_SIZE")
                },
                enabled = handle.fontSize != DEFAULT_FONT_SIZE,
                onClick = { onDismiss(); handle.setFontSize(DEFAULT_FONT_SIZE, commit = true) },
            )
            DropdownMenuItem(
                text = { Text("虚拟大窗口（$VIRTUAL_WINDOW_COLS 列）") },
                trailingIcon = { if (handle.virtualWindow) Text("✓") },
                onClick = { onDismiss(); handle.setVirtualWindow(!handle.virtualWindow) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("SFTP 文件管理") },
                onClick = { onDismiss(); onOpenSftp() },
            )
            DropdownMenuItem(
                text = { Text("断开") },
                onClick = { onDismiss(); onDisconnect() },
            )
        }
    }
}

/**
 * 捏合换算成切换器拖拽距离的倍率。
 *
 * 只有把 [GestureAction.OPEN_SWITCHER] 绑到捏合上时才用得着：切换器量的是
 * 手指走了多少像素（[FULL_DRAG_PX]），捏合给的是 span —— 两指到质心的平均
 * 距离，也就是手指间距的一半。乘 4 之后，大约 150px 的 span 变化（手指靠拢
 * 300px）就能把切换器完全拉开，和一次舒服的捏合差不多；一比一的话要 600px
 * 的 span 变化，手机屏幕上根本做不出来。
 *
 * 方向现在由槽位决定：绑在「缩小」槽位上，捏紧（span 变小）得到正的位移量，
 * 也就是「把终端捏成一张卡片」；绑在「放大」槽位上则是撑开为正。反方向的位移
 * 在识别器里就被 0 下限吃掉（见 `along`），什么都不发生。
 */
private const val PINCH_SPAN_TO_DRAG = 4f

private enum class Touch {
    /** One finger that might yet become a long press. */
    PRESSING,
    /** One finger that is the terminal's: a tap, a scroll, a pan. */
    PASS,
    /**
     * A finger dragging the far end of a selection about.
     *
     * 单指长按（[GestureThresholds.oneFingerHoldMs]）进这个状态时，刻意**不**
     * 像 [HOLD_ARMED] 那样「先响、抬手才做」，哪怕误触的样子一模一样（手指在
     * 滑动前停了一秒，结果选中了一个词）。差别在于：双指长按的 hold 就是手势的
     * 全部，之后的任何移动都属于另一个手势，所以移动可以安全地解释成「不是
     * 它」；而这里 hold 只是手势的**开头**，不抬手继续拖就是拉长选区的正路，
     * 而且那个拖拽恰恰是从震动的那一刻开始的。任何「过了时限之后再移动就取消
     * 选区」的规则，都会把它本想补救的手势一起杀掉。误触本身也便宜 —— 震动就是
     * 告知，单击一下（[SELECTED_TAP]）就清掉 —— 而菜单那边的误触是一个模态
     * 弹窗，把手势剩下的部分全闷在后面。
     */
    SELECT_DRAG,
    /** One finger while a selection is up: a tap ends it, a drag extends it. */
    SELECTED_TAP,

    /**
     * 两根或三根手指按着，还没有判出是哪个手势（手指数见识别器里的 `fingers`）。
     * 这一阶段的事件一律消费，直到下面三种之一发生：判出方向（进
     * [SWITCHER]/[PINCH]/一次性动作后 [IGNORED]）、抬手、或者多出一根手指。
     */
    UNDECIDED,

    /**
     * 手指（两根或三根）静止按满了对应的长按时限，还没有走出 slop：此时抬手就
     * 执行 [GestureSlot.TWO_FINGER_HOLD] / [GestureSlot.THREE_FINGER_HOLD] 绑的
     * 动作（出厂是双指→菜单、三指→无），移动则不会。
     *
     * The menu used to open at the deadline itself, which made it terminal —
     * and at 800ms that is barely longer than the pause people take to aim
     * two fingers, so pausing before a pinch or a switcher drag opened the
     * menu and swallowed the rest of the gesture with nowhere to go back to.
     * So the deadline only buzzes ("lift now and you get it") and otherwise
     * leaves the arbitration exactly as [UNDECIDED] had it: the first
     * movement past slop picks pinch or swipe as usual and the hold's
     * action is simply never run. The stray buzz is the whole cost of a
     * pause.
     *
     * 这条规矩和绑的是哪个动作无关 —— 它要保住的是「手指还可能变成捏合或滑动」
     * 这件事，所以改绑之后照样是先响、抬手才做。三指同理（唯一的例外见识别器里
     * 的 `verdictLocked`：三指的滑动/捏合槽位没绑时，移动不取消长按，和改造前
     * 的三指长按一样）。
     */
    HOLD_ARMED,

    /** 跟手的滑动：切换器卡片随手指拉出。 */
    SWITCHER,

    /** 跟手的捏合：字号随 span 变化量逐档步进。 */
    PINCH,

    /** 这次手势剩下的部分一律消费、不再做任何事，直到手指全部抬起。 */
    IGNORED,
}

/**
 * 判出来的多指手势形状，和手指数无关。配上手指数才能查到槽位，见 [slotFor]。
 */
private enum class Shape { SWIPE_UP, SWIPE_DOWN, HORIZONTAL, PINCH_IN, PINCH_OUT }

/**
 * 手指数 + 形状 → 槽位。三指没有左右拖动这个槽位，返回 null（当作「没绑」）。
 */
private fun slotFor(fingers: Int, shape: Shape): GestureSlot? =
    if (fingers == 2) {
        when (shape) {
            Shape.SWIPE_UP -> GestureSlot.TWO_FINGER_SWIPE_UP
            Shape.SWIPE_DOWN -> GestureSlot.TWO_FINGER_SWIPE_DOWN
            Shape.HORIZONTAL -> GestureSlot.TWO_FINGER_DRAG_HORIZONTAL
            Shape.PINCH_IN -> GestureSlot.TWO_FINGER_PINCH_IN
            Shape.PINCH_OUT -> GestureSlot.TWO_FINGER_PINCH_OUT
        }
    } else {
        when (shape) {
            Shape.SWIPE_UP -> GestureSlot.THREE_FINGER_SWIPE_UP
            Shape.SWIPE_DOWN -> GestureSlot.THREE_FINGER_SWIPE_DOWN
            Shape.HORIZONTAL -> null
            Shape.PINCH_IN -> GestureSlot.THREE_FINGER_PINCH_IN
            Shape.PINCH_OUT -> GestureSlot.THREE_FINGER_PINCH_OUT
        }
    }

/**
 * 判「捏合」要求的 span 变化量，是质心位移的几倍。
 *
 * 旧判据只是 `spread > slop && spread > travel`，等于「只要 span 变化比质心位移
 * 多一丁点就算捏合」。两根手指竖直拖动时，两指速度/落点略有差异（尤其是一上一
 * 下排开的两指，各自的接触面积、压力、屏幕上报节奏都不一样）就会让 span 抖出
 * 几个像素，和质心位移同量级，一旦先过 slop 就被判成捏合 —— 于是「上拖」变成
 * 「字号变了」。要求 span 变化显著大于质心位移，把这一类抖动挡在捏合之外；
 * 真捏合的 span 变化是质心位移的好几倍，不受影响。
 */
private const val PINCH_DOMINANCE = 1.25f

/**
 * 两个量都过了 slop 却长期落在 [PINCH_DOMINANCE] 夹出来的灰区里（捏合和平移
 * 同量级）时的兜底：span 变化超过这么多个 slop 就按捏合判。没有它，一个恰好
 * 「边捏边移」的手势会一直不出结论。
 */
private const val PINCH_FORCE_SLOPS = 4f

/**
 * Every gesture on the terminal that isn't plain typing or scrolling, in one
 * recogniser:
 *
 * - two-finger swipe up / down, two-finger horizontal drag — by default only
 *   "down" is bound: the card switcher ([onSwitcherDrag] receives the progress
 *   the gesture started from plus the distance travelled *along the slot's
 *   direction*, so the same swipe opens it from 0 and, once it is open,
 *   dismisses it again by dragging back);
 * - two-finger pinch in / out — font size by default; [onPinch] gets a value
 *   that grows along the slot's direction, which the caller quantises into size
 *   steps;
 * - the same four shapes with **three** fingers (up: clear screen, down: scroll
 *   to bottom, pinch out / in: open / close the virtual wide window);
 * - two or three fingers held still for [GestureThresholds.twoFingerHoldMs] /
 *   [GestureThresholds.threeFingerHoldMs] and then lifted — the terminal menu
 *   (two fingers). The deadline only buzzes ([onHoldArmed]); the action waits
 *   for the lift, so fingers that pause to aim and then pinch or drag get the
 *   gesture they meant — see [Touch.HOLD_ARMED];
 * - one finger held still for [GestureThresholds.oneFingerHoldMs] — selects
 *   the word under it ([onSelectStart]); dragging that same finger on
 *   stretches the selection
 *   ([onSelectDrag]). While a selection is up ([isSelecting]) a one-finger tap
 *   ends it ([onSelectEnd]) and a one-finger drag moves its far end to the
 *   finger — [onSelectDrag] again, absolute. So one finger can't scroll until
 *   the selection has been finished with a tap, which is the deal: this used
 *   to be a three-finger slide, and MIUI's screenshot gesture swallows the
 *   touch stream the moment a third finger lands. Extra fingers while a
 *   selection is up are ignored outright rather than read as a pinch or a
 *   swipe.
 *
 * 上面这几条写的是**出厂绑定**。识别器本身只认槽位（[GestureSlot]）：判出是
 * 哪个槽位之后，做什么一律是 `config.actionFor(slot)` 查出来的，上面的对应
 * 关系就是 [GestureConfig.DEFAULT] 那张表。为什么非可配不可，见 GestureConfig.kt。
 *
 * ## 为什么槽位按「方向」拆，而不是「上下拖动」「捏合」各一个
 *
 * 旧模型里一个「双指上下拖动」槽位管两个方向，但切换器的进度是 0..1 的钳位量：
 * 从 0 起步时向上的位移被 0 下限吃掉，所以「上拖」在表面上就是「没反应」，
 * 只有下拖能把它拉开（见下面「已知根因」）。「捏合」同理，缩和放被迫共用一个
 * 动作。按方向拆开之后，每个槽位的位移量都是「沿这个方向」的非负量，方向和
 * 动作一一对应，用户想让上滑开切换器就绑上滑，不会再有哪个方向被钳位悄悄吃掉。
 *
 * ## 槽位的两类动作（[GestureAction.tracksGesture]）
 *
 * - 跟手动作（切换器、字号增减）：方向判出后进 [Touch.SWITCHER] / [Touch.PINCH]，
 *   每个事件喂一次沿槽位方向的位移，抬手收尾（[onSwitcherRelease] / [onPinchEnd]）。
 * - 一次性动作（清屏、滑至底部、开关虚拟大窗口、菜单……）：在「方向判出的
 *   那一刻」触发恰好一次，然后 [Touch.IGNORED] 把这次手势剩下的部分全消费掉。
 *   不等松手：这些手势没有「中途反悔」的语义，等松手只会让人觉得迟钝；也不会
 *   重复触发：IGNORED 没有出口，只有抬手。
 *
 * ## 手指数怎么定（两指 vs 三指）
 *
 * 第三根手指通常比前两根晚几十毫秒落下，所以不能在第二根落下时就把手指数定死。
 * 规则：**在还没判出方向（[Touch.UNDECIDED] / [Touch.HOLD_ARMED]）时，第三根
 * 手指落下就把这次手势升级成三指**，并把起点（质心、span、长按计时）整个重置到
 * 三指齐了的那一刻；**一旦已经判出方向（过了 slop）或一次性动作已触发，就不再升级，
 * 第三根手指被当作路人忽略**（不是参与者，不进质心和 span 的计算）。
 *
 * 没有另设毫秒级「汇合窗口」常量，而是借用 slop 当窗口：手指落下后要先走出
 * touchSlop（约 8dp，通常几十毫秒）才会有判定，而人落三根手指的间隔也是这个
 * 量级，两者天然同步；单独再开一个 100ms 之类的窗口，在「手指静止着等三指长按」
 * 的场景里反而会把合理的晚到第三指（旧逻辑里就是允许的）挡掉，破坏三指长按的
 * 既有行为。代价是：两指静止停了很久之后再落第三根手指也会升级成三指 ——
 * 这和改造前三指长按的行为一致，且此时本来也没有别的手势在进行。
 * 没有任何三指槽位（含长按）绑了东西时，不升级，第三指照旧走 IGNORED。
 *
 * 参与者在判出手势（或升级）的那一刻按指针 id 固定下来。之后：质心和 span 只用
 * 参与者算（第三根旁观手指不会让质心跳变）；**任何一个参与者抬起，手势立即结束**，
 * 不会降级成 N-1 指手势再触发别的东西。
 *
 * ## 捏合 vs 平移（以及「双指上拖不触发」）
 *
 * 位移一律是「当前相对起点」的直接量（质心位移、span 变化），而不是逐事件累加：
 * 抖动不会累积，也不依赖 `calculatePan`/`calculateZoom` 对「哪些指针在上一帧
 * 也按着」的判断（那个判断在第三根手指落下的那一帧会把它算进去）。判据见
 * [PINCH_DOMINANCE]。**已知根因**：旧代码里「上拖不开切换器」是一个确定的设计
 * 缺陷而不是识别错误 —— 切换器位移取 `pan.y`（向下为正），进度被 `coerceIn(0,1)`
 * 钳位，从 0 起步向上拖只会得到负进度 → 0，永远打不开。至于设备日志里「上拖后
 * 字号疑似变了」，没能在没有设备的前提下证实它的成因；[PINCH_DOMINANCE] 是
 * 针对「纵拖时 span 抖动被误判成捏合」这一最可能解释的防御，不是已验证的修复。
 *
 * ## 其它
 *
 * 槽位没绑（[GestureAction.NONE]）就完全跳过它的逻辑：两指方向判出但没绑 →
 * 进 [Touch.IGNORED]（和改造前一致）；三指方向判出但没绑 → 若绑了三指长按就保持
 * 等待（移动不取消长按，和改造前的三指长按一致，并锁定不再重新判方向），否则
 * IGNORED。三指的全部槽位都没绑时，行为和改造前完全一样。
 *
 * 切换器开着时（[progressAtStart] > 0）：双指的上滑/下滑永远是「拖回去关掉」
 * （不查表，留的逃生口）；其余双指手势、所有三指手势都不认。关闭方向与打开方向
 * 相反：只有「上滑」绑了切换器时，向下拖才是关闭，其余情况向上拖是关闭。
 *
 * **单指轻点和单指滑动不是槽位，不可配置**：它们必须原样放行给底下的
 * WebView，否则 xterm.js 的光标定位、软键盘、鼠标上报和 scrollback 滚动当场
 * 全失效（[GestureSlot] 的类注释讲了为什么这个限制写在 enum 里）。唯一的例外
 * 是单指双击启用时（现在默认启用）：那时第一下轻点必须先压住等第二下，见
 * [GestureSlot.ONE_FINGER_DOUBLE_TAP] 和 [replayTap]。
 *
 * One recogniser rather than one modifier per gesture because they all start
 * the same way and only diverge some way in: the first of "fingers spread",
 * "fingers travelled together", "time ran out" or "an extra finger arrived"
 * to happen decides which gesture this is, and the rest are ruled out for its
 * remainder.
 *
 * Handled on [PointerEventPass.Initial] because the thing underneath is a
 * WebView: xterm.js claims touches for its own scrolling and selection, and by
 * the Main pass the gesture is already gone. A single finger is left alone
 * until it is known to be one of ours — typing, scrolling and the virtual
 * window's pan all still reach the page. Consuming a change makes the
 * AndroidView interop send the WebView an ACTION_CANCEL, which is what keeps
 * a long press or a selection-ending tap from also landing as a tap on the
 * terminal (and toggling the soft keyboard).
 *
 * Anything starting at or below [barTop] (given this Box's height) belongs to
 * the key bar. While the switcher is open ([progressAtStart] > 0) only the
 * switcher drag is recognised: a pinch, menu or selection on a shrunken card
 * would act on a terminal the user is in the middle of choosing away from.
 */
private fun Modifier.terminalGestures(
    config: GestureConfig,
    topExclusionFraction: Float,
    barTop: (height: Float) -> Float,
    isSelecting: () -> Boolean,
    progressAtStart: () -> Float,
    onSwitcherDrag: (startProgress: Float, dy: Float) -> Unit,
    onSwitcherRelease: () -> Unit,
    onSwitcherOpen: () -> Unit,
    onPinchStart: (span: Float) -> Unit,
    onPinch: (span: Float, centroid: Offset) -> Unit,
    onPinchEnd: () -> Unit,
    onHoldArmed: () -> Unit,
    onMenu: (Offset) -> Unit,
    onSelectStart: (Offset) -> Unit,
    onSelectDrag: (at: Offset, bounds: Offset) -> Unit,
    onSelectEnd: () -> Unit,
    onPaste: () -> Unit,
    onClearScreen: () -> Unit,
    onScrollToBottom: () -> Unit,
    /** 单向、幂等：已经是目标状态就什么都不做（由调用方保证）。 */
    onSetVirtualWindow: (Boolean) -> Unit,
    onToggleKeyBar: () -> Unit,
    onOpenSftp: () -> Unit,
    onDisconnect: () -> Unit,
    /** 把一次压住没放的单指轻点补发给 WebView，见 [replayTap]。 */
    onReplayTap: (Offset) -> Unit,
    // config 进 key：改完绑定要立刻生效，而 pointerInput 只在 key 变了才重建
    // 识别器。GestureConfig 是 data class，内容没变就不会白白打断手势。
): Modifier = pointerInput(topExclusionFraction, config) {
    val slop = viewConfiguration.touchSlop
    val thresholds = config.thresholds

    // 几个要在热路径上反复问的绑定，先取出来：查表本身是 map 查找，而这些
    // 问题（这个 hold 的超时要不要挂？）每个事件都要回答一次。
    val oneFingerHold = config.actionFor(GestureSlot.ONE_FINGER_HOLD)
    val twoFingerHold = config.actionFor(GestureSlot.TWO_FINGER_HOLD)
    val threeFingerHold = config.actionFor(GestureSlot.THREE_FINGER_HOLD)
    val twoFingerDoubleTap = config.actionFor(GestureSlot.TWO_FINGER_DOUBLE_TAP)
    val oneFingerDoubleTap = config.actionFor(GestureSlot.ONE_FINGER_DOUBLE_TAP)

    // 三指有没有任何东西可做。全空就不升级：第三根手指照旧走 IGNORED，和
    // 改造前一致（改造前三指长按没绑时也是 IGNORED）。
    val threeFingerAvailable = threeFingerHold != GestureAction.NONE || listOf(
        GestureSlot.THREE_FINGER_SWIPE_UP,
        GestureSlot.THREE_FINGER_SWIPE_DOWN,
        GestureSlot.THREE_FINGER_PINCH_IN,
        GestureSlot.THREE_FINGER_PINCH_OUT,
    ).any { config.actionFor(it) != GestureAction.NONE }

    // 切换器是「往哪边拉开」：只有上滑绑了它（下滑没绑）才是向上拉开，其余
    // （含两个都绑、两个都没绑）按出厂的向下。已经开着时据此决定哪个方向算「关」。
    val switcherOpensUpward =
        config.actionFor(GestureSlot.TWO_FINGER_SWIPE_UP) == GestureAction.OPEN_SWITCHER &&
            config.actionFor(GestureSlot.TWO_FINGER_SWIPE_DOWN) != GestureAction.OPEN_SWITCHER

    /**
     * 跑一个一次性动作 / 离散槽位上绑着的动作。[at] 是手势落点（菜单在那儿弹出、
     * 选区从那儿开始），长按取的是手指停住时的质心而不是之后漂到哪。
     *
     * 这里一条都没有自己实现：粘贴、清屏、滑至底部、SFTP、断开在 [TerminalMenu]
     * 里本来就各有一项，虚拟大窗口菜单里是切换项、这里拆成单向的开/关，手势只是
     * 它们的第二个入口，走的是同一个回调、同一份状态。
     */
    fun perform(action: GestureAction, at: Offset) {
        when (action) {
            GestureAction.NONE -> {}
            GestureAction.OPEN_MENU -> onMenu(at)
            GestureAction.OPEN_SWITCHER -> onSwitcherOpen()
            GestureAction.START_SELECTION -> onSelectStart(at)
            GestureAction.PASTE -> onPaste()
            GestureAction.CLEAR_SCREEN -> onClearScreen()
            GestureAction.SCROLL_TO_BOTTOM -> onScrollToBottom()
            GestureAction.ENABLE_WIDE_WINDOW -> onSetVirtualWindow(true)
            GestureAction.DISABLE_WIDE_WINDOW -> onSetVirtualWindow(false)
            GestureAction.TOGGLE_KEY_BAR -> onToggleKeyBar()
            GestureAction.OPEN_SFTP -> onOpenSftp()
            GestureAction.DISCONNECT -> onDisconnect()
            // 字号增减只在捏合槽位上以跟手方式进行（见 PINCH 分支），isAllowedOn
            // 不让它绑到别处；写成空分支而不是 error()，是因为手势是进了终端之后
            // 唯一的交互方式，为一个上层的 bug 把 app 崩在这里不划算。
            GestureAction.FONT_SIZE_UP, GestureAction.FONT_SIZE_DOWN -> {}
        }
    }

    // 上一次「双指轻点」抬手的时刻。跨手势存在，所以声明在 awaitEachGesture
    // 外面：第二下双指落下时回头看一眼这个时刻就够了，不用像单指双击那样压住
    // 第一下等着 —— 双指的那一下本来就整段被消费掉，不欠 WebView 任何东西。
    var lastTwoFingerTapAt = 0L

    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val bounds = Offset(size.width.toFloat(), size.height.toFloat())

        if (first.position.y >= barTop(bounds.y)) {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            return@awaitEachGesture
        }

        val selecting = isSelecting()
        val pressedAt = SystemClock.uptimeMillis()
        var mode = when {
            selecting -> Touch.SELECTED_TAP
            progressAtStart() == 0f -> Touch.PRESSING
            else -> Touch.PASS
        }
        var lastPos = first.position
        var dragFrom = Offset.Zero
        var dragging = false

        var startProgress = 0f
        var startedAt = 0L

        // ── 多指手势的状态 ──
        // 参与者：多指手势开始（或升级到三指）时落着的那几根手指，按指针 id 固定。
        // 质心、span、「有没有手指抬起」都只看它们，之后落下的旁观手指不算。
        var ids: Set<PointerId> = emptySet()
        // 参与者数量：2 或 3。
        var fingers = 2
        // 三指判出了方向但那个槽位没绑、又绑了三指长按：锁定「不再判方向」，
        // 继续等长按。见上面的 KDoc。
        var verdictLocked = false
        var startCentroid = Offset.Zero
        var startSpan = 0f
        var centroid = Offset.Zero
        var curSpan = 0f

        // 这次多指手势最后判成了哪个槽位。决定下面的位移量怎么算 —— 同一个
        // [Touch.SWITCHER] 可能是拖出来的，也可能是捏出来的。
        var activeSlot: GestureSlot? = null
        // 字号动作的方向：FONT_SIZE_UP 取 +1，DOWN 取 -1（方向由动作定，不由手势定）。
        var fontSign = 1f

        /** 开始（或升级成）一个多指手势：把起点整个重置到 [down] 这几根手指上。 */
        fun beginMulti(down: List<PointerInputChange>) {
            ids = down.mapTo(HashSet()) { it.id }
            fingers = down.size
            startedAt = SystemClock.uptimeMillis()
            // From `down` rather than calculateCentroid(): that only counts
            // pointers that were already down on the previous event, which
            // leaves out the finger that just landed.
            startCentroid = down.centroid()
            startSpan = down.span(startCentroid)
            centroid = startCentroid
            curSpan = startSpan
            verdictLocked = false
            activeSlot = null
        }

        /** 按参与者刷新当前质心和 span。 */
        fun measure(part: List<PointerInputChange>) {
            centroid = part.centroid()
            curSpan = part.span(centroid)
        }

        /**
         * 沿 [slot] 方向的位移量，≥0（反方向被 0 下限吃掉），契约见 GestureConfig.kt
         * 开头：上滑 = -dy，下滑 = dy，放大 = span - startSpan，缩小 = startSpan - span。
         * 双指左右拖动是双向的，保留符号（切换器的进度自己会钳位）。
         */
        fun along(slot: GestureSlot): Float {
            val dx = centroid.x - startCentroid.x
            val dy = centroid.y - startCentroid.y
            val ds = curSpan - startSpan
            return when (slot) {
                GestureSlot.TWO_FINGER_SWIPE_UP, GestureSlot.THREE_FINGER_SWIPE_UP -> (-dy).coerceAtLeast(0f)
                GestureSlot.TWO_FINGER_SWIPE_DOWN, GestureSlot.THREE_FINGER_SWIPE_DOWN -> dy.coerceAtLeast(0f)
                GestureSlot.TWO_FINGER_PINCH_OUT, GestureSlot.THREE_FINGER_PINCH_OUT -> ds.coerceAtLeast(0f)
                GestureSlot.TWO_FINGER_PINCH_IN, GestureSlot.THREE_FINGER_PINCH_IN -> (-ds).coerceAtLeast(0f)
                GestureSlot.TWO_FINGER_DRAG_HORIZONTAL -> dx
                else -> 0f
            }
        }

        /** 喂给切换器的拖拽量：像素，向「打开」的方向为正（和 [FULL_DRAG_PX] 同一把尺）。 */
        fun switcherDy(): Float {
            if (startProgress != 0f) {
                // 已经开着：双指竖直拖动永远是「拖回去关掉」，打开方向的反面是负。
                val vy = centroid.y - startCentroid.y
                return if (switcherOpensUpward) -vy else vy
            }
            val slot = activeSlot ?: return 0f
            val d = along(slot)
            return when (slot) {
                GestureSlot.TWO_FINGER_PINCH_IN, GestureSlot.TWO_FINGER_PINCH_OUT,
                GestureSlot.THREE_FINGER_PINCH_IN, GestureSlot.THREE_FINGER_PINCH_OUT,
                -> d * PINCH_SPAN_TO_DRAG
                else -> d
            }
        }

        /**
         * 喂给字号的「跨度」。调用方只用它的差值（见 [PinchStepPerFontSize]），
         * 所以不必是真的 span，只要尺子一致、方向一致：沿槽位方向的位移乘上
         * 动作决定的符号，FONT_SIZE_UP 越拉越大、FONT_SIZE_DOWN 越拉越小。
         */
        fun fontSpan(): Float = fontSign * (activeSlot?.let { along(it) } ?: 0f)

        while (true) {
            // The holds have to be able to complete without an event:
            // fingers resting perfectly still may send nothing at all.
            // 空着的槽位压根不挂超时：既省了一次没人要的唤醒，也让「解绑单指
            // 长按」自然退化成「这根手指一直是终端的」。
            val deadline = when {
                mode == Touch.PRESSING && oneFingerHold != GestureAction.NONE ->
                    pressedAt + thresholds.oneFingerHoldMs
                mode == Touch.UNDECIDED && startProgress == 0f && fingers == 2 &&
                    twoFingerHold != GestureAction.NONE ->
                    startedAt + thresholds.twoFingerHoldMs
                mode == Touch.UNDECIDED && startProgress == 0f && fingers == 3 &&
                    threeFingerHold != GestureAction.NONE ->
                    startedAt + thresholds.threeFingerHoldMs
                else -> null
            }
            val event = if (deadline == null) {
                awaitPointerEvent(PointerEventPass.Initial)
            } else {
                val left = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Initial) }
            }

            if (event == null) {
                when (mode) {
                    Touch.PRESSING -> {
                        if (oneFingerHold == GestureAction.START_SELECTION) {
                            // 选区要接着用这根手指（拖拽拉长选区就是从震动那
                            // 一刻开始的，见 Touch.SELECT_DRAG）。The WebView
                            // still thinks the finger is down; the next event
                            // is consumed, which cancels it there.
                            mode = Touch.SELECT_DRAG
                            dragFrom = lastPos
                            onSelectStart(lastPos)
                        } else {
                            // 别的动作做完就跟这根手指没关系了，但剩下的事件
                            // 还是要吞掉：页面那边已经收了整整一个长按时限的
                            // 按压，不给它 ACTION_CANCEL 的话抬手会再落一次
                            // 光标定位。这和改造前长按必定进 SELECT_DRAG（同样
                            // 从下一个事件起消费）是同一种收尾。
                            perform(oneFingerHold, lastPos)
                            mode = Touch.IGNORED
                        }
                    }
                    // Buzz only: the action waits for the lift, and the fingers
                    // can still turn out to be a pinch or a swipe. Fires
                    // exactly once per touch because HOLD_ARMED has no
                    // deadline of its own and nothing re-enters UNDECIDED
                    // except a third finger arriving, which restarts the clock
                    // for the three-finger hold on purpose.
                    Touch.UNDECIDED -> {
                        mode = Touch.HOLD_ARMED
                        onHoldArmed()
                    }
                    else -> {}
                }
                continue
            }

            val down = event.changes.filter { it.pressed }
            if (down.size == 1) lastPos = down[0].position

            // A second finger turns any one-finger state into a multi-touch
            // gesture — except a selection drag, which keeps its finger.
            if (down.size >= 2) when (mode) {
                // While a selection is up there are no multi-finger gestures
                // at all: a pinch or a swipe acting on the terminal the
                // user is halfway through selecting in is never what was
                // meant, and the fingers are as likely to be a fumbled grab
                // at the selection as anything.
                Touch.SELECTED_TAP -> mode = Touch.IGNORED

                Touch.PRESSING, Touch.PASS -> {
                    startProgress = progressAtStart()
                    beginMulti(down)
                    mode = when {
                        // 四根及以上：从来没有手势要它们。
                        down.size > 3 -> Touch.IGNORED
                        // 三根齐落（少见，通常是两根先落）：和晚到的第三根同一条
                        // 规则 —— 没东西可做，或切换器开着，就不认。
                        down.size == 3 && (!threeFingerAvailable || startProgress != 0f) -> Touch.IGNORED
                        else -> Touch.UNDECIDED
                    }

                    // 双指双击的第二下。判在「第二下落下」而不是「第二下抬手」：
                    // 第一下整段都已经被消费掉了（UNDECIDED 一进去就不放行），
                    // 所以这里晚判一点不欠谁的，而落下就触发手感最跟手。
                    if (mode == Touch.UNDECIDED && fingers == 2 &&
                        twoFingerDoubleTap != GestureAction.NONE &&
                        startedAt - lastTwoFingerTapAt <= thresholds.twoFingerDoubleTapWindowMs
                    ) {
                        lastTwoFingerTapAt = 0L
                        perform(twoFingerDoubleTap, startCentroid)
                        mode = Touch.IGNORED
                    }
                }

                else -> {}
            }

            // 参与者。多指手势开始之前 ids 为空，part 就是全部按着的手指。
            val part = if (ids.isEmpty()) down else down.filter { it.id in ids }

            when (mode) {
                Touch.PRESSING -> {
                    if (down.isNotEmpty() && (lastPos - first.position).getDistance() > slop) {
                        mode = Touch.PASS
                    }
                }

                Touch.PASS -> {}

                Touch.SELECTED_TAP -> {
                    if (down.isEmpty()) {
                        // Lifted without wandering off: that's the tap that
                        // ends the selection — and only that, not a tap on
                        // the terminal as well.
                        event.changes.forEach(PointerInputChange::consume)
                        onSelectEnd()
                    } else if ((lastPos - first.position).getDistance() > slop) {
                        // Wandered off instead: that's the selection's far end
                        // being dragged to the finger. Absolute, not a
                        // trackpad nudge — the finger is the handle. This
                        // costs one-finger scrolling for as long as the
                        // selection is up, on purpose: the tap that finishes
                        // it is one tap away, and the three-finger slide this
                        // replaces is unreachable on MIUI, whose screenshot
                        // gesture takes the touch stream with the third
                        // finger.
                        mode = Touch.SELECT_DRAG
                        dragging = true
                        // Consume from here on, as SELECT_DRAG does: the
                        // WebView has had the moves so far and needs the
                        // ACTION_CANCEL to stop scrolling along with us.
                        event.changes.forEach(PointerInputChange::consume)
                        onSelectDrag(lastPos, bounds)
                    }
                }

                Touch.SELECT_DRAG -> {
                    event.changes.forEach(PointerInputChange::consume)
                    if (down.isNotEmpty()) {
                        if (!dragging && (lastPos - dragFrom).getDistance() > slop) dragging = true
                        if (dragging) onSelectDrag(lastPos, bounds)
                    }
                }

                // One branch for both because an armed hold is still an
                // undecided gesture: it arbitrates identically, and every
                // arbitration below that reaches a verdict is also what
                // disarms it.
                Touch.UNDECIDED, Touch.HOLD_ARMED -> {
                    // From here on nothing reaches the WebView.
                    event.changes.forEach(PointerInputChange::consume)
                    when {
                        // A finger lifting rules out everything still open.
                        // If the hold armed, this is the lift that runs it —
                        // at the centroid the fingers were held at, which is
                        // where the user was aiming, not wherever they drifted
                        // to since. 同样不降级：三指抬起一根不会变成双指手势。
                        part.size < fingers -> {
                            if (mode == Touch.HOLD_ARMED) {
                                perform(if (fingers == 3) threeFingerHold else twoFingerHold, startCentroid)
                            } else if (fingers == 2 && twoFingerDoubleTap != GestureAction.NONE) {
                                // 还在 UNDECIDED 就抬手 = 没到长按时限、也没走出
                                // slop（走出了早就转去 SWITCHER/PINCH/IGNORED 了），
                                // 那就是一次双指轻点。记下时刻，等下一对手指。
                                lastTwoFingerTapAt = SystemClock.uptimeMillis()
                            }
                            mode = Touch.IGNORED
                        }

                        // 多出一根手指。两指 + 第三根（且没判出方向）= 升级成三指，
                        // 起点、长按计时整个重置，这也解除两指长按的待发状态；
                        // 其余（第四根、三指没东西可做、切换器开着）都是路人手，
                        // 放弃整个手势，而不是让它被解读成什么：A stray extra finger
                        // is likelier to be the heel of a hand than intent. 它也不是
                        // 抬手，所以不触发长按。
                        down.size > fingers -> {
                            if (fingers == 2 && down.size == 3 && threeFingerAvailable && startProgress == 0f) {
                                beginMulti(down)
                                mode = Touch.UNDECIDED
                            } else {
                                mode = Touch.IGNORED
                            }
                        }

                        else -> {
                            measure(part)
                            if (!verdictLocked) {
                                // 直接量（相对起点），不是逐事件累加：抖动不累积。
                                val offset = centroid - startCentroid
                                val travel = offset.getDistance()
                                val change = curSpan - startSpan
                                val spread = abs(change)
                                // 先判是哪个形状，再查槽位、再查这个槽位绑了什么 ——
                                // 判定本身（谁先过 slop）和绑定无关，一个字没动。
                                // 平移要 spread <= travel，捏合要明显压过平移（见
                                // PINCH_DOMINANCE）；两者之间的灰区继续等，直到有一边
                                // 拉开，或 span 变化大到 PINCH_FORCE_SLOPS 个 slop 兜底。
                                val shape: Shape? = when {
                                    spread > slop && spread > travel * PINCH_DOMINANCE ->
                                        if (change > 0f) Shape.PINCH_OUT else Shape.PINCH_IN
                                    travel > slop && spread <= travel ->
                                        if (abs(offset.y) >= abs(offset.x)) {
                                            if (offset.y < 0f) Shape.SWIPE_UP else Shape.SWIPE_DOWN
                                        } else {
                                            Shape.HORIZONTAL
                                        }
                                    spread > slop * PINCH_FORCE_SLOPS ->
                                        if (change > 0f) Shape.PINCH_OUT else Shape.PINCH_IN
                                    else -> null
                                }
                                if (shape != null) {
                                    val slot = slotFor(fingers, shape)
                                    activeSlot = slot
                                    val action = when {
                                        slot == null -> GestureAction.NONE
                                        // 切换器已经开着的时候，双指竖直滑动永远是
                                        // 「拖回去关掉」，不查表。这是故意留的逃生口：
                                        // 把这个槽位改绑走的用户，开着的切换器就只剩
                                        // 点卡片一条路，而拖拽恰好是他已经做出来的
                                        // 那个动作。
                                        startProgress != 0f &&
                                            (slot == GestureSlot.TWO_FINGER_SWIPE_UP ||
                                                slot == GestureSlot.TWO_FINGER_SWIPE_DOWN) ->
                                            GestureAction.OPEN_SWITCHER
                                        // 其余情况下，切换器开着时一律不认：捏合、
                                        // 菜单、选区作用在一个正要被切走的终端上，
                                        // 从来不是用户的意思。
                                        startProgress != 0f -> GestureAction.NONE
                                        else -> config.actionFor(slot)
                                    }
                                    when {
                                        action == GestureAction.NONE -> {
                                            // 没绑：完全跳过。两指 → 和改造前一样吞掉
                                            // 这次手势；三指且绑了长按 → 保持等待长按
                                            // （改造前三指移动不取消长按），锁定不再判。
                                            if (fingers == 3 && threeFingerHold != GestureAction.NONE) {
                                                verdictLocked = true
                                            } else {
                                                mode = Touch.IGNORED
                                            }
                                        }
                                        action.tracksGesture -> when (action) {
                                            GestureAction.OPEN_SWITCHER -> {
                                                // Opening has to dodge the system's
                                                // quick-settings gesture near the top edge;
                                                // an open switcher can be dismissed from
                                                // anywhere.
                                                val inExclusion = startProgress == 0f &&
                                                    startCentroid.y < size.height * topExclusionFraction
                                                mode = if (inExclusion) Touch.IGNORED else Touch.SWITCHER
                                            }
                                            GestureAction.FONT_SIZE_UP, GestureAction.FONT_SIZE_DOWN -> {
                                                fontSign = if (action == GestureAction.FONT_SIZE_UP) 1f else -1f
                                                mode = Touch.PINCH
                                                // 以判出那一刻的值为参考：走出 slop 的那段位移
                                                // 不计入字号，和改造前一致。
                                                onPinchStart(fontSpan())
                                            }
                                            else -> mode = Touch.IGNORED
                                        }
                                        else -> {
                                            // 一次性动作：方向判出的这一刻触发一次，
                                            // 剩下的部分 IGNORED 消费掉。
                                            perform(action, startCentroid)
                                            mode = Touch.IGNORED
                                        }
                                    }
                                }
                                when (mode) {
                                    Touch.SWITCHER -> onSwitcherDrag(startProgress, switcherDy())
                                    Touch.PINCH -> onPinch(fontSpan(), centroid)
                                    else -> {}
                                }
                            }
                        }
                    }
                }

                Touch.SWITCHER -> {
                    event.changes.forEach(PointerInputChange::consume)
                    if (part.size < fingers) {
                        onSwitcherRelease()
                        mode = Touch.IGNORED
                    } else {
                        measure(part)
                        onSwitcherDrag(startProgress, switcherDy())
                    }
                }

                Touch.PINCH -> {
                    event.changes.forEach(PointerInputChange::consume)
                    if (part.size < fingers) {
                        onPinchEnd()
                        mode = Touch.IGNORED
                    } else {
                        // Measured from the fingers rather than accumulated
                        // out of per-event ratios: the caller steps on an
                        // absolute value, and a product of ratios would drift
                        // over a long pinch.
                        measure(part)
                        onPinch(fontSpan(), centroid)
                    }
                }

                Touch.IGNORED -> event.changes.forEach(PointerInputChange::consume)
            }

            if (down.isEmpty()) {
                // 单指轻点抬手。单指双击没启用时到此为止：一下都没消费过，这一点
                // 原样落到 xterm.js 上（光标定位、唤起软键盘、vim/tmux 的鼠标上报
                // 全靠它）。下面那套在单指双击启用时（现在默认）跑 —— 它让
                // **每一次**点击都迟钝一个双击窗口，代价和取舍见
                // GestureSlot.ONE_FINGER_DOUBLE_TAP。
                if (mode == Touch.PRESSING && config.isOneFingerDoubleTapEnabled) {
                    val tapAt = lastPos
                    // 先压住：消费抬手这一下，interop 会给 WebView 一个
                    // ACTION_CANCEL，页面那边等于这次触摸没发生过。
                    event.changes.forEach(PointerInputChange::consume)
                    if (awaitSecondDown(thresholds.oneFingerDoubleTapWindowMs)) {
                        perform(oneFingerDoubleTap, tapAt)
                        // 第二下整段吞掉：动作已经做了，再让它落到页面上就会
                        // 多出一次光标定位。
                        do {
                            val rest = awaitPointerEvent(PointerEventPass.Initial)
                            rest.changes.forEach(PointerInputChange::consume)
                        } while (rest.changes.any { it.pressed })
                    } else {
                        // 不是双击，把压住的那一下原封不动补给 WebView。
                        onReplayTap(tapAt)
                    }
                }
                break
            }
        }
    }
}

/**
 * 第一下轻点已经抬手，等 [windowMs] 看有没有第二下按下。
 *
 * 在同一个 `awaitEachGesture` 块里接着等，而不是另起协程：手指全抬起来之后
 * 事件照样送到这里（Compose 自己的 `detectTapGestures` 认双击用的就是这个
 * 办法），省掉一套「谁来取消这个延时任务」的生命周期。
 *
 * 等待期间的事件一律消费：这段时间本来就属于「可能是双击的后半截」。
 */
private suspend fun AwaitPointerEventScope.awaitSecondDown(windowMs: Long): Boolean {
    val deadline = SystemClock.uptimeMillis() + windowMs
    while (true) {
        val left = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        val event = withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Initial) }
            ?: return false
        event.changes.forEach(PointerInputChange::consume)
        if (event.changes.any { it.pressed }) return true
    }
}

/**
 * 把一次被压住的单指轻点补发给 [webView]。
 *
 * ## 为什么是合成事件，而不是「晚点再放行」
 *
 * Compose 的指针事件没有「暂缓」这一档：一个事件在 [PointerEventPass.Initial]
 * 这一趟要么不消费（当场穿到底下的 WebView），要么消费（interop 立刻补一个
 * ACTION_CANCEL 过去），没有第三种，更没有「先扣下、300ms 后再放行」的 API。
 * 所以开了单指双击之后，第一下的抬手只能先消费掉 —— 页面收到的是
 * touchstart + touchcancel，等于什么都没发生 —— 确认不是双击之后，再由这里
 * 原地补一对 DOWN/UP 上去。不补的话点击定位和软键盘就全坏了。
 *
 * 坐标不用换算：手势 Box 的左上角就是 WebView 的左上角（pager 只在底部留白），
 * 而且单指轻点这条路径只在切换器关着时存在，那时卡片的缩放是 1。
 *
 * 用带 PointerProperties 的那个 obtain 重载而不是五参数的简版，是为了把
 * source 和 toolType 写实：Chromium 侧要靠它们决定这串 MotionEvent 该不该
 * 翻译成页面的 touch 事件，简版留下的是 SOURCE_UNKNOWN / TOOL_TYPE_UNKNOWN。
 */
private fun replayTap(webView: WebView, at: Offset) {
    val props = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        },
    )
    val coords = arrayOf(
        MotionEvent.PointerCoords().apply {
            x = at.x
            y = at.y
            pressure = 1f
            size = 1f
        },
    )
    // 用「现在」而不是原来那一下的时刻：真按下的时间已经过去一个双击窗口了，
    // 照搬会让这一对事件看起来像一次长按。
    val downAt = SystemClock.uptimeMillis()

    fun send(action: Int, eventTime: Long) {
        val event = MotionEvent.obtain(
            downAt, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        webView.dispatchTouchEvent(event)
        event.recycle()
    }

    send(MotionEvent.ACTION_DOWN, downAt)
    send(MotionEvent.ACTION_UP, downAt + 1)
}

private fun List<PointerInputChange>.centroid(): Offset =
    fold(Offset.Zero) { acc, c -> acc + c.position } / size.toFloat()

/** Mean distance of the fingers from [centroid] — what a pinch changes. */
private fun List<PointerInputChange>.span(centroid: Offset): Float =
    sumOf { (it.position - centroid).getDistance().toDouble() }.toFloat() / size
