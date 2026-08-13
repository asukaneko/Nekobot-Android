package com.nekobot.app.ui.screens.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nekobot.app.integration.NekobotAccessibilityService
import com.nekobot.app.integration.NekobotNotificationListenerService
import com.nekobot.app.ui.components.GlassCard

/** Agent 读取和操作 Android 系统能力的显式授权中心。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemOperationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(readSystemOperationStatus(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = readSystemOperationStatus(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent 系统操作") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "这些能力默认关闭，只能由你在 Android 系统设置中手动开启。Agent 每次读取敏感内容或执行操作时仍会显示会话授权确认。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OperationPermissionCard(
                icon = Icons.Filled.AccessibilityNew,
                title = "辅助功能",
                description = "读取当前界面树、点击、输入、滚动、返回/主页以及 Android 11+ 截图。密码字段永远不会回传。",
                enabled = status.accessibilityEnabled,
                connected = status.accessibilityConnected,
                buttonLabel = "打开辅助功能设置",
                onOpen = { context.openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            )

            OperationPermissionCard(
                icon = Icons.Filled.Notifications,
                title = "通知与媒体控制",
                description = "读取活动通知、打开或清除通知，以及控制系统媒体会话。通知正文和所有写操作都需要会话授权。",
                enabled = status.notificationEnabled,
                connected = status.notificationConnected,
                buttonLabel = "打开通知使用权",
                onOpen = { context.openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("安全边界", fontWeight = FontWeight.SemiBold)
                    Text("• 系统授权可随时关闭\n• 只匹配当前可见界面，不绕过锁屏或应用权限\n• 截图只保存到当前 Agent 会话工作区\n• UI 树最多返回 800 个节点，并对密码内容脱敏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(90.dp))
        }
    }
}

@Composable
private fun OperationPermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    connected: Boolean,
    buttonLabel: String,
    onOpen: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            connected -> "已开启并连接"
                            enabled -> "已开启，等待服务连接"
                            else -> "未开启"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (enabled) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(buttonLabel) }
        }
    }
}

private data class SystemOperationStatus(
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val notificationEnabled: Boolean,
    val notificationConnected: Boolean
)

private fun readSystemOperationStatus(context: Context): SystemOperationStatus = SystemOperationStatus(
    accessibilityEnabled = context.isComponentEnabled(
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ComponentName(context, NekobotAccessibilityService::class.java)
    ),
    accessibilityConnected = NekobotAccessibilityService.instance != null,
    notificationEnabled = context.isComponentEnabled(
        "enabled_notification_listeners",
        ComponentName(context, NekobotNotificationListenerService::class.java)
    ),
    notificationConnected = NekobotNotificationListenerService.instance != null
)

private fun Context.isComponentEnabled(setting: String, component: ComponentName): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, setting).orEmpty()
    return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == component }
}

private fun Context.openSystemSettings(action: String) {
    startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
