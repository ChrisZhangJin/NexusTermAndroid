package com.nexusterm.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一份待浏览的文档：字节已落在 cacheDir 的临时文件里，Intent 里只带 [id]。 */
class ViewerDoc(
    val id: String,
    val name: String,
    /** 展示用来源，如 `user@host:/path/README.md` */
    val source: String,
    val kind: ViewableKind,
    val file: File,
) {
    /** /doc-html 要不要声明 charset=utf-8；要扫整个文件，所以只算一次。 */
    val htmlIsUtf8: Boolean by lazy {
        runCatching { file.inputStream().use(::isValidUtf8) }.getOrDefault(false)
    }
}

/**
 * 进程内的「id → 文档」表。不走 Intent extras：文档可能是几十 MB，Binder 事务只有 1MB；
 * 也不序列化到磁盘 —— 进程被杀后 Activity 被系统恢复时表是空的，[ViewerActivity] 直接
 * finish()，比展示一个读不到内容的空页面诚实。
 */
object ViewerDocStore {
    private val docs = ConcurrentHashMap<String, ViewerDoc>()

    fun get(id: String?): ViewerDoc? = id?.let { docs[it] }

    fun put(doc: ViewerDoc) {
        docs[doc.id] = doc
    }

    /** 删表项和临时文件；重复调用无副作用。 */
    fun remove(id: String) {
        docs.remove(id)?.file?.delete()
    }

    fun liveFileNames(): Set<String> = docs.values.mapTo(HashSet()) { it.file.name }
}

object ViewerLauncher {
    const val EXTRA_DOC_ID = "nx.viewer.docId"

    private val lock = Any()
    private var swept = false

    private fun dir(context: Context) = File(context.cacheDir, "viewer")

    /**
     * 清掉上一个进程留下的临时文件（Activity 的 onDestroy 没机会跑 = 被杀）。
     * 每个进程只扫一次，且在锁里、先于第一个新文件落盘：那时表里一定还没有活文档，
     * 目录里剩下的全是孤儿，不会误删正在写的并发打开。
     */
    private fun sweepOnce(context: Context) = synchronized(lock) {
        if (swept) return@synchronized
        swept = true
        val live = ViewerDocStore.liveFileNames()
        dir(context).listFiles()?.forEach { if (it.name !in live) it.delete() }
    }

    /**
     * 把 [bytes] 写成临时文件并拉起 [ViewerActivity]。落盘可能是几十 MB，所以是 suspend
     * 并切到 IO；Activity 在调用者的协程上下文（主线程）启动。
     * @throws IOException 磁盘写失败（调用方显示「浏览失败」）
     */
    suspend fun open(context: Context, name: String, source: String, kind: ViewableKind, bytes: ByteArray) {
        val id = UUID.randomUUID().toString().replace("-", "")
        val doc = withContext(Dispatchers.IO) {
            sweepOnce(context)
            val d = dir(context)
            if (!d.isDirectory && !d.mkdirs()) throw IOException("无法创建缓存目录")
            val file = File(d, id)
            try {
                file.writeBytes(bytes)
            } catch (e: IOException) {
                file.delete()
                throw e
            }
            ViewerDoc(id, name, source, kind, file)
        }
        ViewerDocStore.put(doc)
        val intent = Intent(context, ViewerActivity::class.java).putExtra(EXTRA_DOC_ID, id)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            ViewerDocStore.remove(id)
            throw e
        }
    }
}
