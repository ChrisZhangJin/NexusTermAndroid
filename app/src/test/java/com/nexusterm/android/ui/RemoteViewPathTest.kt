package com.nexusterm.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 终端「浏览」的纯函数：候选清理、选区 / 落点词的取舍、cwd 拼接、旁路失败分类。 */
class RemoteViewPathTest {

    @Test fun cleanStripsQuotesPunctuationAndFileScheme() {
        assertEquals("/a/b.md", cleanViewCandidate("  '/a/b.md'  "))
        assertEquals("/a/b.md", cleanViewCandidate("\"/a/b.md\","))
        assertEquals("/a/b.md", cleanViewCandidate("`/a/b.md`;"))
        assertEquals("/a/b.md", cleanViewCandidate("/a/b.md:"))
        assertEquals("/a/b.md", cleanViewCandidate("file:///a/b.md"))
        assertEquals("/a/b.md", cleanViewCandidate("FILE:///a/b.md"))
        assertEquals("docs/x.md", cleanViewCandidate("docs/x.md"))
    }

    @Test fun cleanRejectsEmptyAndMultiline() {
        assertNull(cleanViewCandidate(""))
        assertNull(cleanViewCandidate("   "))
        assertNull(cleanViewCandidate("''"))
        assertNull(cleanViewCandidate("a.md\nb.md"))
    }

    @Test fun trailingNewlineIsJustWhitespace() {
        assertEquals("a.md", cleanViewCandidate("a.md\n"))
    }

    @Test fun selectionWithoutExtensionIsViewable() {
        assertEquals("README", pickViewTarget("README", ""))
        assertEquals("/var/log/syslog", pickViewTarget("/var/log/syslog", "other.md"))
    }

    @Test fun selectionWinsOverWord() {
        assertEquals("/a/sel.md", pickViewTarget("/a/sel.md", "word.md"))
    }

    @Test fun unviewableSelectionFallsBackToWordWithExtension() {
        assertEquals("word.md", pickViewTarget("a.exe", "word.md"))
        assertEquals("word.md", pickViewTarget("", "'word.md',"))
        // 多行选区不算
        assertEquals("word.md", pickViewTarget("a.md\nb.md", "word.md"))
    }

    @Test fun autoPickedWordRequiresExtension() {
        assertNull(pickViewTarget("", "user@host:~/path\$"))
        assertNull(pickViewTarget("", "ls"))
        assertNull(pickViewTarget("", "README"))
        assertNull(pickViewTarget("", "git"))
        assertEquals("~/notes.txt", pickViewTarget("", "~/notes.txt"))
        assertEquals("Dockerfile", pickViewTarget("", "Dockerfile"))
        assertNull(pickViewTarget("", "a.exe"))
        assertNull(pickViewTarget("", ""))
    }

    @Test fun absolutePaths() {
        assertTrue(isAbsoluteRemotePath("/etc/hosts"))
        assertTrue(isAbsoluteRemotePath("~/x.md"))
        assertTrue(isAbsoluteRemotePath("~"))
        assertFalse(isAbsoluteRemotePath("x.md"))
        assertFalse(isAbsoluteRemotePath("./x.md"))
        assertFalse(isAbsoluteRemotePath("~user/x.md"))
    }

    @Test fun joinCwdMatchesDesktop() {
        assertEquals("/home/u/x.md", joinCwd("/home/u", "x.md"))
        assertEquals("/home/u/x.md", joinCwd("/home/u/", "./x.md"))
        assertEquals("/home/u/docs/x.md", joinCwd("/home/u", "docs/x.md"))
        assertEquals("/home/u/../x.md", joinCwd("/home/u", "../x.md"))
        assertEquals("/x.md", joinCwd("/", "x.md"))
    }

    @Test fun fileName() {
        assertEquals("README.md", viewFileName("/a/b/README.md"))
        assertEquals("a.md", viewFileName("a.md"))
        assertEquals("b", viewFileName("/a/b/"))
    }

    @Test fun sidecarErrorClassification() {
        assertEquals(SidecarFailure.DENIED, classifySidecarError("服务器不支持并行通道"))
        assertEquals(SidecarFailure.OFF, classifySidecarError("当前连接处于兼容模式"))
        assertEquals(SidecarFailure.OTHER, classifySidecarError("cat: x: No such file"))
        assertEquals(SidecarFailure.OTHER, classifySidecarError(""))
    }
}
