package com.nekobot.app.ui.screens.statehistory

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.MarkdownText
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.Secondary
import com.nekobot.app.ui.theme.SuccessGreen
import com.nekobot.app.ui.theme.Tertiary
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 六维指标定义：(显示名资源ID, JSON键, 颜色)。雷达图与趋势线共用，不含嫉妒（用精力替代）。 */
private val metricDefs = listOf(
    Triple(R.string.state_history_metric_affection, "affection", Color(0xFFfb7185)),
    Triple(R.string.state_history_metric_trust, "trust", Color(0xFF38bdf8)),
    Triple(R.string.state_history_metric_familiarity, "familiarity", Color(0xFF34d399)),
    Triple(R.string.state_history_metric_dependency, "dependency", Color(0xFFa78bfa)),
    Triple(R.string.state_history_metric_security, "security", Color(0xFFf59e0b)),
    Triple(R.string.state_history_metric_energy, "energy", Color(0xFF22c55e))
)

// ==================== JsonObject 取值辅助 ====================

private fun JsonObject.intOr(key: String, default: Int = 0): Int =
    get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: default

private fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asInt

private fun JsonObject.floatOrNull(key: String): Float? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asFloat

private fun JsonObject.strOr(key: String, default: String = "—"): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: default

private fun JsonObject.strOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

/**
 * 状态历程 ViewModel：拉取所有渠道（QQ + Web 等）的角色状态时间线。
 * 数据源：
 *  - GET /api/channel_runtime_timeline（QQ 等渠道会话，已含 timeline）
 *  - GET /api/sessions（获取全部会话元数据，含 web 会话）
 *  - GET /api/sessions/{id}/runtime-timeline（逐个获取 web 会话的 timeline）
 *
 * 缓存策略：进入页面时优先加载缓存文件，点击刷新按钮才重新获取。
 */
class StateHistoryViewModel : BaseViewModel() {

    private val _sessions = MutableStateFlow<List<JsonObject>>(emptyList())
    val sessions: StateFlow<List<JsonObject>> = _sessions.asStateFlow()

    private val _selected = MutableStateFlow<JsonObject?>(null)
    val selected: StateFlow<JsonObject?> = _selected.asStateFlow()

    init { loadFromCache() }

    /** 从缓存文件加载（进入页面时调用） */
    fun loadFromCache() {
        viewModelScope.launch {
            val cacheJson = withContext(Dispatchers.IO) {
                com.nekobot.app.ServiceContainer.localRepository.loadStateHistoryCache()
            }
            if (!cacheJson.isNullOrBlank()) {
                runCatching {
                    val arr = JsonParser.parseString(cacheJson).asJsonArray
                    val list = arr.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                    // 按 updated_at 倒序：最新会话排在最前
                    val sorted = list.sortedByDescending { it.get("updated_at")?.asString ?: "" }
                    _sessions.value = sorted
                    if (_selected.value == null) _selected.value = sorted.firstOrNull()
                }
            } else {
                // 无缓存，首次加载从数据源获取
                fetchFromSource()
            }
        }
    }

    /** 强制刷新：清空内存缓存后从数据源重新获取 */
    fun refresh() {
        _sessions.value = emptyList()
        _selected.value = null
        fetchFromSource()
    }

    private fun fetchFromSource() {
        if (isLocalMode) {
            loadLocalStateHistory()
        } else {
            loadRemoteStateHistory()
        }
    }

    /** 将会话列表保存到缓存文件 */
    private fun saveCache(list: List<JsonObject>) {
        val arr = JsonArray()
        list.forEach { arr.add(it) }
        val jsonStr = arr.toString()
        viewModelScope.launch(Dispatchers.IO) {
            com.nekobot.app.ServiceContainer.localRepository.saveStateHistoryCache(jsonStr)
        }
    }

