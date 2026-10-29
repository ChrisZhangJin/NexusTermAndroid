package com.nexusterm.android.ui

import android.os.SystemClock
import android.util.Base64
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Vertical space the bar occupies while it is docked. [TerminalTabsPager]
 * shortens the terminal by exactly this much.
 */
val TerminalKeyBarHeight: Dp = 84.dp

private val KeyGap = 3.dp
private val BarPadding = 4.dp
private val KeyCorner = 6.dp

private val BarBackground = Color(0xFF17171A)
private val IdleKey = Color(0xFF2A2A2F)
private val IdleKeyText = Color(0xFFE6E6E6)

/** Armed modifiers invert — the one state a glance mid-command has to pick out. */
private val ArmedKey = Color(0xFF5DCAA5)
private val ArmedKeyText = Color(0xFF0F2A23)

private val PageDot = Color(0xFF4A4A50)
private val PageDotCurrent = Color(0xFF5DCAA5)
private val PageDotSize = 5.dp

/** Slide duration. Short enough that the bar never feels like it lags a scroll. */
private const val SLIDE_MS = 180

/**
 * How often Kotlin asks the page where its viewport is and which modifiers are
 * still armed. Both answers only matter at human timescales, so this is kept
 * deliberately slack.
 */
private const val POLL_INTERVAL_MS = 120L

/** A page that never answers (destroyed WebView) must not wedge the poll loop. */
private const val POLL_TIMEOUT_MS = 500L

/** Scrolling up hides the bar on the first poll that says so. */
private const val HIDE_POLLS = 1

/**
 * Coming back needs this many agreeing polls. Showing the bar takes rows away
 * from the terminal, which — see [TerminalTabsPager]'s doc — is a real SSH
 * window-change on the remote shell; a buffer sitting exactly on the
 * at-bottom boundary would otherwise flap between the two sizes and spend one
 * window-change per flap.
 */
private const val SHOW_POLLS = 4

/**
 * The bar slides away after this long without the user touching the terminal,
 * typing, or touching the bar. Pushing up past the bottom of the buffer
 * brings it back.
 *
 * Was 2s, which measured as "it vanished while I was still reading it": the
 * caps are unlabelled escape sequences to anyone who hasn't memorised the
 * layout, and two seconds isn't long enough to find PGUP. The countdown is
 * also suspended entirely while the soft keyboard is up — see [imeVisible].
 */
private const val IDLE_HIDE_MS = 6_000L

/**
 * How much the window has to lose before "the soft keyboard is up" is the only
 * plausible explanation (see [rememberImeVisible]). Every other thing that can
 * shrink this window — a navigation bar, a status bar, a cutout — is well
 * under this; the shortest phone IME is roughly double it.
 */
private val ImeMinHeight = 120.dp

/**
 * Horizontal travel at which a finger on the bar stops being a key press and
 * becomes a page swipe. Deliberately a touch above the platform's ~8dp touch
 * slop: below it, a thumb rolling slightly sideways on a cap would eat the
 * keystroke it meant to send.
 */
private val SwipeDecide = 14.dp

/**
 * Horizontal travel that flips the page without waiting for the finger to
 * come up, so a long drag feels like it's driving the pages rather than
 * queueing a command. Anything shorter than this still flips, on release.
 */
private val SwipeCommit = 40.dp

internal enum class StickyModifier { CTRL, ALT }

/**
 * One cap. Exactly one of [payload]/[sticky] is set: the sticky modifiers arm
 * state in the page instead of sending anything.
 */
internal class KeyDef(
    val label: String,
    val payload: String? = null,
    val sticky: StickyModifier? = null,
)

// Labels follow Termux's row, which is the layout the user asked to be
// matched. `—` is an em dash on the cap but sends a plain hyphen: what it is
// actually there for is typing `-` flags without a trip to the symbol keyboard.
private val ROW_ONE = listOf(
    KeyDef("ESC", "\u001b"),
    KeyDef("/", "/"),
    KeyDef("—", "-"),
    KeyDef("HOME", "\u001b[H"),
    KeyDef("↑", "\u001b[A"),
    KeyDef("END", "\u001b[F"),
    KeyDef("PGUP", "\u001b[5~"),
)

