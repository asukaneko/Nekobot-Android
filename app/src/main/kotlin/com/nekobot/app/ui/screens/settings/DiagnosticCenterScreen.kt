package com.nekobot.app.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.remote.SocketState
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 用于格式化 JSON 输出 */
private val prettyGson = GsonBuilder().setPrettyPrinting().setLenient().disableHtmlEscaping().create()

/** 连通性测试状态 */
sealed class PingState {
    data object Idle : PingState()
    data object Pinging : PingState()
    data class Success(val latencyMs: Long) : PingState()
    data class Error(val message: String) : PingState()
}

/** Gateway 健康判定结果 */
enum class HealthStatus { OK, ERROR, UNKNOWN }

/** 事件日志条目（从 Gateway 事件 JSON 解析） */
data class GatewayEvent(
    val time: String,
    val type: String,
    val status: String,
    val traceId: String
)

/**
 * 运行诊断中心 ViewModel
 *
 * 聚合本地链路状态（Socket / 连通性 / 本地日志）与 Gateway 诊断数据，
 * 供 [DiagnosticCenterScreen] 展示。模型不回复、Socket 异常、工具卡住时
 * 可通过此页面查看真实链路状态。
 */
class DiagnosticViewModel : BaseViewModel() {

    // ==================== Gateway 诊断数据 ====================

    private val _gatewayHealth = MutableStateFlow<JsonElement?>(null)
    val gatewayHealth: StateFlow<JsonElement?> = _gatewayHealth.asStateFlow()

    private val _gatewayStats = MutableStateFlow<JsonElement?>(null)
    val gatewayStats: StateFlow<JsonElement?> = _gatewayStats.asStateFlow()

    private val _queueStatus = MutableStateFlow<JsonElement?>(null)
    val queueStatus: StateFlow<JsonElement?> = _queueStatus.asStateFlow()

    private val _events = MutableStateFlow<JsonElement?>(null)
    val events: StateFlow<JsonElement?> = _events.asStateFlow()

    // ==================== Trace 查询 ====================

    private val _traceResult = MutableStateFlow<JsonElement?>(null)
    val traceResult: StateFlow<JsonElement?> = _traceResult.asStateFlow()

    private val _traceError = MutableStateFlow<String?>(null)
    val traceError: StateFlow<String?> = _traceError.asStateFlow()

    // ==================== 连通性测试 ====================

    private val _pingState = MutableStateFlow<PingState>(PingState.Idle)
    val pingState: StateFlow<PingState> = _pingState.asStateFlow()

    // ==================== 本地日志 ====================

    private val _localLogs = MutableStateFlow<List<LocalLogger.Record>>(emptyList())
    val localLogs: StateFlow<List<LocalLogger.Record>> = _localLogs.asStateFlow()

    init {
        refreshAll()
    }

    /** 重新加载全部数据（本地日志 + Gateway 诊断，仅服务器模式拉取 Gateway） */
    fun refreshAll() {
        // 本地日志同步加载
        _localLogs.value = LocalLogger.listLogs().take(200)
        // 仅服务器模式拉取 Gateway 数据
        if (ServiceContainer.prefs.appMode != AppMode.LOCAL) {
            viewModelScope.launch {
                setLoading(true)
                try {
                    // 并行拉取所有 Gateway 诊断接口
                    val health = unified.gatewayHealth()
                    _gatewayHealth.value = (health as? Resource.Success)?.data

                    val stats = unified.gatewayStats()
                    _gatewayStats.value = (stats as? Resource.Success)?.data

                    val queue = unified.gatewayQueueStatus()
                    _queueStatus.value = (queue as? Resource.Success)?.data

                    val evts = unified.gatewayEvents(limit = 50)
                    _events.value = (evts as? Resource.Success)?.data
                } catch (e: Exception) {
                    showError(e.message ?: string(R.string.common_unknown_error))
                } finally {
                    setLoading(false)
                }
            }
        }
    }

    // ==================== Trace 查询 ====================

    /** 根据 Trace ID 查询链路日志 */
    fun searchTrace(traceId: String) {
        if (traceId.isBlank()) {
            _traceError.value = "请输入 Trace ID"
            return
        }
        _traceError.value = null
        launchResult(
            block = { unified.gatewayLogsTrace(traceId) },
            onSuccess = { _traceResult.value = it },
            onError = { msg ->
                _traceResult.value = null
                _traceError.value = msg
            }
        )
    }

    // ==================== 连通性测试 ====================

