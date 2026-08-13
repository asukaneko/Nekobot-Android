package com.nekobot.app.ui.screens.tokens

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
                title = { Text(stringResource(R.string.routing_title), color = MaterialTheme.colorScheme.onSurface) },
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
                                contentDescription = stringResource(R.string.routing_clear_history),
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
                    title = stringResource(R.string.routing_empty_title),
                    hint = stringResource(R.string.routing_empty_hint)
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
                                title = stringResource(R.string.routing_empty_expandable_title),
                                hint = stringResource(R.string.routing_empty_expandable_hint)
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
            title = stringResource(R.string.routing_clear_title),
            message = stringResource(R.string.routing_clear_message),
            confirmText = stringResource(R.string.common_clear),
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
                text = stringResource(R.string.routing_overview_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.routing_overview_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (modelStats.isEmpty()) {
                Text(
                    stringResource(R.string.routing_no_chat_models),
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
                    text = stringResource(R.string.routing_ab_results),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.routing_ab_results_desc),
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
                        stringResource(R.string.routing_model_samples, stats.scoreSamples, stats.selectedCount)
                    } else {
                        stringResource(R.string.routing_no_samples)
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
                    text = stringResource(R.string.routing_current_score),
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
                label = stringResource(R.string.routing_metric_success_rate),
                value = formatPercent(stats.successRate),
                modifier = Modifier.weight(1f)
            )
            RoutingMetric(
                label = stringResource(R.string.routing_metric_quality),
                value = formatQuality(stats),
                modifier = Modifier.weight(1f)
            )
            RoutingMetric(
                label = stringResource(R.string.routing_metric_average_latency),
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
                label = stringResource(R.string.routing_metric_average_cost),
                value = formatCost(stats.averageActualCostUsd ?: stats.averageEstimatedCostUsd),
                modifier = Modifier.weight(1f)
            )
            RoutingMetric(
                label = stringResource(R.string.routing_metric_selection_rate),
                value = formatPercent(stats.selectionRate),
                modifier = Modifier.weight(1f)
            )
            RoutingMetric(
                label = stringResource(R.string.routing_metric_ab_samples),
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
                    text = stringResource(R.string.routing_latest_breakdown),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                stats.latestBreakdown?.let { RoutingBreakdownGrid(it) } ?: Text(
                    stringResource(R.string.routing_no_breakdown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                stats.lastFailureReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.routing_latest_failure, reason),
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
        BreakdownDisplay(BreakdownKind.PRICE, stringResource(R.string.routing_breakdown_price), breakdown.priceScore),
        BreakdownDisplay(BreakdownKind.SPEED, stringResource(R.string.routing_breakdown_speed), breakdown.speedScore),
        BreakdownDisplay(BreakdownKind.CAPABILITY, stringResource(R.string.routing_breakdown_capability), breakdown.capabilityBonus),
        BreakdownDisplay(BreakdownKind.PRIORITY, stringResource(R.string.routing_breakdown_priority), breakdown.priorityBonus),
        BreakdownDisplay(BreakdownKind.CONTEXT, stringResource(R.string.routing_breakdown_context), breakdown.contextBonus),
        BreakdownDisplay(BreakdownKind.FAILURE, stringResource(R.string.routing_breakdown_failure_penalty), breakdown.failurePenalty),
        BreakdownDisplay(BreakdownKind.NO_HISTORY, stringResource(R.string.routing_breakdown_no_history), breakdown.noHistoryPenalty)
    )
    items.chunked(4).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            row.forEach { item ->
                BreakdownChip(item, Modifier.weight(1f))
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
                text = stringResource(R.string.routing_group, stats.group),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.routing_sample_count, stats.sampleCount),
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
            RoutingMetric(stringResource(R.string.routing_metric_success_rate), formatPercent(stats.successRate), Modifier.weight(1f))
            RoutingMetric(stringResource(R.string.routing_metric_quality), formatPercent(stats.qualityRate), Modifier.weight(1f))
            RoutingMetric(stringResource(R.string.routing_metric_average_latency), formatAverageDuration(stats.averageDurationMs), Modifier.weight(1f))
            RoutingMetric(stringResource(R.string.routing_metric_average_cost), formatCost(stats.averageCostUsd), Modifier.weight(1f))
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
                    text = log.selectedModelName.ifBlank { stringResource(R.string.tokens_unknown_model) },
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
                label = stringResource(R.string.routing_estimated_cost),
                value = formatCost(log.estimatedCostUsd),
                modifier = Modifier.weight(1f)
            )
            log.actualCostUsd?.let { actual ->
                InfoChip(
                    label = stringResource(R.string.routing_actual_cost),
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
                        label = stringResource(R.string.routing_total_duration),
                        value = formatDuration(dur),
                        modifier = Modifier.weight(1f)
                    )
                } ?: Spacer(Modifier.weight(1f))
                log.actualTtftMs?.let { ttft ->
                    InfoChip(
                        label = stringResource(R.string.routing_first_token),
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
                    text = stringResource(R.string.routing_failure, log.failureReason.orEmpty()),
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
                    text = stringResource(R.string.routing_quality_score),
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
                    contentDescription = if (expanded) {
                        stringResource(R.string.chat_collapse)
                    } else {
                        stringResource(R.string.chat_expand)
                    },
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
        contentDescription = if (success) {
            stringResource(R.string.aiconfig_test_success)
        } else {
            stringResource(R.string.aiconfig_test_fail)
        },
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
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val parsed = remember(decisionJson, configuration) { parseDecision(decisionJson, context) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 选择原因
        parsed.reason.takeIf { it.isNotBlank() }?.let { reason ->
            Column {
                Text(
                    stringResource(R.string.routing_selection_reason),
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
                    stringResource(R.string.routing_request_context),
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
                    stringResource(R.string.routing_candidate_scores),
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
        BreakdownDisplay(BreakdownKind.PRICE, stringResource(R.string.routing_breakdown_price), c.priceScore),
        BreakdownDisplay(BreakdownKind.SPEED, stringResource(R.string.routing_breakdown_speed), c.speedScore),
        BreakdownDisplay(BreakdownKind.CAPABILITY, stringResource(R.string.routing_breakdown_capability), c.capabilityBonus),
        BreakdownDisplay(BreakdownKind.PRIORITY, stringResource(R.string.routing_breakdown_priority), c.priorityBonus),
        BreakdownDisplay(BreakdownKind.CONTEXT, stringResource(R.string.routing_breakdown_context), c.contextBonus),
        BreakdownDisplay(BreakdownKind.FAILURE, stringResource(R.string.routing_breakdown_failure_penalty), c.failurePenalty),
        BreakdownDisplay(BreakdownKind.NO_HISTORY, stringResource(R.string.routing_breakdown_no_history), c.noHistoryPenalty)
    )
    items.chunked(4).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            row.forEach { item ->
                BreakdownChip(item = item, modifier = Modifier.weight(1f))
            }
            if (row.size < 4) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun BreakdownChip(item: BreakdownDisplay, modifier: Modifier = Modifier) {
    val isPenalty = item.value > 0 && item.kind in setOf(BreakdownKind.FAILURE, BreakdownKind.NO_HISTORY)
    val color = when {
        item.value == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
        isPenalty -> MaterialTheme.colorScheme.error
        item.kind in setOf(BreakdownKind.PRICE, BreakdownKind.SPEED) -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            String.format(Locale.US, "%.1f", item.value),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private enum class BreakdownKind {
    PRICE, SPEED, CAPABILITY, PRIORITY, CONTEXT, FAILURE, NO_HISTORY
}

private data class BreakdownDisplay(
    val kind: BreakdownKind,
    val label: String,
    val value: Double
)

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
private fun parseDecision(json: String, context: Context): DecisionInfo {
    if (json.isBlank()) return DecisionInfo("", null, emptyList())
    return try {
        val obj = decisionGson.fromJson(json, JsonObject::class.java)
        val reason = obj?.get("reason")?.asString.orEmpty()

        // 请求上下文
        val requestInfo = obj?.getAsJsonObject("request")?.let { req ->
            buildList {
                req.get("estimatedContextTokens")?.let {
                    add(context.getString(R.string.routing_request_estimated_context, it.asInt))
                }
                req.get("promptChars")?.let {
                    add(context.getString(R.string.routing_request_prompt_chars, it.asInt))
                }
                req.get("sessionMode")?.let {
                    add(context.getString(R.string.routing_request_mode, it.asString))
                }
                req.get("hasAttachments")?.takeIf { it.asBoolean }?.let {
                    add(context.getString(R.string.routing_request_has_attachments))
                }
                req.get("isAgent")?.takeIf { it.asBoolean }?.let { add("Agent") }
                req.get("isComplex")?.takeIf { it.asBoolean }?.let {
                    add(context.getString(R.string.routing_request_complex))
                }
                req.get("dailyBudgetUsd")?.let {
                    add(
                        context.getString(
                            R.string.routing_request_daily_budget,
                            String.format(Locale.US, "%.2f", it.asDouble)
                        )
                    )
                }
                req.get("dailySpentUsd")?.let {
                    add(
                        context.getString(
                            R.string.routing_request_daily_spent,
                            String.format(Locale.US, "%.2f", it.asDouble)
                        )
                    )
                }
            }.joinToString(" · ").takeIf { it.isNotBlank() }
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
        DecisionInfo(
            reason = context.getString(R.string.routing_parse_failed, e.message.orEmpty()),
            requestInfo = null,
            candidates = emptyList()
        )
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