private val ROW_TWO = listOf(
    KeyDef("⇥", "\t"),
    KeyDef("CTRL", sticky = StickyModifier.CTRL),
    KeyDef("ALT", sticky = StickyModifier.ALT),
    KeyDef("←", "\u001b[D"),
    KeyDef("↓", "\u001b[B"),
    KeyDef("→", "\u001b[C"),
    KeyDef("PGDN", "\u001b[6~"),
)

private val ESC = Char(0x1B).toString()

// The shell punctuation that is furthest away on a phone's symbol keyboard.
private val SYMBOLS_ONE = listOf("|", "~", "`", "\\", "$", "&", "*").map { KeyDef(it, it) }
private val SYMBOLS_TWO = listOf("{", "}", "[", "]", "<", ">", ";").map { KeyDef(it, it) }

// xterm's encodings, which is what TERM=xterm-256color promises the remote.
private val FN_ONE = listOf(
    KeyDef("F1", "${ESC}OP"),
    KeyDef("F2", "${ESC}OQ"),
    KeyDef("F3", "${ESC}OR"),
    KeyDef("F4", "${ESC}OS"),
    KeyDef("F5", "${ESC}[15~"),
    KeyDef("F6", "${ESC}[17~"),
    KeyDef("F7", "${ESC}[18~"),
)

private val FN_TWO = listOf(
    KeyDef("F8", "${ESC}[19~"),
    KeyDef("F9", "${ESC}[20~"),
    KeyDef("F10", "${ESC}[21~"),
    KeyDef("F11", "${ESC}[23~"),
    KeyDef("F12", "${ESC}[24~"),
    KeyDef("INS", "${ESC}[2~"),
    KeyDef("DEL", "${ESC}[3~"),
)

/** Two rows each; a horizontal swipe across the bar flips between them. */
private val PAGES = listOf(
    listOf(ROW_ONE, ROW_TWO),
    listOf(SYMBOLS_ONE, SYMBOLS_TWO),
    listOf(FN_ONE, FN_TWO),
)

/**
 * Drives the extra-keys row for whichever terminal is on screen.
 *
 * ## Why keystrokes go through the page instead of straight to SshClient
 *
 * CTRL and ALT are sticky: armed by a tap, spent on the *next* key — and that
 * next key is just as likely to come from the soft keyboard, which reaches the
 * session through xterm.js's `onData` without passing through Kotlin at all.
 * Two implementations of "is a modifier armed" would then have to stay in step
 * across an asynchronous bridge, and the window where they don't is exactly
 * the window where Ctrl+C gets dropped or doubled.
 *
 * So there is one implementation and it lives in index.html; this class only
 * mirrors the page's flags for painting. A bar key goes to
 * `NexusTerm.sendKeyBase64`, which applies any armed modifier and hands the
 * result to the same `NexusAndroid.onTermInput` the soft keyboard uses — both
 * input sources then take an identical path and share one piece of state.
 *
 * ## Why this polls
 *
 * The JS -> Kotlin bridge object is built in TerminalScreen.kt and can't grow
 * an event for "the user scrolled into scrollback" or "the armed Ctrl was just
 * spent on a soft-keyboard key". `evaluateJavascript`'s result callback is the
 * only channel left, so every fact the page owns is pulled on one timer as a
 * single flat string — `"<atBottom><ctrl><alt><summon><idleMs>"`, four flag
 * characters and a decimal tail (see `uiState` in index.html). Cheap enough to
 * leave running, and no JSON to parse.
 *
 * [imeVisible] is the one input that does *not* come from the page: the soft
 * keyboard is an Android window, invisible to JS, so it is pushed in from the
 * composition by [rememberImeVisible] instead.
 */
@Stable
class TerminalKeyBarState internal constructor(handle: TerminalHandle) {

