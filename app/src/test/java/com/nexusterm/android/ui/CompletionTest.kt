package com.nexusterm.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompletionTest {
    @Test fun needsTwoCharacters() {
        assertEquals(emptyList<String>(), Completion.suggest("g", listOf("git status"), emptyList()))
        assertEquals(listOf("git status"), Completion.suggest("gi", listOf("git status"), emptyList()))
    }

    @Test fun prefixMatchOnly() {
        assertEquals(listOf("docker ps"), Completion.suggest("do", listOf("sudo ls", "docker ps"), emptyList()))
    }

    @Test fun localBeforeRemoteAndDeduplicated() {
        val got = Completion.suggest("tail", listOf("tail -f a.log"), listOf("tail -n 5 b", "tail -f a.log"))
        assertEquals(listOf("tail -f a.log", "tail -n 5 b"), got)
    }

    @Test fun exactMatchIsNotSuggested() {
        assertEquals(listOf("ls -la"), Completion.suggest("ls", listOf("ls", "ls -la"), emptyList()))
    }

    @Test fun leadingSpacesIgnored() {
        assertEquals(listOf("make test"), Completion.suggest("  ma", listOf("make test"), emptyList()))
        assertEquals("ke test", Completion.remainder("  ma", "make test"))
    }

    @Test fun limitsCount() {
        val many = (1..20).map { "cmd$it" }
        assertEquals(3, Completion.suggest("cm", many, emptyList(), limit = 3).size)
    }

    @Test fun remainderIsTheUntypedTail() {
        assertEquals(" status", Completion.remainder("git", "git status"))
        assertEquals("", Completion.remainder("xyz", "git status"))
    }

    @Test fun insertPrefersSingleLineSelection() {
        assertEquals("abc def", pickInsertTarget("  abc def ", listOf("word")))
        // 多行选区不算，退到落点词
        assertEquals("word", pickInsertTarget("a\nb", listOf("word")))
    }

    @Test fun insertCleansWordButNeedNotBeAFile() {
        assertEquals("3f2a9c1d", pickInsertTarget("", listOf("'3f2a9c1d',")))
        assertEquals("main", pickInsertTarget("", listOf("", "main")))
        assertNull(pickInsertTarget("", emptyList()))
    }
}
