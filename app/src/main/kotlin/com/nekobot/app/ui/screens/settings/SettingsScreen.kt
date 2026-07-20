package com.nekobot.app.ui.screens.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.OnPrimary
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 用于格式化 JSON 输出 */
private val prettyGson = GsonBuilder().setPrettyPrinting().setLenient().disableHtmlEscaping().create()

/** 系统日志条目。 */
data class LogEntry(
    val time: String,
    val level: String,
    val message: String
)

/**
 * 系统设置页 ViewModel
 */
class SettingsViewModel : BaseViewModel() {

    private val _settingsJson = MutableStateFlow("")
    val settingsJson: StateFlow<String> = _settingsJson.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _showLogs = MutableStateFlow(false)
    val showLogs: StateFlow<Boolean> = _showLogs.asStateFlow()

    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    private val _serverUrl = MutableStateFlow(ServiceContainer.prefs.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _appMode = MutableStateFlow(ServiceContainer.prefs.appMode)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    /** 切换运行模式：写入 prefs + 广播全局 Flow + 重建网络 */
    fun switchMode(mode: AppMode) {
        ServiceContainer.switchAppMode(mode)
        _appMode.value = mode
        ServiceContainer.rebuildNetwork()
        showToast(if (mode == AppMode.LOCAL) string(R.string.settings_switched_to_local) else string(R.string.settings_switched_to_server))
    }

    init {
        // 本地模式无需调用远程 getSettings（避免 401 错误）
        if (ServiceContainer.prefs.appMode != AppMode.LOCAL) {
            loadSettings()
        }
    }

    fun loadSettings() {
        launchResult(
            block = { repo.getSettings() },
            onSuccess = { json ->
                _settingsJson.value = prettyGson.toJson(json)
            }
        )
    }

    fun saveSettings(jsonStr: String) {
        try {
            val json = JsonParser.parseString(jsonStr)
            launchResult(
                block = { repo.updateSettings(json) },
                onSuccess = { showToast(string(R.string.settings_settings_saved)) }
            )
        } catch (e: Exception) {
            showError(string(R.string.settings_json_format_error, e.message ?: ""))
        }
    }

    fun reloadConfig() {
        launchResult(
            block = { repo.reloadConfig() },
            onSuccess = { showToast(string(R.string.settings_config_reloaded)) }
        )
    }

    fun loadLogs() {
        launchResult(
            block = { repo.listLogs() },
            onSuccess = { json ->
                _logs.value = parseLogs(json)
                _showLogs.value = true
            }
        )
    }

    /** 解析日志 JSON 数组为 LogEntry 列表（按时间降序）。 */
    private fun parseLogs(json: JsonElement?): List<LogEntry> {
        if (json == null) return emptyList()
        val arr = if (json.isJsonArray) json.asJsonArray else return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject
            LogEntry(
                time = obj.get("time")?.asString ?: "",
                level = obj.get("level")?.asString ?: "info",
                message = obj.get("message")?.asString ?: ""
            )
        }.sortedByDescending { it.time }
    }

    fun dismissLogs() {
        _showLogs.value = false
    }

    /** 加载本地模式运行日志（来自 LocalLogger 持久化存储）。 */
    fun loadLocalLogs() {
        val records = LocalLogger.listLogs()
        _logs.value = records.map { rec ->
            LogEntry(
                time = "${rec.date} ${rec.time}",
                level = rec.level,
                message = if (rec.tag.isNotBlank()) "[${rec.tag}] ${rec.message}" else rec.message
            )
        }
        _showLogs.value = true
    }

    /** 清空本地日志。 */
    fun clearLocalLogs() {
        LocalLogger.clear()
        _logs.value = emptyList()
        _showLogs.value = false
        showToast(string(R.string.settings_local_logs_cleared))
    }

    fun logout() {
        launchResult(
            block = { repo.logout() },
            onSuccess = { _loggedOut.value = true }
        )
    }

