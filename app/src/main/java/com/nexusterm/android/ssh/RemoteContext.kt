package com.nexusterm.android.ssh

// 远端终端上下文感知：工作目录 / tmux 会话 / docker 容器 —— 桌面版 src/lib/context.ts 的 Kotlin 移植，
// 服务于「断线重连后还原现场」。
//
// 数据来自 ssh_probe 式的**旁路** exec 通道（读 /proc），不往用户终端写任何东西；这里只负责
// 把原始 KEY=VALUE 解释成结构化上下文，并生成重连后要发给 shell 的「还原命令」。
//
// 刻意是**纯 Kotlin、不依赖 Android**：解析和命令生成都是纯函数，JVM 单测可以直接跑
// （见 RemoteContextTest），也不会因为引入 android.* 而在单测里撞上 mockable android.jar 的桩。
// 与桌面版的差异：只保留 reconnect 模式（Android 端没有「复制会话进同一 tmux」的场景），
// 所以 restoreCommand / restoreNotice 不再带 mode 参数；`now` 由调用方传入而不是内部取
// 系统时钟，让 parseProbe 保持确定性、可测。

/** 正在前台运行的 `docker exec` 命令：[argv] 是原样命令行，[container] 是解析出的容器名。 */
data class DockerContext(val container: String, val argv: List<String>)

/**
 * 一次探测得到的终端上下文。
 *
 *  - [cwd]：交互 shell（或 tmux 当前 pane）的工作目录；
 *  - [tmux]：所在 tmux 会话名；
 *  - [docker]：前台的 docker exec（只用于提示，**从不重放**，见 [restoreCommand]）；
 *  - [at]：探测时间（毫秒时间戳，由调用方给）。
 */
data class TermContext(
    val cwd: String?,
    val tmux: String?,
    val docker: DockerContext?,
    val at: Long,
)

/** 后端用单元分隔符替换 argv 中的 \0（argv 里的参数可能含空格，不能用空白切）。 */
private const val US = "\u001f"

/** docker exec 的带参选项（其后紧跟一个值）。`--user=root` 这种带 = 的写法自带值，不在此列。 */
private val DOCKER_OPTS_WITH_VALUE = setOf(
    "-u", "--user", "-w", "--workdir", "-e", "--env", "--env-file", "--detach-keys",
)

/** 前台进程名是这些之一才去解析 argv。 */
private val DOCKER_BINS = setOf("docker", "docker-compose", "podman")

/**
 * 从前台命令行里找出 `docker exec ... <容器>`。不是 exec（run / attach 等）一律返回 null：
 * 它们无法安全重放，也无法确定「用户原先是在容器里」。
 */
private fun parseDocker(argv: List<String>): DockerContext? {
    val bin = (argv.getOrNull(0) ?: "").substringAfterLast('/')
    var i = 1
    if (bin == "docker" && argv.getOrNull(1) == "compose") {
        i = 2
    } else if (bin !in DOCKER_BINS) {
        return null
    }
    // 跳过全局选项（如 -H host / --context x）：带 = 的占一格，否则连值占两格。
    while (i < argv.size && argv[i].startsWith("-")) {
        i += if (argv[i].contains("=")) 1 else 2
    }
    if (argv.getOrNull(i) != "exec") return null
    i++
    while (i < argv.size && argv[i].startsWith("-")) {
        i += if (argv[i] in DOCKER_OPTS_WITH_VALUE) 2 else 1
    }
    val container = argv.getOrNull(i)
    if (container.isNullOrEmpty()) return null
    return DockerContext(container, argv)
}

/**
 * 把探测的原始 KEY=VALUE 解释成 [TermContext]；什么有用信息都没有（含空 map）返回 null。
 *
 * 规则与桌面版一致：
 *  - 有 TMUX_SESSION：tmux = 它，cwd 取 TMUX_PATH（pane 的真实目录），缺了才用 SHELL_CWD
 *    （此时 shell 其实就是 tmux 客户端本身，目录没意义，pane 目录更准）；tmux 里不再看 docker；
 *  - 否则前台是 docker / docker-compose / podman 且 FG_ARGS 非空 → 解析 docker exec；
 *  - cwd = SHELL_CWD，缺了用 FG_CWD（前台是 vim / less 之类时，其 cwd 与 shell 一致）。
 *
 * 空字符串当作缺失（桌面版是 JS 的 falsy 语义）。
 */
fun parseProbe(raw: Map<String, String>, now: Long): TermContext? {
    if (raw.isEmpty()) return null
    val fgComm = raw["FG_COMM"].orEmpty()
    val fgArgs = raw["FG_ARGS"].orEmpty().split(US).filter { it.isNotEmpty() }

    val tmuxSession = raw["TMUX_SESSION"]
    if (!tmuxSession.isNullOrEmpty()) {
        val cwd = raw["TMUX_PATH"]?.takeIf { it.isNotEmpty() }
            ?: raw["SHELL_CWD"]?.takeIf { it.isNotEmpty() }
        return TermContext(cwd = cwd, tmux = tmuxSession, docker = null, at = now)
    }

    val docker = if (fgComm in DOCKER_BINS && fgArgs.isNotEmpty()) parseDocker(fgArgs) else null
    val cwd = raw["SHELL_CWD"]?.takeIf { it.isNotEmpty() }
        ?: raw["FG_CWD"]?.takeIf { it.isNotEmpty() }
    if (cwd == null && docker == null) return null
    return TermContext(cwd = cwd, tmux = null, docker = docker, at = now)
}

