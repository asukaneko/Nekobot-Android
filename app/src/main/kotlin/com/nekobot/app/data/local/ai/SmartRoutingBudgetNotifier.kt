package com.nekobot.app.data.local.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nekobot.app.MainActivity
import com.nekobot.app.R
import com.nekobot.app.data.local.PrefsManager
import java.time.LocalDate

object SmartRoutingBudgetNotifier {
    private const val CHANNEL_ID = "smart_routing_budget"
    private const val NOTIFICATION_ID = 0x534D

    fun notifyIfNeeded(
        context: Context,
        prefs: PrefsManager,
        spentUsd: Double,
        budgetUsd: Double
    ) {
        if (budgetUsd <= 0.0) return
        val percent = spentUsd / budgetUsd
        val level = when {
            percent >= 1.0 -> 100
            percent >= 0.8 -> 80
            else -> return
        }
        val state = "${LocalDate.now()}:$level"
        if (prefs.smartRoutingBudgetAlertState == state) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.smart_routing_budget_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val openApp = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.smart_routing_budget_title))
            .setContentText(
                context.getString(
                    if (level >= 100) {
                        R.string.smart_routing_budget_exceeded
                    } else {
                        R.string.smart_routing_budget_near
                    },
                    spentUsd,
                    budgetUsd
                )
            )
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            prefs.smartRoutingBudgetAlertState = state
        }
    }
}
