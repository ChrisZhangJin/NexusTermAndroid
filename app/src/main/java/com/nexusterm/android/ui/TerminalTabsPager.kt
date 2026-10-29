package com.nexusterm.android.ui

import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.input.pointer.PointerEventPass
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
 * A two-finger drag down anywhere in the terminal shrinks it into a card and
 * reveals the other live sessions either side of it; swipe left/right to
 * choose one, tap it to switch, flick it up to disconnect it, or two-finger
 * drag back up to cancel.
 *
 * There is no permanent tab strip — the cards carry the session names — so the
 * gesture stays live even at a single tab, alongside the menu's "断开".
 *
 * The terminal's other gestures live here too, because they share the
 * switcher's opening move and have to be told apart from it (see
 * [terminalGestures]): pinch to change the font size, a two-finger hold for
 * the terminal menu, and selection — one-finger long press, drag to stretch,
 * tap to copy.
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
    val density = LocalDensity.current.density
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Where the two-finger-hold menu is anchored, or null when it's closed.
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
                // The buzz comes at the end of the hold, the menu only at the
                // lift: see Touch.MENU_ARMED for why the two are split.
                onMenuArmed = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
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
 * The two-finger-hold menu: the phone's stand-in for the desktop app's
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

/** How long two fingers have to rest, unmoving, to arm the menu. */
private const val MENU_HOLD_MS = 800L

/**
 * How long one finger has to rest to start selecting.
 *
 * Deliberately *not* arm-then-commit the way [MENU_HOLD_MS] is, even though
 * the misfire is the same shape (a finger parked for a second before a scroll
 * selects a word instead). For the menu the hold is the whole gesture and any
 * movement after it belongs to a different one, so movement can safely mean
 * "not the menu". Here the hold is only the *start* of the gesture: dragging
 * on without lifting is the documented way to stretch the selection, and that
 * drag begins the instant the buzz is felt. Any rule that let post-deadline
 * movement cancel the selection would kill the gesture it was meant to
 * rescue. The misfire is cheap anyway — the buzz says it happened and a
 * single tap (Touch.SELECTED_TAP) clears it — where the menu's was a modal
 * popup with the rest of the gesture swallowed behind it.
 */
private const val SELECT_HOLD_MS = 800L

private enum class Touch {
    /** One finger that might yet become a long press. */
    PRESSING,
    /** One finger that is the terminal's: a tap, a scroll, a pan. */
    PASS,
    /** A finger dragging the far end of a selection about. */
    SELECT_DRAG,
    /** One finger while a selection is up: a tap ends it, a drag extends it. */
    SELECTED_TAP,
    UNDECIDED,
    /**
     * Two fingers that have rested [MENU_HOLD_MS] without moving: lifting them
     * now opens the menu, moving them still doesn't.
     *
     * The menu used to open at the deadline itself, which made it terminal —
     * and at 800ms that is barely longer than the pause people take to aim
     * two fingers, so pausing before a pinch or a switcher drag opened the
     * menu and swallowed the rest of the gesture with nowhere to go back to.
     * So the deadline only buzzes ("lift now and you get the menu") and
     * otherwise leaves the arbitration below exactly as [UNDECIDED] had it:
     * the first movement past slop picks pinch or switcher as usual and the
     * menu is simply never opened. The stray buzz is the whole cost of a
     * pause.
     */
    MENU_ARMED,
    SWITCHER,
    PINCH,
    IGNORED,
}