    /** 发起轻量级 API 请求测试服务器连通性，测量延迟（ms） */
    fun testConnectivity() {
        viewModelScope.launch {
            _pingState.value = PingState.Pinging
            try {
                val start = System.currentTimeMillis()
                val res = repo.gatewayHealth()
                val latency = System.currentTimeMillis() - start
                _pingState.value = when (res) {
                    is Resource.Success -> PingState.Success(latency)
                    is Resource.Error -> PingState.Error(res.message)
                    is Resource.Loading -> PingState.Idle
                }
            } catch (e: Exception) {
                _pingState.value = PingState.Error(e.message ?: "未知错误")
            }
        }
    }

    // ==================== 本地日志 ====================

    /** 从 LocalLogger 加载本地日志（最新在前，限制 200 条避免内存压力） */
    fun loadLocalLogs() {
        _localLogs.value = LocalLogger.listLogs().take(200)
    }

    /** 清空本地日志 */
    fun clearLocalLogs() {
        LocalLogger.clear()
        _localLogs.value = emptyList()
        showToast("日志已清空")
    }
}

// ==================== 页面入口 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticCenterScreen(onBack: () -> Unit) {
    val vm: DiagnosticViewModel = viewModel()
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val pingState by vm.pingState.collectAsState()
    val localLogs by vm.localLogs.collectAsState()
    val gatewayHealth by vm.gatewayHealth.collectAsState()
    val gatewayStats by vm.gatewayStats.collectAsState()
    val queueStatus by vm.queueStatus.collectAsState()
    val events by vm.events.collectAsState()
    val traceResult by vm.traceResult.collectAsState()
    val traceError by vm.traceError.collectAsState()
    val context = LocalContext.current

    // Trace 输入框文本
    var traceInput by remember { mutableStateOf("") }

    // Toast 消息处理
    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostic_center_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.diagnostic_refresh),
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
                // 错误提示
                error?.let {
                    ErrorBanner(message = it, onRetry = { vm.clearError() })
                }

                // ==================== 本地状态区（所有模式可用） ====================

                // 1. Socket 连接卡片
                SocketStateCard()

                // 2. 服务器连通性测试卡片
                ConnectivityTestCard(
                    pingState = pingState,
                    onTest = { vm.testConnectivity() }
                )

                // 3. 本地日志卡片
                LocalLogCard(
                    logs = localLogs,
                    onClear = { vm.clearLocalLogs() },
                    context = context
                )

                // ==================== Gateway 诊断区 ====================

                if (appMode != AppMode.LOCAL) {
                    // 4. Gateway 健康卡片
                    GatewayJsonCard(
                        title = stringResource(R.string.diagnostic_gateway_health),
                        json = gatewayHealth,
                        healthStatus = extractHealthStatus(gatewayHealth),
                        onRefresh = { vm.refreshAll() }
                    )

                    // 5. Gateway 统计卡片
                    GatewayJsonCard(
                        title = stringResource(R.string.diagnostic_gateway_stats),
                        json = gatewayStats,
                        onRefresh = { vm.refreshAll() }
                    )

                    // 6. 队列状态卡片
                    GatewayJsonCard(
                        title = stringResource(R.string.diagnostic_queue_status),
                        json = queueStatus,
                        onRefresh = { vm.refreshAll() }
                    )

                    // 7. 事件日志卡片
                    EventLogCard(
                        json = events,
                        onRefresh = { vm.refreshAll() }
                    )

                    // 8. Trace 查询卡片
                    TraceLookupCard(
                        traceInput = traceInput,
                        onInputChange = { traceInput = it },
                        onSearch = { vm.searchTrace(traceInput) },
                        traceResult = traceResult,
                        traceError = traceError
                    )
                } else {
                    // 本地模式提示
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.diagnostic_only_server_mode),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }
}

// ==================== 辅助 Composable ====================