/** POSIX 单引号转义：整体包进单引号，内部的 ' 写成 '\''（结束引号、转义的引号、重开引号）。 */
fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * 生成断线重连后的还原命令主体（不含前置空格与回车，由 [stealthInput] 包装）；没有要做的事返回 null。
 *
 * 只有 reconnect 一种模式：tmux 会话在服务端还活着，直接 attach 回去；cwd 先 cd 过去
 * （`2>/dev/null`：目录没了就静默跳过，别在终端里喷错误）。两条用 "; " 连接而不是 "&&"，
 * 目录没了也要接着 attach。
 *
 * docker **不重放**：前台进程的命令行无法区分「进容器」与「一次性命令」，重放可能重复执行
 * 用户不想要的操作，只在 [restoreNotice] 里提示。
 */
fun restoreCommand(ctx: TermContext): String? {
    val parts = ArrayList<String>(2)
    if (!ctx.cwd.isNullOrEmpty()) parts += "cd -- ${shq(ctx.cwd)} 2>/dev/null"
    if (!ctx.tmux.isNullOrEmpty()) parts += "tmux attach -t ${shq(ctx.tmux)}"
    if (parts.isEmpty()) return null
    return parts.joinToString("; ")
}

/**
 * 把还原命令包装成「不留痕」的终端输入：
 *  - 前置空格让 HISTCONTROL=ignorespace 不记 history；
 *  - `printf '\033[NA\r\033[J'` 光标上移 N 行、回行首、清到屏尾，抹掉「提示符 + 回显出来的命令」，
 *    改由界面上的说明卡片告知用户做了什么；
 *  - 末尾 \r 提交。
 *
 * N = 提示符与整行命令按列数折行后占的行数 = ceil((promptCells + 命令宽度) / cols)。
 * 难点在于命令里的 N 自己也占宽度（9 → 10 会多一格，可能再多折一行），所以**迭代到稳定**；
 * 最多 4 轮，不收敛（N 在两个值之间摆动）就用最后一个 N —— 多清一行比少清一行安全得多。
 *
 * [widthOf] 由调用方注入（通常是 [cellWidth]），好让单测可以换成 String::length 之类。
 * [cols] 小于 1 按 1 处理，避免除零。
 */
fun stealthInput(body: String, promptCells: Int, cols: Int, widthOf: (String) -> Int): String {
    var n = 1
    repeat(4) {
        val line = " printf '\\033[${n}A\\r\\033[J'; $body"
        val need = maxOf(1, ceilDiv(promptCells + widthOf(line), maxOf(cols, 1)))
        if (need == n) return "$line\r"
        n = need
    }
    return " printf '\\033[${n}A\\r\\033[J'; $body\r"
}

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

/** 终端里展示的「上下文恢复」说明卡片内容：(标签, 说明) 逐行。 */
fun restoreNotice(ctx: TermContext): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>(3)
    if (!ctx.cwd.isNullOrEmpty()) rows += "目录" to ctx.cwd
    if (!ctx.tmux.isNullOrEmpty()) rows += "tmux" to "重新附着会话 ${ctx.tmux}"
    if (ctx.docker != null) rows += "docker" to "原会话在容器 ${ctx.docker.container} 中，未自动进入"
    return rows
}

/**
 * 字符串在终端里占的列数：CJK / 全角 / emoji 占 2，控制字符与零宽字符占 0，其余 1。
 * 按 code point 迭代（emoji 是代理对，按 char 数会数成两个）。
 *
 * 这是 [stealthInput] 估算折行用的近似，不是完整的 wcwidth：覆盖常见区段即可，估偏了的后果
 * 只是多/少清一行。零宽字符（组合附加符、ZWJ、变体选择符）xterm.js 同样按 0 列处理。
 */
fun cellWidth(s: String): Int {
    var width = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        width += cpWidth(cp)
    }
    return width
}

private fun cpWidth(cp: Int): Int = when {
    cp < 0x20 || cp in 0x7F..0x9F -> 0
    cp in 0x0300..0x036F || cp == 0x200B || cp == 0x200D || cp in 0xFE00..0xFE0F -> 0
    isWide(cp) -> 2
    else -> 1
}

private fun isWide(cp: Int): Boolean =
    cp in 0x1100..0x115F ||    // 谚文字母
        cp in 0x2E80..0x303E || // CJK 部首、康熙部首、CJK 符号和标点（含全角空格 U+3000）
        cp in 0x3041..0x33FF || // 假名、注音、CJK 兼容
        cp in 0x3400..0x4DBF || // CJK 扩展 A
        cp in 0x4E00..0x9FFF || // CJK 统一表意文字
        cp in 0xA000..0xA4CF || // 彝文
        cp in 0xAC00..0xD7A3 || // 谚文音节
        cp in 0xF900..0xFAFF || // CJK 兼容表意文字
        cp in 0xFE30..0xFE6F || // CJK 兼容形式、小写变体
        cp in 0xFF00..0xFF60 || // 全角 ASCII 与标点
        cp in 0xFFE0..0xFFE6 || // 全角符号
        cp in 0x1F300..0x1F64F || // emoji：杂项符号与象形、表情
        cp in 0x1F680..0x1F6FF || // emoji：交通与地图
        cp in 0x1F900..0x1F9FF || // emoji：补充符号与象形
        cp in 0x20000..0x3FFFD  // CJK 扩展 B 及以后
