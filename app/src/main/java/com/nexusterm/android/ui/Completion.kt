package com.nexusterm.android.ui

/**
 * 终端补全建议条的纯逻辑：用户在提示符后敲了半句，历史里哪些命令可能是他要的。
 * 桌面版 src/lib/complete.ts 的 `historyMatches` 的移植；不碰 Android，JVM 单测直接跑。
 *
 * 两个来源，都是「最新的在前」：
 *  - [local]：这台主机上本 App 记下的命令（[CommandHistory]，回车时从屏幕读出）；
 *  - [remote]：远端 shell 自己的历史文件（nexus-core `load_remote_history`，连接后读一次）。
 * 本机记录排前面：它一定是用户在这个 App 里真敲过的，远端文件里可能混着别的客户端、脚本的命令。
 */
internal object Completion {
    /** 敲一个字就弹一串完整命令，噪声远大于帮助（与桌面版 MIN_PREDICT_CHARS 一致）。 */
    const val MIN_PREFIX = 2
    const val MAX_SUGGESTIONS = 8

    /**
     * 以 [typed]（去掉行首空白）开头、且比它长的历史命令，去重后最多 [limit] 条。
     * 完全相同的那条不给：人已经打完了，再「建议」一遍只是打扰。
     */
    fun suggest(
        typed: String,
        local: List<String>,
        remote: List<String>,
        limit: Int = MAX_SUGGESTIONS,
    ): List<String> {
        val prefix = typed.trimStart()
        if (prefix.length < MIN_PREFIX || prefix.any { it.isISOControl() }) return emptyList()
        val out = LinkedHashSet<String>()
        for (cmd in local.asSequence() + remote.asSequence()) {
            if (cmd.length > prefix.length && cmd.startsWith(prefix)) out.add(cmd)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    /**
     * 点建议时真正要补进去的那一段：[cmd] 去掉已经敲出来的部分。
     * [typed] 是屏幕上原样的输入（可能带行首空格），匹配时用的是去掉行首空白的前缀。
     */
    fun remainder(typed: String, cmd: String): String {
        val prefix = typed.trimStart()
        return if (cmd.startsWith(prefix)) cmd.substring(prefix.length) else ""
    }
}

/**
 * 菜单「插入」/ 手势「插入点按处的词」要插的文字：单行选区优先（用户明确选的），否则取落点下
 * 的第一个词（[pointWords]，已按落点优先级排好）。词按「浏览」同一套规则清理首尾引号、尾随
 * 标点（`ls` / 日志里常见），但**不要求是文件**——容器 ID、分支名、PID 都是想插的东西。
 * 都没有返回 null。
 */
internal fun pickInsertTarget(selection: String, pointWords: List<String>): String? {
    val sel = selection.trim()
    if (sel.isNotEmpty() && !sel.contains('\n') && sel.none { it.isISOControl() }) return sel
    return pointWords.firstNotNullOfOrNull { cleanViewCandidate(it) }
}
