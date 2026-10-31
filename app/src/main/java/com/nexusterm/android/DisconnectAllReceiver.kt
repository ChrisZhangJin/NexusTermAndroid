package com.nexusterm.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexusterm.android.ssh.SshClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "NexusTermUI"

/**
 * 通知栏「全部断开」按钮的落点。
 *
 * 为什么不是「叫 MainActivity 去断」：`liveSessions` 只活在 MainActivity 的
 * composition 里，没有 ViewModel、也没有进程级单例托管它（NexusTermApplication
 * 只做 initLogging/initStore）。用户在通知栏按这个按钮时，Activity 很可能早就
 * 销毁了，没有任何对象能被唤醒来执行断开。
 *
 * 所以这里绕过 UI 层，直接对 Rust 侧下手：nexus-core 的 `manager()` 是进程级
 * `OnceLock` 单例（nexus-core/src/lib.rs），脱离 Activity 调用完全合法，
 * `disconnect` 本身幂等（同文件注释：「会话已经不存在也不报错」）。
 *
 * Activity 还活着时这条路径也不会和它打架：Rust 关闭会话后会回调
 * `on_closed(reason="user")`，MainActivity 的回调分支负责把对应标签从
 * `liveSessions` 里摘掉（见那里的注释），UI 自然跟着收敛。
 */
class DisconnectAllReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getStringArrayListExtra(EXTRA_SESSION_IDS).orEmpty()
        Log.d(TAG, "通知栏「全部断开」：${ids.size} 条会话")

        // 先收尾通知、再断连接，顺序是故意的：
        // 1) 用户按下按钮的那一刻通知就该消失，否则在断开真正完成前（跨 JNI、
        //    还要等 Rust 的读循环退出）通知会继续挂在那里，看起来像没响应；
        // 2) Activity 若还活着，接下来每收到一条 on_closed 就会从 liveSessions
        //    里摘掉一条，`snapshotFlow` 于是一路吐出「还剩 2 个」「还剩 1 个」
        //    这样的中间态。先告诉 SessionService「这批 id 正在被拆」，它才能
        //    把这些回声吞掉，而不是在后台反复尝试重启前台服务（Android 12+
        //    会直接抛 ForegroundServiceStartNotAllowedException）。
        SessionService.stopForDisconnectAll(context.applicationContext, ids)

        if (ids.isEmpty()) return

        // disconnect 是阻塞的 JNI 调用，不能在 onReceive 的主线程上跑完；
        // goAsync 把广播的生命周期延长到 finish() 为止，否则进程可能在断开
        // 还没发出去时就被判定为空闲回收。
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { id ->
                    // 逐条 runCatching：一条失败（比如这条 id 对应的会话刚好
                    // 已经自己断了）不该让后面几条断不掉。裸 launch 里抛出的
                    // 异常没有 handler，会直接带走进程。
                    runCatching { SshClient.disconnect(id) }
                        .onFailure { Log.w(TAG, "断开 $id 失败", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 随 PendingIntent 一起带走的活跃会话 id 列表。 */
        const val EXTRA_SESSION_IDS = "liveSessionIds"

        fun intent(context: Context, liveSessionIds: List<String>): Intent =
            Intent(context, DisconnectAllReceiver::class.java)
                .putStringArrayListExtra(EXTRA_SESSION_IDS, ArrayList(liveSessionIds))
    }
}