/**
 * Every gesture on the terminal that isn't plain typing or scrolling, in one
 * recogniser:
 *
 * - two-finger vertical drag — the card switcher. [onSwitcherDrag] receives
 *   the progress the gesture started from plus the distance travelled, so the
 *   same gesture opens it (dragging down from 0) and dismisses it (dragging
 *   up from 1);
 * - two-finger pinch — font size; [onPinch] gets the fingers' current span,
 *   which the caller quantises into size steps;
 * - two fingers held still for [MENU_HOLD_MS] and then lifted — the terminal
 *   menu. The deadline only buzzes ([onMenuArmed]); the menu itself waits for
 *   the lift ([onMenu]), so fingers that pause to aim and then pinch or drag
 *   get the gesture they meant and not a menu — see [Touch.MENU_ARMED];
 * - one finger held still for [SELECT_HOLD_MS] — selects the word under it
 *   ([onSelectStart]); dragging that same finger on stretches the selection
 *   ([onSelectDrag]). While a selection is up ([isSelecting]) a one-finger tap
 *   ends it ([onSelectEnd]) and a one-finger drag moves its far end to the
 *   finger — [onSelectDrag] again, absolute. So one finger can't scroll until
 *   the selection has been finished with a tap, which is the deal: this used
 *   to be a three-finger slide, and MIUI's screenshot gesture swallows the
 *   touch stream the moment a third finger lands. Extra fingers while a
 *   selection is up are ignored outright rather than read as a pinch or the
 *   switcher.
 *
 * One recogniser rather than one modifier per gesture because they all start
 * the same way and only diverge some way in: the first of "fingers spread",
 * "fingers travelled together", "time ran out" or "a third finger arrived" to
 * happen decides which gesture this is (the last of those decides on
 * "nothing"), and the rest are ruled out for its remainder.
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
    topExclusionFraction: Float,
    barTop: (height: Float) -> Float,
    isSelecting: () -> Boolean,
    progressAtStart: () -> Float,
    onSwitcherDrag: (startProgress: Float, dy: Float) -> Unit,
    onSwitcherRelease: () -> Unit,
    onPinchStart: (span: Float) -> Unit,
    onPinch: (span: Float, centroid: Offset) -> Unit,
    onPinchEnd: () -> Unit,
    onMenuArmed: () -> Unit,
    onMenu: (Offset) -> Unit,
    onSelectStart: (Offset) -> Unit,
    onSelectDrag: (at: Offset, bounds: Offset) -> Unit,
    onSelectEnd: () -> Unit,
): Modifier = pointerInput(topExclusionFraction) {
    val slop = viewConfiguration.touchSlop

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
        var startCentroid = Offset.Zero
        var startSpan = 0f
        var pan = Offset.Zero
        var zoom = 1f
        var centroid = Offset.Zero

        while (true) {
            // The two holds have to be able to complete without an event:
            // fingers resting perfectly still may send nothing at all.
            val deadline = when {
                mode == Touch.PRESSING -> pressedAt + SELECT_HOLD_MS
                mode == Touch.UNDECIDED && startProgress == 0f -> startedAt + MENU_HOLD_MS
                else -> null
            }
            val event = if (deadline == null) {
                awaitPointerEvent(PointerEventPass.Initial)
            } else {
                val left = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Initial) }
            }

            if (event == null) {
                if (mode == Touch.PRESSING) {
                    // The WebView still thinks the finger is down; the next
                    // event is consumed, which cancels it there.
                    mode = Touch.SELECT_DRAG
                    dragFrom = lastPos
                    onSelectStart(lastPos)
                } else {
                    // Buzz only: the menu waits for the lift, and the fingers
                    // can still turn out to be a pinch or the switcher. Fires
                    // exactly once per touch because MENU_ARMED has no
                    // deadline of its own and nothing re-enters UNDECIDED.
                    mode = Touch.MENU_ARMED
                    onMenuArmed()
                }
                continue
            }

            val down = event.changes.filter { it.pressed }
            if (down.size == 1) lastPos = down[0].position

            // A second finger turns any one-finger state into a multi-touch
            // gesture — except a selection drag, which keeps its finger.
            if (down.size >= 2) when (mode) {
                // While a selection is up there are no multi-finger gestures
                // at all: a pinch or the switcher acting on the terminal the
                // user is halfway through selecting in is never what was
                // meant, and the fingers are as likely to be a fumbled grab
                // at the selection as anything.
                Touch.SELECTED_TAP -> mode = Touch.IGNORED

                Touch.PRESSING, Touch.PASS -> {
                    mode = Touch.UNDECIDED
                    startProgress = progressAtStart()
                    startedAt = SystemClock.uptimeMillis()
                    // From `down` rather than calculateCentroid(): that only counts
                    // pointers that were already down on the previous event, which
                    // leaves out the finger that just landed.
                    startCentroid = down.centroid()
                    startSpan = down.span(startCentroid)
                    pan = Offset.Zero
                    zoom = 1f
                }

                else -> {}
            }

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

                // One branch for both because an armed menu is still an
                // undecided gesture: it arbitrates identically, and every
                // arbitration below that reaches a verdict is also what
                // disarms it.
                Touch.UNDECIDED, Touch.MENU_ARMED -> {
                    // From here on nothing reaches the WebView.
                    event.changes.forEach(PointerInputChange::consume)
                    if (down.isNotEmpty()) centroid = down.centroid()
                    when {
                        // No gesture here takes three fingers (the one that
                        // did, the selection slide, was unusable on MIUI), and
                        // a stray third is likelier to be the heel of a hand
                        // than intent — drop the rest of the gesture rather
                        // than let it mean something. A third finger is also
                        // not a lift, so it disarms the menu.
                        down.size >= 3 -> mode = Touch.IGNORED
                        // A finger lifting rules out everything still open.
                        // If the hold armed the menu this is the lift that
                        // opens it — at the centroid the fingers were held
                        // at, which is where the user was aiming, not
                        // wherever they drifted to since.
                        down.size < 2 -> {
                            if (mode == Touch.MENU_ARMED) onMenu(startCentroid)
                            mode = Touch.IGNORED
                        }
                        else -> {
                            pan += event.calculatePan()
                            zoom *= event.calculateZoom()
                            val spread = abs(zoom - 1f) * startSpan
                            val travel = pan.getDistance()
                            if (spread > slop && spread > travel) {
                                mode = if (startProgress == 0f) Touch.PINCH else Touch.IGNORED
                                if (mode == Touch.PINCH) onPinchStart(down.span(centroid))
                            } else if (travel > slop) {
                                // Opening has to dodge the system's quick-settings
                                // gesture near the top edge; an open switcher can be
                                // dismissed from anywhere. Horizontal two-finger drags
                                // mean nothing.
                                val vertical = abs(pan.y) >= abs(pan.x)
                                val inExclusion = startProgress == 0f &&
                                    startCentroid.y < size.height * topExclusionFraction
                                mode = if (vertical && !inExclusion) Touch.SWITCHER else Touch.IGNORED
                            }
                            when (mode) {
                                Touch.SWITCHER -> onSwitcherDrag(startProgress, pan.y)
                                Touch.PINCH -> onPinch(down.span(centroid), centroid)
                                else -> {}
                            }
                        }
                    }
                }

                Touch.SWITCHER -> {
                    event.changes.forEach(PointerInputChange::consume)
                    if (down.size < 2) {
                        onSwitcherRelease()
                        mode = Touch.IGNORED
                    } else {
                        pan += event.calculatePan()
                        onSwitcherDrag(startProgress, pan.y)
                    }
                }

                Touch.PINCH -> {
                    event.changes.forEach(PointerInputChange::consume)
                    if (down.size < 2) {
                        onPinchEnd()
                        mode = Touch.IGNORED
                    } else {
                        // Measured from the fingers rather than accumulated
                        // out of calculateZoom(): the caller steps on absolute
                        // span, so it wants the span, and a product of
                        // per-event ratios would drift over a long pinch.
                        centroid = down.centroid()
                        onPinch(down.span(centroid), centroid)
                    }
                }

                Touch.IGNORED -> event.changes.forEach(PointerInputChange::consume)
            }

            if (down.isEmpty()) break
        }
    }
}

private fun List<PointerInputChange>.centroid(): Offset =
    fold(Offset.Zero) { acc, c -> acc + c.position } / size.toFloat()

/** Mean distance of the fingers from [centroid] — what a pinch changes. */
private fun List<PointerInputChange>.span(centroid: Offset): Float =
    sumOf { (it.position - centroid).getDistance().toDouble() }.toFloat() / size
