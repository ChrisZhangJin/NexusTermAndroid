package com.nexusterm.android.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RemoteContext.kt 的 JVM 单测。被测代码是纯 Kotlin、不碰 android.*，所以不需要任何桩。
 *
 * stealthInput 的用例不去手算「期望的 N」硬编码（容易和实现同错），而是验证**不变量**：
 * 从输出里解析出 N，再用同一个 widthOf 量输出里那一行的实际宽度，
 * N 必须等于 ceil((promptCells + 宽度) / cols)。另外各配一两个手算过的精确值。
 */
class RemoteContextTest {

    private val now = 1_700_000_000_000L

    /** FG_ARGS 在后端是用 \u001f 连接的 argv。 */
    private fun fgArgs(vararg argv: String) = argv.joinToString("\u001f")

    // ───────────────────────── parseProbe ─────────────────────────

    @Test
    fun emptyMapIsNull() {
        assertNull(parseProbe(emptyMap(), now))
    }

    @Test
    fun nothingUsefulIsNull() {
        // 只有前台进程名、没有任何目录 / tmux / docker。
        assertNull(parseProbe(mapOf("FG_COMM" to "vim"), now))
        assertNull(parseProbe(mapOf("SHELL_CWD" to "", "FG_CWD" to ""), now))
    }

    @Test
    fun tmuxSessionUsesPanePathAsCwd() {
        val ctx = parseProbe(
            mapOf("TMUX_SESSION" to "work", "TMUX_PATH" to "/srv/app", "SHELL_CWD" to "/home/u"),
            now,
        )
        assertEquals(TermContext(cwd = "/srv/app", tmux = "work", docker = null, at = now), ctx)
    }

    @Test
    fun tmuxFallsBackToShellCwdWhenPanePathMissingOrEmpty() {
        val missing = parseProbe(mapOf("TMUX_SESSION" to "work", "SHELL_CWD" to "/home/u"), now)
        assertEquals("/home/u", missing?.cwd)
        val empty = parseProbe(
            mapOf("TMUX_SESSION" to "work", "TMUX_PATH" to "", "SHELL_CWD" to "/home/u"),
            now,
        )
        assertEquals("/home/u", empty?.cwd)
    }

    @Test
    fun tmuxWithoutAnyCwdStillReturnsContext() {
        val ctx = parseProbe(mapOf("TMUX_SESSION" to "work"), now)
        assertEquals(TermContext(cwd = null, tmux = "work", docker = null, at = now), ctx)
    }

    @Test
    fun tmuxTakesPrecedenceOverDocker() {
        val ctx = parseProbe(
            mapOf(
                "TMUX_SESSION" to "work",
                "FG_COMM" to "docker",
                "FG_ARGS" to fgArgs("docker", "exec", "-it", "web", "bash"),
            ),
            now,
        )
        assertNotNull(ctx)
        assertEquals("work", ctx!!.tmux)
        assertNull(ctx.docker)
    }

    @Test
    fun plainShellCwd() {
        val ctx = parseProbe(mapOf("FG_COMM" to "bash", "SHELL_CWD" to "/home/u"), now)
        assertEquals(TermContext(cwd = "/home/u", tmux = null, docker = null, at = now), ctx)
    }

    @Test
    fun shellCwdWinsOverFgCwdAndFgCwdIsFallback() {
        val both = parseProbe(mapOf("SHELL_CWD" to "/a", "FG_CWD" to "/b"), now)
        assertEquals("/a", both?.cwd)
        val onlyFg = parseProbe(mapOf("FG_COMM" to "vim", "FG_CWD" to "/tmp/x"), now)
        assertEquals("/tmp/x", onlyFg?.cwd)
    }

