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

private const val CHANNEL_ID = "sessions"
private const val NOTIFICATION_ID = 1

private const val EXTRA_TITLES = "titles"
private const val EXTRA_RECONNECTING = "reconnecting"

/**
 * Foreground service whose only job is to exist while there are live SSH
 * sessions. The connections themselves live in nexus-core's tokio runtime and
 * the tabs in [MainActivity]'s composition — nothing here touches either. What
 * the service buys is process priority: without it, a backgrounded NexusTerm
 * is an ordinary cached process and Android kills it (and every session with
 * it) within minutes of the user switching to another app.
 *
 * Driven entirely by [MainActivity] through [update]/[stop]; the notification
 * doubles as the "N 个会话已连接" status and a way back into the app.
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val titles = intent?.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val reconnecting = intent?.getIntExtra(EXTRA_RECONNECTING, 0) ?: 0
        // Must happen on every start, not just the first: startForegroundService
        // obliges each call to be answered with startForeground within ~5s.
        // The type argument is only meaningful (and only declared-checked) on
        // API 34+; older platforms take NONE and use the manifest entry.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this, titles, reconnecting), type)
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
         * Main-thread only (both callers live in MainActivity). Lets [update]
         * repaint the notification in place instead of re-issuing
         * startForegroundService, which from the background — e.g. a session
         * dropping into reconnect while the user is in another app — is
         * exactly what Android 12+ refuses with
         * ForegroundServiceStartNotAllowedException.
         */
        private var running = false

        /**
         * Starts the service, or refreshes its notification if it's already
         * up. [titles] are the non-closed tabs; [reconnecting] of them are
         * mid-retry, the rest connected.
         */
        fun update(context: Context, titles: List<String>, reconnecting: Int) {
            if (running) {
                // Same id as the foreground notification, so this replaces it.
                // Silently a no-op if POST_NOTIFICATIONS was denied.
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(context, titles, reconnecting))
                return
            }
            val intent = Intent(context, SessionService::class.java)
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putExtra(EXTRA_RECONNECTING, reconnecting)
            try {
                context.startForegroundService(intent)
                running = true
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
            context.stopService(Intent(context, SessionService::class.java))
        }

        private fun buildNotification(context: Context, titles: List<String>, reconnecting: Int): Notification {
            // Idempotent; cheaper to call every time than to track whether
            // this process has done it yet. LOW = no sound, no heads-up —
            // this is a status line, not an event.
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SSH 会话", NotificationManager.IMPORTANCE_LOW),
            )

            val connected = titles.size - reconnecting
            val title = when {
                connected == 0 -> "$reconnecting 个会话重连中"
                reconnecting > 0 -> "$connected 个会话已连接，$reconnecting 个重连中"
                else -> "$connected 个会话已连接"
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
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
            titles.forEach(inbox::addLine)

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_session)
                .setContentTitle(title)
                .setContentText(titles.joinToString("、"))
                .setStyle(inbox)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // Android 12+ otherwise holds a new FGS notification back for
                // up to 10s; the user just tapped "connect" and should see it.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
