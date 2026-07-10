package com.nekobot.app.ui.screens.tokens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.nekobot.app.ui.theme.Tertiary
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

                // 排行榜：优先用 rankings 接口数据，若为空则回退到 stats 内嵌的 sessions/models/users
                val rankingData = remember(rankings, stats, rankingTab) {
                    val r = rankings
                    val fromRankings: List<JsonElement>? = when (rankingTab) {
                        0 -> r?.sessions
                        1 -> r?.models
                        else -> r?.users
                    }
                    if (!fromRankings.isNullOrEmpty()) {
                        fromRankings
                    } else {
                        // 回退：从 tokenStats 中提取 sessions/models/users（JsonElement，可能是数组）
                        val fallback = when (rankingTab) {
                            0 -> stats?.sessions
                            1 -> stats?.models
                            else -> stats?.users
                        }
                        fallback?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
                    }
                }
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
                    Spacer(Modifier.height(12.dp))
                    if (rankingData.isNullOrEmpty()) {
                        Text("暂无数据", color = OnSurfaceVariant)
                    } else {
                        // 解析为 (name, tokens) 列表并按 token 数降序
                        val parsed = rankingData.map { extractRankEntry(it) }
                            .filter { it.first.isNotBlank() }
                            .sortedByDescending { it.second }
                            .take(10)
                        if (parsed.isEmpty()) {
                            Text("暂无数据", color = OnSurfaceVariant)
                        } else {
                            val maxTokens = parsed.maxOf { it.second }.coerceAtLeast(1L)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                parsed.forEachIndexed { idx, (name, tokens) ->
                                    RankingBarRow(
                                        rank = idx + 1,
                                        name = name,
                                        tokens = tokens,
                                        maxTokens = maxTokens
                                    )
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
 * 排行榜柱状图单行：名次 + 名称 + 横向柱条 + token 数。
 */
@Composable
private fun RankingBarRow(
    rank: Int,
    name: String,
    tokens: Long,
    maxTokens: Long
) {
    val ratio = (tokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
    // 前 3 名用主色渐变，其余用次色
    val barColor = when (rank) {
        1 -> Primary
        2 -> Secondary
        3 -> Tertiary
        else -> Primary.copy(alpha = 0.5f)
    }
    val rankColor = when (rank) {
        1 -> Primary
        2 -> Secondary
        3 -> Tertiary
        else -> OnSurfaceVariant
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.labelMedium,
                color = rankColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(20.dp)
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTokenCount(tokens),
                style = MaterialTheme.typography.bodyMedium,
                color = Primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(OnSurfaceVariant.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}

/** 格式化 token 数：超过 1k 用 k 单位，超过 1M 用 M 单位。 */
private fun formatTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000L -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
        else -> tokens.toString()
    }
}

/**
 * 从 JsonElement 中提取 (name, tokens)，兼容多种字段命名。
 * 失败返回 ("未知", 0L)。
 */
private fun extractRankEntry(elem: JsonElement): Pair<String, Long> {
    return try {
        if (elem.isJsonObject) {
            val obj = elem.asJsonObject
            val name = obj.get("name")?.asString
                ?: obj.get("session_name")?.asString
                ?: obj.get("title")?.asString
                ?: obj.get("model")?.asString
                ?: obj.get("model_name")?.asString
                ?: obj.get("user")?.asString
                ?: obj.get("username")?.asString
                ?: obj.get("user_name")?.asString
                ?: obj.get("sessionName")?.asString
                ?: obj.get("character_name")?.asString
                ?: obj.get("id")?.asString
                ?: obj.get("_id")?.asString
                ?: obj.get("key")?.asString
                ?: "未知"
            val tokens = obj.get("tokens")?.asLong
                ?: obj.get("token_count")?.asLong
                ?: obj.get("total_tokens")?.asLong
                ?: obj.get("total")?.asLong
                ?: obj.get("count")?.asLong
                ?: obj.get("usage")?.asLong
                ?: obj.get("value")?.asLong
                ?: obj.get("tokens_total")?.asLong
                ?: obj.get("sum_tokens")?.asLong
                ?: 0L
            Pair(name, tokens)
        } else {
            Pair("未知", 0L)
        }
    } catch (e: Exception) {
        Pair("未知", 0L)
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
