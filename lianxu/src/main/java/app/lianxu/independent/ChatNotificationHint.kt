package app.lianxu.independent

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class BlockedNotifications(val channelId: String? = null)

internal fun blockedChatNotifications(context: Context): BlockedNotifications? {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return BlockedNotifications()
    if ((Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) || !manager.areNotificationsEnabled()) return BlockedNotifications()
    val channel = manager.getNotificationChannel(ChatRequestService.CHANNEL_ID) ?: return BlockedNotifications()
    return if (channel.importance == NotificationManager.IMPORTANCE_NONE) BlockedNotifications(channel.id) else null
}

/** Pure decision helper: only open the channel page when app-level notifications are on and only the channel is off. */
internal fun notificationSettingsChannelId(background: BackgroundAccessState): String? =
    if (background.notificationsEnabled == true && background.chatChannelBlocked == true) ChatRequestService.CHANNEL_ID else null

internal fun openChatNotificationSettings(context: Context) {
    val blocked = blockedChatNotifications(context)
    openNotificationSettings(context, blocked?.channelId)
}

internal fun notificationSettingsIntents(packageName: String, channelId: String? = null): List<Intent> = buildList {
    if (channelId != null) {
        add(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId))
    }
    add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

internal fun openNotificationSettings(context: Context, channelId: String? = null) {
    fun launch(intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    for (intent in notificationSettingsIntents(context.packageName, channelId)) {
        if (intent.action == Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS && intent.resolveActivity(context.packageManager) == null) continue
        if (launch(intent)) return
    }
}

@Composable
internal fun ChatNotificationHint(vm: AppState) {
    val ambient = LocalContext.current
    val context = ambient.hostActivity() ?: ambient
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val blocked = blockedChatNotifications(context) != null
                visible = blocked && (visible || vm.claimNotificationHint())
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!visible) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(UiSpace.radius), color = Paper) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp)) {
            Text("聊天通知未开启", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("开启后，可在通知栏查看回复进度。", style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { visible = false }) { Text("暂不", color = Muted) }
                TextButton(onClick = {
                    visible = false
                    blockedChatNotifications(context)?.let { openNotificationSettings(context, it.channelId) }
                }) { Text("去设置") }
            }
        }
    }
}
