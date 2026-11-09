package com.nexusterm.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ViewableKind 的 JVM 单测：用例取自桌面版 viewer.rs 的 `kinds()`，外加 requireExt 行为。 */
class ViewableKindTest {

    @Test fun kindsMatchDesktop() {
        assertEquals(ViewableKind.MARKDOWN, viewableKind("/a/README.md"))
        assertEquals(ViewableKind.MARKDOWN, viewableKind("x.MarkDown"))
        assertEquals(ViewableKind.HTML, viewableKind("C:\\x\\index.HTM"))
        assertNull(viewableKind("a.exe"))
        assertNull(viewableKind(".hidden"))
        assertEquals(ViewableKind.IMAGE, viewableKind("/img/Logo.PNG"))
        assertEquals(ViewableKind.IMAGE, viewableKind("icon.svg"))
        assertEquals(ViewableKind.CODE, viewableKind("/etc/nginx/nginx.conf"))
        assertEquals(ViewableKind.CODE, viewableKind("Cargo.toml"))
        assertEquals(ViewableKind.CODE, viewableKind("docker-compose.yml"))
        assertEquals(ViewableKind.CODE, viewableKind("/app/.env"))
        assertEquals(ViewableKind.CODE, viewableKind(".env.production"))
        assertEquals(ViewableKind.CODE, viewableKind("~/.bashrc"))
        assertEquals(ViewableKind.CODE, viewableKind("Dockerfile"))
        assertEquals(ViewableKind.CODE, viewableKind("schema.sql"))
        assertEquals(ViewableKind.CODE, viewableKind("notes.txt"))
        // 没有扩展名：按纯文本处理
        assertEquals(ViewableKind.CODE, viewableKind("README"))
        assertEquals(ViewableKind.CODE, viewableKind("/var/log/syslog"))
        assertEquals(ViewableKind.CODE, viewableKind("md"))
        assertEquals(ViewableKind.AUDIO, viewableKind("/a/song.mp3"))
        assertEquals(ViewableKind.VIDEO, viewableKind("clip.MP4"))
    }

    @Test fun trailingSlashAndTrailingDot() {
        assertEquals(ViewableKind.MARKDOWN, viewableKind("/a/b.md/"))
        assertNull(viewableKind("foo."))
    }

    @Test fun requireExtRejectsBareWords() {
        assertNull(viewableKind("README", requireExt = true))
        assertNull(viewableKind("/var/log/syslog", requireExt = true))
        assertNull(viewableKind("ls", requireExt = true))
        // 有扩展名 / 命中已知文件名的照常放行
        assertEquals(ViewableKind.MARKDOWN, viewableKind("README.md", requireExt = true))
        assertEquals(ViewableKind.CODE, viewableKind("Dockerfile", requireExt = true))
        assertEquals(ViewableKind.CODE, viewableKind("~/.bashrc", requireExt = true))
        assertEquals(ViewableKind.CODE, viewableKind(".env.local", requireExt = true))
        assertNull(viewableKind("a.exe", requireExt = true))
        assertNull(viewableKind(".hidden", requireExt = true))
    }

    @Test fun limitsMatchDesktop() {
        assertEquals(50L * 1024 * 1024, maxBytes(ViewableKind.IMAGE))
        assertEquals(50L * 1024 * 1024, maxBytes(ViewableKind.AUDIO))
        assertEquals(20L * 1024 * 1024, maxBytes(ViewableKind.VIDEO))
        assertEquals(20L * 1024 * 1024, maxBytes(ViewableKind.MARKDOWN))
        assertEquals(20L * 1024 * 1024, maxBytes(ViewableKind.HTML))
        assertEquals(20L * 1024 * 1024, maxBytes(ViewableKind.CODE))
    }

    @Test fun wireNamesMatchViewerJs() {
        assertEquals(
            listOf("markdown", "html", "image", "audio", "video", "code"),
            ViewableKind.values().map { it.wire },
        )
        assertTrue(UNSUPPORTED_VIEW_MESSAGE.startsWith("不支持的文件类型"))
    }
}
