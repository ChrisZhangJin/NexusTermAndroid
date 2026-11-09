package com.nexusterm.android.ui

// 终端里「浏览」的纯函数部分：从选区 / 落点词里挑出要打开的路径、把相对路径按 shell 当前目录
// 拼成绝对路径、判断旁路通道失败的原因。桌面版 TerminalView.tsx 的 `viewablePath` / `doView`
// 的移植；刻意不依赖 Android，JVM 单测直接跑（RemoteViewPathTest）。

/**
 * 选区 / 词的原始文字 → 清理后的路径候选；多行或空返回 null。
 * 去掉 ls / 日志里常见的首尾引号、尾随标点（`,;:`）和 `file://` 前缀。
 * 只清理，不判断是不是可浏览文件（那是 [viewableKind] 的事）。
 */
fun cleanViewCandidate(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty() || s.contains('\n')) return null
    s = s.trimStart('\'', '"', '`').trimEnd('\'', '"', '`', ',', ';', ':')
    if (s.startsWith("file://", ignoreCase = true)) s = s.substring("file://".length)
    return s.ifEmpty { null }
}

/**
 * 菜单「浏览」的目标：[selection]（用户明确选中的）能解析成可浏览文件就用它——这是明确意图，
 * 不要求扩展名，所以 README 这类无扩展名文件选中后能开；否则退到落点下的 [word]，
 * 并且**要求带扩展名**：整行没有空格的 `user@host:~/path$` 会被当成一个词，若放行「无扩展名
 * 当纯文本」就会拿它去 `cat`。都不行返回 null。
 */
fun pickViewTarget(selection: String, word: String): String? {
    cleanViewCandidate(selection)?.takeIf { viewableKind(it) != null }?.let { return it }
    return cleanViewCandidate(word)?.takeIf { viewableKind(it, requireExt = true) != null }
}

/** 绝对路径（`/…`）或家目录路径（`~`、`~/…`）：不需要知道 shell 当前目录。 */
fun isAbsoluteRemotePath(p: String): Boolean = p.startsWith("/") || p == "~" || p.startsWith("~/")

/** 相对路径 [p] 接到 shell 当前目录 [cwd] 后面，去掉开头的 `./`。 */
fun joinCwd(cwd: String, p: String): String {
    var rel = p
    while (rel.startsWith("./")) rel = rel.substring(2)
    return (if (cwd.endsWith("/")) cwd else "$cwd/") + rel
}

/** 菜单项 / 提示里显示的文件名（路径最后一段）。 */
fun viewFileName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/** 旁路通道（探测 / `cat`）失败的原因，文案分类沿用 MainActivity.probeLoop 的判断。 */
enum class SidecarFailure {
    /** 服务器拒绝第二条通道（堡垒机）：这个配置应记入 SidecarPrefs。 */
    DENIED,

    /** 本连接没开旁路（兼容模式）。 */
    OFF,

    /** 其它：文件超限、`cat` 的 stderr、超时、会话已不在…… */
    OTHER,
}

fun classifySidecarError(text: String): SidecarFailure = when {
    text.contains("并行通道") -> SidecarFailure.DENIED
    text.contains("兼容模式") -> SidecarFailure.OFF
    else -> SidecarFailure.OTHER
}

const val VIEW_NO_CWD_MESSAGE = "无法确定远端当前目录，请选中完整路径（/… 或 ~/…）"
const val VIEW_COMPAT_MODE_MESSAGE =
    "该连接处于兼容模式（服务器不支持并行通道），无法自动获取远端目录，请选中完整路径（/… 或 ~/…）"

/** 读到的是目录：SFTP 端是「…」是目录，终端端是 `cat` 的 Is a directory。 */
fun isDirectoryError(text: String): Boolean =
    text.contains("是目录") || text.contains("Is a directory", ignoreCase = true)

/** 把远端常见的英文报错换成中文；认不出的原样返回。 */
fun localizeRemoteError(text: String): String = when {
    text.contains("No such file", ignoreCase = true) -> "文件不存在"
    text.contains("Permission denied", ignoreCase = true) -> "没有权限读取该文件"
    else -> text
}

fun directoryViewMessage(name: String): String = "「$name」是目录，无法浏览"