/** Socket 连接状态卡片：显示当前连接状态 + 服务器地址 */
@Composable
private fun SocketStateCard() {
    val socketState by ServiceContainer.socket.state.collectAsState()
    val serverUrl = ServiceContainer.prefs.serverUrl

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.diagnostic_socket_state))
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态指示灯
            val (dotColor, statusText) = when (socketState) {
                SocketState.Connected -> Color(0xFF4CAF50) to stringResource(R.string.diagnostic_status_connected)
                SocketState.Connecting -> Color(0xFFFFB347) to stringResource(R.string.diagnostic_status_connecting)
                SocketState.Disconnected -> Color(0xFF9E9E9E) to stringResource(R.string.diagnostic_status_disconnected)
                SocketState.Error -> MaterialTheme.colorScheme.error to stringResource(R.string.diagnostic_status_error)
            }
            StatusDot(color = dotColor)
            Spacer(Modifier.width(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = serverUrl.ifBlank { "(未配置)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 服务器连通性测试卡片：发起轻量级请求测量延迟 */
@Composable
private fun ConnectivityTestCard(
    pingState: PingState,
    onTest: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.diagnostic_server_connect))
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onTest,
                enabled = pingState !is PingState.Pinging,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (pingState is PingState.Pinging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.diagnostic_pinging),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        stringResource(R.string.diagnostic_ping_test),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // 结果显示
            when (pingState) {
                is PingState.Success -> {
                    StatusDot(color = Color(0xFF4CAF50))
                    Text(
                        text = stringResource(R.string.diagnostic_status_ok),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.diagnostic_latency_ms, pingState.latencyMs.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                is PingState.Error -> {
                    StatusDot(color = MaterialTheme.colorScheme.error)
                    Text(
                        text = stringResource(R.string.diagnostic_status_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = pingState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.diagnostic_status_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 本地日志卡片：按级别筛选 + 列表展示 + 清空/复制 */
@Composable
private fun LocalLogCard(
    logs: List<LocalLogger.Record>,
    onClear: () -> Unit,
    context: Context
) {
    // 日志级别筛选
    var selectedLevel by remember { mutableStateOf(0) } // 0=全部, 1=错误, 2=警告, 3=信息
    val levels = listOf(
        stringResource(R.string.diagnostic_log_level_all),
        stringResource(R.string.diagnostic_log_level_error),
        stringResource(R.string.diagnostic_log_level_warning),
        stringResource(R.string.diagnostic_log_level_info)
    )

    // 按级别过滤
    val filteredLogs = remember(logs, selectedLevel) {
        when (selectedLevel) {
            1 -> logs.filter { it.level == LocalLogger.LEVEL_ERROR }
            2 -> logs.filter { it.level == LocalLogger.LEVEL_WARNING }
            3 -> logs.filter { it.level == LocalLogger.LEVEL_INFO }
            else -> logs
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.diagnostic_local_log))
        Spacer(Modifier.height(12.dp))

        // 筛选 Chip 行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            levels.forEachIndexed { index, label ->
                FilterChip(
                    selected = selectedLevel == index,
                    onClick = { selectedLevel = index },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 操作按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val logText = formatLogsForCopy(filteredLogs)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("logs", logText))
                    Toast.makeText(context, context.getString(R.string.diagnostic_copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                enabled = filteredLogs.isNotEmpty()
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostic_copy_log))
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f),
                enabled = logs.isNotEmpty()
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostic_clear_log), color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(12.dp))

        // 日志列表
        if (filteredLogs.isEmpty()) {
            Text(
                text = stringResource(R.string.diagnostic_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filteredLogs.forEach { record ->
                    LogItem(record)
                }
            }
        }
    }
}

/** Gateway JSON 数据卡片：显示返回的 JSON（美化），可选状态指示灯 */
@Composable
private fun GatewayJsonCard(
    title: String,
    json: JsonElement?,
    healthStatus: HealthStatus? = null,
    onRefresh: () -> Unit = {}
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // 健康状态指示灯
            if (healthStatus != null) {
                val dotColor = when (healthStatus) {
                    HealthStatus.OK -> Color(0xFF4CAF50)
                    HealthStatus.ERROR -> MaterialTheme.colorScheme.error
                    HealthStatus.UNKNOWN -> Color(0xFF9E9E9E)
                }
                val statusLabel = when (healthStatus) {
                    HealthStatus.OK -> stringResource(R.string.diagnostic_status_ok)
                    HealthStatus.ERROR -> stringResource(R.string.diagnostic_status_error)
                    HealthStatus.UNKNOWN -> stringResource(R.string.diagnostic_status_unknown)
                }
                StatusDot(color = dotColor)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = dotColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(8.dp))
            // 刷新按钮
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.diagnostic_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        JsonText(json)
    }
}

/** 事件日志卡片：从 JSON 数组解析事件列表并展示 */
@Composable
private fun EventLogCard(
    json: JsonElement?,
    onRefresh: () -> Unit
) {
    val eventList = remember(json) { parseEvents(json) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.diagnostic_event_log),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.diagnostic_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (eventList.isEmpty()) {
            Text(
                text = stringResource(R.string.diagnostic_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                eventList.forEach { event ->
                    EventItem(event)
                }
            }
        }
    }
}

/** Trace 查询卡片：输入 Trace ID 查询链路日志 */
@Composable
private fun TraceLookupCard(
    traceInput: String,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    traceResult: JsonElement?,
    traceError: String?
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.diagnostic_trace_lookup))
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = traceInput,
                onValueChange = onInputChange,
                label = { Text(stringResource(R.string.diagnostic_trace_id_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onSearch,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.diagnostic_trace_search),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // 错误信息
        if (traceError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = traceError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 查询结果
        if (traceResult != null) {
            Spacer(Modifier.height(8.dp))
            JsonText(traceResult)
        }
    }
}

// ==================== 通用小组件 ====================

/** 状态指示灯：圆形彩色点 */
@Composable
private fun StatusDot(color: Color, size: Int = 10) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** JSON 文本展示：美化输出 + 等宽字体 */
@Composable
private fun JsonText(json: JsonElement?, modifier: Modifier = Modifier) {
    val text = remember(json) {
        json?.let { prettyGson.toJson(it) } ?: ""
    }
    if (text.isBlank()) {
        Text(
            text = stringResource(R.string.diagnostic_no_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )
    }
}

/** 单条本地日志：时间 + 级别标签 + tag + 消息 */
@Composable
private fun LogItem(record: LocalLogger.Record) {
    val levelColor = when (record.level) {
        LocalLogger.LEVEL_ERROR -> MaterialTheme.colorScheme.error
        LocalLogger.LEVEL_WARNING -> Color(0xFFFFB347)
        LocalLogger.LEVEL_INFO -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF6BAED6) // debug
    }
    val levelLabel = when (record.level) {
        LocalLogger.LEVEL_ERROR -> "ERROR"
        LocalLogger.LEVEL_WARNING -> "WARN"
        LocalLogger.LEVEL_INFO -> "INFO"
        else -> "DEBUG"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 级别标签
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(levelColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = levelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                    fontWeight = FontWeight.Bold
                )
            }
            // 时间
            Text(
                text = "${record.date} ${record.time}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // tag
        if (record.tag.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "[${record.tag}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
        // 消息
        Spacer(Modifier.height(2.dp))
        Text(
            text = record.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 单条事件日志：时间 + 类型 + 状态 + trace_id */
@Composable
private fun EventItem(event: GatewayEvent) {
    val statusColor = when (event.status.lowercase()) {
        "success", "completed", "done", "ok" -> Color(0xFF4CAF50)
        "error", "failed", "fail" -> MaterialTheme.colorScheme.error
        "pending", "processing", "running" -> Color(0xFFFFB347)
        else -> Color(0xFF9E9E9E)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 事件类型
            Text(
                text = event.type.ifBlank { "—" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            // 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = event.status.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        // 时间
        if (event.time.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = event.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // trace_id
        if (event.traceId.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "trace: ${event.traceId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ==================== 工具函数 ====================

/** 从 Gateway 健康 JSON 中提取状态判定 */
private fun extractHealthStatus(json: JsonElement?): HealthStatus {
    if (json == null || !json.isJsonObject) return HealthStatus.UNKNOWN
    val obj = json.asJsonObject
    val status = obj.get("status")?.takeUnless { it.isJsonNull }?.asString?.lowercase()
    return when (status) {
        "ok", "healthy", "up", "running", "operational" -> HealthStatus.OK
        "error", "down", "unhealthy", "fail", "degraded" -> HealthStatus.ERROR
        else -> HealthStatus.UNKNOWN
    }
}

/** 从 Gateway 事件 JSON 数组解析事件列表 */
private fun parseEvents(json: JsonElement?): List<GatewayEvent> {
    if (json == null || !json.isJsonArray) return emptyList()
    return json.asJsonArray.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        GatewayEvent(
            time = obj.get("created_at")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("time")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("timestamp")?.takeUnless { it.isJsonNull }?.asString ?: "",
            type = obj.get("event_type")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("type")?.takeUnless { it.isJsonNull }?.asString ?: "",
            status = obj.get("status")?.takeUnless { it.isJsonNull }?.asString ?: "",
            traceId = obj.get("trace_id")?.takeUnless { it.isJsonNull }?.asString ?: ""
        )
    }
}

/** 将日志列表格式化为可复制的纯文本 */
private fun formatLogsForCopy(logs: List<LocalLogger.Record>): String {
    return logs.joinToString("\n") { record ->
        val levelLabel = when (record.level) {
            LocalLogger.LEVEL_ERROR -> "ERROR"
            LocalLogger.LEVEL_WARNING -> "WARN"
            LocalLogger.LEVEL_INFO -> "INFO"
            else -> "DEBUG"
        }
        val tag = if (record.tag.isNotBlank()) "[${record.tag}] " else ""
        "[${record.date} ${record.time}] [$levelLabel] $tag${record.message}"
    }
}
