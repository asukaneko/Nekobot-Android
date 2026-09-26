package com.nekobot.app.ui.screens.extensions

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.plugin.BuiltInPlugins
import com.nekobot.app.data.local.plugin.InstalledPlugin
import com.nekobot.app.data.local.plugin.PluginCompatLevel
import com.nekobot.app.data.local.plugin.PluginManifest
import com.nekobot.app.data.local.plugin.PluginManifestValidator
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.navigation.Routes

class PluginsViewModel : BaseViewModel() {
    val plugins = ServiceContainer.pluginManager.installed
    val grantsRevision = ServiceContainer.pluginGrants.revision

    fun peekManifest(uri: Uri, onResult: (PluginManifest?) -> Unit) = launchWith {
        Resource.Success(ServiceContainer.pluginManager.peekManifest(uri).also(onResult))
    }

    fun install(uri: Uri, grantedPermissions: Set<String>?) = launchResult(
        block = {
            runCatching {
                Resource.Success(
                    ServiceContainer.pluginManager.install(
                        uri,
                        acceptedThirdPartyAgreement = true,
                        grantedPermissions = grantedPermissions
                    )
                )
            }.getOrElse { Resource.Error(it.message ?: string(R.string.common_unknown_error)) }
        },
        onSuccess = { plugin -> showToast(string(R.string.plugins_installed, plugin.name)) }
    )

    fun setEnabled(plugin: InstalledPlugin, enabled: Boolean) = launchWith {
        runCatching {
            ServiceContainer.pluginManager.setEnabled(plugin.id, enabled)
            Resource.Success(Unit)
        }.getOrElse { Resource.Error(it.message ?: string(R.string.common_unknown_error)) }
    }

    fun uninstall(plugin: InstalledPlugin) = launchWith {
        runCatching {
            ServiceContainer.pluginManager.uninstall(plugin.id)
            Resource.Success(Unit)
        }.getOrElse { Resource.Error(it.message ?: string(R.string.common_unknown_error)) }
    }

