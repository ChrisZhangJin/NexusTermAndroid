package com.nexusterm.android.ui

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** viewer 页面所在的假 origin：只有 [ViewerActivity] 的 shouldInterceptRequest 会应答，真实 DNS 里不存在。 */
const val VIEWER_HOST = "nxviewer.local"
const val VIEWER_ENTRY_PATH = "/viewer/viewer.html"

/**
 * HTML 文档（/doc-html）的 CSP —— 桌面版 viewer.rs `csp()` 的逐字移植。
 *
 * 不允许脚本时只放行静态资源（远程图片 / 样式 / 字体）；允许脚本时额外放开
 * script / connect。frame / object / form 一律禁止。iframe 本身另有 sandbox
 * （永不带 allow-same-origin），这里是第二道。
 */
fun htmlCsp(allowJs: Boolean): String {
    val s = StringBuilder(
        "default-src 'none'; img-src * data: blob:; media-src * data: blob:; " +
            "style-src * 'unsafe-inline'; font-src * data:; " +
            "frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'",
    )
    if (allowJs) {
        s.append("; script-src * 'unsafe-inline' 'unsafe-eval' data: blob:; connect-src *")
    } else {
        s.append("; script-src 'none'")
    }
    return s.toString()
}

/** assets/viewer 下静态文件的 Content-Type；没见过的类型一律 text/plain（配合 nosniff 不会被当页面渲染）。 */
fun assetMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "html" -> "text/html"
    "js" -> "text/javascript"
    "css" -> "text/css"
    else -> "text/plain"
}

/**
 * 流式判断 [input] 是否整体是合法 UTF-8（不整块读进内存）。
 * 合法才在 /doc-html 的 Content-Type 里声明 charset=utf-8；否则不写，让 GBK 老页面
 * 按自己的 `<meta charset>` 解码 —— 与桌面版一致。
 */
fun isValidUtf8(input: InputStream): Boolean {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val bytes = ByteArray(64 * 1024)
    val out = CharBuffer.allocate(64 * 1024)
    var carry = 0 // 上一块末尾没凑齐的多字节序列，挪到本块开头
    while (true) {
        val n = input.read(bytes, carry, bytes.size - carry)
        val eof = n < 0
        val buf = ByteBuffer.wrap(bytes, 0, carry + maxOf(n, 0))
        do {
            val r = decoder.decode(buf, out, eof)
            if (r.isError) return false
            out.clear()
        } while (r.isOverflow)
        if (eof) return true
        carry = buf.remaining()
        System.arraycopy(bytes, buf.position(), bytes, 0, carry)
    }
}

/** 塞进 HTML 文档里的 `<base target="_blank">`：没带 href，不受 CSP base-uri 影响。 */
val BASE_TARGET_TAG = "<base target=\"_blank\">".toByteArray(Charsets.US_ASCII)

/** 找插入点时只看文档开头这么多字节。 */
const val BASE_PREFIX_BYTES = 4096

private val HEAD_OPEN = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val HTML_OPEN = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val DOCTYPE = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)

/**
 * [BASE_TARGET_TAG] 在 [prefix]（前 [length] 字节）里的插入位置：`<head>` 之后，
 * 否则 `<html>` 之后，否则 doctype 之后，否则文档开头（跳过 UTF-8 BOM）。
 * 必须放在 doctype 之后，否则会把文档踢进怪异模式。UTF-16 文档不动（返回 0 也会
 * 破坏编码，所以调用方照样插；浏览器按 BOM 解码时这一小段 ASCII 会变成乱码，
 * 极罕见且只影响链接的打开方式，不值得为它再写一套转码）。
 */
fun baseTagInsertOffset(prefix: ByteArray, length: Int): Int {
    val s = String(prefix, 0, length, Charsets.ISO_8859_1)
    HEAD_OPEN.find(s)?.let { return it.range.last + 1 }
    HTML_OPEN.find(s)?.let { return it.range.last + 1 }
    DOCTYPE.find(s)?.let { return it.range.last + 1 }
    val bom = length >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte()
    return if (bom) 3 else 0
}
