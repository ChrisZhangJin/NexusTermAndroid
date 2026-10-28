package com.nexusterm.android.ui

import android.util.Base64
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
private val ArmedKey = Color(0xFF8AB4F8)
private val ArmedKeyText = Color(0xFF101014)

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
 * only channel left, so both facts are pulled on one timer as a three-character
 * string — cheap enough to leave running, and no JSON to parse.
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

    /** Returns "<atBottom><ctrl><alt>" as 0/1, or null if the page isn't up yet. */
    private suspend fun readUiState(): String? = suspendCancellableCoroutine { cont ->
        handle.webView.evaluateJavascript(
            "window.NexusTerm ? window.NexusTerm.uiState() : null",
        ) { raw ->
            // evaluateJavascript hands back the JSON encoding of the result, so
            // a JS string arrives quoted and a page that hasn't run its inline
            // script yet arrives as "null".
            val value = raw?.trim('"')
            cont.resume(if (value != null && value.length >= 3) value else null)
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

    val showWanted = enabled && state.atBottom
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
    LaunchedEffect(state.atBottom, state.onScreen) {
        if (!state.atBottom && !state.onScreen) state.reserved = false
    }

    return state
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
            .padding(BarPadding),
        verticalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        KeyRow(ROW_ONE, state, Modifier.weight(1f))
        KeyRow(ROW_TWO, state, Modifier.weight(1f))
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