    /** 本地模式：从 LocalRepository 加载状态历程 */
    private fun loadLocalStateHistory() {
        launchResult(
            block = {
                val sessions = com.nekobot.app.ServiceContainer.localRepository.listSessions()
                val jsonSessions = sessions.mapNotNull { s ->
                    val sid = s.id ?: return@mapNotNull null
                    val timeline = com.nekobot.app.ServiceContainer.localRepository
                        .listStateHistory(sid)
                    val timelineArr = JsonArray()
                    timeline.forEach { entry ->
                        val obj = JsonObject()
                        entry.forEach { (k, v) ->
                            when (v) {
                                is String -> obj.addProperty(k, v)
                                is Number -> obj.addProperty(k, v)
                                is Boolean -> obj.addProperty(k, v)
                                is Map<*, *> -> {
                                    val subObj = JsonObject()
                                    @Suppress("UNCHECKED_CAST")
                                    (v as Map<String, Any>).forEach { (sk, sv) ->
                                        when (sv) {
                                            is String -> subObj.addProperty(sk, sv)
                                            is Number -> subObj.addProperty(sk, sv)
                                            is Boolean -> subObj.addProperty(sk, sv)
                                        }
                                    }
                                    obj.add(k, subObj)
                                }
                            }
                        }
                        timelineArr.add(obj)
                    }
                    val obj = JsonObject()
                    obj.addProperty("id", sid)
                    obj.addProperty("name", s.displayName)
                    obj.addProperty("character_id", s.characterId ?: "")
                    obj.addProperty("updated_at", s.updatedAt ?: "")
                    obj.add("character_runtime_timeline", timelineArr)
                    obj
                }
                Resource.Success(jsonSessions)
            },
            onSuccess = { merged ->
                // 按 updated_at 倒序：最新会话排在最前
                val sorted = merged.sortedByDescending { it.get("updated_at")?.asString ?: "" }
                _sessions.value = sorted
                if (_selected.value == null) _selected.value = sorted.firstOrNull()
                saveCache(sorted)
            }
        )
    }

    /** 远程模式：从服务器加载状态历程 */
    private fun loadRemoteStateHistory() {
        launchResult(
            block = {
                // 1. 获取渠道时间线（QQ 等渠道会话，已含 timeline）
                val channelSessions = when (val r = repo.channelRuntimeTimeline()) {
                    is Resource.Success -> parseSessionsFromChannel(r.data)
                    else -> emptyList()
                }

                // 2. 获取所有会话列表（含 web 会话）
                val allSessions = when (val r = repo.listSessions()) {
                    is Resource.Success -> r.data ?: emptyList()
                    else -> emptyList()
                }

                // 3. 对不在渠道时间线中的会话（web 会话），逐个获取时间线
                val channelIds = channelSessions.mapNotNull {
                    it.get("session_id")?.asString ?: it.get("id")?.asString
                }.toSet()

                val webTimelines = allSessions
                    .filter { it.id != null && it.id !in channelIds }
                    .map { s ->
                        val tl = when (val r = repo.sessionRuntimeTimeline(s.id!!)) {
                            is Resource.Success -> parseTimeline(r.data)
                            else -> emptyList()
                        }
                        buildSessionJson(s, tl)
                    }

                Resource.Success(channelSessions + webTimelines)
            },
            onSuccess = { merged ->
                // 按 updated_at 倒序：最新会话排在最前
                val sorted = merged.sortedByDescending { it.get("updated_at")?.asString ?: "" }
                _sessions.value = sorted
                if (_selected.value == null) _selected.value = sorted.firstOrNull()
                saveCache(sorted)
            }
        )
    }

