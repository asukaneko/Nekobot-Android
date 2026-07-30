package com.nekobot.app.integration

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.automation.LocalAutomationNotifier
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.data.remote.RealtimeEvent
import com.nekobot.app.widget.NekobotWidgetProvider
import kotlinx.coroutines.flow.collect

/** 从通知快捷回复发送消息，并在本地模式等待 AI 回复完成。 */
class NotificationReplyWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val text = inputData.getString(KEY_TEXT)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        return runCatching {
            val stream = ServiceContainer.unified.chatStream(sessionId, text)
            if (stream != null) {
                var failure: String? = null
                stream.collect { event ->
                    if (event is RealtimeEvent.Error) failure = event.message
                }
                failure?.let { error(it) }
            } else {
                if (ServiceContainer.prefs.isLocalMode) {
                    error("未配置可用的本地聊天模型")
                }
                when (val result = ServiceContainer.unified.chat(sessionId, text)) {
                    is Resource.Error -> error(result.message)
                    else -> Unit
                }
            }

            val latestReply = when (val messages = ServiceContainer.unified.listMessages(sessionId)) {
                is Resource.Success -> messages.data
                    .orEmpty()
                    .lastOrNull { it.role == "assistant" }
                    ?.content
                    .orEmpty()
                else -> ""
            }
            LocalAutomationNotifier.show(
                context = applicationContext,
                notificationId = sessionId.hashCode(),
                title = "NekoBot",
                content = latestReply.ifBlank { "消息已发送" },
                sessionId = sessionId
            )
            NekobotShortcutManager.refresh(applicationContext)
            NekobotWidgetProvider.refreshAll(applicationContext)
            Result.success()
        }.getOrElse { error ->
            LocalAutomationNotifier.show(
                context = applicationContext,
                notificationId = sessionId.hashCode(),
                title = "快捷回复失败",
                content = error.message ?: "请打开应用重试",
                sessionId = sessionId
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_TEXT = "text"
    }
}
