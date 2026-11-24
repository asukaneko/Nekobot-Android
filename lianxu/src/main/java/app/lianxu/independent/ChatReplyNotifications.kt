package app.lianxu.independent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

internal object ChatReplyNotifications {
    const val TAG = "lianxu-chat-reply"

    fun post(context: Context, turn: ChatTurn, messageId: Long): LocalResult {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return LocalResult.APP_DISABLED
        val channel = CompanionNotifications.CHANNEL
        manager.createNotificationChannel(NotificationChannel(channel, "角色消息", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return LocalResult.CHANNEL_DISABLED

        val open = LianxuActivity.createIntent(context).apply {
            data = Uri.Builder().scheme("lianxu").authority("chat-reply").appendPath(turn.id).build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CompanionNotifications.ROLE, turn.friendId)
            putExtra(CompanionNotifications.MESSAGE, messageId)
        }
        val pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle("收到新消息")
            .setContentText("轻点查看聊天")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
        manager.notify(TAG, messageId.toInt(), notification)
        return LocalResult.SUBMITTED
    }
}


/** Keep visibility checks on both sides of the durable message lookup, as on the UI path. */
internal suspend fun savedReplyNotification(turn: ChatTurn, reading: ((String) -> Boolean)?,
    savedId: suspend () -> Long?, post: (Long) -> LocalResult, onPostError: (Exception) -> Unit) {
    LocalStep.SAVED_REPLY.observe(turn, result = { it }) {
        if (reading == null) return@observe LocalResult.VISIBILITY_UNAVAILABLE
        if (reading(turn.friendId)) return@observe LocalResult.VISIBLE
        try {
            val id = savedId() ?: return@observe LocalResult.NO_SAVED_MESSAGE
            if (reading(turn.friendId)) LocalResult.VISIBLE else post(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onPostError(e)
            LocalResult.forFailure(e)
        }
    }
}
