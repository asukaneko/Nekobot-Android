package com.nekobot.app.ui.screens.tokens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonElement
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.data.repository.Resource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Token 用量页 ViewModel
 */
@Immutable
internal data class TokenRecordUi(
    val id: String,
    val timestamp: String,
    val date: String,
    val model: String,
    val purpose: String,
    val source: String,
    val sessionId: String,
    val input: Long,
    val output: Long,
    val total: Long,
    val cost: String?,
    val durationMs: Double?,
    val ttftMs: Double?
)

class TokensViewModel : BaseViewModel() {

    private val _stats = MutableStateFlow<TokenStats?>(null)
    val stats: StateFlow<TokenStats?> = _stats.asStateFlow()

    private val _rankings = MutableStateFlow<TokenRankings?>(null)
    val rankings: StateFlow<TokenRankings?> = _rankings.asStateFlow()

    private val _records = MutableStateFlow<List<TokenRecordUi>>(emptyList())
    internal val records: StateFlow<List<TokenRecordUi>> = _records.asStateFlow()

    private val _dateRange = MutableStateFlow("today")
    val dateRange: StateFlow<String> = _dateRange.asStateFlow()

    private val _startDate = MutableStateFlow<String?>(null)
    val startDate: StateFlow<String?> = _startDate.asStateFlow()

    private val _endDate = MutableStateFlow<String?>(null)
    val endDate: StateFlow<String?> = _endDate.asStateFlow()

    fun load() {
        val range = _dateRange.value
        val isCustom = range == "custom"
        val start = if (isCustom) _startDate.value else null
        val end = if (isCustom) _endDate.value else null
        val effectiveRange = if (isCustom) null else range
        viewModelScope.launch {
            setLoading(true)
            clearError()
            try {
                val (statsResult, rankingsResult) = coroutineScope {
                    val statsRequest = async { unified.tokenStats(effectiveRange, start, end) }
                    val rankingsRequest = async { unified.tokenRankings() }
                    statsRequest.await() to rankingsRequest.await()
                }
                when (statsResult) {
                    is Resource.Success -> {
                        val value = statsResult.data
                        val parsedRecords = withContext(Dispatchers.Default) {
                            (value.records ?: value.recentRecords ?: emptyList())
                                .mapNotNull(::parseTokenRecord)
                                .sortedByDescending { it.timestamp }
                        }
                        _stats.value = value
                        _records.value = parsedRecords
                    }
                    is Resource.Error -> showError(statsResult.message)
                    is Resource.Loading -> Unit
                }
                when (rankingsResult) {
                    is Resource.Success -> _rankings.value = rankingsResult.data
                    is Resource.Error -> if (_stats.value == null) showError(rankingsResult.message)
                    is Resource.Loading -> Unit
                }
            } catch (e: Exception) {
                showError(e.message ?: "加载 Token 用量失败")
            } finally {
                setLoading(false)
            }
        }
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
    val records by vm.records.collectAsState()
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
    var recordPage by remember(records.size, dateRange) { mutableStateOf(0) }

    val rankingData = remember(rankings, stats, rankingTab) {
        val fromRankings: List<JsonElement>? = when (rankingTab) {
            0 -> rankings?.sessions
            1 -> rankings?.models
            else -> rankings?.purposes ?: rankings?.users
        }
        if (!fromRankings.isNullOrEmpty()) {
            fromRankings
        } else {
            val fallback = when (rankingTab) {
                0 -> stats?.sessions
                1 -> stats?.models
                else -> stats?.purposes ?: stats?.users
            }
            fallback?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
        }
    }
    val parsedRanking = remember(rankingData) {
        rankingData.orEmpty().map(::extractRankEntry)
            .filter { it.first.isNotBlank() }
            .sortedByDescending { it.second }
            .take(10)
    }
    val pageSize = 30
    val totalPages = ((records.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val pageRecords = records.drop(recordPage * pageSize).take(pageSize)
    val groupedRecords = remember(pageRecords) { pageRecords.groupBy { it.date } }

    // 模式切换时自动刷新 Token 用量
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    LaunchedEffect(appMode) { vm.load() }

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 日期范围选择器
                item(key = "date_filters", contentType = "controls") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(selected = dateRange == "today", onClick = { vm.setDateRange("today") }, label = { Text("今日") })
                        FilterChip(selected = dateRange == "month", onClick = { vm.setDateRange("month") }, label = { Text("本月") })
                        FilterChip(selected = dateRange == "total", onClick = { vm.setDateRange("total") }, label = { Text("全部") })
                        FilterChip(
                            selected = dateRange == "custom",
                            onClick = {
                                if (startDate == null) showStartPicker = true
                                else vm.setDateRange("custom")
                            },
                            label = { Text("自定义") }
                        )
                    }
                }

                // 自定义日期显示
                if (dateRange == "custom") {
                    item(key = "custom_dates", contentType = "controls") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }

                // 错误提示
                error?.let {
                    item(key = "error", contentType = "status") {
                        ErrorBanner(message = it, onRetry = {
                            vm.clearError()
                            vm.load()
                        })
                    }
                }

                // 统计网格
                stats?.let { s ->
                    item(key = "stats", contentType = "summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatChipGrid(stats = s, dateRange = dateRange, startDate = startDate, endDate = endDate)
                            // 拆分卡片标题随选择日期变化
                            val breakdownTitle = when (dateRange) {
                                "today" -> "今日 Token 拆分"
                                "month" -> "本月 Token 拆分"
                                "total" -> "累计 Token 拆分"
                                "custom" -> {
                                    val seg = listOfNotNull(startDate, endDate).joinToString(" ~ ")
                                    if (seg.isBlank()) "自定义范围 Token 拆分" else "自定义范围 Token 拆分（$seg）"
                                }
                                else -> "Token 拆分"
                            }
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(title = breakdownTitle)
                                Spacer(Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("输入", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${s.todayInput ?: 0L}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("输出", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("${s.todayOutput ?: 0L}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "rankings", contentType = "rankings") {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(title = "Token 排行榜")
                        Spacer(Modifier.height(8.dp))
                        RankingSegmentedBar(
                            tabs = listOf("会话", "模型", "用途"),
                            selectedIndex = rankingTab,
                            onSelect = { rankingTab = it }
                        )
                        Spacer(Modifier.height(12.dp))
                        if (parsedRanking.isEmpty()) {
                            Text("暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val maxTokens = parsedRanking.maxOf { it.second }.coerceAtLeast(1L)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                parsedRanking.forEachIndexed { idx, (name, tokens) ->
                                    RankingBarRow(rank = idx + 1, name = name, tokens = tokens, maxTokens = maxTokens)
                                }
                            }
                        }
                    }
                }

                if (records.isNotEmpty()) {
                    item(key = "records_header", contentType = "section_header") {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = "Token 记录", subtitle = "共 ${records.size} 条，第 ${recordPage + 1}/$totalPages 页 · 每页 $pageSize 条")
                        }
                    }
                    groupedRecords.forEach { (date, dateRecords) ->
                        item(key = "date_$date", contentType = "date_header") {
                            TokenRecordDateHeader(date)
                        }
                        dateRecords.forEach { record ->
                            item(key = record.id, contentType = "token_record") {
                                TokenRecordCard(record = record)
                            }
                        }
                    }
                    if (totalPages > 1) {
                        item(key = "records_pagination", contentType = "controls") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { if (recordPage > 0) recordPage-- }, enabled = recordPage > 0) { Text("上一页") }
                                Text("${recordPage + 1} / $totalPages", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { if (recordPage < totalPages - 1) recordPage++ }, enabled = recordPage < totalPages - 1) { Text("下一页") }
                            }
                        }
                    }
                }
                item(key = "bottom_space", contentType = "spacer") { Spacer(Modifier.height(8.dp)) }
            }

            // 仅首次（尚无数据）加载时盖全屏遮罩；已有数据的刷新不再黑屏，避免切回本页时生硬闪烁。
            LoadingOverlay(visible = loading && stats == null)
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
 * 顶部第一张卡片随选择的日期范围变化（今日/本月/累计/自定义范围），
 * 后端 today_input/today_output 字段实际代表当前查询范围内的输入/输出，
 * 因此今日 Token 数也随选择日期变化。整体共 6 张卡片。
 */