    private fun parseSessionsFromChannel(json: JsonElement): List<JsonObject> {
        val arr = when {
            json.isJsonObject -> json.asJsonObject
                .get("sessions")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: json.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            json.isJsonArray -> json.asJsonArray
            else -> null
        }
        return arr?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject } ?: emptyList()
    }

    private fun parseTimeline(json: JsonElement?): List<JsonObject> {
        if (json == null) return emptyList()
        val arr = when {
            json.isJsonObject -> json.asJsonObject
                .get("timeline")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: json.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: json.asJsonObject.get("records")?.takeIf { it.isJsonArray }?.asJsonArray
            json.isJsonArray -> json.asJsonArray
            else -> null
        }
        return arr?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject } ?: emptyList()
    }

    private fun buildSessionJson(session: com.nekobot.app.data.model.Session, timeline: List<JsonObject>): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", session.id)
        obj.addProperty("name", session.displayName)
        obj.addProperty("character_id", session.characterId)
        obj.addProperty("updated_at", session.updatedAt ?: "")
        val timelineArr = JsonArray()
        timeline.forEach { timelineArr.add(it) }
        obj.add("character_runtime_timeline", timelineArr)
        return obj
    }

    fun select(s: JsonObject?) { _selected.value = s }
}

/**
 * 状态历程页：通过下拉菜单选择会话，展示该会话的状态时间线。
 * 重写后向原仓库看齐：雷达图 + 趋势线 + Delta卡片 + 对话回放 + 时间轴滑块。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StateHistoryScreen(onBack: () -> Unit) {
    val vm: StateHistoryViewModel = viewModel()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadFromCache() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.state_history_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.state_history_refresh), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && sessions.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                }
                sessions.isEmpty() -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        error?.let {
                            Box(Modifier.padding(16.dp)) {
                                ErrorBanner(message = it, onRetry = { vm.clearError(); vm.refresh() })
                            }
                        }
                        EmptyState(title = stringResource(R.string.state_history_empty_title), hint = stringResource(R.string.state_history_empty_hint))
                    }
                }
                else -> {
                    val selectedSession = selected
                    val sessionName = selectedSession?.get("name")?.asString ?: stringResource(R.string.state_history_unnamed_session)
                    val timelineEl = selectedSession?.get("character_runtime_timeline")
                        ?: selectedSession?.get("timeline")
                    val timeline: List<JsonObject> = remember(selectedSession) {
                        when {
                            timelineEl?.isJsonArray == true -> timelineEl.asJsonArray
                                .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                            else -> emptyList()
                        }
                    }

                    // 状态提升到外层，供 stickyHeader 和 body 共享
                    // timeline 正序（旧→新），初始定位到末尾（最新节点）
                    var currentIndex by remember { mutableStateOf(0) }
                    var isPlaying by remember { mutableStateOf(false) }
                    var selectedMetricIndex by remember { mutableStateOf(0) }

                    // 切换会话时重置到最新节点（末尾）
                    LaunchedEffect(timeline) {
                        currentIndex = (timeline.size - 1).coerceAtLeast(0)
                        isPlaying = false
                    }
                    // 自动播放：每 1.5 秒推进一节，到末尾自动停止
                    LaunchedEffect(isPlaying, timeline) {
                        if (isPlaying && timeline.isNotEmpty()) {
                            while (isActive) {
                                delay(1500)
                                val next = currentIndex + 1
                                if (next >= timeline.size) {
                                    isPlaying = false
                                    break
                                }
                                currentIndex = next
                            }
                        }
                    }

                    val currentNode = if (timeline.isNotEmpty())
                        timeline.getOrElse(currentIndex.coerceIn(0, timeline.lastIndex)) { timeline.first() }
                    else null
                    val prevNode = if (currentIndex > 0 && timeline.isNotEmpty()) timeline[currentIndex - 1] else null

                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 会话选择下拉菜单
                        item {
                            var dropdownExpanded by remember { mutableStateOf(false) }
                            Box {
                                GlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clickable { dropdownExpanded = true },
                                    cornerRadius = 16,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    ) {
                                        Text(
                                            text = sessionName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                DropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false },
                                    modifier = Modifier.heightIn(max = 420.dp)
                                ) {
                                    sessions.forEach { s ->
                                        val name = s.get("name")?.asString ?: stringResource(R.string.state_history_unnamed_session)
                                        val isActive = s == selectedSession
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    name,
                                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            onClick = {
                                                dropdownExpanded = false
                                                vm.select(s)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 会话标题 + 记录数
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(title = sessionName, subtitle = stringResource(R.string.state_history_record_count, timeline.size))
                                if (timeline.isEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(stringResource(R.string.state_history_no_timeline), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        if (timeline.isNotEmpty() && currentNode != null) {
                            // 时间轴 sticky header：紧凑单行布局，向下滚动时保持在顶部不被隐藏
                            stickyHeader {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 节点序号（紧凑）
                                    Text(
                                        text = "${currentIndex + 1}/${timeline.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    // 紧凑滑块
                                    val lastIndex = (timeline.size - 1).coerceAtLeast(0)
                                    val single = timeline.size <= 1
                                    Slider(
                                        value = if (single) 0f else currentIndex.toFloat(),
                                        onValueChange = { if (!single) currentIndex = it.roundToInt().coerceIn(0, lastIndex) },
                                        valueRange = if (single) 0f..1f else 0f..lastIndex.toFloat(),
                                        steps = if (timeline.size > 2) timeline.size - 2 else 0,
                                        enabled = !single,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    // 紧凑播放控制
                                    IconButton(
                                        onClick = { currentIndex = (currentIndex - 1).coerceAtLeast(0); isPlaying = false },
                                        enabled = currentIndex > 0,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.state_history_prev), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            if (!isPlaying && timeline.isNotEmpty() && currentIndex >= timeline.size - 1) {
                                                currentIndex = 0  // 已在末尾，从头开始播放
                                            }
                                            isPlaying = !isPlaying
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (isPlaying) stringResource(R.string.state_history_pause) else stringResource(R.string.state_history_play),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { currentIndex = (currentIndex + 1).coerceAtMost(lastIndex); isPlaying = false },
                                        enabled = currentIndex < lastIndex,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.state_history_next), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }

                            // 其他内容：节点元信息/雷达图/趋势图/Delta/对话回放
                            item {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    NodeMetaPanel(node = currentNode, index = currentIndex, total = timeline.size)
                                    Spacer(Modifier.height(12.dp))

                                    SectionHeader(title = stringResource(R.string.state_history_radar_title), subtitle = stringResource(R.string.state_history_radar_subtitle))
                                    Spacer(Modifier.height(8.dp))
                                    val radarValues = metricDefs.map { (labelRes, key, _) -> stringResource(labelRes) to currentNode.intOr(key) }
                                    RadarChart(values = radarValues)
                                    Spacer(Modifier.height(12.dp))

                                    SectionHeader(title = stringResource(R.string.state_history_trend_title), subtitle = stringResource(R.string.state_history_trend_subtitle))
                                    Spacer(Modifier.height(8.dp))
                                    MetricSelector(selectedIndex = selectedMetricIndex, onSelect = { selectedMetricIndex = it })
                                    Spacer(Modifier.height(8.dp))
                                    val (_, metricKey, metricColor) = metricDefs[selectedMetricIndex]
                                    TrendLineChart(
                                        timeline = timeline,
                                        metricKey = metricKey,
                                        metricColor = metricColor,
                                        currentIndex = currentIndex
                                    )
                                    Spacer(Modifier.height(12.dp))

                                    SectionHeader(title = stringResource(R.string.state_history_delta_title), subtitle = stringResource(R.string.state_history_delta_subtitle))
                                    Spacer(Modifier.height(8.dp))
                                    DeltaCardGrid(current = currentNode, previous = prevNode)
                                    Spacer(Modifier.height(12.dp))

                                    SectionHeader(title = stringResource(R.string.state_history_dialogue_title), subtitle = stringResource(R.string.state_history_dialogue_subtitle))
                                    Spacer(Modifier.height(8.dp))
                                    DialogueReplay(node = currentNode)
                                }
                            }
                        }

                        error?.let {
                            item {
                                ErrorBanner(message = it, onRetry = { vm.clearError(); vm.refresh() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 选中会话的状态时间线展示已内联到 StateHistoryScreen 的 LazyColumn 中，时间轴作为 stickyHeader。 */

