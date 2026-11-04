package com.nexusterm.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** looksLikePrompt() 的 JVM 单测：移植自桌面端 termRegistry.ts 的 PROMPT_TAIL。 */
class PromptDetectTest {
    @Test fun typicalBashPrompt() {
        assertTrue(looksLikePrompt("(base) chris@host:~$ "))
        assertTrue(looksLikePrompt("chris@host:~$"))
    }

    @Test fun rootAndBarePrompts() {
        assertTrue(looksLikePrompt("# "))
        assertTrue(looksLikePrompt("> "))
        assertTrue(looksLikePrompt("% "))
    }

    @Test fun fancyGlyphPrompts() {
        assertTrue(looksLikePrompt("❯ "))
        assertTrue(looksLikePrompt("➜ "))
        assertTrue(looksLikePrompt("λ "))
        assertTrue(looksLikePrompt("»"))
    }

    @Test fun nonBreakingSpaceAfterGlyph() {
        assertTrue(looksLikePrompt("~ ❯\u00A0"))
    }

    @Test fun notAPrompt() {
        assertFalse(looksLikePrompt(""))
        assertFalse(looksLikePrompt("   "))
        assertFalse(looksLikePrompt("Last login: Tue Oct 28 10:00:00 2026 from 10.0.0.2"))
        assertFalse(looksLikePrompt("$ ls -la"))
        assertFalse(looksLikePrompt("user@host:~$  ")) // two trailing spaces: same as desktop
    }

    @Test fun glyphFirstThemeIsNotMatched() {
        // oh-my-zsh robbyrussell: "➜  ~ " has the cwd after the glyph, so the tail
        // isn't a prompt char. Pins parity with the desktop regex.
        assertFalse(looksLikePrompt("➜  ~ "))
    }
}
