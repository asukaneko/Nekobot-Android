package com.nekobot.app.ui.screens.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.WebDavBackupRequest
import com.nekobot.app.data.model.WebDavTransferProgress
import com.nekobot.app.data.model.WebDavTransferStage
import com.nekobot.app.data.model.WebDavConfig
import com.nekobot.app.data.model.WebDavTestRequest
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.BorderlessOutlinedButton as OutlinedButton
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import java.time.ZoneId

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

    private val _incrementalHistory = MutableStateFlow<JsonObject?>(null)
    val incrementalHistory: StateFlow<JsonObject?> = _incrementalHistory.asStateFlow()

    private val _restorePreview = MutableStateFlow<JsonObject?>(null)
    val restorePreview: StateFlow<JsonObject?> = _restorePreview.asStateFlow()

    private val _configRevision = MutableStateFlow(0)
    val configRevision: StateFlow<Int> = _configRevision.asStateFlow()

    private val _transferProgress = MutableStateFlow<WebDavTransferProgress?>(null)
    val transferProgress: StateFlow<WebDavTransferProgress?> = _transferProgress.asStateFlow()
    private val activeTransfer = AtomicReference<Any?>()
    private var activeRequests = 0

    private fun <T> launchWebDavResult(
        block: suspend () -> Resource<T>,
        onSuccess: (T) -> Unit,
        onFinished: () -> Unit = {}
    ) {
        activeRequests += 1
        setLoading(true)
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is Resource.Success -> onSuccess(result.data)
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            } finally {
                onFinished()
                activeRequests -= 1
                setLoading(activeRequests > 0)
            }
        }
    }

    private fun <T> launchWebDavTransfer(
        block: suspend ((WebDavTransferProgress) -> Unit) -> Resource<T>,
        onSuccess: (T) -> Unit
    ) {
        val token = Any()
        activeTransfer.set(token)
        _transferProgress.value = WebDavTransferProgress(
            stage = if (ServiceContainer.prefs.isLocalMode) WebDavTransferStage.Preparing
                else WebDavTransferStage.ServerProcessing
        )
        launchWebDavResult(
            block = {
                block { progress ->
                    if (activeTransfer.get() === token) _transferProgress.value = progress
                }
            },
            onSuccess = onSuccess,
            onFinished = {
                if (activeTransfer.compareAndSet(token, null)) _transferProgress.value = null
            }
        )
    }

    init {
        loadConfig()
    }

    /** 加载 WebDAV 配置 */
    fun loadConfig(onLoaded: () -> Unit = {}) {
        launchWebDavResult(
            block = { unified.getWebDavConfig() },
            onSuccess = {
                _config.value = it
                _remoteInfo.value = null
                _incrementalHistory.value = null
                _configRevision.value += 1
                onLoaded()
            }
        )
    }

    /** 加载远程备份文件信息 */
    fun loadInfo() {
        launchWebDavResult(
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
        encryptionPassword: String,
        autoIncrementalSyncEnabled: Boolean? = null,
        autoIncrementalSyncIntervalHours: Int? = null,
        incrementalSyncMaxVersions: Int? = null,
        onSaved: () -> Unit = {}
    ) {
        val cfg = WebDavConfig(
            enabled = enabled,
            url = url.trim(),
            username = username.trim(),
            password = password.ifBlank { null },
            encryptionPassword = encryptionPassword.ifBlank { null },
            autoIncrementalSyncEnabled = autoIncrementalSyncEnabled,
            autoIncrementalSyncIntervalHours = autoIncrementalSyncIntervalHours,
            incrementalSyncMaxVersions = incrementalSyncMaxVersions
        )
        launchWebDavResult(
            block = { unified.saveWebDavConfig(cfg) },
            onSuccess = {
                showToast(string(R.string.webdav_config_saved))
                loadConfig(onSaved)
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
        launchWebDavResult(
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
        launchWebDavTransfer(
            block = { progress -> unified.webDavBackup(req, onProgress = progress) },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                val success = obj?.get("success")?.asBoolean ?: false
                if (success) {
                    showToast(string(R.string.webdav_backup_success))
                    loadConfig()
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
        launchWebDavTransfer(
            block = { progress -> unified.webDavSync(req, onProgress = progress) },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                val success = obj?.get("success")?.asBoolean ?: false
                if (success) {
                    showToast(string(R.string.webdav_sync_success))
                    loadConfig()
                } else {
                    showError(obj?.get("error")?.asString ?: string(R.string.webdav_sync_failed))
                }
            }
        )
    }

    fun previewSync(password: String) {
        val req = WebDavBackupRequest(password = password.ifBlank { null })
        launchWebDavTransfer(
            block = { progress -> unified.webDavPreviewSync(req, onProgress = progress) },
            onSuccess = { elem -> _restorePreview.value = elem?.asJsonObject }
        )
    }

    fun clearRestorePreview() {
        _restorePreview.value = null
    }

    fun incrementalSync(password: String) {
        val req = WebDavBackupRequest(password = password.ifBlank { null })
        launchWebDavTransfer(
            block = { progress -> unified.webDavIncrementalSync(req, onProgress = progress) },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                if (obj?.get("success")?.asBoolean == true) {
                    showToast(
                        string(
                            R.string.webdav_incremental_result,
                            obj.get("uploaded")?.asInt ?: 0,
                            obj.get("downloaded")?.asInt ?: 0,
                            obj.get("conflicts")?.asInt ?: 0
                        )
                    )
                    loadConfig()
                } else {
                    showError(
                        obj?.get("error")?.asString
                            ?: string(R.string.webdav_incremental_failed)
                    )
                }
            }
        )
    }

    fun loadHistory(password: String = "") {
        val req = WebDavBackupRequest(password = password.ifBlank { null })
        launchWebDavTransfer(
            block = { progress -> unified.webDavIncrementalHistory(req, onProgress = progress) },
            onSuccess = { elem -> _incrementalHistory.value = elem?.asJsonObject }
        )
    }

    fun resolveConflict(conflictCopyKey: String, password: String) {
        val req = WebDavBackupRequest(password = password.ifBlank { null })
        launchWebDavTransfer(
            block = { progress -> unified.webDavResolveIncrementalConflict(conflictCopyKey, req, onProgress = progress) },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                if (obj?.get("success")?.asBoolean == true) {
                    showToast(string(R.string.webdav_conflict_resolved))
                    loadHistory(password)
                } else {
                    showError(obj?.get("error")?.asString ?: string(R.string.webdav_conflict_resolve_failed))
                }
            }
        )
    }

    fun restoreRevision(revision: Long, password: String) {
        val req = WebDavBackupRequest(password = password.ifBlank { null })
        launchWebDavTransfer(
            block = { progress -> unified.webDavRestoreIncrementalRevision(revision, req, onProgress = progress) },
            onSuccess = { elem ->
                val obj = elem?.asJsonObject
                if (obj?.get("success")?.asBoolean == true) {
                    showToast(
                        string(
                            R.string.webdav_revision_restored,
                            revision,
                            obj.get("new_revision")?.asLong ?: revision
                        )
                    )
                    loadConfig()
                } else {
                    showError(obj?.get("error")?.asString ?: string(R.string.webdav_revision_restore_failed))
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
    if (bytes == null || bytes < 0) return ServiceContainer.getString(R.string.common_unknown)
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

private enum class WebDavPage { Backup, History, Settings }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WebDavBackupScreen(onBack: () -> Unit) {
    val vm: WebDavBackupViewModel = viewModel()
    val config by vm.config.collectAsStateWithLifecycle()
    val configRevision by vm.configRevision.collectAsStateWithLifecycle()
    val remoteInfo by vm.remoteInfo.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val incrementalHistory by vm.incrementalHistory.collectAsStateWithLifecycle()
    val restorePreview by vm.restorePreview.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val transferProgress by vm.transferProgress.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deviceZoneId by remember { mutableStateOf(ZoneId.systemDefault()) }

    LifecycleResumeEffect(Unit) {
        deviceZoneId = ZoneId.systemDefault()
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                deviceZoneId = ZoneId.systemDefault()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val isLocal = ServiceContainer.prefs.isLocalMode
    val pages = if (isLocal) WebDavPage.entries else listOf(WebDavPage.Backup, WebDavPage.Settings)
    var selectedPageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pages.firstOrNull { it.name == selectedPageName }
    ?: if (config?.url.isNullOrBlank()) WebDavPage.Settings else WebDavPage.Backup
    var showBackupOptions by rememberSaveable { mutableStateOf(false) }
    var showRestoreOptions by rememberSaveable { mutableStateOf(false) }
    var showRemoteDetails by rememberSaveable { mutableStateOf(false) }
    var showIncrementalPassword by rememberSaveable { mutableStateOf(false) }
    var showHistoryPassword by rememberSaveable { mutableStateOf(false) }
    val backupScroll = rememberScrollState()
    val historyScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()

    // 配置输入：以已加载配置为初始值，配置刷新时同步
    var urlInput by remember(config?.url) { mutableStateOf(config?.url.orEmpty()) }
    var usernameInput by remember(config?.username) { mutableStateOf(config?.username.orEmpty()) }
    var passwordInput by remember { mutableStateOf("") }
    var encryptionPasswordInput by remember { mutableStateOf("") }
    var enabledInput by remember(config?.enabled) { mutableStateOf(config?.enabled == true) }
    var autoIncrementalSyncEnabledInput by remember(config?.autoIncrementalSyncEnabled) {
        mutableStateOf(config?.autoIncrementalSyncEnabled == true)
    }
    var autoIncrementalSyncIntervalHoursInput by remember(config?.autoIncrementalSyncIntervalHours) {
        mutableStateOf((config?.autoIncrementalSyncIntervalHours ?: 6).toString())
    }
    var incrementalSyncMaxVersionsInput by remember(config?.incrementalSyncMaxVersions) {
        mutableStateOf((config?.incrementalSyncMaxVersions ?: 10).toString())
    }

    // 备份/同步选项
    var backupPassword by remember { mutableStateOf("") }
    var incrementalPassword by remember { mutableStateOf("") }
    var backupIncludePortraits by remember { mutableStateOf(false) }
    var syncPassword by remember { mutableStateOf("") }
    var syncIncludePortraits by remember { mutableStateOf(false) }
    var showSyncConfirm by remember { mutableStateOf(false) }
    var restoreRevisionTarget by remember { mutableStateOf<Long?>(null) }

    val configured = !config?.url.isNullOrBlank()
    val intervalValid = !isLocal || (autoIncrementalSyncIntervalHoursInput.toIntOrNull() ?: 0) in 1..168
    val versionsValid = !isLocal || (incrementalSyncMaxVersionsInput.toIntOrNull() ?: 0) in 1..50
    val hasChanges = config != null && (
        urlInput.trim() != config?.url.orEmpty() || usernameInput.trim() != config?.username.orEmpty() ||
        passwordInput.isNotBlank() || encryptionPasswordInput.isNotBlank() ||
        enabledInput != (config?.enabled == true) || (isLocal && (
                autoIncrementalSyncEnabledInput != (config?.autoIncrementalSyncEnabled == true) ||
                autoIncrementalSyncIntervalHoursInput.toIntOrNull() != (config?.autoIncrementalSyncIntervalHours ?: 6) ||
                incrementalSyncMaxVersionsInput.toIntOrNull() != (config?.incrementalSyncMaxVersions ?: 10))))
    val canOperate = configured && !hasChanges && !loading

    LaunchedEffect(page, configRevision, hasChanges) {
        if (configured && !hasChanges) {
            when (page) {
                WebDavPage.Backup -> if (remoteInfo == null) vm.loadInfo()
                WebDavPage.History -> if (incrementalHistory == null && (config?.hasEncryptionPassword == true || incrementalPassword.isNotBlank())) vm.loadHistory(incrementalPassword)
                WebDavPage.Settings -> Unit
            }
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            Column {
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
                TabRow(selectedTabIndex = pages.indexOf(page), containerColor = MaterialTheme.colorScheme.background) {
                    pages.forEach { target ->
                        Tab(selected = page == target, onClick = { selectedPageName = target.name }, text = {
                                Text(stringResource(when (target) {
                                            WebDavPage.Backup -> R.string.webdav_tab_backup
                                            WebDavPage.History -> R.string.webdav_tab_history
                                            WebDavPage.Settings -> R.string.webdav_tab_settings
                                }))
                        })
                    }
                }
            }
        },
        bottomBar = {
            if (page == WebDavPage.Settings) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp, 8.dp)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 2
                        ) {
                            Button(
                                enabled = !loading && config != null && hasChanges && urlInput.isNotBlank() && intervalValid && versionsValid,
                                onClick = {
                                    vm.saveConfig(
                                        enabledInput,
                                        urlInput,
                                        usernameInput,
                                        passwordInput,
                                        encryptionPasswordInput,
                                        autoIncrementalSyncEnabled = if (ServiceContainer.prefs.isLocalMode) {
                                            autoIncrementalSyncEnabledInput
                                        } else {
                                            null
                                        },
                                        autoIncrementalSyncIntervalHours = if (ServiceContainer.prefs.isLocalMode) {
                                            autoIncrementalSyncIntervalHoursInput.toIntOrNull()
                                        } else {
                                            null
                                        },
                                        incrementalSyncMaxVersions = if (ServiceContainer.prefs.isLocalMode) {
                                            incrementalSyncMaxVersionsInput.toIntOrNull()
                                        } else {
                                            null
                                        },
                                        onSaved = {
                                            passwordInput = ""
                                            encryptionPasswordInput = ""
                                            selectedPageName = WebDavPage.Backup.name
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f).widthIn(min = 140.dp).heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.common_save), color = MaterialTheme.colorScheme.onPrimary)
                            }
                            OutlinedButton(
                                onClick = { vm.testConnection(urlInput.trim(), usernameInput.trim(), passwordInput) },
                                enabled = !loading && urlInput.isNotBlank(),
                                modifier = Modifier.weight(1f).widthIn(min = 140.dp).heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Filled.WifiFind, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.webdav_test_connection))
                            }
                        }

                    }
                }
            }
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
                .verticalScroll(when (page) {
                        WebDavPage.Backup -> backupScroll
                        WebDavPage.History -> historyScroll
                        WebDavPage.Settings -> settingsScroll
                })
                .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                error?.let {
                    ErrorBanner(message = it, onRetry = { vm.clearError() })
                }
                if (page != WebDavPage.Settings && (!configured || hasChanges)) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (hasChanges) R.string.webdav_save_before_action else R.string.webdav_setup_hint))
                        TextButton(onClick = { selectedPageName = WebDavPage.Settings.name }) {
                            Text(stringResource(R.string.webdav_tab_settings))
                        }
                    }
                }
                when (page) {
                    WebDavPage.Settings -> {
                        // 1. 配置卡片
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = stringResource(R.string.webdav_config_title))
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
                                    enabled = !loading,
                                    checked = enabledInput,
                                    onCheckedChange = { enabledInput = it }
                                )
                            }
                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = urlInput,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                onValueChange = { urlInput = it; vm.clearTestResult() },
                                label = { Text(stringResource(R.string.webdav_url)) },
                                placeholder = { Text("https://example.com/dav/") },
                                singleLine = true,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = { usernameInput = it; vm.clearTestResult() },
                                label = { Text(stringResource(R.string.webdav_username)) },
                                singleLine = true,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it; vm.clearTestResult() },
                                label = { Text(stringResource(R.string.webdav_password)) },
                                supportingText = { if (config?.hasPassword == true) Text(stringResource(R.string.webdav_secret_saved)) },
                                singleLine = true,
                                enabled = !loading,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))

                            OutlinedTextField(
                                value = encryptionPasswordInput,
                                onValueChange = { encryptionPasswordInput = it },
                                label = { Text(stringResource(R.string.webdav_encryption_password)) },
                                supportingText = { if (config?.hasEncryptionPassword == true) Text(stringResource(R.string.webdav_secret_saved)) },
                                singleLine = true,
                                enabled = !loading,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )

                            testResult?.let { result ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (result == stringResource(R.string.webdav_connection_success)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (ServiceContainer.prefs.isLocalMode) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(
                                    title = stringResource(R.string.webdav_auto_incremental_title),
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            stringResource(R.string.webdav_auto_incremental_enabled),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            stringResource(R.string.webdav_auto_short_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        enabled = !loading,
                                        checked = autoIncrementalSyncEnabledInput,
                                        onCheckedChange = { autoIncrementalSyncEnabledInput = it }
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = autoIncrementalSyncIntervalHoursInput,
                                    isError = !intervalValid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    onValueChange = { value ->
                                        autoIncrementalSyncIntervalHoursInput = value.filter(Char::isDigit)
                                    },
                                    label = { Text(stringResource(R.string.webdav_auto_incremental_interval_hours)) },
                                    supportingText = {
                                        Text(stringResource(R.string.webdav_interval_range))
                                    },
                                    singleLine = true,
                                    enabled = !loading,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = incrementalSyncMaxVersionsInput,
                                    isError = !versionsValid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    onValueChange = { value ->
                                        incrementalSyncMaxVersionsInput = value.filter(Char::isDigit)
                                    },
                                    label = { Text(stringResource(R.string.webdav_incremental_max_versions)) },
                                    supportingText = {
                                        Text(stringResource(R.string.webdav_versions_range))
                                    },
                                    singleLine = true,
                                    enabled = !loading,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                    }
                    WebDavPage.History -> {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = stringResource(R.string.webdav_revision_history))
                            TextButton(onClick = { showHistoryPassword = !showHistoryPassword }) {
                                Text(stringResource(R.string.webdav_password_override))
                                Icon(if (showHistoryPassword) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                            }
                            if (showHistoryPassword || config?.hasEncryptionPassword != true) {
                                WebDavPasswordField(value = incrementalPassword, onValueChange = { incrementalPassword = it }, enabled = !loading)
                            }
                            OutlinedButton(onClick = { vm.loadHistory(incrementalPassword) }, enabled = canOperate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Filled.History, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.webdav_refresh_revision_history))
                            }
                            val currentRevision = incrementalHistory?.get("current_revision")?.asLong ?: 0L
                            val recordCount = incrementalHistory?.get("record_count")?.asInt ?: 0
                            val conflicts = incrementalHistory?.getAsJsonArray("conflict_details")
                            val revisions = incrementalHistory?.getAsJsonArray("revisions")
                            if (currentRevision > 0L) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.webdav_current_revision_summary, currentRevision, recordCount),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (conflicts != null && conflicts.size() > 0) {
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.webdav_conflicts_summary, conflicts.size()),
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                conflicts.forEach { element ->
                                    val conflict = element.asJsonObject
                                    val key = conflict.get("key")?.asString.orEmpty()
                                    val type = conflict.get("type")?.asString.orEmpty()
                                    val recordId = conflict.get("id")?.asString.orEmpty()
                                    val shortId = recordId.substringBefore(':').ifBlank { recordId }
                                    Text(
                                        if (type.isBlank()) key else "$type · $shortId",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                    val localCopyKey = conflict.get("local_conflict_copy_key")
                                    ?.takeUnless { it.isJsonNull }?.asString
                                    val remoteCopyKey = conflict.get("remote_conflict_copy_key")
                                    ?.takeUnless { it.isJsonNull }?.asString
                                    if (!localCopyKey.isNullOrBlank() && !remoteCopyKey.isNullOrBlank()) {
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            maxItemsInEachRow = 2
                                        ) {
                                            OutlinedButton(
                                                onClick = { vm.resolveConflict(localCopyKey, incrementalPassword) },
                                                enabled = canOperate,
                                                modifier = Modifier.weight(1f).widthIn(min = 140.dp).heightIn(min = 48.dp)
                                            ) {
                                                Text(stringResource(R.string.webdav_keep_local))
                                            }
                                            OutlinedButton(
                                                onClick = { vm.resolveConflict(remoteCopyKey, incrementalPassword) },
                                                enabled = canOperate,
                                                modifier = Modifier.weight(1f).widthIn(min = 140.dp).heightIn(min = 48.dp)
                                            ) {
                                                Text(stringResource(R.string.webdav_use_remote))
                                            }
                                        }
                                    } else {
                                        val resolution = when (conflict.get("resolution")?.asString) {
                                            "local" -> stringResource(R.string.webdav_keep_local)
                                            "remote" -> stringResource(R.string.webdav_use_remote)
                                            else -> stringResource(R.string.webdav_auto_resolved)
                                        }
                                        Text(
                                            resolution,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (revisions != null && revisions.size() > 0) {
                                Spacer(Modifier.height(12.dp))
                                Text(stringResource(R.string.webdav_revision_history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                revisions.forEach { element ->
                                    val revision = element.asJsonObject
                                    val number = revision.get("revision")?.asLong ?: return@forEach
                                    val current = revision.get("current")?.asBoolean == true
                                    val updatedAt = revision.get("updated_at")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                                        .ifBlank { revision.get("last_modified")?.takeUnless { it.isJsonNull }?.asString.orEmpty() }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                stringResource(R.string.webdav_revision_number, number) +
                                                if (current) stringResource(R.string.webdav_current_suffix) else "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (updatedAt.isNotBlank()) {
                                                Text(
                                                    formatWebDavTimestamp(updatedAt, deviceZoneId).orEmpty(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { restoreRevisionTarget = number },
                                            enabled = !current && canOperate
                                        ) {
                                            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                if (current) stringResource(R.string.webdav_current)
                                                else stringResource(R.string.webdav_restore)
                                            )
                                        }
                                    }
                                }
                            }

                            if (!loading && (incrementalHistory?.getAsJsonArray("revisions")?.size() ?: 0) == 0) {
                                Text(
                                    stringResource(if (incrementalHistory == null) R.string.webdav_history_load_hint else R.string.webdav_history_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            }
                        }
                    }
                    WebDavPage.Backup -> {
                        // 2. 远程文件信息
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(
                                title = stringResource(R.string.webdav_overview),
                                subtitle = config?.url?.takeIf { it.isNotBlank() },
                                trailing = {
                                    IconButton(onClick = { selectedPageName = WebDavPage.Settings.name }) {
                                        Icon(Icons.Filled.Settings, stringResource(R.string.webdav_tab_settings))
                                    }
                                }
                            )
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
                            val lastModified = remoteInfo?.get("last_modified")?.takeUnless { it.isJsonNull }?.asString ?: config?.lastModified
                            val resolvedUrl = remoteInfo?.get("resolved_file_url")?.takeUnless { it.isJsonNull }?.asString ?: config?.resolvedFileUrl

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                maxItemsInEachRow = 2
                            ) {
                                InfoRow(stringResource(R.string.webdav_last_backup), formatWebDavTimestamp(lastBackup, deviceZoneId) ?: stringResource(R.string.webdav_never_backup), Modifier.weight(1f).widthIn(min = 140.dp))
                                InfoRow(stringResource(R.string.webdav_last_sync), formatWebDavTimestamp(lastSync, deviceZoneId) ?: stringResource(R.string.webdav_never_sync), Modifier.weight(1f).widthIn(min = 140.dp))
                            }
                            TextButton(onClick = { showRemoteDetails = !showRemoteDetails }) {
                                Text(stringResource(R.string.webdav_remote_info_title))
                                Icon(if (showRemoteDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                            }
                            if (showRemoteDetails) {
                                InfoRow(stringResource(R.string.webdav_file_size), formatFileSize(fileSize))
                                InfoRow(stringResource(R.string.webdav_last_modified), formatWebDavTimestamp(lastModified, deviceZoneId) ?: stringResource(R.string.common_unknown))
                                if (!resolvedUrl.isNullOrBlank()) InfoRow(stringResource(R.string.webdav_file_url), resolvedUrl)
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
                                enabled = canOperate,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.webdav_refresh_info))
                            }
                        }

                        if (isLocal) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(title = stringResource(R.string.webdav_incremental_title), subtitle = stringResource(R.string.webdav_incremental_short_desc))
                                TextButton(onClick = { showIncrementalPassword = !showIncrementalPassword }) {
                                    Text(stringResource(R.string.webdav_password_override))
                                    Icon(if (showIncrementalPassword) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                                }
                                if (showIncrementalPassword || config?.hasEncryptionPassword != true) {
                                    WebDavPasswordField(value = incrementalPassword, onValueChange = { incrementalPassword = it }, enabled = !loading)
                                }
                                Button(onClick = { vm.incrementalSync(incrementalPassword) }, enabled = canOperate && (config?.hasEncryptionPassword == true || incrementalPassword.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Icon(Icons.Filled.CloudSync, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.webdav_incremental_button))
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 2) {
                                    TextButton(onClick = { selectedPageName = WebDavPage.Settings.name }) {
                                        Text(stringResource(if (config?.autoIncrementalSyncEnabled == true) R.string.webdav_auto_on else R.string.webdav_auto_off))
                                    }
                                    TextButton(onClick = { selectedPageName = WebDavPage.History.name }) {
                                        Text(stringResource(R.string.webdav_revision_history))
                                    }
                                }
                            }
                        }
                        SectionHeader(title = stringResource(R.string.webdav_full_backup))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
                            WebDavActionCard(stringResource(R.string.webdav_upload_backup), stringResource(R.string.webdav_upload_short_desc), Icons.Filled.Backup, canOperate, { showBackupOptions = true }, Modifier.weight(1f).widthIn(min = 140.dp))
                            WebDavActionCard(stringResource(R.string.webdav_restore), stringResource(R.string.webdav_restore_short_desc), Icons.Filled.Restore, canOperate, { showRestoreOptions = true }, Modifier.weight(1f).widthIn(min = 140.dp))
                        }
                    }
                }
            }

            LoadingOverlay(visible = loading && transferProgress == null)
            transferProgress?.let { WebDavTransferDialog(it) }
        }
    }

    if (showBackupOptions || showRestoreOptions) {
        val restoring = showRestoreOptions
        AlertDialog(
            onDismissRequest = { showBackupOptions = false; showRestoreOptions = false },
            title = { Text(stringResource(if (restoring) R.string.webdav_restore else R.string.webdav_upload_backup)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    WebDavOperationOptions(
                        password = if (restoring) syncPassword else backupPassword,
                        onPasswordChange = { if (restoring) syncPassword = it else backupPassword = it },
                        includeImages = if (restoring) syncIncludePortraits else backupIncludePortraits,
                        onIncludeImagesChange = { if (restoring) syncIncludePortraits = it else backupIncludePortraits = it },
                        hasSavedPassword = config?.hasEncryptionPassword == true,
                        enabled = !loading
                    )
                }
            },
            confirmButton = {
                val operationPassword = if (restoring) syncPassword else backupPassword
                TextButton(
                    enabled = canOperate && (!isLocal || config?.hasEncryptionPassword == true || operationPassword.isNotBlank()),
                    onClick = {
                        showBackupOptions = false
                        showRestoreOptions = false
                        if (restoring) {
                            if (isLocal) vm.previewSync(syncPassword) else showSyncConfirm = true
                        } else {
                            vm.backup(backupPassword, backupIncludePortraits)
                        }
                    }
                ) {
                    Text(stringResource(if (restoring && isLocal) R.string.webdav_preview_restore else if (restoring) R.string.webdav_restore else R.string.webdav_upload_backup))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupOptions = false; showRestoreOptions = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showSyncConfirm) {
        AlertDialog(
            onDismissRequest = { showSyncConfirm = false },
            title = { Text(stringResource(R.string.webdav_sync_confirm_title)) },
            text = { Text(stringResource(R.string.webdav_sync_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !loading,
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

    restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { vm.clearRestorePreview() },
            title = { Text(stringResource(R.string.webdav_sync_confirm_title)) },
            text = {
                val summary = stringResource(
                    R.string.webdav_restore_preview_summary,
                    preview.get("archive_version")?.asInt ?: 1,
                    preview.get("total_rows")?.asInt ?: 0,
                    preview.get("total_files")?.asInt ?: 0,
                    preview.getAsJsonArray("categories")?.size() ?: 0,
                    formatWebDavTimestamp(preview.get("created_at")?.takeUnless { it.isJsonNull }?.asString, deviceZoneId)
                        ?: stringResource(R.string.common_unknown)
                )
                Text(stringResource(R.string.webdav_sync_confirm_message) + "\n\n" + summary)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearRestorePreview()
                        vm.sync(syncPassword, syncIncludePortraits)
                    },
                    enabled = !loading
                ) {
                    Text(stringResource(R.string.webdav_sync_pull_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.clearRestorePreview() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    restoreRevisionTarget?.let { revision ->
        AlertDialog(
            onDismissRequest = { restoreRevisionTarget = null },
            title = { Text(stringResource(R.string.webdav_restore_revision_title, revision)) },
            text = {
                Text(stringResource(R.string.webdav_restore_revision_message))
            },
            confirmButton = {
                TextButton(
                    enabled = canOperate,
                    onClick = {
                        restoreRevisionTarget = null
                        vm.restoreRevision(revision, incrementalPassword)
                    }
                ) {
                    Text(stringResource(R.string.webdav_confirm_restore), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreRevisionTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 标签和值分行，时间与长地址自然换行。 */
@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WebDavActionCard(title: String, subtitle: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WebDavPasswordField(value: String, onValueChange: (String) -> Unit, enabled: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, enabled = enabled,
        label = { Text(stringResource(R.string.webdav_encryption_password_optional_override)) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    )
}

@Composable
private fun WebDavOperationOptions(
    password: String,
    onPasswordChange: (String) -> Unit,
    includeImages: Boolean,
    onIncludeImagesChange: (Boolean) -> Unit,
    hasSavedPassword: Boolean,
    enabled: Boolean
) {
    var showPassword by remember(hasSavedPassword) { mutableStateOf(!hasSavedPassword || password.isNotBlank()) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.webdav_include_images_short), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.webdav_images_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = includeImages, onCheckedChange = onIncludeImagesChange, enabled = enabled)
    }
    if (hasSavedPassword) {
        TextButton(onClick = {
                showPassword = !showPassword
                if (!showPassword) onPasswordChange("")
        }) {
            Text(stringResource(R.string.webdav_password_override))
            Icon(if (showPassword) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
    }
    if (showPassword) {
        WebDavPasswordField(value = password, onValueChange = onPasswordChange, enabled = enabled)
    }
}

@Composable
private fun WebDavTransferDialog(progress: WebDavTransferProgress) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val title = when {
                    progress.completed -> R.string.webdav_transfer_processing
                    progress.stage == WebDavTransferStage.Uploading -> R.string.webdav_transfer_uploading
                    progress.stage == WebDavTransferStage.Downloading -> R.string.webdav_transfer_downloading
                    progress.stage == WebDavTransferStage.ServerProcessing -> R.string.webdav_transfer_server
                    else -> R.string.webdav_transfer_preparing
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                if (progress.fileName.isNotBlank()) {
                    Text(
                        progress.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val percent = progress.percent
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.webdav_transfer_percent, percent), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (progress.stage == WebDavTransferStage.Uploading || progress.stage == WebDavTransferStage.Downloading) {
                    val bytes = formatFileSize(progress.transferredBytes)
                    Text(
                        if (progress.totalBytes != null) "$bytes / ${formatFileSize(progress.totalBytes)}" else bytes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
