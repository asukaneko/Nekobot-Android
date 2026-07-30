package com.nekobot.app.data.local.automation

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

/** 主动聊天、任务和工作流共用的结果通知。 */
object LocalAutomationNotifier {
    private const val CHANNEL_ID = "local_automation"

    fun show(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        sessionId: String?
    ) {
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            sessionId?.let { putExtra("session_id", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.take(1200)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "本地自动化",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "主动聊天、定时任务与工作流执行结果"
            }
        )
    }
}
