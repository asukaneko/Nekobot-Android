package com.nekobot.app.integration

import android.app.Notification
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 用户显式开启通知使用权后，为 Agent 提供受控的通知摘要与媒体会话操作。 */
class NekobotNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    internal fun notificationSnapshot(
        packageName: String?,
        limit: Int,
        includeContent: Boolean
    ): List<Map<String, Any?>> = activeNotifications.orEmpty()
        .asSequence()
        .filter { packageName.isNullOrBlank() || it.packageName.equals(packageName, ignoreCase = true) }
        .sortedByDescending(StatusBarNotification::getPostTime)
        .take(limit.coerceIn(1, MAX_NOTIFICATIONS))
        .map { it.toSnapshot(includeContent) }
        .toList()

    internal fun notificationAction(key: String, action: String, actionIndex: Int): NotificationOperationResult {
        val notification = activeNotifications.orEmpty().firstOrNull { it.key == key }
            ?: return NotificationOperationResult(false, "未找到指定通知")
        return try {
            when (action.lowercase()) {
                "open" -> {
                    val pendingIntent = notification.notification.contentIntent
                        ?: return NotificationOperationResult(false, "该通知没有可打开的内容")
                    pendingIntent.send()
                    NotificationOperationResult(true, "已打开通知")
                }
                "dismiss" -> {
                    if (!notification.isClearable) return NotificationOperationResult(false, "该通知不可清除")
                    cancelNotification(notification.key)
                    NotificationOperationResult(true, "已清除通知")
                }
                "action" -> {
                    val actions = notification.notification.actions.orEmpty()
                    val selected = actions.getOrNull(actionIndex)
                        ?: return NotificationOperationResult(false, "action_index 超出通知动作范围")
                    selected.actionIntent.send()
                    NotificationOperationResult(true, "已执行通知动作：${selected.title}")
                }
                else -> NotificationOperationResult(false, "action 仅支持 open/dismiss/action")
            }
        } catch (error: Exception) {
            NotificationOperationResult(false, error.message ?: "通知操作失败")
        }
    }

    internal fun mediaSessions(): List<Map<String, Any?>> = activeMediaControllers().map { controller ->
        val metadata = controller.metadata
        val playback = controller.playbackState
        mapOf(
            "package_name" to controller.packageName,
            "title" to metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            "artist" to metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            "album" to metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            "duration_ms" to (metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L),
            "position_ms" to (playback?.position ?: 0L),
            "state" to playbackStateName(playback?.state),
            "actions" to (playback?.actions ?: 0L)
        )
    }

    internal fun mediaControl(action: String, packageName: String?): NotificationOperationResult {
        val controllers = activeMediaControllers()
        val controller = controllers.firstOrNull { packageName.isNullOrBlank() || it.packageName == packageName }
            ?: return NotificationOperationResult(false, "没有找到活动媒体会话")
        val transport = controller.transportControls
        return try {
            when (action.lowercase()) {
                "play" -> transport.play()
                "pause" -> transport.pause()
                "play_pause", "toggle" -> {
                    if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) transport.pause()
                    else transport.play()
                }
                "next" -> transport.skipToNext()
                "previous" -> transport.skipToPrevious()
                "stop" -> transport.stop()
                else -> return NotificationOperationResult(false, "action 仅支持 play/pause/toggle/next/previous/stop")
            }
            NotificationOperationResult(true, "已向 ${controller.packageName} 发送媒体控制", controller.packageName)
        } catch (error: Exception) {
            NotificationOperationResult(false, error.message ?: "媒体控制失败", controller.packageName)
        }
    }

    private fun activeMediaControllers(): List<MediaController> {
        val manager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return emptyList()
        return runCatching {
            manager.getActiveSessions(ComponentName(this, NekobotNotificationListenerService::class.java))
        }.getOrDefault(emptyList())
            .sortedByDescending { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
    }

    private fun StatusBarNotification.toSnapshot(includeContent: Boolean): Map<String, Any?> {
        val extras = notification.extras
        val contentHidden = notification.visibility == Notification.VISIBILITY_SECRET || !includeContent
        val title = if (contentHidden) "" else extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = if (contentHidden) "" else listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return mapOf(
            "key" to key,
            "package_name" to packageName,
            "post_time" to postTime,
            "category" to notification.category.orEmpty(),
            "title" to title.take(MAX_CONTENT_CHARS),
            "text" to text.take(MAX_CONTENT_CHARS),
            "content_hidden" to contentHidden,
            "ongoing" to isOngoing,
            "clearable" to isClearable,
            "action_titles" to notification.actions.orEmpty().map { it.title?.toString().orEmpty() }
        )
    }

    private fun playbackStateName(state: Int?): String = when (state) {
        android.media.session.PlaybackState.STATE_PLAYING -> "playing"
        android.media.session.PlaybackState.STATE_PAUSED -> "paused"
        android.media.session.PlaybackState.STATE_BUFFERING -> "buffering"
        android.media.session.PlaybackState.STATE_STOPPED -> "stopped"
        android.media.session.PlaybackState.STATE_ERROR -> "error"
        else -> "none"
    }

    internal companion object {
        @Volatile
        var instance: NekobotNotificationListenerService? = null
            private set

        private const val MAX_NOTIFICATIONS = 100
        private const val MAX_CONTENT_CHARS = 2_000
    }
}

internal data class NotificationOperationResult(
    val success: Boolean,
    val message: String,
    val packageName: String? = null
)