    fun saveGrants(pluginId: String, permissions: Set<String>) {
        ServiceContainer.pluginGrants.setGranted(pluginId, permissions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val vm: PluginsViewModel = viewModel()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val grantsRevision by vm.grantsRevision.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val languageCode = LocalConfiguration.current.locales[0]?.language.orEmpty()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var agreementChecked by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }
    var installPreview by remember { mutableStateOf<PluginManifest?>(null) }
    var installChecked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var grantTarget by remember { mutableStateOf<InstalledPlugin?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            agreementChecked = false
            installPreview = null
            installChecked = emptySet()
            showAgreement = true
            vm.peekManifest(uri) { manifest ->
                installPreview = manifest
                installChecked = manifest
                    ?.let { PluginManifestValidator.defaultGrantedPermissions(it.permissions) }
                    .orEmpty()
            }
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plugins_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        filePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.plugins_install))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { PluginInfoCard(appMode = appMode) }
                error?.let { message ->
                    item { ErrorBanner(message = message, onRetry = vm::clearError) }
                }
                if (plugins.isEmpty() && !loading) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.plugins_empty_title),
                            hint = stringResource(R.string.plugins_empty_hint),
                            icon = {
                                Icon(
                                    Icons.Filled.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }
                } else {
                    items(plugins, key = { it.id }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            languageCode = languageCode,
                            grantsRevision = grantsRevision,
                            onToggle = { vm.setEnabled(plugin, it) },
                            onDelete = if (plugin.isBuiltIn) null else { { deleteTarget = plugin } },
                            onManagePermissions = { grantTarget = plugin },
                            onOpenPage = { pageId ->
                                onNavigate(Routes.pluginPage(plugin.id, pageId))
                            }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    if (showAgreement && selectedUri != null) {
        NekoDialog(
            onDismiss = {
                showAgreement = false
                selectedUri = null
                installPreview = null
            },
            title = stringResource(R.string.plugins_install_title),
            message = stringResource(R.string.plugins_install_file, selectedUri?.lastPathSegment ?: "ZIP"),
            confirmText = stringResource(R.string.plugins_install),
            confirmEnabled = agreementChecked,
            onConfirm = {
                val uri = selectedUri ?: return@NekoDialog
                val preview = installPreview
                val granted = if (preview != null) installChecked else null
                showAgreement = false
                selectedUri = null
                installPreview = null
                vm.clearError()
                vm.install(uri, granted)
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = {
                showAgreement = false
                selectedUri = null
                installPreview = null
            },
            contentScrollable = true
        ) {
            Text(
                text = stringResource(R.string.plugins_third_party_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = agreementChecked,
                    onCheckedChange = { agreementChecked = it }
                )
                Text(
                    text = stringResource(R.string.plugins_third_party_agreement),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            installPreview?.let { manifest ->
                Spacer(Modifier.height(8.dp))
                PermissionChecklist(
                    declared = manifest.permissions,
                    checked = installChecked,
                    onCheckedChange = { installChecked = it }
                )
            }
        }
    }

    deleteTarget?.let { plugin ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.plugins_delete_title),
            message = stringResource(R.string.plugins_delete_message, plugin.name),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                deleteTarget = null
                vm.uninstall(plugin)
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { deleteTarget = null }
        )
    }

    grantTarget?.let { plugin ->
        val current = ServiceContainer.pluginGrants.granted(plugin.id)
        var checked by remember(plugin.id, current) {
            mutableStateOf(
                current ?: PluginManifestValidator.defaultGrantedPermissions(plugin.permissions)
            )
        }
        NekoDialog(
            onDismiss = { grantTarget = null },
            title = stringResource(R.string.plugins_grant_title),
            message = stringResource(R.string.plugins_grant_message),
            confirmText = stringResource(R.string.common_save),
            onConfirm = {
                vm.saveGrants(plugin.id, checked)
                grantTarget = null
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = { grantTarget = null },
            contentScrollable = true
        ) {
            PermissionChecklist(
                declared = plugin.permissions,
                checked = checked,
                onCheckedChange = { checked = it }
            )
        }
    }
}

@Composable
private fun PluginInfoCard(appMode: AppMode) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.plugins_info_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.plugins_info_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (appMode != AppMode.LOCAL) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.plugins_server_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 权限清单：按「基础 / 读取 / 网络 / 写入 / AI」分组，危险项单独标注。 */
@Composable
private fun PermissionChecklist(
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

@Composable
private fun hookDisplayName(hook: String): String = when (hook) {
    "message.beforeSend" -> stringResource(R.string.plugin_hook_message_before_send)
    "app.lifecycle" -> stringResource(R.string.plugin_hook_app_lifecycle)
    else -> hook
}

private fun compatLevelRes(level: PluginCompatLevel): Int = when (level) {
    PluginCompatLevel.NATIVE -> R.string.plugins_compat_native
    PluginCompatLevel.PORTED_FULL -> R.string.plugins_compat_ported_full
    PluginCompatLevel.PORTED_PARTIAL -> R.string.plugins_compat_ported_partial
    PluginCompatLevel.UNSUPPORTED -> R.string.plugins_compat_unsupported
}

@Composable
private fun PluginCard(
    plugin: InstalledPlugin,
    languageCode: String,
    grantsRevision: Long,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onManagePermissions: () -> Unit,
    onOpenPage: (String) -> Unit
) {
    val displayName = when (plugin.id) {
        BuiltInPlugins.JM_ID -> stringResource(R.string.plugins_builtin_jm_name)
        BuiltInPlugins.LIGHT_NOVEL_ID -> stringResource(R.string.plugins_builtin_light_novel_name)
        else -> plugin.name
    }
    val displayDescription = when (plugin.id) {
        BuiltInPlugins.JM_ID -> stringResource(R.string.plugins_builtin_jm_desc)
        BuiltInPlugins.LIGHT_NOVEL_ID -> stringResource(R.string.plugins_builtin_light_novel_desc)
        else -> plugin.description
    }
    val granted = remember(plugin.id, grantsRevision) {
        ServiceContainer.pluginGrants.granted(plugin.id)
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                tint = if (plugin.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.plugins_version_author, plugin.version, plugin.author.ifBlank { "—" }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (plugin.isBuiltIn) {
                    Text(
                        text = stringResource(R.string.plugins_builtin),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (plugin.compat != PluginCompatLevel.NATIVE) {
                    Text(
                        text = stringResource(
                            R.string.plugins_compat_label,
                            stringResource(compatLevelRes(plugin.compat))
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (displayDescription.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = displayDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (plugin.compatNote.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = plugin.compatNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.plugins_command_count, plugin.commands.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                plugin.commands.take(4).forEach { command ->
                    Text(
                        text = "  /${command.name} — ${command.description.ifBlank { stringResource(R.string.plugins_command_default_desc) }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (plugin.hooks.isNotEmpty()) {
                    val hookLabels = plugin.hooks.map { hookDisplayName(it) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.plugins_hooks_label,
                            hookLabels.joinToString("、")
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (plugin.pages.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.plugins_pages_count, plugin.pages.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    plugin.pages.forEach { page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenPage(page.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Web,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = page.localizedTitle(languageCode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (plugin.permissions.isNotEmpty() && !plugin.isBuiltIn) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (granted == null) {
                                stringResource(R.string.plugins_permissions_unconfirmed)
                            } else {
                                stringResource(
                                    R.string.plugins_permissions_granted,
                                    granted.count { it in plugin.permissions },
                                    plugin.permissions.size
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (granted == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        TextButton(onClick = onManagePermissions) {
                            Text(
                                text = stringResource(R.string.plugins_grant_manage),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = plugin.enabled, onCheckedChange = onToggle)
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