    fun saveServerUrl(url: String) {
        ServiceContainer.prefs.serverUrl = url
        ServiceContainer.rebuildNetwork()
        _serverUrl.value = ServiceContainer.prefs.serverUrl
        showToast(string(R.string.settings_server_url_updated))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogout: () -> Unit, onNavigate: (String) -> Unit, onBack: () -> Unit = onLogout) {
    val vm: SettingsViewModel = viewModel()
    val settingsJson by vm.settingsJson.collectAsState()
    val logs by vm.logs.collectAsState()
    val showLogs by vm.showLogs.collectAsState()
    val loggedOut by vm.loggedOut.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()
    val appMode by vm.appMode.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    // 本地编辑状态
    var serverUrlInput by remember(serverUrl) { mutableStateOf(serverUrl) }
    var settingsInput by remember(settingsJson) { mutableStateOf(settingsJson) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(loggedOut) {
        if (loggedOut) {
            onLogout()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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

                // 0. 运行模式切换
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = stringResource(R.string.settings_run_mode))
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (appMode == AppMode.LOCAL) stringResource(R.string.settings_local_mode) else stringResource(R.string.settings_server_mode),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (appMode == AppMode.LOCAL) stringResource(R.string.settings_local_mode_desc)
                                else stringResource(R.string.settings_server_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = appMode == AppMode.LOCAL,
                            onCheckedChange = { checked ->
                                vm.switchMode(if (checked) AppMode.LOCAL else AppMode.SERVER)
                            }
                        )
                    }
                    if (appMode == AppMode.LOCAL) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onNavigate("local_ai_models") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_local_ai_config))
                        }
                    }
                }

                // 1. 服务器配置（仅服务器模式）
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.settings_server_config))
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text(stringResource(R.string.settings_server_address)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { vm.saveServerUrl(serverUrlInput) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(stringResource(R.string.settings_save_address), color = MaterialTheme.colorScheme.onPrimary)
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_current_user, ServiceContainer.prefs.username.ifBlank { stringResource(R.string.common_not_logged_in) }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 2. 系统设置（仅服务器模式）
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onNavigate("system_settings") }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.SettingsIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_system_settings), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.settings_system_settings_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 4. 系统操作（仅服务器模式）
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.settings_system_actions))
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.reloadConfig() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_reload_config), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                // 4.5 高级功能：本地模式仅显示"数据维护"，服务器模式显示全部
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.settings_advanced))
                        Spacer(Modifier.height(12.dp))
                        SettingNavRow(
                            icon = Icons.Filled.Tune,
                            iconColor = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.settings_feature_switches),
                            subtitle = stringResource(R.string.settings_feature_switches_desc)
                        ) { onNavigate("feature_switches") }
                        SettingNavRow(
                            icon = Icons.Filled.Build,
                            iconColor = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.settings_data_maintenance),
                            subtitle = stringResource(R.string.settings_data_maintenance_desc)
                        ) { onNavigate("data_maintenance") }
                        SettingNavRow(
                            icon = Icons.Filled.ImportExport,
                            iconColor = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.settings_config_transfer),
                            subtitle = stringResource(R.string.settings_config_transfer_desc)
                        ) { onNavigate("config_transfer") }
                        SettingNavRow(
                            icon = Icons.Filled.CloudUpload,
                            iconColor = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.settings_webdav_backup),
                            subtitle = stringResource(R.string.settings_webdav_backup_desc)
                        ) { onNavigate("webdav_backup") }
                    }
                } else {
                    // 本地模式：仅显示"数据维护"入口（其他高级功能依赖服务器）
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.settings_advanced))
                        Spacer(Modifier.height(12.dp))
                        SettingNavRow(
                            icon = Icons.Filled.Build,
                            iconColor = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.settings_data_maintenance),
                            subtitle = stringResource(R.string.settings_data_maintenance_desc)
                        ) { onNavigate("data_maintenance") }
                    }
                }

                // 5. 日志查看（服务器模式看服务端日志，本地模式看 LocalLogger）
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = if (appMode == AppMode.LOCAL) stringResource(R.string.settings_local_logs) else stringResource(R.string.settings_logs_view))
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            if (appMode == AppMode.LOCAL) vm.loadLocalLogs() else vm.loadLogs()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(if (appMode == AppMode.LOCAL) stringResource(R.string.settings_view_local_logs) else stringResource(R.string.settings_view_recent_logs))
                    }
                    if (appMode == AppMode.LOCAL) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.clearLocalLogs() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_clear_local_logs), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // 6. 账号（仅服务器模式，本地模式无需登录）
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = stringResource(R.string.settings_account))
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.logout() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                // 7. 语言设置
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showLanguagePicker = true }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                            Text(
                                when (ServiceContainer.prefs.language) {
                                    PrefsManager.LANGUAGE_ZH -> stringResource(R.string.language_chinese)
                                    PrefsManager.LANGUAGE_EN -> stringResource(R.string.language_english)
                                    PrefsManager.LANGUAGE_JA -> stringResource(R.string.language_japanese)
                                    PrefsManager.LANGUAGE_KO -> stringResource(R.string.language_korean)
                                    else -> stringResource(R.string.language_system)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 8. 关于
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = stringResource(R.string.settings_about))
                    Spacer(Modifier.height(8.dp))
                    AboutRow(
                        icon = Icons.Filled.Person,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_about_producer),
                        subtitle = "Asukaneko",
                        onClick = { openUrl(context, "https://github.com/asukaneko") }
                    )
                    AboutRow(
                        icon = Icons.Filled.Description,
                        iconColor = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.settings_about_android_repo),
                        subtitle = "github.com/asukaneko/Nekobot-Android",
                        onClick = { openUrl(context, "https://github.com/asukaneko/Nekobot-Android") }
                    )
                    AboutRow(
                        icon = Icons.Filled.Memory,
                        iconColor = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.settings_about_server_repo),
                        subtitle = "github.com/asukaneko/nekobot",
                        onClick = { openUrl(context, "https://github.com/asukaneko/nekobot") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_about_version, getAppVersion(context)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 语言选择弹窗
    if (showLanguagePicker) {
        LanguagePickerDialog(
            currentLanguage = ServiceContainer.prefs.language,
            onSelect = { tag ->
                ServiceContainer.prefs.language = tag
                ServiceContainer.refreshLocale()
                showLanguagePicker = false
                // 重建 Activity 使新 locale 生效
                (context as? Activity)?.recreate()
            },
            onDismiss = { showLanguagePicker = false }
        )
    }

    // 日志弹窗：卡片列表展示
    if (showLogs) {
        NekoDialog(
            onDismiss = { vm.dismissLogs() },
            title = stringResource(R.string.settings_system_logs, logs.size),
            confirmText = stringResource(R.string.common_close),
            onConfirm = { vm.dismissLogs() },
            cancelText = null,
            onCancel = null
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs) { log ->
                        LogCard(log)
                    }
                }
            }
        }
    }
}

