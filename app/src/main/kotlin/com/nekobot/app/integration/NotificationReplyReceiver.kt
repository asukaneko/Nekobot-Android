package com.nekobot.app.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** 接收通知栏的直接回复，并交给 WorkManager 在后台发送。 */
class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return
        val request = OneTimeWorkRequestBuilder<NotificationReplyWorker>()
            .setInputData(
                Data.Builder()
                    .putString(NotificationReplyWorker.KEY_SESSION_ID, sessionId)
                    .putString(NotificationReplyWorker.KEY_TEXT, text)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val KEY_REPLY_TEXT = "nekobot_notification_reply"
        const val EXTRA_SESSION_ID = "session_id"
    }
}
