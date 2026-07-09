package com.nekobot.app.ui.screens.tokens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.components.StatChip
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.Secondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Token 用量页 ViewModel
 */
class TokensViewModel : BaseViewModel() {

    private val _stats = MutableStateFlow<TokenStats?>(null)
    val stats: StateFlow<TokenStats?> = _stats.asStateFlow()

    private val _rankings = MutableStateFlow<TokenRankings?>(null)
    val rankings: StateFlow<TokenRankings?> = _rankings.asStateFlow()

    private val _dateRange = MutableStateFlow("today")
    val dateRange: StateFlow<String> = _dateRange.asStateFlow()

    private val _startDate = MutableStateFlow<String?>(null)
    val startDate: StateFlow<String?> = _startDate.asStateFlow()

    private val _endDate = MutableStateFlow<String?>(null)
    val endDate: StateFlow<String?> = _endDate.asStateFlow()

    init {
        load()
    }

    fun load() {
        val range = _dateRange.value
        val isCustom = range == "custom"
        val start = if (isCustom) _startDate.value else null
        val end = if (isCustom) _endDate.value else null
        val effectiveRange = if (isCustom) null else range
        launchResult(
            block = { repo.tokenStats(effectiveRange, start, end) },
            onSuccess = { _stats.value = it }
        )
        launchResult(
            block = { repo.tokenRankings() },
            onSuccess = { _rankings.value = it }
        )
    }

    fun setDateRange(range: String) {
        _dateRange.value = range
        if (range != "custom") load()
    }

    fun setCustomRange(start: String, end: String) {
        _dateRange.value = "custom"
        _startDate.value = start
        _endDate.value = end
        load()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokensScreen() {
    val vm: TokensViewModel = viewModel()
    val stats by vm.stats.collectAsState()
    val rankings by vm.rankings.collectAsState()
    val dateRange by vm.dateRange.collectAsState()
    val startDate by vm.startDate.collectAsState()
    val endDate by vm.endDate.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var rankingTab by remember { mutableStateOf(0) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Token 用量") },
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
                // 日期范围选择器
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = dateRange == "today",
                        onClick = { vm.setDateRange("today") },
                        label = { Text("今日") }
                    )
                    FilterChip(
                        selected = dateRange == "month",
                        onClick = { vm.setDateRange("month") },
                        label = { Text("本月") }
                    )
                    FilterChip(
                        selected = dateRange == "total",
                        onClick = { vm.setDateRange("total") },
                        label = { Text("全部") }
                    )
                    FilterChip(
                        selected = dateRange == "custom",
                        onClick = {
                            if (startDate == null) showStartPicker = true
                            else vm.setDateRange("custom")
                        },
                        label = { Text("自定义") }
                    )
                }

                // 自定义日期显示
                if (dateRange == "custom") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = { showStartPicker = true },
                            label = { Text("起: ${startDate ?: "选择"}") },
                            leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) }
                        )
                        AssistChip(
                            onClick = { showEndPicker = true },
                            label = { Text("止: ${endDate ?: "选择"}") },
                            leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) }
                        )
                    }
                }

                // 错误提示
                error?.let {
                    ErrorBanner(message = it, onRetry = {
                        vm.clearError()
                        vm.load()
                    })
                }

                // 统计网格
                stats?.let { s ->
                    StatChipGrid(stats = s)

                    // 今日输入/输出拆分
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "今日 Token 拆分")
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("输入", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                                Text(
                                    "${s.todayInput ?: 0L}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("输出", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                                Text(
                                    "${s.todayOutput ?: 0L}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Secondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 排行榜
                rankings?.let { r ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "Token 排行榜")
                        Spacer(Modifier.height(8.dp))
                        TabRow(selectedTabIndex = rankingTab) {
                            listOf("会话", "模型", "用户").forEachIndexed { index, title ->
                                Tab(
                                    selected = rankingTab == index,
                                    onClick = { rankingTab = index },
                                    text = { Text(title) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val list = when (rankingTab) {
                            0 -> r.sessions
                            1 -> r.models
                            else -> r.users
                        }
                        if (list.isNullOrEmpty()) {
                            Text("暂无数据", color = OnSurfaceVariant)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                list.forEach { elem ->
                                    RankingRow(elem)
                                }
                            }
                        }
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 起始日期选择器
    if (showStartPicker) {
        NekoDatePickerDialog(
            onConfirm = { millis ->
                val dateStr = formatDate(millis)
                val currentEnd = endDate ?: dateStr
                vm.setCustomRange(dateStr, currentEnd)
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false }
        )
    }

    // 结束日期选择器
    if (showEndPicker) {
        NekoDatePickerDialog(
            onConfirm = { millis ->
                val dateStr = formatDate(millis)
                val currentStart = startDate ?: dateStr
                vm.setCustomRange(currentStart, dateStr)
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false }
        )
    }
}

/**
 * 统计芯片网格：两列布局
 */
@Composable
private fun StatChipGrid(stats: TokenStats) {
    val items = listOf(
        "今日 Token" to "${stats.today ?: stats.todayTotal}",
        "本月 Token" to "${stats.month ?: 0L}",
        "累计 Token" to "${stats.totalDisplay}",
        "消息数" to "${stats.messageCount ?: 0L}",
        "平均/条" to String.format(Locale.US, "%.0f", stats.avgTokensPerMsg ?: 0.0),
        "估算费用" to (stats.estimatedCost ?: "—"),
        "活跃会话" to "${stats.activeSessions ?: 0}",
        "平均响应" to (stats.avgResponseTime ?: "—")
    )
    items.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { (label, value) ->
                StatChip(
                    label = label,
                    value = value,
                    modifier = Modifier.weight(1f)
                )
            }
            if (row.size == 1) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 排行榜单行
 */
@Composable
private fun RankingRow(elem: JsonElement) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val name = extractName(elem)
        val tokens = extractTokens(elem)
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = tokens,
            style = MaterialTheme.typography.bodyMedium,
            color = Primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 从 JsonElement 中提取名称
 */
private fun extractName(elem: JsonElement): String {
    return try {
        val obj = elem.asJsonObject
        obj.get("name")?.asString
            ?: obj.get("session_name")?.asString
            ?: obj.get("title")?.asString
            ?: obj.get("model")?.asString
            ?: obj.get("model_name")?.asString
            ?: obj.get("user")?.asString
            ?: obj.get("username")?.asString
            ?: obj.get("id")?.asString
            ?: obj.get("_id")?.asString
            ?: "未知"
    } catch (e: Exception) {
        "未知"
    }
}

/**
 * 从 JsonElement 中提取 token 数
 */
private fun extractTokens(elem: JsonElement): String {
    return try {
        val obj = elem.asJsonObject
        val tokens = obj.get("tokens")?.asLong
            ?: obj.get("token_count")?.asLong
            ?: obj.get("total_tokens")?.asLong
            ?: obj.get("count")?.asLong
            ?: 0L
        tokens.toString()
    } catch (e: Exception) {
        "0"
    }
}

/**
 * 日期选择器弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NekoDatePickerDialog(
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.selectedDateMillis) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    ) {
        DatePicker(state = state)
    }
}

/**
 * 将毫秒时间戳格式化为 yyyy-MM-dd
 */
private fun formatDate(millis: Long?): String {
    if (millis == null) return ""
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(millis))
}
