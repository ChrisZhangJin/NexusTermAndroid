package com.nexusterm.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryLogTest {
    @Test fun recordsOrdinaryCommands() {
        assertTrue(HistoryLog.recordable("ls -la /var/log"))
        assertTrue(HistoryLog.recordable("tmux attach -d -t '=accid'"))
        assertTrue(HistoryLog.recordable("ls /etc/pam.d"))
    }

    @Test fun skipsBlankLeadingSpaceControlCharsAndOverlong() {
        assertFalse(HistoryLog.recordable(""))
        assertFalse(HistoryLog.recordable("   "))
        assertFalse(HistoryLog.recordable(" tmux attach -d -t '=a'"))
        assertFalse(HistoryLog.recordable("a\u0007b"))
        assertFalse(HistoryLog.recordable("x".repeat(HistoryLog.MAX_LENGTH + 1)))
    }

    @Test fun skipsLikelySecrets() {
        assertFalse(HistoryLog.recordable("mysql -u root --password=hunter2"))
        assertFalse(HistoryLog.recordable("export API_KEY=abc123"))
        assertFalse(HistoryLog.recordable("curl -H 'token: abc' https://x"))
        assertFalse(HistoryLog.recordable("sshpass -p x ssh h"))
    }

    @Test fun addPutsNewestFirstAndDedupes() {
        val l = HistoryLog.add(HistoryLog.add(HistoryLog.add(emptyList(), "a"), "b"), "a")
        assertEquals(listOf("a", "b"), l)
    }

    @Test fun addCapsTheList() {
        var l = emptyList<String>()
        repeat(HistoryLog.MAX_ENTRIES + 20) { l = HistoryLog.add(l, "cmd$it") }
        assertEquals(HistoryLog.MAX_ENTRIES, l.size)
        assertEquals("cmd${HistoryLog.MAX_ENTRIES + 19}", l.first())
    }

    @Test fun searchMatchesAllTermsCaseInsensitively() {
        val l = listOf("git push origin main", "Git status", "ls")
        assertEquals(listOf("git push origin main", "Git status"), HistoryLog.search(l, "GIT"))
        assertEquals(listOf("git push origin main"), HistoryLog.search(l, "push main"))
        assertEquals(l, HistoryLog.search(l, "  "))
    }
}
