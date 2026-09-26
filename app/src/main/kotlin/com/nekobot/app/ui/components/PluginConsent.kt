package com.nekobot.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.plugin.PluginManifestValidator

/**
 * 第三方插件同意内容：免责警告 + 协议勾选 + 权限勾选清单。
 *
 * 插件页的 ZIP 安装弹窗（PluginsScreen）与 Agent 安装工作区 ZIP 的会话弹窗
 * （ChatScreen）共用同一组件，保证两处展示与交互完全一致。
 *
 * @param header 可选的弹窗顶部上下文（如「本地 Agent 请求安装」与插件信息）。
 */
@Composable
fun ThirdPartyPluginConsentContent(
    declaredPermissions: List<String>,
    checkedPermissions: Set<String>,
    onPermissionsChange: (Set<String>) -> Unit,
    agreementChecked: Boolean,
    onAgreementCheckedChange: (Boolean) -> Unit,
    header: (@Composable () -> Unit)? = null
) {
    header?.invoke()
    Text(
        text = stringResource(R.string.plugins_third_party_warning),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = agreementChecked,
            onCheckedChange = onAgreementCheckedChange
        )
        Text(
            text = stringResource(R.string.plugins_third_party_agreement),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
    PluginPermissionChecklist(
        declared = declaredPermissions,
        checked = checkedPermissions,
        onCheckedChange = onPermissionsChange
    )
}

/** 权限清单：按「基础 / 读取 / 网络 / 写入 / AI」分组，危险项单独标注。 */
@Composable
fun PluginPermissionChecklist(
    declared: List<String>,
    checked: Set<String>,
    onCheckedChange: (Set<String>) -> Unit
) {
    val supported = declared.filter { it in PluginManifestValidator.supportedPermissions }.distinct()
    if (supported.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.plugins_grant_permissions_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(4.dp))
    PluginManifestValidator.permissionGroups.forEach { (group, permissions) ->
        val groupPermissions = supported.filter { it in permissions }
        if (groupPermissions.isEmpty()) return@forEach
        val danger = groupPermissions.any { it in PluginManifestValidator.dangerousPermissions }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(permissionGroupRes(group)),
            style = MaterialTheme.typography.labelLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        groupPermissions.forEach { permission ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = permission in checked,
                    onCheckedChange = { isChecked ->
                        onCheckedChange(
                            if (isChecked) checked + permission else checked - permission
                        )
                    }
                )
                Text(
                    text = stringResource(permissionNameRes(permission)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (permission in PluginManifestValidator.dangerousPermissions) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

private fun permissionGroupRes(group: String): Int = when (group) {
    "basic" -> R.string.plugin_perm_group_basic
    "read" -> R.string.plugin_perm_group_read
    "network" -> R.string.plugin_perm_group_network
    "write" -> R.string.plugin_perm_group_write
    else -> R.string.plugin_perm_group_ai
}

private fun permissionNameRes(permission: String): Int = when (permission) {
    "storage" -> R.string.plugin_perm_storage
    "notify" -> R.string.plugin_perm_notify
    "chat.progress" -> R.string.plugin_perm_chat_progress
    "files" -> R.string.plugin_perm_files
    "chat.read" -> R.string.plugin_perm_chat_read
    "characters.read" -> R.string.plugin_perm_characters_read
    "worldbooks.read" -> R.string.plugin_perm_worldbooks_read
    "memory.read" -> R.string.plugin_perm_memory_read
    "network" -> R.string.plugin_perm_network
    "chat.write" -> R.string.plugin_perm_chat_write
    "memory.write" -> R.string.plugin_perm_memory_write
    "characters.write" -> R.string.plugin_perm_characters_write
    "workspace" -> R.string.plugin_perm_workspace
    else -> R.string.plugin_perm_ai_call
}