    @Test
    fun dockerExecWithOptions() {
        // -u / -e 各带一个值，-it 是布尔选项，容器名是第一个非选项参数。
        val argv = listOf("docker", "exec", "-it", "-u", "root", "-e", "A=1", "mycontainer", "bash")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/home/u"),
            now,
        )
        assertEquals(
            TermContext("/home/u", null, DockerContext("mycontainer", argv), now),
            ctx,
        )
    }

    @Test
    fun dockerExecWithGlobalHostOption() {
        // -H 带一个值（占两格），要先跳过全局选项再找 exec。
        val argv = listOf("docker", "-H", "ssh://host", "exec", "-it", "-u", "root", "-e", "A=1", "mycontainer", "bash")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertEquals(DockerContext("mycontainer", argv), ctx?.docker)
    }

    @Test
    fun dockerGlobalOptionWithEqualsTakesOneSlot() {
        val argv = listOf("docker", "--context=prod", "exec", "web", "sh")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertEquals("web", ctx?.docker?.container)
    }

    @Test
    fun dockerExecOptionWithEqualsDoesNotSwallowContainer() {
        val argv = listOf("docker", "exec", "--user=root", "web", "sh")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertEquals("web", ctx?.docker?.container)
    }

    @Test
    fun dockerBinaryGivenAsAbsolutePath() {
        val argv = listOf("/usr/bin/docker", "exec", "web", "sh")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertEquals("web", ctx?.docker?.container)
    }

    @Test
    fun dockerComposeExecBothSpellings() {
        val v2 = listOf("docker", "compose", "exec", "-T", "web", "sh")
        val ctxV2 = parseProbe(
            mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*v2.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertEquals("web", ctxV2?.docker?.container)

        val v1 = listOf("docker-compose", "exec", "db", "psql")
        val ctxV1 = parseProbe(
            mapOf("FG_COMM" to "docker-compose", "FG_ARGS" to fgArgs(*v1.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertEquals("db", ctxV1?.docker?.container)
    }

    @Test
    fun podmanExec() {
        val argv = listOf("podman", "exec", "-it", "c1", "bash")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "podman", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertEquals("c1", ctx?.docker?.container)
    }

    @Test
    fun dockerRunIsNotRecognised() {
        // run 无法安全重放；有 SHELL_CWD 时仍返回 cwd，但 docker 为 null。
        val argv = listOf("docker", "run", "-it", "ubuntu", "bash")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertEquals(TermContext("/w", null, null, now), ctx)
    }

    @Test
    fun dockerRunWithoutCwdIsNull() {
        val argv = listOf("docker", "run", "-it", "ubuntu", "bash")
        val ctx = parseProbe(mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray())), now)
        assertNull(ctx)
    }

    @Test
    fun dockerExecWithoutCwdStillUseful() {
        val argv = listOf("docker", "exec", "web", "sh")
        val ctx = parseProbe(mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray())), now)
        assertEquals(TermContext(null, null, DockerContext("web", argv), now), ctx)
    }

    @Test
    fun dockerExecWithoutContainerIsNotDocker() {
        val argv = listOf("docker", "exec", "-it")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "docker", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertNull(ctx?.docker)
    }

    @Test
    fun nonDockerForegroundArgsAreIgnored() {
        val argv = listOf("docker", "exec", "web", "sh")
        val ctx = parseProbe(
            mapOf("FG_COMM" to "ssh", "FG_ARGS" to fgArgs(*argv.toTypedArray()), "SHELL_CWD" to "/w"),
            now,
        )
        assertNull(ctx?.docker)
    }

    // ───────────────────────── shq ─────────────────────────

    @Test
    fun shqPlain() {
        assertEquals("'abc'", shq("abc"))
        assertEquals("''", shq(""))
        assertEquals("'/home/my dir'", shq("/home/my dir"))
    }

    @Test
    fun shqEscapesEmbeddedSingleQuotes() {
        assertEquals("""'it'\''s'""", shq("it's"))
        assertEquals("""'a'\''b'\''c'""", shq("a'b'c"))
        assertEquals("""''\'''""", shq("'"))
    }

    // ───────────────────────── restoreCommand ─────────────────────────

    @Test
    fun restoreCommandCwdOnly() {
        val ctx = TermContext("/home/u", null, null, now)
        assertEquals("cd -- '/home/u' 2>/dev/null", restoreCommand(ctx))
    }

    @Test
    fun restoreCommandTmuxWithCwd() {
        val ctx = TermContext("/srv", "work", null, now)
        assertEquals("cd -- '/srv' 2>/dev/null; tmux attach -t 'work'", restoreCommand(ctx))
    }

    @Test
    fun restoreCommandTmuxOnly() {
        val ctx = TermContext(null, "work", null, now)
        assertEquals("tmux attach -t 'work'", restoreCommand(ctx))
    }

    @Test
    fun restoreCommandQuotesAwkwardNames() {
        val ctx = TermContext("/it's here", "a b", null, now)
        assertEquals(
            """cd -- '/it'\''s here' 2>/dev/null; tmux attach -t 'a b'""",
            restoreCommand(ctx),
        )
    }

    @Test
    fun restoreCommandNothingToDo() {
        assertNull(restoreCommand(TermContext(null, null, null, now)))
        assertNull(restoreCommand(TermContext("", "", null, now)))
    }

    @Test
    fun restoreCommandNeverReplaysDocker() {
        val docker = DockerContext("web", listOf("docker", "exec", "-it", "web", "bash"))
        assertNull(restoreCommand(TermContext(null, null, docker, now)))
        assertEquals(
            "cd -- '/w' 2>/dev/null",
            restoreCommand(TermContext("/w", null, docker, now)),
        )
    }

    // ───────────────────────── stealthInput ─────────────────────────

    private val nRegex = Regex("""printf '\\033\[(\d+)A""")

    /** 从 stealthInput 的输出里取出 N，并算出按同一 widthOf 量出的期望 N。返回 (实际, 期望)。 */
    private fun actualAndExpectedN(out: String, promptCells: Int, cols: Int, widthOf: (String) -> Int): Pair<Int, Int> {
        assertTrue("应以回车结尾: $out", out.endsWith("\r"))
        assertTrue("应以前置空格开头: $out", out.startsWith(" printf "))
        val actual = nRegex.find(out)!!.groupValues[1].toInt()
        val line = out.removeSuffix("\r")
        val expected = maxOf(1, (promptCells + widthOf(line) + cols - 1) / cols)
        return actual to expected
    }

    private fun assertStable(body: String, promptCells: Int, cols: Int, widthOf: (String) -> Int = ::cellWidth) {
        val out = stealthInput(body, promptCells, cols, widthOf)
        val (actual, expected) = actualAndExpectedN(out, promptCells, cols, widthOf)
        assertEquals("body=$body prompt=$promptCells cols=$cols", expected, actual)
    }

    @Test
    fun stealthInputShortCommandFitsOneLine() {
        // 行宽 27（前缀）+ 27（命令）= 54，加提示符 10 = 64 <= 80，所以 N=1。
        val body = restoreCommand(TermContext("/home/u", null, null, now))!!
        assertEquals(
            " printf '\\033[1A\\r\\033[J'; cd -- '/home/u' 2>/dev/null\r",
            stealthInput(body, 10, 80, ::cellWidth),
        )
    }

    @Test
    fun stealthInputLongCwdWraps() {
        // 前缀 27 + 命令 120 = 147，加提示符 20 = 167，按 80 列折成 3 行（ceil(167/80)=3）。
        val body = restoreCommand(TermContext("a".repeat(100), null, null, now))!!
        val out = stealthInput(body, 20, 80, ::cellWidth)
        assertEquals(" printf '\\033[3A\\r\\033[J'; $body\r", out)
        assertStable(body, 20, 80)
    }

    @Test
    fun stealthInputStableAcrossPromptsAndCols() {
        val short = restoreCommand(TermContext("/home/u", "work", null, now))!!
        val long = restoreCommand(TermContext("/very/long/" + "dir/".repeat(30), "work", null, now))!!
        for (body in listOf(short, long)) {
            for (prompt in listOf(0, 8, 20, 45)) {
                for (cols in listOf(5, 20, 40, 80, 120, 200)) {
                    assertStable(body, prompt, cols)
                }
            }
        }
    }

    @Test
    fun stealthInputCountsWideCharsAsTwoCells() {
        // 全角目录：用 cellWidth 量和用 length 量得出的 N 应各自自洽（且 cellWidth 下不小于 length 下）。
        val body = restoreCommand(TermContext("/home/" + "用户目录".repeat(10), null, null, now))!!
        assertStable(body, 12, 40, ::cellWidth)
        assertStable(body, 12, 40) { it.length }
        val wide = nRegex.find(stealthInput(body, 12, 40, ::cellWidth))!!.groupValues[1].toInt()
        val narrow = nRegex.find(stealthInput(body, 12, 40) { it.length })!!.groupValues[1].toInt()
        assertTrue("宽字符应折出不少于按 length 量的行数", wide >= narrow)
    }

    @Test
    fun stealthInputToleratesZeroCols() {
        val out = stealthInput("echo hi", 5, 0, ::cellWidth)
        assertTrue(out.startsWith(" printf '\\033["))
        assertTrue(out.endsWith("; echo hi\r"))
    }

    // ───────────────────────── restoreNotice ─────────────────────────

    @Test
    fun restoreNoticeAllRows() {
        val docker = DockerContext("web", listOf("docker", "exec", "web", "sh"))
        val ctx = TermContext("/srv", "work", docker, now)
        assertEquals(
            listOf(
                "目录" to "/srv",
                "tmux" to "重新附着会话 work",
                "docker" to "原会话在容器 web 中，未自动进入",
            ),
            restoreNotice(ctx),
        )
    }

    @Test
    fun restoreNoticeCwdOnlyAndEmpty() {
        assertEquals(listOf("目录" to "/home/u"), restoreNotice(TermContext("/home/u", null, null, now)))
        assertEquals(emptyList<Pair<String, String>>(), restoreNotice(TermContext(null, null, null, now)))
    }

    // ───────────────────────── cellWidth ─────────────────────────

    @Test
    fun cellWidthAscii() {
        assertEquals(0, cellWidth(""))
        assertEquals(3, cellWidth("abc"))
        assertEquals(1, cellWidth("é"))
        assertEquals(12, cellWidth("hello world!"))
    }

    @Test
    fun cellWidthCjkAndFullwidth() {
        assertEquals(4, cellWidth("中文"))
        assertEquals(4, cellWidth("a中b"))
        assertEquals(2, cellWidth("，")) // U+FF0C 全角逗号
        assertEquals(2, cellWidth("한")) // 谚文音节
        assertEquals(4, cellWidth("あア")) // 平假名 + 片假名
    }

    @Test
    fun cellWidthEmojiIsOneCodePointTwoCells() {
        // U+1F600 在 UTF-16 里是代理对（2 个 char），必须按 code point 数成 1 个、宽 2。
        assertEquals(2, cellWidth("\uD83D\uDE00"))
        assertEquals(3, cellWidth("a\uD83D\uDE00"))
    }

    @Test
    fun cellWidthControlCharsAreZero() {
        assertEquals(0, cellWidth("\u001b"))
        assertEquals(0, cellWidth("\r"))
        assertEquals(0, cellWidth("\t"))
        assertEquals(2, cellWidth("a\u001bb"))
    }
}
