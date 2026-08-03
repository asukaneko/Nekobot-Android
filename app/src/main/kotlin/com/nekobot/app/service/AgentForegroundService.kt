package com.nekobot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nekobot.app.MainActivity
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.LocalLinuxSandboxCoordinator
import java.util.Collections

/**
 * Agent 执行期间的前台保活服务。
 *
 * 服务按会话引用计数：多个 Agent 会话并行运行时共享一条通知，最后一个会话结束后自动停止。
 */
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELEASE -> {
                intent.getStringExtra(EXTRA_SESSION_ID)?.let(activeSessions::remove)
                if (activeSessions.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP_ALL -> {
                activeSessions.clear()
                runCatching { ServiceContainer.localRepository.stopGeneration() }
                LocalLinuxSandboxCoordinator.closeAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SESSION -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                if (sessionId.isNotBlank()) {
                    activeSessions.remove(sessionId)
                    runCatching { ServiceContainer.localRepository.stopGeneration(sessionId) }
                    LocalLinuxSandboxCoordinator.stopSession(sessionId)
                }
                if (activeSessions.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                intent?.getStringExtra(EXTRA_SESSION_ID)
                    ?.takeIf(String::isNotBlank)
                    ?.let(activeSessions::add)
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        wakeLock?.takeIf { !it.isHeld }?.acquire(6 * 60 * 60 * 1_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release()
        wakeLock = null
        activeSessions.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent 后台执行",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 Agent、浏览器和 Linux 沙盒任务继续运行"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sessions = synchronized(activeSessions) { activeSessions.toList() }
        val onlySessionId = sessions.singleOrNull()
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentForegroundService::class.java)
                .setAction(if (onlySessionId != null) ACTION_STOP_SESSION else ACTION_STOP_ALL)
                .apply { onlySessionId?.let { putExtra(EXTRA_SESSION_ID, it) } },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Agent 正在运行")
            .setContentText("正在执行 ${activeSessions.size.coerceAtLeast(1)} 个会话任务")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (onlySessionId != null) "停止此任务" else "停止全部",
                    stopIntent
                ).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_foreground"
        private const val NOTIFICATION_ID = 0xA63
        private const val ACTION_ACQUIRE = "com.nekobot.app.action.AGENT_ACQUIRE"
        private const val ACTION_RELEASE = "com.nekobot.app.action.AGENT_RELEASE"
        private const val ACTION_STOP_SESSION = "com.nekobot.app.action.AGENT_STOP_SESSION"
        private const val ACTION_STOP_ALL = "com.nekobot.app.action.AGENT_STOP_ALL"
        private const val EXTRA_SESSION_ID = "session_id"

        private val activeSessions = Collections.synchronizedSet(mutableSetOf<String>())

        fun acquire(context: Context, sessionId: String) {
            if (sessionId.isBlank()) return
            activeSessions.add(sessionId)
            context.applicationContext.startForegroundService(
                Intent(context, AgentForegroundService::class.java)
                    .setAction(ACTION_ACQUIRE)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
            )
        }

        fun release(context: Context, sessionId: String) {
            if (sessionId.isBlank()) return
            activeSessions.remove(sessionId)
            runCatching {
                context.applicationContext.startService(
                    Intent(context, AgentForegroundService::class.java)
                        .setAction(ACTION_RELEASE)
                        .putExtra(EXTRA_SESSION_ID, sessionId)
                )
            }
        }
    }
}
