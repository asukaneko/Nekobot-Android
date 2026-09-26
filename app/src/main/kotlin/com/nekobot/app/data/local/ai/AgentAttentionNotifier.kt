package com.nekobot.app.data.local.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nekobot.app.MainActivity
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer

/**
 * Agent 等待用户处理的系统通知。
 *
 * AI 请求命令授权、调用 ask_user_question 提问时，若用户不在该会话，界面弹窗看不到，
 * 也不知道 Agent 已卡在等待中。这里发通知提醒，点击直接进入对应会话；
 * 由 [AgentAttentionCenter] 统一决定何时提醒与撤销。
 */
object AgentAttentionNotifier {

    private const val CHANNEL_ID = "agent_attention"

    /** 通知 id 以会话 hashCode 为基准；回复提醒占 0、失败提醒占 +1，这里错开。 */
    private const val EXEC_AUTH_OFFSET = 2
    private const val ASK_QUESTION_OFFSET = 3
    private const val PLUGIN_INSTALL_OFFSET = 4

    /** 命令授权提醒；返回是否成功发出（权限未授予时返回 false，交给上层重试）。 */
    fun notifyExecAuthorization(context: Context, sessionId: String, command: String): Boolean =
        show(
            context = context,
            notificationId = sessionId.hashCode() + EXEC_AUTH_OFFSET,
            title = strings(context).getString(R.string.agent_attention_auth_title),
            content = command.trim(),
            sessionId = sessionId
        )

    /** 提问提醒（questionText 由 [AgentAttentionCenter] 汇总好）；返回是否成功发出。 */
    fun notifyQuestion(context: Context, sessionId: String, questionText: String): Boolean =
        show(
            context = context,
            notificationId = sessionId.hashCode() + ASK_QUESTION_OFFSET,
            title = strings(context).getString(R.string.agent_attention_question_title),
            content = questionText.trim(),
            sessionId = sessionId
        )

    /** 第三方插件安装确认提醒；返回是否成功发出。 */
    fun notifyPluginInstall(context: Context, sessionId: String, pluginText: String): Boolean =
        show(
            context = context,
            notificationId = sessionId.hashCode() + PLUGIN_INSTALL_OFFSET,
            title = strings(context).getString(R.string.agent_attention_plugin_install_title),
            content = pluginText.trim(),
            sessionId = sessionId
        )

    /** 授权已处理/等待已结束：取消对应通知。 */
    fun cancelExecAuthorization(context: Context, sessionId: String) {
        cancel(context, sessionId.hashCode() + EXEC_AUTH_OFFSET)
    }

    /** 提问已回答/已跳过/已取消：取消对应通知。 */
    fun cancelQuestion(context: Context, sessionId: String) {
        cancel(context, sessionId.hashCode() + ASK_QUESTION_OFFSET)
    }

    /** 插件安装确认已处理或已取消：取消对应通知。 */
    fun cancelPluginInstall(context: Context, sessionId: String) {
        cancel(context, sessionId.hashCode() + PLUGIN_INSTALL_OFFSET)
    }

    private fun cancel(context: Context, notificationId: Int) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }

    private fun show(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        sessionId: String
    ): Boolean {
        if (sessionId.isBlank() || content.isBlank()) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        return runCatching {
            createChannel(context)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("session_id", sessionId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(content.take(180))
                .setStyle(NotificationCompat.BigTextStyle().bigText(content.take(1200)))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.agent_attention_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.agent_attention_channel_desc)
            }
        )
    }

    /** 通知文案跟随应用内选择的语言，而不是系统语言。 */
    private fun strings(context: Context): Context =
        ServiceContainer.localizedContext ?: context
}
