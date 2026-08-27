package com.nekobot.app.ui.screens.extensions

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog

class PluginsViewModel : BaseViewModel() {
    val plugins = ServiceContainer.pluginManager.installed

    fun install(uri: Uri) = launchResult(
        block = {
            runCatching {
                Resource.Success(ServiceContainer.pluginManager.install(uri, acceptedThirdPartyAgreement = true))
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val vm: PluginsViewModel = viewModel()
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var agreementChecked by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<InstalledPlugin?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            agreementChecked = false
            showAgreement = true
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
                            onToggle = { vm.setEnabled(plugin, it) },
                            onDelete = if (plugin.isBuiltIn) null else { { deleteTarget = plugin } }
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
            },
            title = stringResource(R.string.plugins_install_title),
            message = stringResource(R.string.plugins_install_file, selectedUri?.lastPathSegment ?: "ZIP"),
            confirmText = stringResource(R.string.plugins_install),
            confirmEnabled = agreementChecked,
            onConfirm = {
                val uri = selectedUri ?: return@NekoDialog
                showAgreement = false
                selectedUri = null
                vm.clearError()
                vm.install(uri)
            },
            cancelText = stringResource(R.string.common_cancel),
            onCancel = {
                showAgreement = false
                selectedUri = null
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

@Composable
private fun PluginCard(
    plugin: InstalledPlugin,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?
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
                if (plugin.permissions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.plugins_permissions, plugin.permissions.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