// ==================== 节点元信息面板 ====================

/** 当前节点元信息：序号/时间/心情/表层情绪/隐藏情绪。 */
@Composable
private fun NodeMetaPanel(node: JsonObject, index: Int, total: Int) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 节点序号徽章
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.state_history_node_progress, index + 1, total),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                val ts = node.strOrNull("timestamp")?.take(19) ?: "—"
                Text(
                    text = ts,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 心情 + 强度
        val mood = node.strOrNull("mood")?.takeIf { it.isNotBlank() } ?: "—"
        val intensity = node.floatOrNull("mood_intensity")
        val intensityStr = intensity?.let { " (${(it * 100).roundToInt()}%)" } ?: ""
        MetaRow(label = stringResource(R.string.state_history_mood), value = "$mood$intensityStr", valueColor = MaterialTheme.colorScheme.primary)

        // 表层情绪（无则显示 —，保持卡片高度稳定）
        val visible = node.strOrNull("visible_emotion")?.takeIf { it.isNotBlank() } ?: "—"
        MetaRow(label = stringResource(R.string.state_history_visible_emotion), value = visible, valueColor = Tertiary)

        // 隐藏情绪（无则显示 —，保持卡片高度稳定）
        val hidden = node.strOrNull("hidden_emotion")?.takeIf { it.isNotBlank() } ?: "—"
        MetaRow(label = stringResource(R.string.state_history_hidden_emotion), value = hidden, valueColor = WarningAmber)
    }
}