@Composable
private fun StatChipGrid(
    stats: TokenStats,
    dateRange: String,
    startDate: String?,
    endDate: String?
) {
    // 根据选择的日期范围决定展示哪个时间维度的 token 数
    val (rangeLabel, rangeValue) = when (dateRange) {
        "today" -> "今日 Token" to "${stats.today ?: stats.todayTotal}"
        "month" -> "本月 Token" to "${stats.month ?: 0L}"
        "total" -> "累计 Token" to "${stats.totalDisplay}"
        "custom" -> {
            "自定义范围 Token" to "${(stats.todayInput ?: 0L) + (stats.todayOutput ?: 0L)}"
        }
        else -> "Token" to "${stats.totalDisplay}"
    }
    val items = listOf(
        rangeLabel to rangeValue,
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
 * 排行榜分段切换条：圆角药丸式 segmented control，替代默认 TabRow。
 * 选中态以主色填充胶囊 + 白色文字；未选中态透明背景 + 次级文字色。
 */
@Composable
private fun RankingSegmentedBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, title ->
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
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
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    }
    val rankColor = when (rank) {
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTokenCount(tokens),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
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
 * 单条 Token 记录卡片：将身份信息、Token 指标和性能数据分层展示。
 */
@Composable
private fun TokenRecordCard(record: TokenRecordUi) {
    val compactTs = compactTimestamp(record.timestamp)?.substringAfter(' ')
    val purposeLabel = purposeLabel(record.purpose)
    val badgeColor = purposeColor(record.purpose)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.model.ifBlank { "未知模型" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (record.source.isNotBlank() || record.sessionId.isNotBlank()) {
                    Text(
                        listOfNotNull(
                            record.source.takeIf { it.isNotBlank() }?.let { "来源 ${sourceLabel(it)}" },
                            record.sessionId.takeIf { it.isNotBlank() }?.let { "会话 ${it.take(8)}" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.18f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(purposeLabel, style = MaterialTheme.typography.labelSmall, color = badgeColor, fontWeight = FontWeight.Medium)
                }
                if (!compactTs.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(compactTs, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TokenMetric(label = "输入", value = record.input, modifier = Modifier.weight(1f))
            TokenMetric(label = "输出", value = record.output, modifier = Modifier.weight(1f))
            TokenMetric(label = "合计", value = record.total, emphasized = true, modifier = Modifier.weight(1f))
        }
        if (!record.cost.isNullOrBlank() || record.durationMs != null || record.ttftMs != null) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!record.cost.isNullOrBlank()) {
                    Text("费用 ¥${record.cost}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                record.durationMs?.let {
                    Text("耗时 ${formatDuration(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                record.ttftMs?.let {
                    Text("首字 ${formatDuration(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TokenRecordDateHeader(date: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(date, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TokenMetric(
    label: String,
    value: Long,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            formatTokenCount(value),
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun parseTokenRecord(elem: JsonElement): TokenRecordUi? {
    if (!elem.isJsonObject) return null
    val obj = elem.asJsonObject
    fun string(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        obj.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.runCatching { asString }?.getOrNull()
    }.orEmpty()
    fun long(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        obj.get(key)?.takeIf { !it.isJsonNull }?.runCatching { asLong }?.getOrNull()
    }
    fun double(key: String): Double? = obj.get(key)?.takeIf { !it.isJsonNull }?.runCatching { asDouble }?.getOrNull()

    val timestamp = string("timestamp", "created_at", "time")
    val date = string("date").ifBlank {
        when {
            timestamp.contains('T') -> timestamp.substringBefore('T')
            timestamp.contains(' ') -> timestamp.substringBefore(' ')
            else -> "日期未知"
        }
    }
    val input = long("input", "input_tokens") ?: 0L
    val output = long("output", "output_tokens") ?: 0L
    return TokenRecordUi(
        id = string("id").ifBlank { "$timestamp:${string("model")}:$input:$output" },
        timestamp = timestamp,
        date = date,
        model = string("model", "model_name"),
        purpose = string("purpose"),
        source = string("source", "channel_type"),
        sessionId = string("session_id"),
        input = input,
        output = output,
        total = long("total", "total_tokens", "tokens") ?: (input + output),
        cost = string("cost").takeIf { it.isNotBlank() },
        durationMs = double("duration_ms"),
        ttftMs = double("ttft_ms")
    )
}

private fun purposeLabel(purpose: String): String = when (purpose.lowercase()) {
    "chat" -> "对话"
    "vision" -> "图片理解"
    "video" -> "视频理解"
    "tts" -> "语音合成"
    "stt" -> "语音识别"
    "embedding" -> "向量嵌入"
    "memory" -> "记忆"
    "plot" -> "剧情"
    "utility" -> "工具调用"
    "decision" -> "决策"
    "heartbeat" -> "心跳"
    "react" -> "ReAct"
    "image_gen" -> "图片生成"
    else -> purpose.ifBlank { "未分类" }
}

/**
 * purpose 对应的徽章颜色：按用途分组，对话类→primary，记忆/理解类→secondary，
 * 生成类→tertiary，系统/工具类→error。
 */
@Composable
private fun purposeColor(purpose: String): Color = when (purpose.lowercase()) {
    "chat", "react" -> MaterialTheme.colorScheme.primary
    "memory", "vision", "video", "embedding" -> MaterialTheme.colorScheme.secondary
    "plot", "decision", "tts", "stt" -> MaterialTheme.colorScheme.tertiary
    "utility", "heartbeat", "image_gen" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

/** source 字段中文映射 */
private fun sourceLabel(source: String): String = when (source.lowercase()) {
    "plot" -> "剧情"
    "state" -> "状态评估"
    "memory" -> "记忆抽取"
    "web" -> "联网搜索"
    "vision" -> "视觉识别"
    "stt" -> "语音识别"
    "rule" -> "规则审查"
    "heartbeat" -> "心跳"
    else -> source
}

private fun formatDuration(milliseconds: Double): String = when {
    milliseconds >= 1000 -> String.format(Locale.US, "%.1fs", milliseconds / 1000.0)
    else -> "${milliseconds.toLong()}ms"
}

/** 精简时间戳到 MM-dd HH:mm */
private fun compactTimestamp(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        val s = raw.trim()
        when {
            s.matches(Regex("^\\d{10,13}$")) -> {
                val ms = if (s.length == 10) s.toLong() * 1000 else s.toLong()
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
            }
            s.contains('T') -> {
                val datePart = s.substringBefore('T').takeLast(5)
                val timePart = s.substringAfter('T').take(5)
                "$datePart $timePart"
            }
            s.contains(' ') -> {
                val datePart = s.substringBefore(' ').takeLast(5)
                val timePart = s.substringAfter(' ').take(5)
                "$datePart $timePart"
            }
            else -> s.take(11)
        }
    } catch (e: Exception) {
        null
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
                ?: obj.get("purpose")?.asString
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
