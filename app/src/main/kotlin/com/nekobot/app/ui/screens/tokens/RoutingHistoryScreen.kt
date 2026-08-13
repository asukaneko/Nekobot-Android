package com.nekobot.app.ui.screens.tokens

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.RoutingAbTestStats
import com.nekobot.app.data.local.ai.RoutingModelStats
import com.nekobot.app.data.local.ai.RoutingScoreBreakdown
import com.nekobot.app.data.local.db.RoutingDecisionLogEntity
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 路由决策历史界面。
 *
 * 展示智能路由产生的决策日志列表，支持展开查看候选模型分项得分、
 * 对回复质量进行好评/差评，以及清空历史。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingHistoryScreen(onBack: () -> Unit) {
    val vm: RoutingHistoryViewModel = viewModel()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val modelStats by vm.modelStats.collectAsStateWithLifecycle()
    val abTestStats by vm.abTestStats.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadLogs() }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("路由可解释性", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResourceSafe(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "清空历史",
                                tint = MaterialTheme.colorScheme.onSurface
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
            if (logs.isEmpty() && modelStats.isEmpty() && !loading) {
                EmptyState(
                    title = "暂无路由决策记录",
                    hint = "智能路由在每次选择模型时会自动记录决策快照"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (modelStats.isNotEmpty() || abTestStats.isNotEmpty()) {
                        item(key = "routing_overview", contentType = "routing_overview") {
                            RoutingOverview(
                                modelStats = modelStats,
                                abTestStats = abTestStats
                            )
                        }
                    }
                    if (logs.isEmpty() && !loading) {
                        item(key = "empty_history", contentType = "empty_history") {
                            EmptyState(
                                title = "暂无可展开的决策记录",
                                hint = "完成一次智能路由请求后，这里会显示选择原因、候选模型分数和实际执行数据"
                            )
                        }
                    }
                    error?.let {
                        item(key = "error") {
                            ErrorBanner(message = it, onRetry = {
                                vm.clearError()
                                vm.loadLogs()
                            })
                        }
                    }
                    items(logs, key = { it.id }) { log ->
                        RoutingLogCard(
                            log = log,
                            onQuality = { score -> vm.recordQuality(log.id, score) }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading && logs.isEmpty())
        }
    }

    if (showClearDialog) {
        NekoDialog(
            onDismiss = { showClearDialog = false },
            title = "清空路由决策历史",
            message = "确定要清空全部路由决策记录吗？此操作不可撤销。",
            confirmText = "清空",
            onConfirm = {
                showClearDialog = false
                vm.clearLogs()
            }
        )
    }
}

/**
 * 单条路由决策卡片：摘要信息 + 可展开的决策详情。
 */
@Composable
private fun RoutingOverview(
    modelStats: List<RoutingModelStats>,
    abTestStats: List<RoutingAbTestStats>
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "模型评分概览",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "分数越低越优先。评分来自最近 200 条路由决策，点击模型卡可展开价格、速度、能力和故障惩罚。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (modelStats.isEmpty()) {
                Text(
                    "暂无已配置的聊天模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                modelStats.forEachIndexed { index, stats ->
                    ModelRoutingScoreCard(stats)
                    if (index != modelStats.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        if (abTestStats.isNotEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "A/B 测试结果",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "按分组比较完成率、质量评分、平均费用和响应延迟。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                abTestStats.forEachIndexed { index, stats ->
                    AbTestStatsRow(stats)
                    if (index != abTestStats.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRoutingScoreCard(stats: RoutingModelStats) {
    var expanded by remember(stats.modelId) { mutableStateOf(false) }
    val score = stats.latestScore
    val scoreProgress = score?.let {
        (1.0 / (1.0 + it.coerceAtLeast(0.0) / 100.0)).toFloat().coerceIn(0.04f, 1f)
    } ?: 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stats.modelName.ifBlank { stats.modelId },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (stats.scoreSamples > 0) {
                        "${stats.scoreSamples} 次候选 · 选择 ${stats.selectedCount} 次"
                    } else {
                        "尚无路由样本，等待实际请求"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = score?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (score == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "当前评分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (score != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { scoreProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoutingMetric(
                label = "成功率",
                value = formatPercent(stats.successRate),
                modifier = Modifier.weight(1f)
            )
            RoutingMetric(
                label = "质量",
                value = formatQuality(stats),
                modifier = Modifier.weight(1f)
            )
            RoutingMetric(
                label = "平均延迟",
                value = formatAverageDuration(stats.averageDurationMs),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoutingMetric(
                label = "平均费用",
                value = formatCost(stats.averageActualCostUsd ?: stats.averageEstimatedCostUsd),
                modifier = Modifier.weight(1f)
            )
            RoutingMetric(
                label = "选择率",
                value = formatPercent(stats.selectionRate),
                modifier = Modifier.weight(1f)
            )
            RoutingMetric(
                label = "A/B 样本",
                value = "${stats.selectedCount}",
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "最近一次评分拆解",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                stats.latestBreakdown?.let { RoutingBreakdownGrid(it) } ?: Text(
                    "暂无评分拆解",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                stats.lastFailureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "最近失败：$reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RoutingBreakdownGrid(breakdown: RoutingScoreBreakdown) {
    val items = listOf(
        "价格" to breakdown.priceScore,
        "速度" to breakdown.speedScore,
        "能力" to breakdown.capabilityBonus,
        "优先级" to breakdown.priorityBonus,
        "上下文" to breakdown.contextBonus,
        "故障惩罚" to breakdown.failurePenalty,
        "无历史" to breakdown.noHistoryPenalty
    )
    items.chunked(4).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            row.forEach { (label, value) ->
                BreakdownChip(label, value, Modifier.weight(1f))
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        if (row != items.chunked(4).last()) Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AbTestStatsRow(stats: RoutingAbTestStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "分组 ${stats.group}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${stats.sampleCount} 个样本",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        stats.modelNames.takeIf { it.isNotEmpty() }?.let { names ->
            Spacer(Modifier.height(3.dp))
            Text(
                text = names.joinToString("、"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RoutingMetric("成功率", formatPercent(stats.successRate), Modifier.weight(1f))
            RoutingMetric("质量", formatPercent(stats.qualityRate), Modifier.weight(1f))
            RoutingMetric("平均延迟", formatAverageDuration(stats.averageDurationMs), Modifier.weight(1f))
            RoutingMetric("平均费用", formatCost(stats.averageCostUsd), Modifier.weight(1f))
        }
    }
}

private fun formatPercent(value: Double?): String =
    value?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—"

private fun formatQuality(stats: RoutingModelStats): String =
    if (stats.qualitySamples == 0) "—"
    else "${stats.positiveQualityCount}/${stats.qualitySamples}"

private fun formatAverageDuration(value: Double?): String = when {
    value == null -> "—"
    value >= 1000.0 -> String.format(Locale.US, "%.1fs", value / 1000.0)
    else -> "${value.toLong()}ms"
}

@Composable
private fun RoutingLogCard(
    log: RoutingDecisionLogEntity,
    onQuality: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：状态图标 + 模型名 + 时间
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(success = log.success, hasActual = log.actualCostUsd != null)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.selectedModelName.ifBlank { "未知模型" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTimestamp(log.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (log.isAbTest) {
                AbTestBadge(group = log.abTestGroup)
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(10.dp))

        // 费用与延迟信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoChip(
                label = "预估费用",
                value = formatCost(log.estimatedCostUsd),
                modifier = Modifier.weight(1f)
            )
            log.actualCostUsd?.let { actual ->
                InfoChip(
                    label = "实际费用",
                    value = formatCost(actual),
                    modifier = Modifier.weight(1f),
                    emphasized = true
                )
            } ?: Spacer(Modifier.weight(1f))
        }
        if (log.actualDurationMs != null || log.actualTtftMs != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                log.actualDurationMs?.let { dur ->
                    InfoChip(
                        label = "总耗时",
                        value = formatDuration(dur),
                        modifier = Modifier.weight(1f)
                    )
                } ?: Spacer(Modifier.weight(1f))
                log.actualTtftMs?.let { ttft ->
                    InfoChip(
                        label = "首Token",
                        value = formatDuration(ttft),
                        modifier = Modifier.weight(1f)
                    )
                } ?: Spacer(Modifier.weight(1f))
            }
        }

        // 失败原因
        if (!log.success && !log.failureReason.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "失败: ${log.failureReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 质量评分 + 展开按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "质量评分",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                QualityButton(
                    selected = log.qualityScore > 0,
                    icon = Icons.Filled.ThumbUp,
                    onClick = { onQuality(1) }
                )
                Spacer(Modifier.width(4.dp))
                QualityButton(
                    selected = log.qualityScore < 0,
                    icon = Icons.Filled.ThumbDown,
                    onClick = { onQuality(-1) }
                )
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 展开详情：解析 decisionJson 展示候选模型分项得分
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(10.dp))
                DecisionDetail(log.decisionJson)
            }
        }
    }
}

/**
 * 成功/失败状态图标。
 * success=true → 绿色勾；success=false → 红色叉；未回填结果 → 灰色圆点。
 */
@Composable
private fun StatusIcon(success: Boolean, hasActual: Boolean) {
    val icon = if (hasActual) {
        if (success) Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        else Icons.Outlined.Cancel to MaterialTheme.colorScheme.error
    } else {
        Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Icon(
        icon.first,
        contentDescription = if (success) "成功" else "失败",
        tint = icon.second,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/**
 * A/B 测试分组徽章。
 */
@Composable
private fun AbTestBadge(group: String?) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .width(12.dp)
                    .height(12.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "A/B ${group ?: "?"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 信息芯片：标签 + 值。
 */
@Composable
private fun InfoChip(
    label: String,
    value: String,
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
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 质量评分按钮。
 */
@Composable
private fun QualityButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(16.dp)
                .height(16.dp)
        )
    }
}

/**
 * 决策详情：解析 decisionJson 展示选择原因与候选模型分项得分。
 */
@Composable
private fun DecisionDetail(decisionJson: String) {
    val parsed = remember(decisionJson) { parseDecision(decisionJson) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 选择原因
        parsed.reason.takeIf { it.isNotBlank() }?.let { reason ->
            Column {
                Text(
                    "选择原因",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 请求上下文
        parsed.requestInfo?.let { req ->
            Column {
                Text(
                    "请求上下文",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    req,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 候选模型分项得分
        if (parsed.candidates.isNotEmpty()) {
            Column {
                Text(
                    "候选模型得分",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                parsed.candidates.forEach { c ->
                    CandidateScoreRow(c)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/**
 * 候选模型分项得分行。
 */
@Composable
private fun CandidateScoreRow(c: CandidateInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                c.modelName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                String.format(Locale.US, "%.2f", c.score),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        // 分项得分条
        ScoreBreakdownGrid(c)
        // 原因列表
        if (c.reasons.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            c.reasons.forEach { reason ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("· ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 分项得分网格：价格/速度/能力/优先级/故障惩罚/无历史惩罚/上下文加分。
 */
@Composable
private fun ScoreBreakdownGrid(c: CandidateInfo) {
    Spacer(Modifier.height(6.dp))
    val items = listOf(
        "价格" to c.priceScore,
        "速度" to c.speedScore,
        "能力" to c.capabilityBonus,
        "优先级" to c.priorityBonus,
        "上下文" to c.contextBonus,
        "故障惩罚" to c.failurePenalty,
        "无历史" to c.noHistoryPenalty
    )
    items.chunked(4).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            row.forEach { (label, value) ->
                BreakdownChip(label = label, value = value, modifier = Modifier.weight(1f))
            }
            if (row.size < 4) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun BreakdownChip(label: String, value: Double, modifier: Modifier = Modifier) {
    val isPenalty = value > 0 && (label == "故障惩罚" || label == "无历史")
    val color = when {
        value == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
        isPenalty -> MaterialTheme.colorScheme.error
        label == "价格" || label == "速度" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            String.format(Locale.US, "%.1f", value),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ==================== 数据模型与解析 ====================

/** 解析后的决策信息。 */
private data class DecisionInfo(
    val reason: String,
    val requestInfo: String?,
    val candidates: List<CandidateInfo>
)

/** 候选模型得分信息。 */
private data class CandidateInfo(
    val modelId: String,
    val modelName: String,
    val score: Double,
    val priceScore: Double,
    val speedScore: Double,
    val failurePenalty: Double,
    val contextBonus: Double,
    val capabilityBonus: Double,
    val priorityBonus: Double,
    val noHistoryPenalty: Double,
    val reasons: List<String>
)

private val decisionGson = Gson()

/**
 * 解析 decisionJson 字符串为结构化信息。
 * decisionJson 由 RoutingDecisionLogger.log 序列化，包含 reason / candidates / request 字段。
 */
private fun parseDecision(json: String): DecisionInfo {
    if (json.isBlank()) return DecisionInfo("", null, emptyList())
    return try {
        val obj = decisionGson.fromJson(json, JsonObject::class.java)
        val reason = obj?.get("reason")?.asString.orEmpty()

        // 请求上下文
        val requestInfo = obj?.getAsJsonObject("request")?.let { req ->
            buildString {
                req.get("estimatedContextTokens")?.let { append("估算上下文: ${it.asInt} tokens  ") }
                req.get("promptChars")?.let { append("提示词: ${it.asInt} 字符  ") }
                req.get("sessionMode")?.let { append("模式: ${it.asString}  ") }
                req.get("hasAttachments")?.takeIf { it.asBoolean }?.let { append("含附件  ") }
                req.get("isAgent")?.takeIf { it.asBoolean }?.let { append("Agent  ") }
                req.get("isComplex")?.takeIf { it.asBoolean }?.let { append("复杂任务  ") }
                req.get("dailyBudgetUsd")?.let { append("日预算: \$${String.format(Locale.US, "%.2f", it.asDouble)}  ") }
                req.get("dailySpentUsd")?.let { append("已花费: \$${String.format(Locale.US, "%.2f", it.asDouble)}") }
            }.takeIf { it.isNotBlank() }
        }

        val candidates = obj?.getAsJsonArray("candidates")?.mapNotNull { elem ->
            if (!elem.isJsonObject) return@mapNotNull null
            val c = elem.asJsonObject
            CandidateInfo(
                modelId = c.get("modelId")?.asString.orEmpty(),
                modelName = c.get("modelName")?.asString.orEmpty(),
                score = c.get("score")?.asDouble ?: 0.0,
                priceScore = c.get("priceScore")?.asDouble ?: 0.0,
                speedScore = c.get("speedScore")?.asDouble ?: 0.0,
                failurePenalty = c.get("failurePenalty")?.asDouble ?: 0.0,
                contextBonus = c.get("contextBonus")?.asDouble ?: 0.0,
                capabilityBonus = c.get("capabilityBonus")?.asDouble ?: 0.0,
                priorityBonus = c.get("priorityBonus")?.asDouble ?: 0.0,
                noHistoryPenalty = c.get("noHistoryPenalty")?.asDouble ?: 0.0,
                reasons = c.getAsJsonArray("reasons")?.map { it.asString } ?: emptyList()
            )
        } ?: emptyList()

        DecisionInfo(reason = reason, requestInfo = requestInfo, candidates = candidates)
    } catch (e: Exception) {
        DecisionInfo(reason = "解析失败: ${e.message}", requestInfo = null, candidates = emptyList())
    }
}

// ==================== 格式化辅助 ====================

/** 格式化时间戳（yyyy-MM-dd HH:mm:ss → MM-dd HH:mm）。 */
private fun formatTimestamp(raw: String): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val date = sdf.parse(raw)
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date ?: Date())
    } catch (e: Exception) {
        raw.take(16)
    }
}

/** 格式化费用（USD → $0.0000）。 */
private fun formatCost(usd: Double?): String {
    if (usd == null) return "—"
    return if (usd >= 0.01) String.format(Locale.US, "$%.4f", usd)
    else String.format(Locale.US, "$%.6f", usd)
}

/** 格式化耗时（毫秒 → x.xs / xxxms）。 */
private fun formatDuration(ms: Long): String {
    return if (ms >= 1000) String.format(Locale.US, "%.1fs", ms / 1000.0)
    else "${ms}ms"
}

/**
 * 安全获取字符串资源。
 */
@Composable
private fun stringResourceSafe(resId: Int): String {
    return androidx.compose.ui.res.stringResource(resId)
}
