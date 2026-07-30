package com.nekobot.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.nekobot.app.MainActivity
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.repository.Resource
import kotlinx.coroutines.launch

/** 首页小组件：显示最近会话、今日 Token，并提供直接进入会话的快捷发送入口。 */
class NekobotWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        ServiceContainer.applicationScope.launch {
            try {
                refresh(context, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        suspend fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NekobotWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) refresh(context, manager, ids)
        }

        private suspend fun refresh(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray
        ) {
            val sessions = when (val result = ServiceContainer.unified.listSessions()) {
                is Resource.Success -> result.data.orEmpty()
                else -> emptyList()
            }
            val recent = sessions
                .filter { !it.id.isNullOrBlank() && it.archived != true }
                .maxWithOrNull(
                    compareBy<Session> { it.updatedAt.orEmpty() }
                        .thenBy { it.createdAt.orEmpty() }
                )
            val todayTokens = when (val result = ServiceContainer.unified.tokenStats("today")) {
                is Resource.Success -> result.data.todayTotal
                else -> 0L
            }
            ids.forEach { widgetId ->
                val views = RemoteViews(context.packageName, R.layout.widget_nekobot).apply {
                    setTextViewText(
                        R.id.widget_recent_session,
                        recent?.displayName ?: context.getString(R.string.widget_no_session)
                    )
                    setTextViewText(
                        R.id.widget_token_usage,
                        context.getString(R.string.widget_today_tokens, todayTokens)
                    )
                    val openApp = PendingIntent.getActivity(
                        context,
                        widgetId,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_root, openApp)
                    recent?.id?.let { sessionId ->
                        val openChat = PendingIntent.getActivity(
                            context,
                            widgetId + 10_000,
                            Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("session_id", sessionId)
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        setOnClickPendingIntent(R.id.widget_open_chat, openChat)
                        setBoolean(R.id.widget_open_chat, "setEnabled", true)
                    } ?: setBoolean(R.id.widget_open_chat, "setEnabled", false)
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }
}
