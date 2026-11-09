package com.nexusterm.android.ui

/**
 * 「浏览」支持的文档类型。名字（小写）就是 viewer.js 里 `kind` 的取值，
 * 原样写进 /doc-meta 的 JSON，所以不能随便改。
 */
enum class ViewableKind(val wire: String) {
    MARKDOWN("markdown"),
    HTML("html"),
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video"),
    CODE("code"),
}

private const val MB = 1024L * 1024L

/** 读取上限，与桌面版 viewer.rs 的 max_bytes 一致：md/html/code 20MB，图片/音频 50MB，视频 20MB。 */
fun maxBytes(kind: ViewableKind): Long = when (kind) {
    ViewableKind.IMAGE, ViewableKind.AUDIO -> 50 * MB
    ViewableKind.VIDEO -> 20 * MB
    else -> 20 * MB
}

/** 与桌面版 require_kind 同文案。 */
const val UNSUPPORTED_VIEW_MESSAGE =
    "不支持的文件类型。可浏览：Markdown、HTML、图片（png/jpg/gif/webp/bmp/ico/svg/avif）、" +
        "音频（mp3/wav/flac/ogg 等）、视频（mp4/webm/mov/mkv/avi 等，≤20MB）、" +
        "文本与配置文件（json/yaml/toml/ini/conf/properties/xml/sql/.env 等，无扩展名的文件按纯文本处理）"

private val CODE_NAMES = setOf(
    ".env", ".gitconfig", ".gitmodules", ".editorconfig", ".npmrc",
    ".bashrc", ".zshrc", ".profile", ".bash_profile", "dockerfile", "makefile",
)
private val MD_EXT = setOf("md", "markdown", "mdown", "mkd", "mkdn")
private val HTML_EXT = setOf("html", "htm", "xhtml")
private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "jfif", "gif", "webp", "bmp", "ico", "svg", "avif")
private val AUDIO_EXT = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma")
private val VIDEO_EXT = setOf("mp4", "webm", "mov", "mkv", "avi", "m4v")
private val CODE_EXT = setOf(
    "json", "jsonc", "json5", "yaml", "yml", "toml", "ini", "cfg", "conf", "cnf", "env",
    "service", "properties", "xml", "plist", "sql", "txt", "log",
)

/**
 * 按文件名判断可浏览类型 —— 桌面版 viewer.rs `kind_of` 与 src/lib/viewable.ts
 * `viewableKind` 的 Kotlin 移植，三处必须保持一致。
 *
 * [requireExt]：终端里「光标下自动挑出来的单词」用。无扩展名文件在 SFTP 面板里按纯文本
 * 打开（README、syslog），但终端里随手一个单词（`ls`、`git`）也会被当成纯文本文件，
 * 所以那边要求必须带扩展名（或命中 [CODE_NAMES]）。
 */
fun viewableKind(path: String, requireExt: Boolean = false): ViewableKind? {
    val name = path.split('/', '\\').lastOrNull { it.isNotEmpty() }?.lowercase() ?: path.lowercase()
    if (name in CODE_NAMES || name.startsWith(".env.")) return ViewableKind.CODE
    val dot = name.lastIndexOf('.')
    // ".foo" 这类隐藏文件、"foo." 都不算扩展名
    val ext = if (dot <= 0 || dot == name.length - 1) null else name.substring(dot + 1)
    if (ext == null) {
        if (requireExt) return null
        return if (name.contains('.')) null else ViewableKind.CODE
    }
    return when (ext) {
        in MD_EXT -> ViewableKind.MARKDOWN
        in HTML_EXT -> ViewableKind.HTML
        in IMAGE_EXT -> ViewableKind.IMAGE
        in AUDIO_EXT -> ViewableKind.AUDIO
        in VIDEO_EXT -> ViewableKind.VIDEO
        in CODE_EXT -> ViewableKind.CODE
        else -> null
    }
}