@Composable
private fun MetaRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ==================== 六维雷达图 ====================

/** Canvas 绘制六维雷达图：五层六边形网格 + 当前节点数值多边形 + 顶点标注。 */
@Composable
private fun RadarChart(values: List<Pair<String, Int>>) {
    val density = LocalDensity.current
    val labelTextPx = with(density) { 10.sp.toPx() }
    val valueTextPx = with(density) { 13.sp.toPx() }

    val labelPaint = remember(labelTextPx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            color = OnSurfaceVariant.toArgb()
            textSize = labelTextPx
        }
    }
    val valuePaint = remember(valueTextPx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            color = OnSurface.toArgb()
            textSize = valueTextPx
            isFakeBoldText = true
        }
    }
    val gridColor = OnSurfaceVariant.copy(alpha = 0.3f)
    val axisColor = OnSurfaceVariant.copy(alpha = 0.45f)
    val fillColor = Primary.copy(alpha = 0.28f)
    val strokeColor = Primary

    Canvas(modifier = Modifier.fillMaxWidth().height(300.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (minOf(size.width, size.height) / 2f) * 0.70f
        // 6 个顶点角度，从顶部开始顺时针
        val angles = (0 until 6).map { i -> -PI / 2.0 + i * (PI / 3.0) }

        // 五层六边形网格（20/40/60/80/100）
        for (level in listOf(20f, 40f, 60f, 80f, 100f)) {
            val r = radius * level / 100f
            val path = Path()
            angles.forEachIndexed { i, a ->
                val x = cx + (r * cos(a)).toFloat()
                val y = cy + (r * sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path = path, color = gridColor, style = Stroke(width = 1f))
        }

        // 轴线
        angles.forEach { a ->
            val x = cx + (radius * cos(a)).toFloat()
            val y = cy + (radius * sin(a)).toFloat()
            drawLine(axisColor, start = Offset(cx, cy), end = Offset(x, y), strokeWidth = 1f)
        }

        // 数据多边形
        val dataPath = Path()
        values.forEachIndexed { i, (_, v) ->
            val r = radius * (v.coerceIn(0, 100) / 100f)
            val a = angles[i]
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        drawPath(path = dataPath, color = fillColor)
        drawPath(path = dataPath, color = strokeColor, style = Stroke(width = 2.5f))

        // 数据顶点
        values.forEachIndexed { i, (_, v) ->
            val r = radius * (v.coerceIn(0, 100) / 100f)
            val a = angles[i]
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            drawCircle(color = strokeColor, radius = 4f, center = Offset(x, y))
        }

        // 顶点标注（维度名 + 数值）
        drawIntoCanvas { canvas ->
            values.forEachIndexed { i, (label, v) ->
                val a = angles[i]
                val labelR = radius + 22f
                val x = cx + (labelR * cos(a)).toFloat()
                val y = cy + (labelR * sin(a)).toFloat()
                canvas.nativeCanvas.drawText(label, x, y, labelPaint)
                canvas.nativeCanvas.drawText(v.toString(), x, y + valueTextPx + 2f, valuePaint)
            }
        }
    }
}

// ==================== 趋势折线图 ====================

/** 指标选择器：FlowRow 形式的彩色 chips。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricSelector(selectedIndex: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        metricDefs.forEachIndexed { i, (labelRes, _, color) ->
            val selected = i == selectedIndex
            val bg = if (selected) color.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** Canvas 绘制趋势折线图：X轴节点序号，Y轴 0-100，当前节点高亮。 */
@Composable
private fun TrendLineChart(
    timeline: List<JsonObject>,
    metricKey: String,
    metricColor: Color,
    currentIndex: Int
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }

    val yLabelPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
            color = OnSurfaceVariant.toArgb()
            textSize = labelPx
        }
    }
    val xLabelPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            color = OnSurfaceVariant.toArgb()
            textSize = labelPx
        }
    }
    val gridColor = OnSurfaceVariant.copy(alpha = 0.22f)
    val axisColor = OnSurfaceVariant.copy(alpha = 0.5f)

    Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
        if (timeline.isEmpty()) return@Canvas
        val padLeft = 34f
        val padRight = 14f
        val padTop = 14f
        val padBottom = 22f
        val w = size.width
        val h = size.height
        val chartW = w - padLeft - padRight
        val chartH = h - padTop - padBottom
        if (chartW <= 0f || chartH <= 0f) return@Canvas

        // 横向网格线 + Y 轴标签（0/50/100）
        for (level in listOf(0, 25, 50, 75, 100)) {
            val y = padTop + chartH * (1 - level / 100f)
            drawLine(gridColor, Offset(padLeft, y), Offset(w - padRight, y), strokeWidth = 1f)
        }
        // 基线
        drawLine(axisColor, Offset(padLeft, padTop + chartH), Offset(w - padRight, padTop + chartH), strokeWidth = 1.5f)

        val n = timeline.size
        fun xOf(i: Int) = if (n <= 1) padLeft + chartW / 2f else padLeft + chartW * (i.toFloat() / (n - 1))
        fun yOf(v: Int) = padTop + chartH * (1 - v.coerceIn(0, 100) / 100f)

        val values = timeline.map { it.intOr(metricKey) }

        // 折线
        if (n > 1) {
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = xOf(i); val y = yOf(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = metricColor, style = Stroke(width = 2.5f))
        }

        // 当前节点竖线高亮
        val curIdx = currentIndex.coerceIn(0, n - 1)
        val curX = xOf(curIdx)
        drawLine(metricColor.copy(alpha = 0.35f), Offset(curX, padTop), Offset(curX, padTop + chartH), strokeWidth = 1f)

        // 数据点
        values.forEachIndexed { i, v ->
            val x = xOf(i); val y = yOf(v)
            val isCurrent = i == curIdx
            if (isCurrent) {
                drawCircle(metricColor.copy(alpha = 0.25f), radius = 10f, center = Offset(x, y))
            }
            drawCircle(metricColor, radius = if (isCurrent) 5f else 3f, center = Offset(x, y))
        }

        // 轴标签
        drawIntoCanvas { canvas ->
            for (level in listOf(0, 50, 100)) {
                val y = padTop + chartH * (1 - level / 100f)
                canvas.nativeCanvas.drawText(level.toString(), padLeft - 5f, y + labelPx / 3f, yLabelPaint)
            }
            // X 轴序号标签（最多 ~6 个）
            val step = if (n <= 6) 1 else (n + 5) / 6
            for (i in 0 until n step step) {
                val x = xOf(i)
                canvas.nativeCanvas.drawText((i + 1).toString(), x, h - 5f, xLabelPaint)
            }
        }
    }
}

