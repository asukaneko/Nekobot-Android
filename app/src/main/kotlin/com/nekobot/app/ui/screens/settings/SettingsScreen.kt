package com.nekobot.app.ui.screens.settings

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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.LocalLogger
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
        showToast(if (mode == AppMode.LOCAL) "已切换到本地模式" else "已切换到服务器模式")
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
                onSuccess = { showToast("设置已保存") }
            )
        } catch (e: Exception) {
            showError("JSON 格式错误: ${e.message}")
        }
    }

    fun reloadConfig() {
        launchResult(
            block = { repo.reloadConfig() },
            onSuccess = { showToast("配置已重载") }
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
        showToast("已清空本地日志")
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
        showToast("服务器地址已更新")
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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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
                    SectionHeader(title = "运行模式")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (appMode == AppMode.LOCAL) "本地模式" else "服务器模式",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (appMode == AppMode.LOCAL) "直连 AI API，数据存储在本地"
                                else "通过后端服务器进行 AI 对话",
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
                            Text("本地 AI 模型配置")
                        }
                    }
                }

                // 1. 服务器配置（仅服务器模式）
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "服务器配置")
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text("服务器地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { vm.saveServerUrl(serverUrlInput) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("保存地址", color = MaterialTheme.colorScheme.onPrimary)
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "当前用户: ${ServiceContainer.prefs.username.ifBlank { "未登录" }}",
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
                                Text("系统设置", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                Text("以 JSON 格式编辑系统配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 4. 系统操作（仅服务器模式）
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "系统操作")
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.reloadConfig() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("重载配置", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                // 4.5 高级功能（仅服务器模式）：功能开关 / 数据维护 / 配置迁移 / WebDAV 备份
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "高级功能")
                        Spacer(Modifier.height(12.dp))
                        SettingNavRow(
                            icon = Icons.Filled.Tune,
                            iconColor = MaterialTheme.colorScheme.primary,
                            title = "功能开关",
                            subtitle = "开启或关闭各项功能模块"
                        ) { onNavigate("feature_switches") }
                        SettingNavRow(
                            icon = Icons.Filled.Build,
                            iconColor = MaterialTheme.colorScheme.tertiary,
                            title = "数据维护",
                            subtitle = "清理缓存、重建索引、压缩数据"
                        ) { onNavigate("data_maintenance") }
                        SettingNavRow(
                            icon = Icons.Filled.ImportExport,
                            iconColor = MaterialTheme.colorScheme.secondary,
                            title = "配置迁移",
                            subtitle = "导入或导出配置包"
                        ) { onNavigate("config_transfer") }
                        SettingNavRow(
                            icon = Icons.Filled.CloudUpload,
                            iconColor = MaterialTheme.colorScheme.primary,
                            title = "WebDAV 备份",
                            subtitle = "配置远程备份并定时同步"
                        ) { onNavigate("webdav_backup") }
                    }
                }

                // 5. 日志查看（服务器模式看服务端日志，本地模式看 LocalLogger）
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = if (appMode == AppMode.LOCAL) "本地日志" else "日志查看")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            if (appMode == AppMode.LOCAL) vm.loadLocalLogs() else vm.loadLogs()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(if (appMode == AppMode.LOCAL) "查看本地运行日志" else "查看最近日志")
                    }
                    if (appMode == AppMode.LOCAL) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.clearLocalLogs() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("清空本地日志", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // 6. 账号（仅服务器模式，本地模式无需登录）
                if (appMode != AppMode.LOCAL) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "账号")
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.logout() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("退出登录", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 日志弹窗：卡片列表展示
    if (showLogs) {
        NekoDialog(
            onDismiss = { vm.dismissLogs() },
            title = "系统日志 (${logs.size})",
            confirmText = "关闭",
            onConfirm = { vm.dismissLogs() },
            cancelText = null,
            onCancel = null
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "暂无日志",
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
