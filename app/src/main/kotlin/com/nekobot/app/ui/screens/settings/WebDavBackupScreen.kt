package com.nekobot.app.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.WebDavBackupRequest
import com.nekobot.app.data.model.WebDavConfig
import com.nekobot.app.data.model.WebDavTestRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WebDAV 备份界面 ViewModel：管理配置、远程信息、备份/同步操作。
 */
class WebDavBackupViewModel : BaseViewModel() {

    private val _config = MutableStateFlow<WebDavConfig?>(null)
    val config: StateFlow<WebDavConfig?> = _config.asStateFlow()

    private val _remoteInfo = MutableStateFlow<JsonObject?>(null)
    val remoteInfo: StateFlow<JsonObject?> = _remoteInfo.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    init {
        loadConfig()
    }

    /** 加载 WebDAV 配置 */
    fun loadConfig() {
        launchResult(
            block = { unified.getWebDavConfig() },
            onSuccess = {
                _config.value = it
                if (!it.url.isNullOrBlank()) {
                    loadInfo()
                } else {
                    _remoteInfo.value = null
                }
            }
        )
    }

    /** 加载远程备份文件信息 */
    fun loadInfo() {
        launchResult(
            block = { unified.webDavInfo() },
            onSuccess = { elem ->
                _remoteInfo.value = elem?.asJsonObject
            }
        )
    }

    /** 保存 WebDAV 配置 */
    fun saveConfig(
        enabled: Boolean,
        url: String,
        username: String,
        password: String,
        encryptionPassword: String
    ) {
        val cfg = WebDavConfig(
            enabled = enabled,
            url = url.trim(),
            username = username.trim(),
            password = password.ifBlank { null },
            encryptionPassword = encryptionPassword.ifBlank { null }
        )
        launchResult(
            block = { unified.saveWebDavConfig(cfg) },
            onSuccess = {
                showToast(string(R.string.webdav_config_saved))
                loadConfig()
            }
        )
    }

    /** 测试 WebDAV 连接（使用当前输入的临时配置） */
    fun testConnection(url: String, username: String, password: String) {
        val req = WebDavTestRequest(
            url = url.ifBlank { null },
            username = username.ifBlank { null },
            password = password.ifBlank { null }
        )
        launchResult(
            block = { unified.testWebDav(req) },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                val success = obj?.get("success")?.asBoolean == true &&
                    obj.get("ok")?.asBoolean != false
                _testResult.value = if (success) {
                    string(R.string.webdav_connection_success)
                } else {
                    obj?.get("error")?.asString
                        ?: obj?.get("message")?.asString
                        ?: string(R.string.webdav_connection_failed)
                }
            }
        )
    }

    /** 上传备份到 WebDAV */
    fun backup(password: String, includePortraits: Boolean) {
        val req = WebDavBackupRequest(
            password = password.ifBlank { null },
            includePortraits = includePortraits
        )
        launchResult(
            block = { unified.webDavBackup(req) },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                val success = obj?.get("success")?.asBoolean ?: false
                if (success) {
                    showToast(string(R.string.webdav_backup_success))
                    loadConfig()
                    loadInfo()
                } else {
                    showError(obj?.get("error")?.asString ?: string(R.string.webdav_backup_failed))
                }
            }
        )
    }

    /** 从 WebDAV 拉取同步 */
    fun sync(password: String, includePortraits: Boolean) {
        val req = WebDavBackupRequest(
            password = password.ifBlank { null },
            includePortraits = includePortraits
        )
        launchResult(
            block = { unified.webDavSync(req) },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                val success = obj?.get("success")?.asBoolean ?: false
                if (success) {
                    showToast(string(R.string.webdav_sync_success))
                    loadConfig()
                    loadInfo()
                } else {
                    showError(obj?.get("error")?.asString ?: string(R.string.webdav_sync_failed))
                }
            }
        )
    }

    fun clearTestResult() {
        _testResult.value = null
    }
}

