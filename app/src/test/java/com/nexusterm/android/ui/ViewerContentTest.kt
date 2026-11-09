package com.nexusterm.android.ui

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ViewerContent 的 JVM 单测：CSP 开关、UTF-8 流式校验、静态资源 mime。 */
class ViewerContentTest {

    @Test fun cspTogglesScript() {
        assertTrue(htmlCsp(false).contains("script-src 'none'"))
        assertFalse(htmlCsp(false).contains("connect-src"))
        assertTrue(htmlCsp(true).contains("script-src *"))
        assertTrue(htmlCsp(true).contains("connect-src *"))
        // 两种模式都不能放开 frame / object / form
        for (js in listOf(false, true)) {
            val c = htmlCsp(js)
            assertTrue(c.contains("default-src 'none'"))
            assertTrue(c.contains("frame-src 'none'"))
            assertTrue(c.contains("object-src 'none'"))
            assertTrue(c.contains("form-action 'none'"))
            assertTrue(c.contains("base-uri 'none'"))
        }
    }

    private fun utf8(bytes: ByteArray) = isValidUtf8(ByteArrayInputStream(bytes))

    @Test fun utf8Validation() {
        assertTrue(utf8(ByteArray(0)))
        assertTrue(utf8("hello 你好 😀".toByteArray(Charsets.UTF_8)))
        assertFalse(utf8("你好".toByteArray(charset("GBK"))))
        // 末尾被截断的多字节序列
        assertFalse(utf8("你".toByteArray(Charsets.UTF_8).copyOf(2)))
        assertFalse(utf8(byteArrayOf(0xFF.toByte())))
    }

    @Test fun utf8SequenceStraddlingChunkBoundary() {
        // 64KB 块边界正好劈开一个 3 字节字符：不能误判为非法
        for (pad in 65534..65537) {
            val s = "a".repeat(pad) + "你好" + "b".repeat(10)
            assertTrue("pad=$pad", utf8(s.toByteArray(Charsets.UTF_8)))
        }
        val big = "你好世界".repeat(100_000).toByteArray(Charsets.UTF_8)
        assertTrue(utf8(big))
        assertFalse(utf8(big + byteArrayOf(0xE4.toByte())))
    }

    @Test fun assetMimes() {
        assertEquals("text/html", assetMime("viewer.html"))
        assertEquals("text/javascript", assetMime("vendor/marked.min.js"))
        assertEquals("text/css", assetMime("viewer.css"))
        assertEquals("text/plain", assetMime("vendor/LICENSE-marked"))
        assertEquals("text/plain", assetMime("vendor/README.txt"))
    }

    private fun inject(html: String): String {
        val b = html.toByteArray(Charsets.UTF_8)
        val at = baseTagInsertOffset(b, b.size)
        return String(b, 0, at, Charsets.UTF_8) + String(BASE_TARGET_TAG) + String(b, at, b.size - at, Charsets.UTF_8)
    }

    @Test fun baseTagGoesAfterHeadHtmlOrDoctype() {
        val tag = String(BASE_TARGET_TAG)
        assertEquals("<!doctype html><HTML lang=en><head>$tag<title>x</title>", inject("<!doctype html><HTML lang=en><head><title>x</title>"))
        assertEquals("<html>$tag<body>hi", inject("<html><body>hi"))
        assertEquals("<!DOCTYPE html>$tag\n<p>hi", inject("<!DOCTYPE html>\n<p>hi"))
        assertEquals("${tag}<p>hi", inject("<p>hi"))
        assertEquals(tag, inject(""))
    }

    @Test fun baseTagSkipsUtf8Bom() {
        val b = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'x'.code.toByte())
        assertEquals(3, baseTagInsertOffset(b, b.size))
    }
}
