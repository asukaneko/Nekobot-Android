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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nekobot.app.R
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
                title = { Text(stringResource(R.string.system_ops_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                stringResource(R.string.system_ops_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OperationPermissionCard(
                icon = Icons.Filled.AccessibilityNew,
                title = stringResource(R.string.system_ops_accessibility),
                description = stringResource(R.string.system_ops_accessibility_desc),
                enabled = status.accessibilityEnabled,
                connected = status.accessibilityConnected,
                buttonLabel = stringResource(R.string.system_ops_open_accessibility),
                onOpen = { context.openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            )

            OperationPermissionCard(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.system_ops_notifications),
                description = stringResource(R.string.system_ops_notifications_desc),
                enabled = status.notificationEnabled,
                connected = status.notificationConnected,
                buttonLabel = stringResource(R.string.system_ops_open_notifications),
                onOpen = { context.openSystemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.system_ops_safety_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.system_ops_safety_body),
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
                            connected -> stringResource(R.string.system_ops_status_connected)
                            enabled -> stringResource(R.string.system_ops_status_waiting)
                            else -> stringResource(R.string.system_ops_status_disabled)
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