/** 格式化文件大小为人类可读字符串 */
private fun formatFileSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ServiceContainer.getString(R.string.common_unknown)
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format("%.2f GB", gb)
        mb >= 1 -> String.format("%.2f MB", mb)
        kb >= 1 -> String.format("%.2f KB", kb)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBackupScreen(onBack: () -> Unit) {
    val vm: WebDavBackupViewModel = viewModel()
    val config by vm.config.collectAsState()
    val remoteInfo by vm.remoteInfo.collectAsState()
    val testResult by vm.testResult.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    // 配置输入：以已加载配置为初始值，配置刷新时同步
    var urlInput by remember(config) { mutableStateOf(config?.url.orEmpty()) }
    var usernameInput by remember(config) { mutableStateOf(config?.username.orEmpty()) }
    var passwordInput by remember { mutableStateOf("") }
    var encryptionPasswordInput by remember { mutableStateOf("") }
    var enabledInput by remember(config) { mutableStateOf(config?.enabled == true) }

    // 备份/同步选项
    var backupPassword by remember { mutableStateOf("") }
    var backupIncludePortraits by remember { mutableStateOf(false) }
    var syncPassword by remember { mutableStateOf("") }
    var syncIncludePortraits by remember { mutableStateOf(false) }
    var showSyncConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.webdav_title), color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                    error?.let {
                        ErrorBanner(message = it, onRetry = { vm.clearError() })
                    }

                    // 1. 配置卡片
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.webdav_config_title), subtitle = stringResource(R.string.webdav_config_subtitle))
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.webdav_enabled),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.webdav_enabled_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enabledInput,
                                onCheckedChange = { enabledInput = it }
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text(stringResource(R.string.webdav_url)) },
                            placeholder = { Text("https://example.com/dav/") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = { usernameInput = it },
                            label = { Text(stringResource(R.string.webdav_username)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text(if (config?.hasPassword == true) stringResource(R.string.webdav_password_set) else stringResource(R.string.webdav_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = encryptionPasswordInput,
                            onValueChange = { encryptionPasswordInput = it },
                            label = { Text(if (config?.hasEncryptionPassword == true) stringResource(R.string.webdav_encryption_password_set) else stringResource(R.string.webdav_encryption_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    vm.saveConfig(
                                        enabledInput,
                                        urlInput,
                                        usernameInput,
                                        passwordInput,
                                        encryptionPasswordInput
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.onPrimary)
                            }
                            OutlinedButton(
                                onClick = { vm.testConnection(urlInput, usernameInput, passwordInput) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.WifiFind, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.webdav_test_connection))
                            }
                        }

                        testResult?.let { result ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (result == stringResource(R.string.webdav_connection_success)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // 2. 远程文件信息
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.webdav_remote_info_title), subtitle = stringResource(R.string.webdav_remote_info_subtitle))
                        Spacer(Modifier.height(12.dp))

                        val lastBackup = config?.lastBackupAt
                        val lastSync = config?.lastSyncAt
                        val lastError = config?.lastError
                        val fileSize = remoteInfo?.get("file_size")
                            ?.takeUnless { it.isJsonNull }
                            ?.asLong
                            ?: remoteInfo?.get("size")
                                ?.takeUnless { it.isJsonNull }
                                ?.asLong
                            ?: config?.lastFileSize
                        val lastModified = remoteInfo?.get("last_modified")?.asString ?: config?.lastModified
                        val resolvedUrl = remoteInfo?.get("resolved_file_url")?.asString ?: config?.resolvedFileUrl

                        InfoRow(label = stringResource(R.string.webdav_file_size), value = formatFileSize(fileSize))
                        InfoRow(label = stringResource(R.string.webdav_last_modified), value = lastModified ?: stringResource(R.string.common_unknown))
                        InfoRow(label = stringResource(R.string.webdav_last_backup), value = lastBackup ?: stringResource(R.string.webdav_never_backup))
                        InfoRow(label = stringResource(R.string.webdav_last_sync), value = lastSync ?: stringResource(R.string.webdav_never_sync))
                        if (!resolvedUrl.isNullOrBlank()) {
                            InfoRow(label = stringResource(R.string.webdav_file_url), value = resolvedUrl)
                        }
                        if (!lastError.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.webdav_recent_error, lastError),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.loadInfo() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.webdav_refresh_info))
                        }
                    }

                    // 3. 备份上传
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.webdav_backup_upload_title), subtitle = stringResource(R.string.webdav_backup_upload_subtitle))
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = { backupPassword = it },
                            label = { Text(stringResource(R.string.webdav_encryption_password_optional_override)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.webdav_include_portraits),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.webdav_upload_portraits),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = backupIncludePortraits,
                                onCheckedChange = { backupIncludePortraits = it }
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = { vm.backup(backupPassword, backupIncludePortraits) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.webdav_upload_backup), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    // 4. 同步拉取
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.webdav_sync_pull_title), subtitle = stringResource(R.string.webdav_sync_pull_subtitle))
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = syncPassword,
                            onValueChange = { syncPassword = it },
                            label = { Text(stringResource(R.string.webdav_encryption_password_optional_override)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.webdav_include_portraits),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.webdav_pull_portraits),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = syncIncludePortraits,
                                onCheckedChange = { syncIncludePortraits = it }
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = { showSyncConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.webdav_sync_pull_button), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
            }

            LoadingOverlay(visible = loading)
        }
    }

    if (showSyncConfirm) {
        AlertDialog(
            onDismissRequest = { showSyncConfirm = false },
            title = { Text(stringResource(R.string.webdav_sync_confirm_title)) },
            text = { Text(stringResource(R.string.webdav_sync_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSyncConfirm = false
                        vm.sync(syncPassword, syncIncludePortraits)
                    }
                ) {
                    Text(
                        stringResource(R.string.webdav_sync_pull_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 信息行：左侧标签 + 右侧值 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}
