package com.nekobot.app.ui.screens.statehistory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
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
 * 状态历程 ViewModel：拉取跨渠道角色状态时间线。
 * 数据源：GET /api/channel_runtime_timeline（合成 sessions 列表，每个含 timeline 数组）。
 */
class StateHistoryViewModel : BaseViewModel() {

    private val _sessions = MutableStateFlow<List<JsonObject>>(emptyList())
    val sessions: StateFlow<List<JsonObject>> = _sessions.asStateFlow()

    private val _selected = MutableStateFlow<JsonObject?>(null)
    val selected: StateFlow<JsonObject?> = _selected.asStateFlow()

    init { load() }

    fun load() {
        launchResult(
            block = { repo.channelRuntimeTimeline() },
            onSuccess = { json ->
                val arr = when {
                    json.isJsonObject -> json.asJsonObject
                        .get("sessions")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?: json.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                    json.isJsonArray -> json.asJsonArray
                    else -> null
                }
                _sessions.value = arr?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject } ?: emptyList()
                if (_selected.value == null) _selected.value = _sessions.value.firstOrNull()
            }
        )
    }

    fun select(s: JsonObject?) { _selected.value = s }
}

/**
 * 状态历程页：顶部会话选择条，选中后展示该会话的状态时间线。
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
                title = { Text("状态历程", color = OnSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgDark,
                    titleContentColor = OnSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnSurface)
                    }
                }
            )
        },
        containerColor = BgDark
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && sessions.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator(color = Primary) }
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
                        Text("会话", style = MaterialTheme.typography.titleSmall, color = OnSurfaceVariant)
                        // 会话选择 chips（横向滚动）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            sessions.forEach { s ->
                                val name = s.get("name")?.asString
                                    ?: s.get("character_id")?.asString
                                    ?: "会话"
                                val isActive = s == selected
                                GlassCard(
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                                    cornerRadius = 12,
                                    containerColor = if (isActive) Primary.copy(alpha = 0.25f) else BgSurfaceVariant
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isActive) Primary else OnSurface,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .width(140.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { vm.select(s) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
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
            Text("该会话暂无状态历程数据", color = OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
        Triple("好感", "affection", Primary),
        Triple("信任", "trust", Secondary),
        Triple("熟悉", "familiarity", Tertiary),
        Triple("依赖", "dependency", WarningAmber),
        Triple("安全感", "security", SuccessGreen),
        Triple("能量", "energy", OnSurfaceVariant)
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            .background(BgSurface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value?.toString() ?: "—",
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
    }
}

/** 单条时间线项：时间轴圆点 + 心情 + 消息摘要 */
@Composable
private fun TimelineItem(rank: Int, entry: JsonObject) {
    val mood = entry.get("mood")?.asString ?: "—"
    val intensity = entry.get("mood_intensity")?.let { if (it.isJsonPrimitive) it.asFloat else null }
    val ts = entry.get("timestamp")?.asString?.take(16) ?: ""
    val userMsg = entry.get("user_message")?.asString?.take(80)
    val aiMsg = entry.get("assistant_message")?.asString?.take(80)

    Row(modifier = Modifier.fillMaxWidth()) {
        // 左侧时间轴圆点 + 连线
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Primary)
            )
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(48.dp)
                    .background(OnSurfaceVariant.copy(alpha = 0.2f))
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(mood, style = MaterialTheme.typography.bodyMedium, color = Primary, fontWeight = FontWeight.SemiBold)
                intensity?.let {
                    Spacer(Modifier.width(4.dp))
                    Text("(${String.format("%.0f", it * 100)}%)", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(ts, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
            }
            if (!userMsg.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text("我：$userMsg", style = MaterialTheme.typography.bodySmall, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!aiMsg.isNullOrBlank()) {
                Text("AI：$aiMsg", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
