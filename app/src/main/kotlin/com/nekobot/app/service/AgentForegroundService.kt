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
import android.provider.Settings
import com.nekobot.app.MainActivity
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.AgentAttentionCenter
import com.nekobot.app.data.local.ai.AgentAttentionItem
import com.nekobot.app.data.local.ai.AgentAttentionKind
import com.nekobot.app.data.local.ai.AgentOverlayBus
import com.nekobot.app.data.local.ai.LocalLinuxSandboxCoordinator
import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Agent 执行期间的前台保活服务。
 *
 * 服务按会话引用计数：多个 Agent 会话并行运行时共享一条通知，最后一个会话结束后自动停止。
 */
class AgentForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    /** 悬浮窗相关协程只在服务存活期间收集状态。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlayWindow: AgentOverlayWindow? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent")
            .apply { setReferenceCounted(false) }
        overlayWindow = AgentOverlayWindow(this).also { AgentOverlayRegistry.attach(it) }
        serviceScope.launch {
            combine(
                AgentOverlayBus.state,
                ServiceContainer.appForegroundFlow,
                AgentAttentionCenter.pendingBySession
            ) { state, foreground, pending -> Triple(state, foreground, pending) }
                .collect { (state, foreground, pending) -> syncOverlay(state, foreground, pending) }
        }
    }

    /**
     * 悬浮窗可见性：应用在后台且开关开启、有悬浮窗权限时，
     * 优先展示待处理的命令授权请求，其次展示 Android 工具进度。
     *
     * 应用在前台时聊天界面本身有进度卡片与授权弹窗，不重复展示。
     */
    private fun syncOverlay(
        state: AgentOverlayBus.State,
        foreground: Boolean,
        pending: Map<String, List<AgentAttentionItem>>
    ) {
        val overlay = overlayWindow ?: return
        val enabled = !foreground &&
            ServiceContainer.prefs.agentOverlayEnabled &&
            Settings.canDrawOverlays(this)
        if (!enabled) {
            overlay.clearConfirmation()
            overlay.setVisible(false)
            return
        }
        val confirmation = resolvePendingConfirmation(state.sessionId, pending)
        if (confirmation != null) {
            overlay.showConfirmation(confirmation, ::respondToExecConfirmation)
            overlay.setVisible(true)
            return
        }
        overlay.clearConfirmation()
        if (!state.active || state.toolName == null) {
            overlay.setVisible(false)
            return
        }
        overlay.update(state.toolName, state.toolRunning, state.text)
        overlay.setVisible(true)
    }

    /** 优先匹配进度卡片所属会话的授权请求；否则取第一个有待授权项的会话。 */
    private fun resolvePendingConfirmation(
        sessionId: String?,
        pending: Map<String, List<AgentAttentionItem>>
    ): ExecConfirmationRequest? {
        val target = sessionId?.takeIf { id ->
            pending[id].orEmpty().any { it.kind == AgentAttentionKind.ExecAuthorization }
        } ?: pending.entries
            .firstOrNull { (_, items) -> items.any { it.kind == AgentAttentionKind.ExecAuthorization } }
            ?.key
        return target?.let { AgentAttentionCenter.pendingExecConfirmation(it) }
    }

    /** 悬浮窗直接回传授权结果；本地与远程模式走各自的确认通道。 */
    private fun respondToExecConfirmation(
        request: ExecConfirmationRequest,
        authorization: ExecAuthorization
    ) {
        ServiceContainer.applicationScope.launch {
            runCatching {
                if (ServiceContainer.prefs.isLocalMode) {
                    ServiceContainer.unified.respondToLocalExecConfirmation(
                        requestId = request.requestId,
                        authorization = authorization,
                        sessionId = request.sessionId
                    )
                } else {
                    ServiceContainer.socket.respondToExecConfirmation(
                        requestId = request.requestId,
                        authorization = authorization,
                        sessionId = request.sessionId
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELEASE -> {
                intent.getStringExtra(EXTRA_SESSION_ID)?.let { sessionId ->
                    activeSessions.remove(sessionId)
                    AgentOverlayBus.onRunFinished(sessionId)
                }
                if (activeSessions.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP_ALL -> {
                activeSessions.clear()
                AgentOverlayBus.clear()
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
                    AgentOverlayBus.onRunFinished(sessionId)
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
        serviceScope.cancel()
        overlayWindow?.setVisible(false)
        overlayWindow?.hide()
        overlayWindow = null
        AgentOverlayRegistry.attach(null)
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
                getString(R.string.agent_foreground_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.agent_foreground_channel_description)
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
            .setContentTitle(getString(R.string.agent_foreground_title))
            .setContentText(getString(R.string.agent_foreground_task_count, activeSessions.size.coerceAtLeast(1)))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (onlySessionId != null) getString(R.string.agent_foreground_stop_task)
                    else getString(R.string.agent_foreground_stop_all),
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

        /**
         * 后台自动化（定时任务/工作流/主动聊天）专用槽位。
         *
         * 与聊天会话的引用计数分开维护：这些任务由 WorkManager 在应用可能完全处于后台时启动，
         * 必须独立保活，不能因为没有任何聊天会话而停掉前台服务。
         */
        private const val AUTOMATION_SLOT = "automation"

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

        /** 后台自动化任务开始：占用自动化槽位保活（不打扰通知文案的会话计数）。 */
        fun acquireAutomation(context: Context) {
            activeSessions.add(AUTOMATION_SLOT)
            runCatching {
                context.applicationContext.startForegroundService(
                    Intent(context, AgentForegroundService::class.java)
                        .setAction(ACTION_ACQUIRE)
                        .putExtra(EXTRA_SESSION_ID, AUTOMATION_SLOT)
                )
            }
        }

        /** 后台自动化任务结束：释放槽位；无其它会话时前台服务自动停止。 */
        fun releaseAutomation(context: Context) {
            activeSessions.remove(AUTOMATION_SLOT)
            runCatching {
                context.applicationContext.startService(
                    Intent(context, AgentForegroundService::class.java)
                        .setAction(ACTION_RELEASE)
                        .putExtra(EXTRA_SESSION_ID, AUTOMATION_SLOT)
                )
            }
        }
    }
}