// ==================== Delta 差分卡片网格 ====================

/** 六维 Delta 卡片网格：每个卡片显示当前值 + 与上一节点差值 + 进度条。 */
@Composable
private fun DeltaCardGrid(current: JsonObject, previous: JsonObject?) {
    // 2 列 × 3 行
    metricDefs.chunked(2).forEach { rowDefs ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowDefs.forEach { (labelRes, key, color) ->
                val cur = current.intOr(key)
                val delta = if (previous != null) cur - previous.intOr(key) else null
                DeltaCard(
                    label = stringResource(labelRes),
                    value = cur,
                    delta = delta,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
            }
            // 若一行只有一个，补一个占位
            if (rowDefs.size == 1) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeltaCard(
    label: String,
    value: Int,
    delta: Int?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            delta?.let {
                val deltaColor = if (it > 0) SuccessGreen else if (it < 0) MaterialTheme.colorScheme.error else OnSurfaceVariant
                val sign = if (it > 0) "+" else ""
                Text(
                    text = "$sign$it",
                    style = MaterialTheme.typography.labelMedium,
                    color = deltaColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        // 百分比进度条
        val progress = (value.coerceIn(0, 100) / 100f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

// ==================== 对话回放 ====================

/** 当前节点关联的对话回放：用户消息靠右、AI 消息靠左，MarkdownText 渲染。 */
@Composable
private fun DialogueReplay(node: JsonObject) {
    val userMsg = node.strOrNull("user_message")?.takeIf { it.isNotBlank() }
    val aiMsg = node.strOrNull("assistant_message")?.takeIf { it.isNotBlank() }
    val msgIndex = node.intOrNull("message_index")

    if (userMsg == null && aiMsg == null) {
        Text(
            stringResource(R.string.state_history_no_messages),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        msgIndex?.let {
            Text(
                stringResource(R.string.state_history_message_index, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        userMsg?.let { MessageBubble(text = it, isUser = true) }
        aiMsg?.let { MessageBubble(text = it, isUser = false) }
    }
}

/** 单条消息气泡：用户靠右（primary 底+白字）、AI 靠左（surface 底）。 */
@Composable
private fun MessageBubble(text: String, isUser: Boolean) {
    val bgColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val bubbleShape = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomEnd = if (isUser) 4.dp else 14.dp,
        bottomStart = if (isUser) 14.dp else 4.dp
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(bubbleShape)
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            MarkdownText(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ==================== 时间轴滑块 + 播放控制 ====================

/** 时间轴滑块 + 上一节/播放/暂停/下一节按钮。 */
@Composable
private fun TimelineSlider(
    currentIndex: Int,
    total: Int,
    isPlaying: Boolean,
    onIndexChange: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val lastIndex = (total - 1).coerceAtLeast(0)
    val single = total <= 1
    val sliderValue = if (single) 0f else currentIndex.toFloat()
    val sliderRange = if (single) 0f..1f else 0f..lastIndex.toFloat()
    val steps = if (total > 2) total - 2 else 0

    Slider(
        value = sliderValue,
        onValueChange = { if (!single) onIndexChange(it.roundToInt()) },
        valueRange = sliderRange,
        steps = steps,
        enabled = !single
    )

    Spacer(Modifier.height(4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev, enabled = currentIndex > 0) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.state_history_prev), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onPlayPause) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.state_history_pause) else stringResource(R.string.state_history_play),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onNext, enabled = currentIndex < lastIndex) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.state_history_next), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