/** 语言选择弹窗：跟随系统 / 简体中文 / English / 日本語 / 한국어。 */
@Composable
private fun LanguagePickerDialog(
    currentLanguage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        PrefsManager.LANGUAGE_SYSTEM to stringResource(R.string.language_system),
        PrefsManager.LANGUAGE_ZH to stringResource(R.string.language_chinese),
        PrefsManager.LANGUAGE_EN to stringResource(R.string.language_english),
        PrefsManager.LANGUAGE_JA to stringResource(R.string.language_japanese),
        PrefsManager.LANGUAGE_KO to stringResource(R.string.language_korean)
    )
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_language),
        confirmText = stringResource(R.string.common_close),
        onConfirm = onDismiss,
        cancelText = null,
        onCancel = null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            options.forEach { (tag, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(tag) }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (tag == currentLanguage) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** 单条日志卡片：左侧等级色条 + 时间 + 消息内容。 */
@Composable
private fun LogCard(log: LogEntry) {
    val levelColor = when (log.level.lowercase()) {
        "error" -> MaterialTheme.colorScheme.error
        "warning", "warn" -> Color(0xFFFFB347)
        "debug" -> Color(0xFF6BAED6)
        else -> MaterialTheme.colorScheme.primary
    }
    val levelLabel = when (log.level.lowercase()) {
        "warning" -> "WARN"
        "warn" -> "WARN"
        else -> log.level.uppercase()
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 10,
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // 左侧等级色条
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(levelColor)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = levelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = log.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 通用设置导航行：图标 + 标题 + 副标题 + 右箭头。 */
@Composable
private fun SettingNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 关于页面的导航行：与 [SettingNavRow] 等价，但右箭头前可显示更紧凑的副标题。 */
@Composable
private fun AboutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 用浏览器打开指定 URL，失败时 Toast 提示。 */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.common_cannot_open_link), Toast.LENGTH_SHORT).show()
    }
}

/** 获取当前应用版本名；失败时返回 "unknown"。 */
private fun getAppVersion(context: android.content.Context): String {
    return runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        info.versionName ?: "unknown"
    }.getOrDefault("unknown")
}
