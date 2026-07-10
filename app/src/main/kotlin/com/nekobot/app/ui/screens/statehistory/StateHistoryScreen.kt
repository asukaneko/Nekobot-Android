package com.nekobot.app.ui.screens.statehistory

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 状态历程 ViewModel：拉取所有渠道（QQ + Web 等）的角色状态时间线。
 * 数据源：
 *  - GET /api/channel_runtime_timeline（QQ 等渠道会话，已含 timeline）
 *  - GET /api/sessions（获取全部会话元数据，含 web 会话）
 *  - GET /api/sessions/{id}/runtime-timeline（逐个获取 web 会话的 timeline）
 */
class StateHistoryViewModel : BaseViewModel() {

    private val _sessions = MutableStateFlow<List<JsonObject>>(emptyList())
    val sessions: StateFlow<List<JsonObject>> = _sessions.asStateFlow()

    private val _selected = MutableStateFlow<JsonObject?>(null)
    val selected: StateFlow<JsonObject?> = _selected.asStateFlow()

    init { load() }

    /** 强制刷新：清空缓存后重新加载。 */
    fun refresh() {
        _sessions.value = emptyList()
        _selected.value = null
        load()
    }

    fun load() {
        // 已有缓存则不重复加载
        if (_sessions.value.isNotEmpty()) return
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
                _sessions.value = merged
                if (_selected.value == null) _selected.value = merged.firstOrNull()
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
        val timelineArr = JsonArray()
        timeline.forEach { timelineArr.add(it) }
        obj.add("character_runtime_timeline", timelineArr)
        return obj
    }

    fun select(s: JsonObject?) { _selected.value = s }
}

/**
 * 状态历程页：通过下拉菜单选择会话，展示该会话的状态时间线。
 * 每条时间线项含心情/能量/好感/信任等数值与对应消息摘要。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StateHistoryScreen(onBack: () -> Unit) {
    val vm: StateHistoryViewModel = viewModel()
    val sessions by vm.sessions.collectAsState()
    val selected by vm.selected.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("状态历程", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
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
                                ErrorBanner(message = it, onRetry = { vm.clearError(); vm.load() })
                            }
                        }
                        EmptyState(title = "暂无状态数据", hint = "开始对话后，角色状态历程会在此展示")
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 会话选择下拉菜单
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        val selectedName = selected?.get("name")?.asString ?: "选择会话"
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
                                        text = selectedName,
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
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                sessions.forEach { s ->
                                    val name = s.get("name")?.asString ?: "未命名会话"
                                    val isActive = s == selected
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

                        selected?.let { StateTimelineSection(it) }

                        error?.let {
                            ErrorBanner(message = it, onRetry = { vm.clearError(); vm.load() })
                        }
                    }
                }
            }
        }
    }
}

/** 选中会话的状态时间线展示 */
@Composable
private fun StateTimelineSection(session: JsonObject) {
    val name = session.get("name")?.asString ?: "未命名会话"
    val timelineEl = session.get("character_runtime_timeline")
        ?: session.get("timeline")
    val timeline: List<JsonObject> = when {
        timelineEl?.isJsonArray == true -> timelineEl.asJsonArray
            .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
        else -> emptyList()
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = name, subtitle = "${timeline.size} 条状态记录")
        Spacer(Modifier.height(12.dp))

        if (timeline.isEmpty()) {
            Text("该会话暂无状态历程数据", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            return@GlassCard
        }

        // 最新快照数值卡片
        timeline.lastOrNull()?.let { StateSummaryGrid(it) }
        Spacer(Modifier.height(16.dp))

        // 时间线列表（倒序，最新在上）
        timeline.reversed().forEachIndexed { idx, entry ->
            TimelineItem(rank = timeline.size - idx, entry = entry)
            if (idx < timeline.size - 1) Spacer(Modifier.height(8.dp))
        }
    }
}

/** 6 维状态数值小卡片 */
@Composable
private fun StateSummaryGrid(entry: JsonObject) {
    val items = listOf(
        Triple("好感", "affection", MaterialTheme.colorScheme.primary),
        Triple("信任", "trust", MaterialTheme.colorScheme.secondary),
        Triple("熟悉", "familiarity", MaterialTheme.colorScheme.tertiary),
        Triple("依赖", "dependency", WarningAmber),
        Triple("安全感", "security", SuccessGreen),
        Triple("能量", "energy", MaterialTheme.colorScheme.onSurfaceVariant)
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, key, color) ->
            val value = entry.get(key)?.let { if (it.isJsonPrimitive) it.asInt else null }
            StateMiniCard(label = label, value = value, color = color, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StateMiniCard(
    label: String,
    value: Int?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value?.toString() ?: "—",
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 单条时间线项：时间轴圆点 + 心情 + 消息摘要 */
@Composable
private fun TimelineItem(rank: Int, entry: JsonObject) {
    val mood = entry.get("mood")?.asString ?: "—"
    val intensity = entry.get("mood_intensity")?.let { if (it.isJsonPrimitive) it.asFloat else null }
    val ts = entry.get("timestamp")?.asString?.take(16) ?: ""
    val userMsg = entry.get("user_message")?.asString?.take(50)
    val aiMsg = entry.get("assistant_message")?.asString?.take(50)

    Row(modifier = Modifier.fillMaxWidth()) {
        // 左侧时间轴圆点 + 连线
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(mood, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                intensity?.let {
                    Spacer(Modifier.width(4.dp))
                    Text("(${String.format("%.0f", it * 100)}%)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(ts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!userMsg.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text("我：$userMsg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!aiMsg.isNullOrBlank()) {
                Text("AI：$aiMsg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
