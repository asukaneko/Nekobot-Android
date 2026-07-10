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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowRight
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

    init {
        loadSettings()
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

    /** 解析日志 JSON 数组为 LogEntry 列表。 */
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
        }
    }

    fun dismissLogs() {
        _showLogs.value = false
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

                // 1. 服务器配置
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

                // 2. AI 管理
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = "AI 管理")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onNavigate("ai_config") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("AI 配置")
                        }
                        OutlinedButton(
                            onClick = { onNavigate("ai_models") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("AI 模型")
                        }
                    }
                }

                // 3. 系统设置（跳转子界面编辑 JSON）
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

                // 4. 系统操作
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

                // 5. 日志查看
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = "日志查看")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { vm.loadLogs() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("查看最近日志")
                    }
                }

                // 6. 账号
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
