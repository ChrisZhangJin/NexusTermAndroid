package com.nexusterm.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 * There is no permanent tab strip — the cards carry the session names — which
 * makes this the only route to closing a session. That's why the gesture stays
 * live even at a single tab: otherwise the last session would be impossible to
 * disconnect from inside the terminal.
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .twoFingerVerticalDrag(
                // Deliberately on even at one tab — see this composable's doc.
                enabled = true,
                // Starting a two-finger drag at the very top is Android's
                // quick-settings gesture and the system will take it from us,
                // so ignore anything that begins up there.
                topExclusionFraction = 0.12f,
                progressAtStart = { progress.value },
                onDrag = { startProgress, dy ->
                    scope.launch {
                        progress.snapTo((startProgress + dy / FULL_DRAG_PX).coerceIn(0f, 1f))
                    }
                },
                onRelease = {
                    scope.launch { settle(if (progress.value > OPEN_THRESHOLD) 1f else 0f) }
                },
            ),
    ) {
        val p = progress.value
        val scale = 1f - (1f - OPEN_SCALE) * p

        // Keys go to whichever session is actually on screen, and nowhere at
        // all while the cards are up — a tap there means "pick this terminal",
        // not "type into it".
        val keyBar = rememberTerminalKeyBarState(
            handle = tabs[activeIndex].handle,
            enabled = !isOpen,
        )

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
    }
}

/**
 * Reports two-finger vertical drags, and nothing else.
 *
 * Handled on [PointerEventPass.Initial] because the thing underneath is a
 * WebView: xterm.js claims touches for its own scrolling and selection, and by
 * the Main pass the gesture is already gone. Watching the Initial pass lets
 * this peek at every event before the WebView and consume only once a genuine
 * two-finger vertical drag is in progress — single-finger touches are passed
 * through untouched, so typing, scrolling and selection still work normally.
 *
 * [onDrag] receives the progress the gesture started from plus the distance
 * travelled, so the same gesture opens the switcher (dragging down from 0) and
 * dismisses it (dragging up from 1).
 */
private fun Modifier.twoFingerVerticalDrag(
    enabled: Boolean,
    topExclusionFraction: Float,
    progressAtStart: () -> Float,
    onDrag: (startProgress: Float, dy: Float) -> Unit,
    onRelease: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(topExclusionFraction) {
    val slop = viewConfiguration.touchSlop

    awaitEachGesture {
        // Don't consume the first finger — it may well be an ordinary tap or
        // scroll meant for the terminal.
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        var startProgress = 0f
        var anchorY: Float? = null
        var anchorX = 0f
        var dragging = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            if (down.isEmpty()) break

            if (down.size < 2) {
                // Dropped back to one finger: whatever this is, it isn't ours.
                if (dragging) break else continue
            }

            val centroidY = down.averageY()

            if (anchorY == null) {
                // Second finger just landed. An open switcher can be dismissed
                // from anywhere; only *opening* has to dodge the system's
                // quick-settings gesture near the top edge.
                startProgress = progressAtStart()
                if (startProgress == 0f && centroidY < size.height * topExclusionFraction) break
                anchorY = centroidY
                anchorX = down.averageX()
                continue
            }

            val dy = centroidY - anchorY
            if (!dragging) {
                if (abs(dy) < slop) continue
                // Vertical-dominant only, so a two-finger horizontal swipe
                // stays available to the terminal.
                if (abs(dy) < abs(down.averageX() - anchorX)) continue
                dragging = true
            }

            onDrag(startProgress, dy)
            // Consuming here is what actually keeps the WebView out of it.
            down.forEach(PointerInputChange::consume)
        }

        if (dragging) onRelease()
    }
}

private fun List<PointerInputChange>.averageY(): Float = sumOf { it.position.y.toDouble() }.toFloat() / size

private fun List<PointerInputChange>.averageX(): Float = sumOf { it.position.x.toDouble() }.toFloat() / size
