package com.nexusterm.android.ui

import android.content.Context
import org.json.JSONArray

/**
 * 命令历史的纯逻辑：什么值得记、怎么去重 / 限长、怎么搜。不碰 Android，JVM 单测直接跑。
 * 列表约定「最新的在最前」。
 */
internal object HistoryLog {
    const val MAX_ENTRIES = 500
    const val MAX_LENGTH = 1000

    // 看起来像把口令 / 密钥写在命令行里的写法：`--password=x`、`TOKEN=x`、`sshpass -p x`。
    // 宁可漏记也别把密码存进明文 SharedPreferences；漏掉的命令还有 shell 自己的历史。
    private val SECRET = Regex(
        "(pass(word|wd)?|secret|token|api[_-]?key|sshpass)\\S*(\\s*[=:]\\s*\\S|\\s+\\S)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 记不记：空行、前导空格（沿用 HISTCONTROL=ignorespace 的约定，重连恢复命令也靠这条被
     * 排除）、过长、含控制字符、疑似口令都不记。
     */
    fun recordable(cmd: String): Boolean {
        if (cmd.isBlank() || cmd.length > MAX_LENGTH) return false
        if (cmd[0] == ' ' || cmd[0] == '\t') return false
        if (cmd.any { it.isISOControl() }) return false
        return !SECRET.containsMatchIn(cmd)
    }

    /** 把 [cmd] 放到最前，旧的同名条目去掉，超过 [MAX_ENTRIES] 截尾。 */
    fun add(list: List<String>, cmd: String): List<String> =
        (listOf(cmd) + list.filter { it != cmd }).take(MAX_ENTRIES)

    /** 空格分隔的多个词，全部出现（不分大小写）才算命中；空查询 = 全部。 */
    fun search(list: List<String>, query: String): List<String> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return list
        return list.filter { c ->
            val lower = c.lowercase()
            terms.all { it in lower }
        }
    }
}

/** 按「已保存的会话 id」分开存的命令历史，放在应用私有的 SharedPreferences 里。 */
internal object CommandHistory {
    private const val FILE = "command_history"

    @Synchronized
    fun list(context: Context, key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, null)
            ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(context: Context, key: String, cmd: String) {
        if (key.isEmpty() || !HistoryLog.recordable(cmd)) return
        val next = HistoryLog.add(list(context, key), cmd)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(key, JSONArray(next).toString()).apply()
    }

    @Synchronized
    fun clear(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(key).apply()
    }
}