    /**
     * The terminal keys currently reach. A plain var rather than Compose
     * state: switching tabs must not restart the poll loop or reset the
     * slide, or every trip through the card switcher would cost an animation
     * and a layout flip — and so a window-change on every live shell.
     */
    internal var handle: TerminalHandle = handle

    /** Debounced: the page's viewport is sitting on the live bottom of the buffer. */
    internal var atBottom by mutableStateOf(true)

    internal var ctrlArmed by mutableStateOf(false)
    internal var altArmed by mutableStateOf(false)

    /**
     * The user wants the bar: true from the start and after an overscroll,
     * false once everything has sat idle for [IDLE_HIDE_MS].
     */
    internal var summoned by mutableStateOf(true)

    /**
     * The soft keyboard is up. Written from the composition (see
     * [rememberImeVisible]), because the page can't see Android's IME.
     *
     * While it's true the bar is shown and the idle countdown is suspended:
     * Ctrl/Alt/arrows sitting next to the letters is the single case these
     * keys exist for, and timing them out from under a user who is mid-command
     * is exactly the complaint that moved [IDLE_HIDE_MS] to six seconds.
     */
    internal var imeVisible by mutableStateOf(false)

    /** Index into [PAGES]. */
    internal var page by mutableIntStateOf(0)

    /** Last touch on the bar itself, which the page never hears about. */
    private var lastBarActivity = SystemClock.uptimeMillis()

    internal fun noteActivity() {
        lastBarActivity = SystemClock.uptimeMillis()
    }

    internal fun flipPage(by: Int) {
        page = (page + by).mod(PAGES.size)
    }

    /** Composed at all. Separate from [slide] so sliding costs no recomposition. */
    internal var onScreen by mutableStateOf(false)

    /**
     * Whether the terminal is currently measured short. Starts true to match
     * [atBottom]: a terminal that laid itself out full height and handed 84dp
     * back a frame later would resize the remote shell for nothing.
     */
    internal var reserved by mutableStateOf(true)

    internal val slide = Animatable(0f)

    /**
     * Bumped on every local change to the armed flags, so a poll that was
     * already in flight when the user tapped CTRL can be discarded instead of
     * un-lighting the cap they just pressed.
     */
    private var writeSeq = 0

    /** What [TerminalTabsPager] subtracts from the terminal's height. */
    val reservedHeight: Dp get() = if (reserved) TerminalKeyBarHeight else 0.dp

