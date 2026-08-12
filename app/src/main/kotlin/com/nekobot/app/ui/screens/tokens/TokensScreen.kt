package com.nekobot.app.ui.screens.tokens

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonElement
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.ModelPricingCatalog
import com.nekobot.app.data.model.TokenRankings
import com.nekobot.app.data.model.TokenStats
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.GlassDropdownMenu
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Token 用量页 ViewModel
 */
@Immutable
internal data class TokenRecordUi(
    val id: String,
    val timestamp: String,
    val date: String,
    val model: String,
    val actualModel: String,
    val purpose: String,
    val source: String,
    val sessionId: String,
    val sessionName: String,
    val input: Long,
    val output: Long,
    val total: Long,
    val cost: String?,
    val estimatedCostUsd: Double?,
    val durationMs: Double?,
    val ttftMs: Double?,
    val estimated: Boolean
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

    private val _loadedQueryKey = MutableStateFlow<String?>(null)
    internal val loadedQueryKey: StateFlow<String?> = _loadedQueryKey.asStateFlow()

    private val _loadedRankingKey = MutableStateFlow<String?>(null)
    internal val loadedRankingKey: StateFlow<String?> = _loadedRankingKey.asStateFlow()

    private var loadJob: Job? = null
    private var loadRequestId = 0L
    private val sessionNameCache = ConcurrentHashMap<String, String>()
    private val pendingSessionNames = ConcurrentHashMap<String, Deferred<String?>>()

    /**
     * 仅保留最后一次筛选请求，避免用户快速切换日期时旧响应覆盖新范围。
     * 排行榜接口是全量口径，不随日期范围变化，因此只在首次进入或主动刷新时请求。
     */
    fun load(refreshRankings: Boolean = false) {
        val range = _dateRange.value
        val isCustom = range == "custom"
        val start = if (isCustom) _startDate.value else null
        val end = if (isCustom) _endDate.value else null
        val mode = ServiceContainer.prefs.appMode.toString()
        val dataSourceRevision = ServiceContainer.dataSourceRevision.value
        val queryKey = tokenUsageQueryKey(
            mode = mode,
            dataSourceRevision = dataSourceRevision,
            range = range,
            startDate = start,
            endDate = end
        )
        val rankingKey = tokenUsageRankingKey(mode, dataSourceRevision)
        val shouldLoadRankings = refreshRankings ||
            _rankings.value == null ||
            _loadedRankingKey.value != rankingKey
        val requestId = ++loadRequestId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            setLoading(true)
            clearError()
            try {
                val (statsResult, rankingsResult) = coroutineScope {
                    val statsRequest = async { unified.tokenStats(range, start, end) }
                    val rankingsRequest = if (shouldLoadRankings) async { unified.tokenRankings() } else null
                    statsRequest.await() to rankingsRequest?.await()
                }
                if (requestId != loadRequestId) return@launch
                when (statsResult) {
                    is Resource.Success -> {
                        val value = statsResult.data
                        val parsedRecords = withContext(Dispatchers.Default) {
                            (value.records ?: value.recentRecords ?: emptyList())
                                .mapIndexedNotNull(::parseTokenRecord)
                                // 统一把空格分隔符替换为 'T'，避免 ISO(带T) 与 "yyyy-MM-dd HH:mm:ss"(带空格)
                                // 混合时字符串排序错乱，导致同日记录时间不降序
                                .sortedByDescending { it.timestamp.replace(' ', 'T') }
                        }
                        if (requestId != loadRequestId) return@launch
                        _stats.value = value
                        _records.value = parsedRecords
                        _loadedQueryKey.value = queryKey
                    }
                    is Resource.Error -> showError(statsResult.message)
                    is Resource.Loading -> Unit
                }
                when (rankingsResult) {
                    is Resource.Success -> {
                        _rankings.value = rankingsResult.data
                        _loadedRankingKey.value = rankingKey
                    }
                    is Resource.Error -> showError(rankingsResult.message)
                    is Resource.Loading -> Unit
                    null -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId == loadRequestId) {
                    showError(e.message ?: string(R.string.tokens_load_failed))
                }
            } finally {
                if (requestId == loadRequestId) setLoading(false)
            }
        }
    }

    fun setDateRange(range: String) {
        if (_dateRange.value == range) return
        _dateRange.value = range
        if (range != "custom" || (_startDate.value != null && _endDate.value != null)) load()
    }

    fun setCustomRange(start: String, end: String) {
        val (normalizedStart, normalizedEnd) = if (start <= end) start to end else end to start
        _dateRange.value = "custom"
        _startDate.value = normalizedStart
        _endDate.value = normalizedEnd
        load()
    }

    /** 同一模式、同一会话仅解析一次名称，避免明细滚动时重复触发网络或数据库查询。 */
    internal suspend fun resolveSessionName(sessionId: String): String {
        if (sessionId.isBlank()) return ""
        val cacheKey = "${ServiceContainer.prefs.appMode}:${ServiceContainer.dataSourceRevision.value}:$sessionId"
        sessionNameCache[cacheKey]?.let { return it }
        val candidate = viewModelScope.async(start = CoroutineStart.LAZY) {
            runCatching {
                when (val result = unified.getSession(sessionId)) {
                    is Resource.Success -> result.data?.displayName?.takeIf { it.isNotBlank() }
                    else -> null
                }
            }.getOrNull()?.also { sessionNameCache.putIfAbsent(cacheKey, it) }
        }
        val request = pendingSessionNames.putIfAbsent(cacheKey, candidate) ?: candidate
        if (request === candidate) {
            candidate.invokeOnCompletion { pendingSessionNames.remove(cacheKey, candidate) }
            candidate.start()
        } else {
            candidate.cancel()
        }
        return request.await() ?: sessionId.take(8)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokensScreen(onNavigate: (String) -> Unit = {}) {
    val vm: TokensViewModel = viewModel()
    val stats by vm.stats.collectAsState()
    val rankings by vm.rankings.collectAsState()
    val records by vm.records.collectAsState()
    val dateRange by vm.dateRange.collectAsState()
    val startDate by vm.startDate.collectAsState()
    val endDate by vm.endDate.collectAsState()
    val loadedQueryKey by vm.loadedQueryKey.collectAsState()
    val loadedRankingKey by vm.loadedRankingKey.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    var showStartPicker by rememberSaveable { mutableStateOf(false) }
    var showEndPicker by rememberSaveable { mutableStateOf(false) }
    var pendingCustomStart by rememberSaveable { mutableStateOf<String?>(null) }
    var pickEndAfterStart by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    var rankingTab by rememberSaveable { mutableIntStateOf(0) }
    var expandedRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleRecordCount by rememberSaveable(records.size, dateRange) {
        mutableIntStateOf(RECORD_BATCH_SIZE)
    }

    val rankingData = remember(rankings, rankingTab) {
        when (rankingTab) {
            0 -> rankings?.sessions
            1 -> rankings?.models
            else -> rankings?.purposes ?: rankings?.users
        }
    }
    val parsedRanking = remember(rankingData) {
        rankingData.orEmpty().map(::extractRankEntry)
            .filter { it.first.isNotBlank() }
            .sortedByDescending { it.second }
            .take(10)
    }
    val visibleRecords = remember(records, visibleRecordCount) {
        records.take(visibleRecordCount)
    }
    val groupedRecords = remember(visibleRecords) {
        visibleRecords.groupBy { it.date }.toSortedMap(reverseOrder())
    }

    // 模式或本地数据档案切换时自动刷新 Token 用量
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsState()
    val contentIsCurrent = loadedQueryKey == tokenUsageQueryKey(
        mode = appMode.toString(),
        dataSourceRevision = dataSourceRevision,
        range = dateRange,
        startDate = startDate,
        endDate = endDate
    )
    val rankingIsCurrent = loadedRankingKey == tokenUsageRankingKey(
        mode = appMode.toString(),
        dataSourceRevision = dataSourceRevision
    )
    LaunchedEffect(appMode, dataSourceRevision) { vm.load(refreshRankings = true) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.tokens_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    TokenDateFilterButton(
                        selectedRange = dateRange,
                        startDate = startDate,
                        endDate = endDate,
                        onRangeSelect = vm::setDateRange,
                        onCustomSelect = {
                            pendingCustomStart = null
                            pickEndAfterStart = true
                            showStartPicker = true
                        }
                    )
                    IconButton(
                        onClick = { vm.load(refreshRankings = true) },
                        enabled = !loading
                    ) {
                        if (loading && stats != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(19.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.tokens_refresh),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.tokens_more_actions),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        GlassDropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tokens_route_history)) },
                                leadingIcon = { Icon(Icons.Filled.Route, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    onNavigate(Routes.ROUTING_HISTORY)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tokens_ab_test_settings)) },
                                leadingIcon = { Icon(Icons.Filled.Science, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    onNavigate(Routes.AB_TEST_SETTINGS)
                                }
                            )
                        }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let {
                    item(key = "error", contentType = "status") {
                        ErrorBanner(message = it, onRetry = {
                            vm.clearError()
                            vm.load(refreshRankings = true)
                        })
                    }
                }

                item(key = "section_tabs", contentType = "controls") {
                    TokenSegmentedBar(
                        tabs = listOf(
                            stringResource(R.string.tokens_tab_overview),
                            stringResource(R.string.tokens_tab_rankings),
                            stringResource(R.string.tokens_tab_records)
                        ),
                        selectedIndex = selectedSection,
                        onSelect = { selectedSection = it }
                    )
                }

                val selectedSectionIsCurrent = if (selectedSection == SECTION_RANKINGS) {
                    rankingIsCurrent
                } else {
                    contentIsCurrent
                }
                if (!selectedSectionIsCurrent) {
                    if (loading) {
                        item(key = "scope_loading", contentType = "status") {
                            TokenScopeLoading()
                        }
                    }
                } else when (selectedSection) {
                    SECTION_OVERVIEW -> stats?.let { currentStats ->
                        item(key = "usage_hero", contentType = "summary") {
                            TokenUsageHero(
                                stats = currentStats,
                                dateRange = dateRange,
                                startDate = startDate,
                                endDate = endDate
                            )
                        }
                        item(key = "key_metrics", contentType = "summary") {
                            TokenKeyMetrics(stats = currentStats)
                        }
                    }

                    SECTION_RANKINGS -> item(key = "rankings", contentType = "rankings") {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(
                                title = stringResource(R.string.tokens_rankings_title),
                                subtitle = stringResource(R.string.tokens_rankings_scope_all)
                            )
                            Spacer(Modifier.height(12.dp))
                            TokenSegmentedBar(
                                tabs = listOf(
                                    stringResource(R.string.tokens_ranking_sessions),
                                    stringResource(R.string.tokens_ranking_models),
                                    stringResource(R.string.tokens_ranking_purposes)
                                ),
                                selectedIndex = rankingTab,
                                onSelect = { rankingTab = it }
                            )
                            Spacer(Modifier.height(14.dp))
                            if (parsedRanking.isEmpty()) {
                                Text(
                                    stringResource(R.string.common_empty_data),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                val maxTokens = parsedRanking.maxOf { it.second }.coerceAtLeast(1L)
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    parsedRanking.forEachIndexed { idx, (name, tokens) ->
                                        RankingBarRow(
                                            rank = idx + 1,
                                            name = if (rankingTab == 2) purposeLabel(name) else name,
                                            tokens = tokens,
                                            maxTokens = maxTokens
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SECTION_RECORDS -> {
                        if (records.isEmpty()) {
                            item(key = "records_empty", contentType = "status") {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        stringResource(R.string.common_empty_data),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            item(key = "records_header", contentType = "section_header") {
                                SectionHeader(
                                    title = stringResource(R.string.tokens_records_title),
                                    subtitle = stringResource(R.string.tokens_records_count, records.size),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            groupedRecords.forEach { (date, dateRecords) ->
                                item(key = "date_$date", contentType = "date_header") {
                                    TokenRecordDateHeader(date)
                                }
                                dateRecords.forEach { record ->
                                    item(key = record.id, contentType = "token_record") {
                                        TokenRecordCard(
                                            record = record,
                                            expanded = expandedRecordId == record.id,
                                            onToggle = {
                                                expandedRecordId = if (expandedRecordId == record.id) null else record.id
                                            },
                                            resolveSessionName = vm::resolveSessionName
                                        )
                                    }
                                }
                            }
                            if (visibleRecordCount < records.size) {
                                item(key = "records_load_more", contentType = "controls") {
                                    OutlinedButton(
                                        onClick = { visibleRecordCount += RECORD_BATCH_SIZE },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.tokens_load_more,
                                                records.size - visibleRecordCount
                                            )
                                        )
                                    }
                                }
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
            initialDate = startDate,
            onConfirm = { millis ->
                val dateStr = formatDate(millis)
                val currentEnd = endDate
                showStartPicker = false
                if (dateStr.isNotBlank()) {
                    if (pickEndAfterStart || currentEnd == null) {
                        pendingCustomStart = dateStr
                        showEndPicker = true
                    } else {
                        vm.setCustomRange(dateStr, currentEnd)
                        pickEndAfterStart = false
                    }
                }
            },
            onDismiss = {
                pendingCustomStart = null
                pickEndAfterStart = false
                showStartPicker = false
            }
        )
    }

    // 结束日期选择器
    if (showEndPicker) {
        NekoDatePickerDialog(
            initialDate = endDate,
            onConfirm = { millis ->
                val dateStr = formatDate(millis)
                showEndPicker = false
                if (dateStr.isNotBlank()) {
                    val currentStart = pendingCustomStart ?: startDate ?: dateStr
                    vm.setCustomRange(currentStart, dateStr)
                }
                pendingCustomStart = null
                pickEndAfterStart = false
            },
            onDismiss = {
                pendingCustomStart = null
                pickEndAfterStart = false
                showEndPicker = false
            }
        )
    }
}

private const val SECTION_OVERVIEW = 0
private const val SECTION_RANKINGS = 1
private const val SECTION_RECORDS = 2
private const val RECORD_BATCH_SIZE = 20

private fun tokenUsageQueryKey(
    mode: String,
    dataSourceRevision: Long,
    range: String,
    startDate: String?,
    endDate: String?
): String {
    val customStart = if (range == "custom") startDate.orEmpty() else ""
    val customEnd = if (range == "custom") endDate.orEmpty() else ""
    return listOf(mode, dataSourceRevision, range, customStart, customEnd).joinToString("|")
}

private fun tokenUsageRankingKey(mode: String, dataSourceRevision: Long): String =
    "$mode|$dataSourceRevision"

@Composable
private fun TokenScopeLoading() {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.common_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TokenDateFilterButton(
    selectedRange: String,
    startDate: String?,
    endDate: String?,
    onRangeSelect: (String) -> Unit,
    onCustomSelect: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val ranges = listOf(
        "today" to stringResource(R.string.tokens_range_today),
        "month" to stringResource(R.string.tokens_range_month),
        "total" to stringResource(R.string.tokens_range_total),
        "custom" to stringResource(R.string.tokens_range_custom)
    )
    val selectedLabel = ranges.firstOrNull { it.first == selectedRange }?.second.orEmpty()
    Box {
        IconButton(onClick = { expanded = true }) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = stringResource(R.string.tokens_date_filter, selectedLabel),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 210.dp)
        ) {
            ranges.forEach { (range, label) ->
                DropdownMenuItem(
                    modifier = Modifier.semantics {
                        selected = range == selectedRange
                    },
                    text = {
                        Column {
                            Text(label)
                            if (range == "custom" && startDate != null && endDate != null) {
                                Text(
                                    "$startDate  —  $endDate",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    trailingIcon = if (range == selectedRange) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        if (range == "custom") onCustomSelect() else onRangeSelect(range)
                    }
                )
            }
        }
    }
}

@Composable
private fun TokenSegmentedBar(
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .widthIn(min = 104.dp)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(index) },
                            role = Role.Tab
                        )
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TokenUsageHero(
    stats: TokenStats,
    dateRange: String,
    startDate: String?,
    endDate: String?
) {
    val input = stats.todayInput ?: 0L
    val output = stats.todayOutput ?: 0L
    val breakdownTotal = input + output
    val inputFraction = if (breakdownTotal > 0L) input.toFloat() / breakdownTotal else 0f
    val rangeLabel = tokenRangeLabel(dateRange)
    val total = tokenRangeTotal(stats, dateRange)
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.94f),
                        MaterialTheme.colorScheme.secondary
                    )
                ),
                shape
            )
            .padding(20.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(112.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.09f))
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DataUsage,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    rangeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(11.dp))
            Text(
                formatTokenCount(total),
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                stringResource(R.string.tokens_stat_token),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.76f)
            )
            if (dateRange == "custom" && (startDate != null || endDate != null)) {
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(startDate, endDate).joinToString("  —  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
            stats.estimatedCost
                ?.takeIf { it.isNotBlank() && it != "—" }
                ?.let { cost ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.widthIn(max = 220.dp),
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.16f)
                    ) {
                        Text(
                            "${stringResource(R.string.tokens_stat_estimated_cost)} $cost",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            Spacer(Modifier.height(17.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.14f)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp)) {
                    Row {
                        TokenHeroMetric(
                            label = stringResource(R.string.tokens_input),
                            value = input,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        TokenHeroMetric(
                            label = stringResource(R.string.tokens_output),
                            value = output,
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.weight(1f),
                            alignEnd = true
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (breakdownTotal > 0L) Color.White.copy(alpha = 0.35f)
                                else Color.White.copy(alpha = 0.16f)
                            )
                    ) {
                        if (breakdownTotal > 0L) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(inputFraction)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenHeroMetric(
    label: String,
    value: Long,
    color: Color,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f))
        Text(
            formatTokenCount(value),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TokenKeyMetrics(stats: TokenStats) {
    val singleColumn = LocalConfiguration.current.screenWidthDp < 340 ||
        LocalDensity.current.fontScale >= 1.3f
    val items = listOf(
        TokenStatItem(
            label = stringResource(R.string.tokens_stat_messages),
            value = formatTokenCount(stats.messageCount ?: 0L),
            icon = Icons.Filled.ChatBubbleOutline,
            tint = MaterialTheme.colorScheme.primary
        ),
        TokenStatItem(
            label = stringResource(R.string.tokens_stat_avg_per_msg),
            value = String.format(Locale.US, "%.0f", stats.avgTokensPerMsg ?: 0.0),
            icon = Icons.Filled.DataUsage,
            tint = MaterialTheme.colorScheme.secondary
        ),
        TokenStatItem(
            label = stringResource(R.string.tokens_stat_active_sessions),
            value = "${stats.activeSessions ?: 0}",
            icon = Icons.Filled.Forum,
            tint = MaterialTheme.colorScheme.tertiary
        ),
        TokenStatItem(
            label = stringResource(R.string.tokens_stat_avg_response),
            value = stats.avgResponseTime ?: "—",
            icon = Icons.Filled.Speed,
            tint = MaterialTheme.colorScheme.primary
        )
    )
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.tokens_metrics_title))
        Spacer(Modifier.height(12.dp))
        if (singleColumn) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    TokenStatTile(
                        label = item.label,
                        value = item.value,
                        icon = item.icon,
                        tint = item.tint,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp)
                    )
                }
            }
        } else {
            items.chunked(2).forEachIndexed { index, rowItems ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { item ->
                        TokenStatTile(
                            label = item.label,
                            value = item.value,
                            icon = item.icon,
                            tint = item.tint,
                            modifier = Modifier.weight(1f).heightIn(min = 100.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class TokenStatItem(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val tint: Color
)

@Composable
private fun TokenStatTile(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = tint.copy(alpha = 0.09f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun tokenRangeLabel(dateRange: String): String = when (dateRange) {
    "today" -> stringResource(R.string.tokens_stat_today)
    "month" -> stringResource(R.string.tokens_stat_month)
    "total" -> stringResource(R.string.tokens_stat_total)
    "custom" -> stringResource(R.string.tokens_stat_custom)
    else -> stringResource(R.string.tokens_stat_token)
}

private fun tokenRangeTotal(stats: TokenStats, dateRange: String): Long = when (dateRange) {
    "today" -> stats.today ?: stats.todayTotal
    "month" -> stats.month ?: stats.todayTotal
    "total" -> stats.totalDisplay
    "custom" -> (stats.todayInput ?: 0L) + (stats.todayOutput ?: 0L)
    else -> stats.totalDisplay
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

/** 手机端紧凑记录卡：默认保留主信息，点按后再显示拆分与性能数据。 */
@Composable
private fun TokenRecordCard(
    record: TokenRecordUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    resolveSessionName: suspend (String) -> String
) {
    val compactTs = compactTimestamp(record.timestamp)?.substringAfter(' ')
    val purposeLabel = purposeLabel(record.purpose)
    val badgeColor = purposeColor(record.purpose)
    val sessionId = record.sessionId
    val sessionName by androidx.compose.runtime.produceState(
        initialValue = record.sessionName.ifBlank { sessionId.take(8) },
        key1 = sessionId,
        key2 = record.sessionName
    ) {
        if (record.sessionName.isBlank() && sessionId.isNotBlank()) {
            value = resolveSessionName(sessionId)
        }
    }
    val sessionDisplay = sessionName.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.tokens_session_prefix, it)
    }.orEmpty()
    val isChatChannelSource = record.purpose.equals("chat", ignoreCase = true) &&
        record.source.equals("web", ignoreCase = true)
    val sourceDisplay = record.source
        .takeIf { it.isNotBlank() && !isChatChannelSource }
        ?.let { stringResource(R.string.tokens_source_prefix, sourceLabel(it)) }
        .orEmpty()
    val priceDisplay = record.cost?.takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.tokens_cost, it)
    } ?: record.estimatedCostUsd?.let {
        stringResource(R.string.tokens_estimated_price, formatUsdCost(it))
    }
    val performanceText = listOfNotNull(
        record.durationMs?.let { stringResource(R.string.tokens_duration, formatDuration(it)) },
        record.ttftMs?.let { stringResource(R.string.tokens_ttft, formatDuration(it)) }
    ).joinToString(" · ")
    val recordActionLabel = stringResource(
        if (expanded) R.string.tokens_collapse_record else R.string.tokens_expand_record
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp)
            )
            .animateContentSize()
            .clickable(
                onClickLabel = recordActionLabel,
                role = Role.Button,
                onClick = onToggle
            )
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.model.ifBlank { stringResource(R.string.tokens_unknown_model) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            if (record.estimated) {
                                stringResource(R.string.tokens_estimated_badge, purposeLabel)
                            } else {
                                purposeLabel
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    if (sourceDisplay.isNotBlank()) {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            sourceDisplay,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatTokenCount(record.total),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.tokens_stat_token),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        if (sessionDisplay.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = sessionDisplay,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (expanded) {
            Spacer(Modifier.height(11.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TokenMetric(
                    label = stringResource(R.string.tokens_input),
                    value = record.input,
                    modifier = Modifier.weight(1f)
                )
                TokenMetric(
                    label = stringResource(R.string.tokens_output),
                    value = record.output,
                    modifier = Modifier.weight(1f)
                )
                TokenMetric(
                    label = stringResource(R.string.tokens_total),
                    value = record.total,
                    emphasized = true,
                    modifier = Modifier.weight(1f)
                )
            }
            priceDisplay?.let { price ->
                Spacer(Modifier.height(9.dp))
                Text(
                    price,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!compactTs.isNullOrBlank() || performanceText.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    listOfNotNull(
                        compactTs?.takeIf { it.isNotBlank() },
                        performanceText.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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

private fun parseTokenRecord(index: Int, elem: JsonElement): TokenRecordUi? {
    if (!elem.isJsonObject) return null
    val obj = elem.asJsonObject
    fun string(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        obj.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.runCatching { asString }?.getOrNull()
    }.orEmpty()
    fun long(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        obj.get(key)?.takeIf { !it.isJsonNull }?.runCatching { asLong }?.getOrNull()
    }
    fun bool(vararg keys: String): Boolean = keys.firstNotNullOfOrNull { key ->
        obj.get(key)?.takeIf { !it.isJsonNull }?.runCatching { asBoolean }?.getOrNull()
    } ?: false
    fun double(key: String): Double? = obj.get(key)?.takeIf { !it.isJsonNull }?.runCatching { asDouble }?.getOrNull()

    val timestamp = string("timestamp", "created_at", "time")
    val date = string("date").ifBlank {
        when {
            timestamp.contains('T') -> timestamp.substringBefore('T')
            timestamp.contains(' ') -> timestamp.substringBefore(' ')
            else -> ServiceContainer.getString(R.string.tokens_date_unknown)
        }
    }
    val input = long("input", "input_tokens") ?: 0L
    val output = long("output", "output_tokens") ?: 0L
    val model = string("model", "model_name")
    val actualModel = string("actual_model", "actualModel")
    val provider = string("provider")
    val recordedEstimatedCost = double("estimated_cost_usd")
    val estimatedCostUsd = recordedEstimatedCost ?: estimateTokenRecordCost(
        modelName = actualModel.ifBlank { model },
        provider = provider,
        inputTokens = input,
        outputTokens = output
    )
    return TokenRecordUi(
        id = string("id")
            .takeIf { it.isNotBlank() }
            ?.let { "$it:$index" }
            ?: "$timestamp:${string("model")}:${string("session_id")}:$input:$output:${elem.hashCode()}:$index",
        timestamp = timestamp,
        date = date,
        model = model,
        actualModel = actualModel,
        purpose = string("purpose"),
        source = string("source", "channel_type"),
        sessionId = string("session_id"),
        sessionName = string("session_name", "session_title", "conversation_name"),
        input = input,
        output = output,
        total = long("total", "total_tokens", "tokens") ?: (input + output),
        cost = string("cost").takeIf { it.isNotBlank() },
        estimatedCostUsd = estimatedCostUsd,
        durationMs = double("duration_ms"),
        ttftMs = double("ttft_ms"),
        estimated = bool("estimated", "usage_estimated")
    )
}

private fun estimateTokenRecordCost(
    modelName: String,
    provider: String,
    inputTokens: Long,
    outputTokens: Long
): Double? {
    if (modelName.isBlank()) return null
    val prices = ModelPricingCatalog.resolvePrices(modelName = modelName, provider = provider)
    if (prices.first == null && prices.second == null) return null
    return (inputTokens / 1_000_000.0) * (prices.first ?: 0.0) +
        (outputTokens / 1_000_000.0) * (prices.second ?: 0.0)
}

private fun purposeLabel(purpose: String): String = when (purpose.lowercase()) {
    "chat" -> ServiceContainer.getString(R.string.tokens_purpose_chat)
    "vision" -> ServiceContainer.getString(R.string.tokens_purpose_vision)
    "video" -> ServiceContainer.getString(R.string.tokens_purpose_video)
    "tts" -> ServiceContainer.getString(R.string.tokens_purpose_tts)
    "stt" -> ServiceContainer.getString(R.string.tokens_purpose_stt)
    "embedding" -> ServiceContainer.getString(R.string.tokens_purpose_embedding)
    "memory" -> ServiceContainer.getString(R.string.tokens_purpose_memory)
    "plot" -> ServiceContainer.getString(R.string.tokens_purpose_plot)
    "utility" -> ServiceContainer.getString(R.string.tokens_purpose_utility)
    "decision" -> ServiceContainer.getString(R.string.tokens_purpose_decision)
    "heartbeat" -> ServiceContainer.getString(R.string.tokens_purpose_heartbeat)
    "react" -> "ReAct"
    "image_gen" -> ServiceContainer.getString(R.string.tokens_purpose_image_gen)
    else -> purpose.ifBlank { ServiceContainer.getString(R.string.tokens_purpose_uncategorized) }
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
    "plot" -> ServiceContainer.getString(R.string.tokens_source_plot)
    "state" -> ServiceContainer.getString(R.string.tokens_source_state)
    "memory" -> ServiceContainer.getString(R.string.tokens_source_memory)
    "web" -> ServiceContainer.getString(R.string.tokens_source_web)
    "vision" -> ServiceContainer.getString(R.string.tokens_source_vision)
    "stt" -> ServiceContainer.getString(R.string.tokens_source_stt)
    "rule" -> ServiceContainer.getString(R.string.tokens_source_rule)
    "heartbeat" -> ServiceContainer.getString(R.string.tokens_source_heartbeat)
    else -> source
}

private fun formatDuration(milliseconds: Double): String = when {
    milliseconds >= 1000 -> String.format(Locale.US, "%.1fs", milliseconds / 1000.0)
    else -> "${milliseconds.toLong()}ms"
}

private fun formatUsdCost(cost: Double): String = when {
    !cost.isFinite() -> "—"
    cost >= 0.01 -> String.format(Locale.US, "%.4f", cost)
    cost >= 0.000001 -> String.format(Locale.US, "%.6f", cost)
    else -> String.format(Locale.US, "%.8f", cost)
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
    val unknownName = ServiceContainer.getString(R.string.common_unknown)
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
                ?: unknownName
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
            Pair(unknownName, 0L)
        }
    } catch (e: Exception) {
        Pair(unknownName, 0L)
    }
}

/**
 * 日期选择器弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NekoDatePickerDialog(
    initialDate: String? = null,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val initialDateMillis = remember(initialDate) { parseDateMillis(initialDate) }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(state.selectedDateMillis) },
                enabled = state.selectedDateMillis != null
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
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
    // Material DatePicker 的毫秒值以 UTC 午夜为基准；按 UTC 格式化可避免负时区出现前一天。
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return sdf.format(Date(millis))
}

private fun parseDateMillis(date: String?): Long? {
    if (date.isNullOrBlank()) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(date)?.time
    }.getOrNull()
}
