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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.theme.BgDark
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

/**
 * 系统设置页 ViewModel
 */
class SettingsViewModel : BaseViewModel() {

    private val _settingsJson = MutableStateFlow("")
    val settingsJson: StateFlow<String> = _settingsJson.asStateFlow()

    private val _logsJson = MutableStateFlow("")
    val logsJson: StateFlow<String> = _logsJson.asStateFlow()

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
                _logsJson.value = prettyGson.toJson(json)
                _showLogs.value = true
            }
        )
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
fun SettingsScreen(onLogout: () -> Unit, onNavigate: (String) -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val settingsJson by vm.settingsJson.collectAsState()
    val logsJson by vm.logsJson.collectAsState()
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgDark,
                    titleContentColor = OnSurface
                )
            )
        },
        containerColor = BgDark
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
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("保存地址", color = OnPrimary)
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "当前用户: ${ServiceContainer.prefs.username.ifBlank { "未登录" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceVariant
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
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Primary)
                            Spacer(Modifier.width(8.dp))
                            Text("AI 配置")
                        }
                        OutlinedButton(
                            onClick = { onNavigate("ai_models") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Memory, contentDescription = null, tint = Primary)
                            Spacer(Modifier.width(8.dp))
                            Text("AI 模型")
                        }
                    }
                }

                // 3. 系统设置 (JSON 编辑器)
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = "系统设置", subtitle = "以 JSON 格式编辑")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = settingsInput,
                        onValueChange = { settingsInput = it },
                        label = { Text("设置 JSON") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.saveSettings(settingsInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, tint = OnPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("保存设置", color = OnPrimary)
                    }
                }

                // 4. 系统操作
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = "系统操作")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.reloadConfig() },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.CloudSync, contentDescription = null, tint = OnPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("重载配置", color = OnPrimary)
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
                        Icon(Icons.Filled.Description, contentDescription = null, tint = Primary)
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
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Logout, contentDescription = null, tint = OnPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("退出登录", color = OnPrimary)
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 日志弹窗
    if (showLogs) {
        NekoDialog(
            onDismiss = { vm.dismissLogs() },
            title = "系统日志",
            confirmText = "关闭",
            onConfirm = { vm.dismissLogs() },
            cancelText = null,
            onCancel = null
        ) {
            Text(
                text = logsJson.ifBlank { "暂无日志" },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}