    internal fun onKey(key: KeyDef) {
        noteActivity()
        val sticky = key.sticky
        if (sticky != null) {
            when (sticky) {
                StickyModifier.CTRL -> ctrlArmed = !ctrlArmed
                StickyModifier.ALT -> altArmed = !altArmed
            }
            writeSeq++
            eval("window.NexusTerm.setModifiers($ctrlArmed, $altArmed)")
            return
        }
        // Base64 for the same reason writeBase64 uses it: these payloads are
        // escape sequences, and base64's alphabet survives being spliced into
        // a single-quoted JS literal untouched. Every payload here is ASCII,
        // so the page's atob() reproduces the string exactly.
        val b64 = Base64.encodeToString(key.payload!!.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        eval("window.NexusTerm.sendKeyBase64('$b64')")
        // The page consumed whatever was armed as part of that call; clearing
        // here rather than waiting for the next poll stops the caps staying lit
        // after the key they applied to has already gone out.
        writeSeq++
        ctrlArmed = false
        altArmed = false
    }

    private fun eval(js: String) {
        handle.webView.evaluateJavascript("window.NexusTerm && $js;", null)
    }

    /**
     * Returns "<atBottom><ctrl><alt><summon><idleMs>" (see `uiState` in
     * index.html), or null if the page isn't up yet.
     */
    private suspend fun readUiState(): String? = suspendCancellableCoroutine { cont ->
        handle.webView.evaluateJavascript(
            "window.NexusTerm ? window.NexusTerm.uiState() : null",
        ) { raw ->
            // evaluateJavascript hands back the JSON encoding of the result, so
            // a JS string arrives quoted and a page that hasn't run its inline
            // script yet arrives as "null".
            val value = raw?.trim('"')
            cont.resume(if (value != null && value.length >= 5) value else null)
        }
    }

    internal suspend fun pollForever() {
        var agree = 0
        while (true) {
            val seqAtRequest = writeSeq
            val raw = withTimeoutOrNull(POLL_TIMEOUT_MS) { readUiState() }
            if (raw != null) {
                val rawAtBottom = raw[0] == '1'
                if (rawAtBottom == atBottom) {
                    agree = 0
                } else {
                    agree++
                    if (agree >= (if (rawAtBottom) SHOW_POLLS else HIDE_POLLS)) {
                        atBottom = rawAtBottom
                        agree = 0
                    }
                }
                // The page is the authority: a Ctrl armed by a tap and then
                // spent on a soft-keyboard key is consumed entirely inside
                // index.html, and this poll is how the cap finds out.
                if (seqAtRequest == writeSeq) {
                    ctrlArmed = raw[1] == '1'
                    altArmed = raw[2] == '1'
                }
                if (raw[3] == '1') {
                    summoned = true
                    noteActivity()
                }
                // Idle means idle everywhere: no touches or typing in the page,
                // nothing on the bar, no modifier armed and waiting for a key,
                // and no soft keyboard open. The keyboard isn't "activity" that
                // ages out — it's a standing request for these keys — so it
                // suspends the countdown rather than resetting it, and
                // `rememberTerminalKeyBarState` restarts the clock when it goes.
                val pageIdleMs = raw.substring(4).toLongOrNull() ?: 0L
                val barIdleMs = SystemClock.uptimeMillis() - lastBarActivity
                if (summoned && !imeVisible && !ctrlArmed && !altArmed &&
                    minOf(pageIdleMs, barIdleMs) >= IDLE_HIDE_MS
                ) {
                    summoned = false
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }
}

/**
 * @param handle the terminal keys should reach; may change as tabs are switched.
 * @param enabled false slides the bar away without giving its space back, so
 *   opening the card switcher doesn't resize every live shell twice.
 */
@Composable
fun rememberTerminalKeyBarState(handle: TerminalHandle, enabled: Boolean): TerminalKeyBarState {
    // Deliberately un-keyed — see TerminalKeyBarState.handle.
    val state = remember { TerminalKeyBarState(handle) }
    SideEffect { state.handle = handle }

    LaunchedEffect(state) { state.pollForever() }

    val imeVisible = rememberImeVisible()
    SideEffect { state.imeVisible = imeVisible }
    LaunchedEffect(imeVisible) {
        // Arriving: the keyboard is the clearest "I am about to type" there
        // is, so undo any dismissal rather than waiting for an overscroll.
        // Leaving: the idle clock has been parked for however long the
        // keyboard was up, so restart it from now — otherwise the bar would
        // blink out the instant the keyboard did.
        state.noteActivity()
        if (imeVisible) state.summoned = true
    }

    // The keyboard forces the bar on even from inside scrollback: xterm.js
    // scrolls to the bottom on the first keystroke anyway, so a bar hidden on
    // `atBottom` would reappear one character later — one extra pair of
    // window-changes for nothing.
    val showWanted = enabled && (imeVisible || (state.atBottom && state.summoned))
    LaunchedEffect(showWanted) {
        if (showWanted) {
            // Take the rows back *before* sliding in, so the bar slides into
            // space the terminal has already vacated rather than over the top
            // of live output.
            state.reserved = true
            state.onScreen = true
            state.slide.animateTo(1f, tween(durationMillis = SLIDE_MS))
        } else {
            state.slide.animateTo(0f, tween(durationMillis = SLIDE_MS))
            state.onScreen = false
        }
    }

    // Handing the strip back is a genuine resize of the remote pty, so it
    // happens exactly once per transition and only once the bar has finished
    // getting out of the way — never on an intermediate animation frame.
    // (`enabled` is deliberately not part of this: the card switcher hides the
    // bar but keeps its space, see the parameter doc.)
    val dismissed = !imeVisible && (!state.atBottom || !state.summoned)
    LaunchedEffect(dismissed, state.onScreen) {
        if (dismissed && !state.onScreen) state.reserved = false
    }

    return state
}

/**
 * True for as long as the soft keyboard is on screen.
 *
 * ## Why not `WindowInsets.isImeVisible`
 *
 * Because it would answer `false` here, always. Compose's insets come from an
 * `OnApplyWindowInsetsListener` it installs on the *Compose view*, and this
 * activity is deliberately not edge-to-edge — AndroidManifest.xml explains why
 * at length: the window is `adjustResize`d so that the terminal really does get
 * shorter when the keyboard appears, which is what lets index.html's `fit()`
 * report honest `rows` to the remote pty. With the decor view still fitting
 * system windows it consumes the insets on the way down, so everything Compose
 * sees is zero. `setDecorFitsSystemWindows(window, false)` would fix the insets
 * and break the resize — on API 30+ it makes `adjustResize` a no-op, and the
 * terminal would sit full-height behind the keyboard until someone added an
 * `imePadding()` in TerminalTabsPager. Not a trade worth making for a bar.
 *
 * So the question is asked two ways and either `true` wins:
 *
 * - the *root* window insets, which nothing has consumed yet. Exact from API
 *   30, where `isVisible(ime())` is a real platform flag;
 * - failing that, the window simply being shorter than the tallest it has been
 *   at this width. Under a guaranteed `adjustResize` that is what an open
 *   keyboard *is*, and it needs no API level at all — which matters, because
 *   androidx's pre-30 backport of IME insets leans on reflection into
 *   `AttachInfo.mVisibleInsets` that may or may not survive a given OEM build.
 *
 * Tracking the tallest height rather than comparing against the display makes
 * it self-calibrating across status/navigation bars, cutouts and rotation; the
 * baseline resets whenever the width changes, which is the only cheap signal
 * that the window's shape (and not just the keyboard) moved.
 */
@Composable
private fun rememberImeVisible(): Boolean {
    val view = LocalView.current
    val minShrinkPx = with(LocalDensity.current) { ImeMinHeight.roundToPx() }
    var visible by remember { mutableStateOf(false) }

    DisposableEffect(view, minShrinkPx) {
        var baselineWidth = -1
        var tallest = 0

        fun recompute() {
            if (view.width != baselineWidth) {
                baselineWidth = view.width
                tallest = view.height
            }
            if (view.height > tallest) tallest = view.height
            val byInsets = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            visible = byInsets || (tallest - view.height) >= minShrinkPx
        }

        // A layout pass is guaranteed on both edges of the keyboard precisely
        // because the window is `adjustResize`d, so this needs no polling and
        // no listener of its own on the insets (which would clobber the one
        // Compose has already put on this very view).
        val onLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> recompute() }
        view.addOnLayoutChangeListener(onLayout)
        recompute()
        onDispose { view.removeOnLayoutChangeListener(onLayout) }
    }

    return visible
}

/**
 * The docked row itself. Draw it as a sibling of the terminal, aligned to the
 * bottom: it is translated rather than measured in and out, so the animation
 * never touches the terminal's size.
 */
@Composable
fun TerminalKeyBar(state: TerminalKeyBarState, modifier: Modifier = Modifier) {
    if (!state.onScreen) return

    val heightPx = with(LocalDensity.current) { TerminalKeyBarHeight.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(TerminalKeyBarHeight)
            .graphicsLayer {
                // Read inside the layer block, which defers to the draw phase.
                // Reading it in the composition instead would recompose the
                // whole bar sixty times a second for one slide.
                translationY = (1f - state.slide.value) * heightPx
                alpha = state.slide.value
            }
            .background(BarBackground)
            .pageSwipe(state)
            .padding(BarPadding),
        verticalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        AnimatedContent(
            targetState = state.page,
            transitionSpec = {
                // Wrapping from the last page to the first still reads as "next".
                val forward = targetState == (initialState + 1) % PAGES.size
                val dir = if (forward) 1 else -1
                (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it * dir } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "keyBarPage",
        ) { page ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                PAGES[page].forEach { row -> KeyRow(row, state, Modifier.weight(1f)) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PageDotSize, Alignment.CenterHorizontally),
        ) {
            PAGES.indices.forEach { i ->
                Box(
                    Modifier
                        .size(PageDotSize)
                        .clip(CircleShape)
                        .background(if (i == state.page) PageDotCurrent else PageDot),
                )
            }
        }
    }
}

/**
 * A one-finger horizontal swipe anywhere on the bar flips its page. Every
 * touch here also counts as activity, keeping the bar up.
 *
 * Watched on the Initial pass, which puts this ahead of the key caps' own
 * `clickable`s — the only place an ancestor can get a say before a descendant.
 * It was two fingers precisely to dodge the arbitration that implies; the
 * complaint was that nobody discovers a two-finger gesture on a 84dp strip, so
 * the arbitration has to be done properly instead:
 *
 * - until [SwipeDecide] of horizontal travel, nothing is consumed. The caps
 *   get their press and their ripple, and a tap — which is up long before it
 *   could travel that far — fires on release exactly as it always did. Nothing
 *   is held back waiting to find out, so taps stay instant;
 * - past [SwipeDecide] this is a swipe, and from that event on every change is
 *   consumed. A consumed change is how `clickable`'s `waitForUpOrCancellation`
 *   learns it has been cancelled, so the cap under the finger goes dark and
 *   emits nothing — which is the whole requirement: a swipe must never also
 *   send a keystroke;
 * - the flip itself lands at [SwipeCommit], or on release for anything that
 *   got past [SwipeDecide] without reaching it. There is no dead band: once
 *   the keystroke has been given up, the gesture owes the user a page.
 *
 * Travel is measured as the displacement of the finger that opened the gesture
 * rather than accumulated through `calculatePan()`, because `calculatePan()`
 * only counts pointers pressed in *both* the previous and current event and so
 * silently drops the last leg of a fast flick, the one carried by the UP event.
 * That lost leg is the difference between a flick registering as a swipe and
 * registering as a tap on whatever key it started over.
 *
 * A finger that commits to vertical first is ruled out for the rest of the
 * gesture and never consumed, so it stays a plain (if wandering) key press.
 */
private fun Modifier.pageSwipe(state: TerminalKeyBarState): Modifier = pointerInput(state) {
    val decide = SwipeDecide.toPx()
    val commit = SwipeCommit.toPx()
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        state.noteActivity()
        var travel = Offset.Zero
        var swiping = false
        var ruledOut = false
        var flipped = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            state.noteActivity()

            // Null once that finger lifts with others still down; keeping the
            // last reading then is right — the gesture is already decided.
            event.changes.firstOrNull { it.id == first.id }?.let {
                travel = it.position - first.position
            }

            if (!swiping && !ruledOut) {
                when {
                    abs(travel.x) >= decide && abs(travel.x) > abs(travel.y) -> swiping = true
                    abs(travel.y) >= decide -> ruledOut = true
                }
            }

            if (swiping) {
                event.changes.forEach(PointerInputChange::consume)
                if (!flipped && abs(travel.x) >= commit) {
                    // A finger moving left brings in the page to the right.
                    state.flipPage(if (travel.x < 0) 1 else -1)
                    flipped = true
                }
            }

            if (event.changes.none { it.pressed }) {
                if (swiping && !flipped) state.flipPage(if (travel.x < 0) 1 else -1)
                break
            }
        }
    }
}

@Composable
private fun KeyRow(keys: List<KeyDef>, state: TerminalKeyBarState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        keys.forEach { key ->
            KeyCap(
                label = key.label,
                armed = when (key.sticky) {
                    StickyModifier.CTRL -> state.ctrlArmed
                    StickyModifier.ALT -> state.altArmed
                    null -> false
                },
                onClick = { state.onKey(key) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    armed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(KeyCorner))
            .background(if (armed) ArmedKey else IdleKey)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (armed) ArmedKeyText else IdleKeyText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}
