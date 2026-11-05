package com.nexusterm.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

private const val TAG = "NexusTermUI"

// 渠道一旦创建，名字之外的属性（角标、锁屏可见性）之后都改不了，所以升级通知
// 的外观只能换一个新 id，并把旧渠道删掉，免得用户设置里留着两个同名条目。
private const val CHANNEL_ID = "sessions_v2"
private const val LEGACY_CHANNEL_ID = "sessions"
private const val NOTIFICATION_ID = 1

private const val EXTRA_TITLES = "titles"
private const val EXTRA_RECONNECTING = "reconnecting"

/** 通知里最多逐行列出几个会话；系统的 InboxStyle 本来就只画 5 行，超出的折进摘要。 */
private const val MAX_INBOX_LINES = 5
private const val EXTRA_SESSION_IDS = "liveSessionIds"

/**
 * PendingIntent 的 requestCode。固定值 + [PendingIntent.FLAG_UPDATE_CURRENT]
 * 才构成「同一个 PendingIntent，换一份 extras」——见 [SessionService.buildNotification]
 * 里对 flag 的说明。
 */
private const val REQUEST_OPEN_APP = 0
private const val REQUEST_DISCONNECT_ALL = 1

/**
 * Foreground service whose only job is to exist while there are live SSH
 * sessions. The connections themselves live in nexus-core's tokio runtime and
 * the tabs in [MainActivity]'s composition — nothing here touches either. What
 * the service buys is process priority: without it, a backgrounded NexusTerm
 * is an ordinary cached process and Android kills it (and every session with
 * it) within minutes of the user switching to another app.
 *
 * Driven by [MainActivity] through [update]/[stop]; the notification doubles
 * as the "N 个会话已连接" status and a way back into the app, and carries the
 * 「全部断开」action（[DisconnectAllReceiver]，它是唯一一个不经过 MainActivity
 * 就能停掉这个服务的调用方，见 [Companion.stopForDisconnectAll]）。
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val titles = intent?.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val ids = intent?.getStringArrayListExtra(EXTRA_SESSION_IDS).orEmpty()
        val reconnecting = intent?.getBooleanArrayExtra(EXTRA_RECONNECTING)?.toList().orEmpty()
        // Must happen on every start, not just the first: startForegroundService
        // obliges each call to be answered with startForeground within ~5s.
        // The type argument is only meaningful (and only declared-checked) on
        // API 34+; older platforms take NONE and use the manifest entry.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this, titles, ids, reconnecting), type)
        // NOT_STICKY: if the process dies the tabs die with it, so a restarted
        // service would just be an empty notification claiming sessions exist.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        /**
         * Main-thread only — MainActivity 的两个调用点，加上
         * [DisconnectAllReceiver.onReceive]（广播的 onReceive 也跑在主线程；
         * 它那条后台线程只负责调 Rust 的 disconnect，不碰这里）。Lets [update]
         * repaint the notification in place instead of re-issuing
         * startForegroundService, which from the background — e.g. a session
         * dropping into reconnect while the user is in another app — is
         * exactly what Android 12+ refuses with
         * ForegroundServiceStartNotAllowedException.
         */
        private var running = false

        /**
         * 「全部断开」已经发起、但 Activity 侧还没把这些标签清干净的那段窗口里，
         * 被断掉的那批 `liveSessionId`。
         *
         * 存在的理由：[DisconnectAllReceiver] 一按就停服务，而 Activity 若还
         * 活着，会随着一条条 `on_closed` 回调逐个摘标签，`snapshotFlow` 于是
         * 吐出「还剩 2 个」「还剩 1 个」这样的中间态。那些 update 全是过期回声，
         * 照单全收的话：轻则通知闪一下又回来，重则在后台触发
         * startForegroundService 被 Android 12+ 拒掉（见 [update] 的 catch）。
         *
         * 用 id 集合而不是一个 boolean，是为了能区分「回声」和「用户又连了一条
         * 新的」——后者的 id 不在这个集合里，服务就该照常起来。Receiver 和
         * Activity 在同一个进程（Manifest 里没有 android:process），所以这个
         * companion 字段两边看到的是同一份；@Volatile 是因为 Receiver 的收尾
         * 线程和主线程都会读写它。
         */
        @Volatile private var tornDownIds: Set<String> = emptySet()

        /** 这一轮会话里第一次起服务的时刻（毫秒），通知头部的计时器以它为起点；0 = 还没有。 */
        private var startedAt = 0L

        /**
         * Starts the service, or refreshes its notification if it's already
         * up. [titles] are the non-closed tabs, [liveSessionIds] their
         * nexus-core session ids in the same order（通知上的「全部断开」要靠
         * 它才知道该断哪些）；[reconnecting] of them are mid-retry, the rest
         * connected. [reconnecting] 与 [titles] 一一对应，true = 这一条正在重连。
         */
        fun update(
            context: Context,
            titles: List<String>,
            liveSessionIds: List<String>,
            reconnecting: List<Boolean>,
        ) {
            val tornDown = tornDownIds
            if (tornDown.isNotEmpty()) {
                // 整批都是正在被拆的那些 id → 这是一声回声，忽略。
                if (liveSessionIds.isNotEmpty() && liveSessionIds.all { it in tornDown }) return
                // 出现了不属于这一批的会话（用户又连上了一条），拆解窗口结束。
                tornDownIds = emptySet()
            }
            if (running) {
                // Same id as the foreground notification, so this replaces it.
                // Silently a no-op if POST_NOTIFICATIONS was denied.
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(context, titles, liveSessionIds, reconnecting))
                return
            }
            val intent = Intent(context, SessionService::class.java)
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putStringArrayListExtra(EXTRA_SESSION_IDS, ArrayList(liveSessionIds))
                .putExtra(EXTRA_RECONNECTING, reconnecting.toBooleanArray())
            try {
                context.startForegroundService(intent)
                running = true
                // 通知上的计时从这一轮会话第一次出现算起；stop 时清零。
                if (startedAt == 0L) startedAt = System.currentTimeMillis()
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException (API 31+, app in
                // background) / IllegalStateException. Losing the notification
                // isn't worth a crash: the sessions still work, they're just
                // back to being killable once the app is backgrounded. The
                // next state change after the user returns retries the start.
                Log.w(TAG, "无法启动前台服务，后台会话可能被系统回收", e)
            }
        }

        fun stop(context: Context) {
            running = false
            startedAt = 0L
            // 正常停服务 = 这一轮会话全没了，拆解窗口自然结束。放在 stop 里而
            // 不是只在 update 里清，是为了让「Activity 最后那次空列表 stop」
            // 成为窗口的终点——否则这个集合会一直留着，影响下一轮连接。
            tornDownIds = emptySet()
            context.stopService(Intent(context, SessionService::class.java))
        }

        /**
         * [DisconnectAllReceiver] 专用的停服务入口：先按正常路径停掉（服务被
         * 销毁时系统会一并撤掉它的前台通知），再把这批 id 标成「正在拆」，
         * 让 Activity 随后吐出的那串中间态 update 被 [update] 吞掉。
         *
         * 顺序不能反：[stop] 自己会清空 [tornDownIds]。
         */
        fun stopForDisconnectAll(context: Context, liveSessionIds: List<String>) {
            stop(context)
            tornDownIds = liveSessionIds.toSet()
        }

        private fun buildNotification(
            context: Context,
            titles: List<String>,
            liveSessionIds: List<String>,
            reconnecting: List<Boolean>,
        ): Notification {
            // Idempotent; cheaper to call every time than to track whether
            // this process has done it yet. LOW = no sound, no heads-up —
            // this is a status line, not an event.
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SSH 会话", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "有 SSH 会话保持连接时常驻，用来在后台保活并提供快捷入口"
                    // 它是一条状态，不是「有新消息」：不要在图标上打角标。
                    setShowBadge(false)
                    // 通知上有主机名，锁屏上默认不展示，见下面的 publicVersion。
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )

            // 同一台主机开两个标签时标题一模一样，通知里「101、101」分不出谁是谁，
            // 重名的补上序号。只用于通知里的列表；单个会话的标题保持原样。
            val names = titles.mapIndexed { i, name ->
                if (titles.count { it == name } > 1) "$name #${titles.take(i + 1).count { it == name }}" else name
            }
            val total = titles.size
            val retrying = reconnecting.count { it }
            val connected = total - retrying
            val single = total == 1

            // 折叠态一眼要回答两个问题：连着几个？有没有出问题？
            // 只有一个会话时直接把它的名字放在标题——「1 个会话已连接」没有信息量。
            val title = when {
                single -> titles[0]
                connected == 0 -> "$retrying 个会话重连中"
                retrying > 0 -> "$connected 个会话已连接，$retrying 个重连中"
                else -> "$connected 个会话已连接"
            }
            val text = when {
                single && retrying == 0 -> "已连接 · 点按回到终端"
                single -> "连接中断，正在自动重连"
                else -> names.joinToString("、")
            }

            // Same action/category as the launcher icon plus SINGLE_TOP |
            // REORDER_TO_FRONT, so tapping brings the existing task (and its
            // still-connected tabs) forward rather than stacking a fresh
            // MainActivity with an empty liveSessions on top of it.
            val open = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            val contentIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN_APP,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            // 「全部断开」。两个 flag 都是必须的，而且理由不同：
            // - FLAG_IMMUTABLE：API 31+ 强制要求可变性必须显式声明，不带就直接
            //   抛 IllegalArgumentException；这个 PendingIntent 也确实不需要
            //   被接收方改写。
            // - FLAG_UPDATE_CURRENT：extras 里装着会话 id 列表，而它每次连接/
            //   断开/重连成功都会变。PendingIntent 的相等性只看
            //   (requestCode, action, data, type, class, categories)，**不看
            //   extras** —— 不带这个 flag，系统会把后来的构造请求认成「同一个
            //   已存在的 PendingIntent」并原样返回旧的那个，按钮于是永远拿着
            //   第一次建通知时的那份 id 列表去断，后开的标签一条也断不掉。
            val disconnectAll = PendingIntent.getBroadcast(
                context,
                REQUEST_DISCONNECT_ALL,
                DisconnectAllReceiver.intent(context, liveSessionIds),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            // 展开态：多个会话逐行列出，前面的符号区分状态（● 在线，↻ 重连中）。
            // 只有一个会话时逐行列表只会把标题再抄一遍、还把提示语挤掉，
            // 改用 BigTextStyle 展开显示那句提示。
            val style: NotificationCompat.Style = if (single) {
                NotificationCompat.BigTextStyle().bigText(text)
            } else {
                val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
                names.take(MAX_INBOX_LINES).forEachIndexed { i, name ->
                    inbox.addLine(if (reconnecting.getOrElse(i) { false }) "↻ $name" else "● $name")
                }
                if (total > MAX_INBOX_LINES) inbox.setSummaryText("另外 ${total - MAX_INBOX_LINES} 个会话")
                inbox
            }

            // 锁屏上的版本：不带主机名，只说有几个会话在跑。
            val public = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle("NexusTerm")
                .setContentText("$total 个 SSH 会话")
                .setColor(context.getColor(R.color.nexus_green))
                .build()

            // 有重连中的会话时换成琥珀色，状态栏之外的这条通知也能一眼看出「不太对」。
            val accent = context.getColor(if (retrying > 0) R.color.nexus_amber else R.color.nexus_green)

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                // 图标传 0：API 24 起通知抽屉就不再画 action 图标了，而
                // NotificationCompat 对 0 的处理是「不带图标」（它只在非 0 时
                // 才去 IconCompat.createWithResource），所以这里不必为一个不会
                // 显示的位置造一个新 drawable。
                .addAction(0, if (single) "断开" else "全部断开", disconnectAll)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setColor(accent)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setPublicVersion(public)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // Android 12+ otherwise holds a new FGS notification back for
                // up to 10s; the user just tapped "connect" and should see it.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            // 头部显示「已连接多久」。计时器由系统自己走，不用我们定时刷新通知；
            // 全在重连时不显示——那时说「已连接 12:34」是在撒谎。
            if (connected > 0 && startedAt != 0L) {
                builder.setShowWhen(true).setWhen(startedAt).setUsesChronometer(true)
            } else {
                builder.setShowWhen(false)
            }
            return builder.build()
        }
    }
}
