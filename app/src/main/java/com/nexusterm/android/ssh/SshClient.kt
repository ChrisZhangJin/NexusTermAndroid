package com.nexusterm.android.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.nexus_core.AuthMethod
import uniffi.nexus_core.SessionHandle
import uniffi.nexus_core.SessionException
import uniffi.nexus_core.TerminalEventListener
import uniffi.nexus_core.connect as uniffiConnect
import uniffi.nexus_core.disconnect as uniffiDisconnect
import uniffi.nexus_core.probe as uniffiProbe
import uniffi.nexus_core.resize as uniffiResize
import uniffi.nexus_core.sshReadFile as uniffiSshReadFile
import uniffi.nexus_core.write as uniffiWrite

/**
 * Thin Kotlin-side wrapper over the generated UniFFI bindings for
 * `nexus-core` (see nexus-core/src/lib.rs for the Rust side of this
 * contract: `connect`/`write`/`resize`/`disconnect`).
 *
 * Kept deliberately dumb: no state, no caching, just type-safe entry points
 * plus the coroutine dispatch needed to keep the (currently blocking, JNI)
 * calls off the main thread. `connect()` in particular runs a full SSH
 * handshake + auth on the Rust side via a blocking JNI call, so it must
 * never be invoked from Dispatchers.Main.
 */
object SshClient {

    /**
     * Establish a connection and open an interactive shell channel.
     * `listener` receives every byte of terminal output plus lifecycle
     * events (pulse/closed) and must answer any keyboard-interactive / key
     * passphrase prompts synchronously (see [TerminalEventListener.onAuthPrompt]).
     *
     * @throws SessionException on handshake/auth/channel failure — callers
     *   should catch this and surface `message` to the user (nexus-core
     *   already translates russh's raw errors into Chinese human-readable
     *   text, see nexus-core/src/errors.rs).
     */
    /**
     * @param configId the saved [uniffi.nexus_core.SessionRecord] id this dial
     *   came from. nexus-core registers the authenticated connection under it,
     *   which is what lets [SftpClient.connect] borrow this connection instead
     *   of handshaking and authenticating all over again.
     * @param sidecar 是否允许 app 另开旁路 exec 通道（[probe] 靠它探测远端 cwd / tmux）。
     *   只允许一条通道的堡垒机会拒绝第二条，甚至掐断整条连接，所以调用方在某个配置
     *   被探测判定为「不支持并行通道」后要改传 false（见 [SidecarPrefs]）；为 false
     *   时 nexus-core 一条额外通道都不开。
     */
    suspend fun connect(
        configId: String,
        host: String,
        port: Int,
        username: String,
        auth: AuthMethod,
        listener: TerminalEventListener,
        sidecar: Boolean = true,
    ): SessionHandle = withContext(Dispatchers.IO) {
        uniffiConnect(configId, host, port.toUShort(), username, auth, listener, sidecar)
    }

    /**
     * 经旁路 exec 通道探测该会话交互 shell 的远端上下文（原始 KEY=VALUE，交给
     * [parseProbe] 解释）。需要连接时 `sidecar = true`。
     *
     * @throws SessionException `Channel` 且文本含「并行通道」= 服务器拒绝第二条通道（堡垒机）；
     *   含「兼容模式」= 本连接没开旁路；`NotFound` = 会话已不在。调用方按需区分，别把它当成用户可见的错误。
     */
    suspend fun probe(sessionId: String): Map<String, String> = withContext(Dispatchers.IO) {
        uniffiProbe(sessionId)
    }

    /**
     * 经旁路 exec 通道 `cat -- <path>` 读远端文件（「浏览」用，不依赖 SFTP）。需要连接时
     * `sidecar = true`。`path` 为绝对路径或 `~/` 开头。累计超过 [max] 字节即中止。
     *
     * @throws SessionException `Channel` 且文本含「并行通道」= 服务器拒绝第二条通道（堡垒机）；
     *   含「兼容模式」= 本连接没开旁路；其余文本（文件超限 / `cat` 的 stderr / 读取超时）可直接展示；
     *   `NotFound` = 会话已不在。
     */
    suspend fun readFile(sessionId: String, path: String, max: Long): ByteArray =
        withContext(Dispatchers.IO) {
            uniffiSshReadFile(sessionId, path, max.toULong())
        }

    // 每次按键都是一个独立协程；全都跳到 Dispatchers.IO 的线程池上并发写，先发的可能后到
    // （快速输入把 "export" 敲成 "expotr"，历史面板的「粘贴 + 回车」让回车跑到粘贴前面）。
    // 同一会话的写入串行化：Mutex 是 FIFO 公平锁，按调用顺序放行；不同会话互不阻塞。
    private val writeLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun write(sessionId: String, bytes: ByteArray) {
        writeLocks.getOrPut(sessionId) { Mutex() }.withLock {
            withContext(Dispatchers.IO) { uniffiWrite(sessionId, bytes) }
        }
    }

    suspend fun resize(sessionId: String, cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        uniffiResize(sessionId, cols.toUShort(), rows.toUShort())
    }

    suspend fun disconnect(sessionId: String) = withContext(Dispatchers.IO) {
        uniffiDisconnect(sessionId)
    }
}
