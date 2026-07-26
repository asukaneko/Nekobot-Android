package com.nekobot.app.ui.screens.extensions

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AchievementManager
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.AchievementBadge
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.achievementCategoryLabel
import com.nekobot.app.ui.components.achievementDescription
import com.nekobot.app.ui.components.achievementTierColor
import com.nekobot.app.ui.components.achievementTierLabel
import com.nekobot.app.ui.components.achievementTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

private enum class AchievementStatusFilter {
    ALL, UNLOCKED, IN_PROGRESS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    onBack: () -> Unit
) {
    val viewModel: AchievementsViewModel = viewModel()
    val snapshots by viewModel.snapshots.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsState()
    val gridState = rememberLazyGridState()
    var statusFilter by remember { mutableStateOf(AchievementStatusFilter.ALL) }
    var metricFilter by remember { mutableStateOf<AchievementManager.Target.Metric?>(null) }

    LaunchedEffect(appMode, dataSourceRevision) {
        viewModel.refresh()
    }

    val visibleAchievements = remember(snapshots, statusFilter, metricFilter) {
        snapshots.filter { snapshot ->
            val matchesStatus = when (statusFilter) {
                AchievementStatusFilter.ALL -> true
                AchievementStatusFilter.UNLOCKED -> snapshot.isUnlocked
                AchievementStatusFilter.IN_PROGRESS -> !snapshot.isUnlocked
            }
            val matchesMetric = metricFilter == null ||
                AchievementManager.targetFor(snapshot.id)?.metric == metricFilter
            matchesStatus && matchesMetric
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.achievements_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !loading) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.achievements_refresh)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AchievementOverview(snapshots)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AchievementFilters(
                    statusFilter = statusFilter,
                    onStatusFilterChange = { statusFilter = it },
                    metricFilter = metricFilter,
                    onMetricFilterChange = { metricFilter = it }
                )
            }
            error?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ErrorBanner(message = message, onRetry = viewModel::refresh)
                }
            }
            if (visibleAchievements.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.achievements_filter_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(visibleAchievements, key = { it.id }) { snapshot ->
                    AchievementGridCard(snapshot)
                }
            }
        }
    }
}

@Composable
private fun AchievementOverview(snapshots: List<AchievementManager.Snapshot>) {
    val unlocked = snapshots.count(AchievementManager.Snapshot::isUnlocked)
    val total = snapshots.size
    val completion = if (total == 0) 0f else unlocked.toFloat() / total

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.achievements_overview_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.achievements_overview_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "$unlocked / $total",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { completion },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(7.dp))
        Text(
            stringResource(R.string.achievements_completion, (completion * 100).toInt()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AchievementFilters(
    statusFilter: AchievementStatusFilter,
    onStatusFilterChange: (AchievementStatusFilter) -> Unit,
    metricFilter: AchievementManager.Target.Metric?,
    onMetricFilterChange: (AchievementManager.Target.Metric?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AchievementStatusFilter.entries.forEach { filter ->
                FilterChip(
                    selected = statusFilter == filter,
                    onClick = { onStatusFilterChange(filter) },
                    label = {
                        Text(
                            stringResource(
                                when (filter) {
                                    AchievementStatusFilter.ALL -> R.string.achievements_filter_all
                                    AchievementStatusFilter.UNLOCKED -> R.string.achievements_filter_unlocked
                                    AchievementStatusFilter.IN_PROGRESS -> R.string.achievements_filter_in_progress
                                }
                            )
                        )
                    }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = metricFilter == null,
                onClick = { onMetricFilterChange(null) },
                label = { Text(stringResource(R.string.achievements_category_all)) }
            )
            AchievementManager.Target.Metric.entries.forEach { metric ->
                FilterChip(
                    selected = metricFilter == metric,
                    onClick = { onMetricFilterChange(metric) },
                    label = { Text(achievementCategoryLabel(metric)) }
                )
            }
        }
    }
}

@Composable
private fun AchievementGridCard(snapshot: AchievementManager.Snapshot) {
    val target = AchievementManager.targetFor(snapshot.id) ?: return
    val tierColor = achievementTierColor(target.tier)
    val numberFormat = NumberFormat.getIntegerInstance()

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 292.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        containerColor = if (snapshot.isUnlocked) {
            tierColor.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        },
        borderColor = if (snapshot.isUnlocked) {
            tierColor.copy(alpha = 0.48f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = achievementTierLabel(target.tier),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tierColor
            )
        }
        AchievementBadge(
            achievementId = snapshot.id,
            unlocked = snapshot.isUnlocked,
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            achievementTitle(snapshot.id),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (snapshot.isUnlocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            achievementDescription(target),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 2
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { snapshot.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = tierColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${numberFormat.format(snapshot.current.coerceAtMost(snapshot.target))} / " +
                    numberFormat.format(snapshot.target),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snapshot.isUnlocked) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.achievements_unlocked),
                    tint = tierColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        snapshot.unlockedAt?.let { timestamp ->
            Spacer(Modifier.height(5.dp))
            Text(
                stringResource(
                    R.string.achievements_unlocked_at,
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
                ),
                style = MaterialTheme.typography.labelSmall,
                color = tierColor,
                maxLines = 1
            )
        }
    }
}

class AchievementsViewModel : BaseViewModel() {
    private var refreshVersion = 0L
    private val _snapshots = MutableStateFlow(
        AchievementManager.getSnapshots(
            totalTokens = 0,
            totalMessages = 0,
            totalSessions = 0,
            highAffectionCharacterCount = 0
        )
    )
    val snapshots: StateFlow<List<AchievementManager.Snapshot>> = _snapshots.asStateFlow()

    fun refresh() {
        val requestVersion = ++refreshVersion
        viewModelScope.launch {
            setLoading(true)
            clearError()
            try {
                val (sessionsResult, tokenStatsResult, highAffectionCountResult) = coroutineScope {
                    val sessions = async { unified.listSessions() }
                    val tokenStats = async { unified.tokenStats(dateRange = "total") }
                    val highAffectionCount = async {
                        unified.countHighAffectionCharacters(threshold = 90)
                    }
                    Triple(sessions.await(), tokenStats.await(), highAffectionCount.await())
                }

                val sessions = (sessionsResult as? Resource.Success)?.data.orEmpty()
                    .filterNot { it.isArchive == true }
                val tokenStats = (tokenStatsResult as? Resource.Success)?.data
                val highAffectionCharacterCount =
                    (highAffectionCountResult as? Resource.Success)?.data ?: 0

                val totalMessages = sessions.sumOf { (it.messageCount ?: 0).toLong() }

                if (requestVersion != refreshVersion) return@launch
                _snapshots.value = AchievementManager.getSnapshots(
                    totalTokens = tokenStats?.totalDisplay ?: 0L,
                    totalMessages = totalMessages,
                    totalSessions = sessions.size,
                    highAffectionCharacterCount = highAffectionCharacterCount
                )
            } catch (error: Exception) {
                if (requestVersion == refreshVersion) {
                    showError(error.message ?: string(R.string.common_unknown_error))
                }
            } finally {
                if (requestVersion == refreshVersion) {
                    setLoading(false)
                }
            }
        }
    }
}
