package com.nekobot.app.ui.screens.sessions

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RecentActors
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.resolveAvatarUrl
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
internal fun SessionStatsDashboard(
    data: SessionStatsDashboardData,
    loading: Boolean,
    widgetOrder: List<String>,
    hiddenWidgets: Set<String>,
    characterRankingMode: CharacterRankingMode,
    onCharacterRankingModeChange: (CharacterRankingMode) -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleWidgets = remember(widgetOrder, hiddenWidgets) {
        widgetOrder.filterNot(hiddenWidgets::contains)
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = visibleWidgets,
                key = { "dashboard-widget-$it" },
                contentType = { it }
            ) { widget ->
                when (widget) {
                    "banner" -> DashboardBanner(data)
                    "overview" -> DashboardOverview(data)
                    "frequent_characters" -> DashboardFrequentCharacters(
                        characters = data.frequentCharacters,
                        rankingMode = characterRankingMode,
                        onRankingModeChange = onCharacterRankingModeChange
                    )
                    "heatmap" -> DashboardHeatmap(data.heatmap)
                    "trend" -> DashboardTrend(data.trend, data.todayTokens)
                    "session_ranking" -> DashboardRanking(
                        title = stringResource(R.string.stats_session_ranking_title),
                        icon = Icons.Filled.EmojiEvents,
                        items = data.sessionRanking,
                        valueIsTokens = data.totalTokens > 0
                    )
                    "model_ranking" -> DashboardRanking(
                        title = stringResource(R.string.stats_model_ranking_title),
                        icon = Icons.Filled.Memory,
                        items = data.modelRanking,
                        valueIsTokens = true
                    )
                    "channels" -> DashboardChannels(data.channels, data.totalSessions)
                }
            }
            if (visibleWidgets.isEmpty()) {
                item(key = "dashboard-empty", contentType = "empty") {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.DashboardCustomize,
                                contentDescription = null,
                                modifier = Modifier.size(38.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.stats_all_widgets_hidden))
                            TextButton(onClick = onCustomize) {
                                Text(stringResource(R.string.stats_customize_layout))
                            }
                        }
                    }
                }
            }
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(22.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun DashboardBanner(data: SessionStatsDashboardData) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.92f),
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
                .size(108.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.09f))
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.stats_banner_eyebrow),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.stats_banner_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashboardBannerChip(
                    icon = Icons.Filled.AutoGraph,
                    text = stringResource(R.string.stats_banner_tokens, formatCompactNumber(data.totalTokens))
                )
                DashboardBannerChip(
                    icon = Icons.Filled.LocalFireDepartment,
                    text = stringResource(R.string.stats_banner_streak, data.streakDays)
                )
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.stats_banner_today_usage),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                        Text(
                            formatCompactNumber(data.todayTokens),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(Modifier.width(1.dp).height(30.dp).background(Color.White.copy(alpha = 0.22f)))
                    Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(
                            stringResource(R.string.stats_banner_active_sessions),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                        Text(
                            data.activeSessions.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardBannerChip(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.16f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DashboardOverview(data: SessionStatsDashboardData) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        DashboardWidgetHeader(stringResource(R.string.stats_overview_title), Icons.Filled.ViewModule)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardStatTile(
                stringResource(R.string.stats_total_sessions), data.totalSessions.toString(), Icons.Filled.ChatBubble,
                Modifier.weight(1f)
            )
            DashboardStatTile(
                stringResource(R.string.stats_total_messages), formatCompactNumber(data.totalMessages), Icons.Filled.AutoGraph,
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashboardStatTile(
                stringResource(R.string.stats_active_sessions), data.activeSessions.toString(), Icons.Filled.CalendarMonth,
                Modifier.weight(1f)
            )
            DashboardStatTile(
                stringResource(R.string.stats_favorites), data.favorites.toString(), Icons.Filled.Favorite,
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DashboardFrequentCharacters(
    characters: List<DashboardCharacterItem>,
    rankingMode: CharacterRankingMode,
    onRankingModeChange: (CharacterRankingMode) -> Unit
) {
    val sortedCharacters = remember(characters, rankingMode) {
        sortDashboardCharacters(characters, rankingMode)
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DashboardWidgetHeader(
                stringResource(R.string.stats_frequent_characters_title),
                Icons.Filled.RecentActors,
                Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.stats_character_swipe_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (sortedCharacters.isEmpty()) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                DashboardEmptyData()
            }
        } else {
            var selectedIndex by rememberSaveable(rankingMode) { mutableIntStateOf(0) }
            val currentIndex = selectedIndex.coerceIn(sortedCharacters.indices)
            LaunchedEffect(sortedCharacters.size) {
                if (selectedIndex != currentIndex) selectedIndex = currentIndex
            }
            val currentCharacter = sortedCharacters[currentIndex]
            val maxValue = sortedCharacters.maxOf {
                if (rankingMode == CharacterRankingMode.SESSIONS) it.sessionCount.toLong() else it.chatTokens
            }.coerceAtLeast(1L)
            val currentValue = if (rankingMode == CharacterRankingMode.SESSIONS) {
                currentCharacter.sessionCount.toLong()
            } else {
                currentCharacter.chatTokens
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(start = 10.dp, top = 6.dp, end = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardCharacterPortraitStack(
                    characters = sortedCharacters,
                    currentIndex = currentIndex,
                    onCurrentIndexChange = { selectedIndex = it },
                    modifier = Modifier.weight(0.47f).fillMaxHeight(),
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(0.53f).fillMaxHeight()
                ) {
                    DashboardCharacterRankingToggle(
                        rankingMode = rankingMode,
                        onRankingModeChange = onRankingModeChange
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                        ) {
                            Text(
                                stringResource(R.string.stats_character_rank, currentIndex + 1),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${currentIndex + 1}/${sortedCharacters.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        currentCharacter.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardCharacterMetric(
                            label = stringResource(R.string.stats_character_sessions_label),
                            value = currentCharacter.sessionCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        DashboardCharacterMetric(
                            label = stringResource(R.string.stats_character_tokens_label),
                            value = formatCompactNumber(currentCharacter.chatTokens),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((currentValue.toFloat() / maxValue.toFloat()).coerceIn(0.04f, 1f))
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        stringResource(
                            R.string.stats_character_average,
                            formatCompactNumber(
                                if (currentCharacter.sessionCount > 0) {
                                    currentCharacter.chatTokens / currentCharacter.sessionCount
                                } else {
                                    0L
                                }
                            )
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardCharacterPortraitStack(
    characters: List<DashboardCharacterItem>,
    currentIndex: Int,
    onCurrentIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 42.dp.toPx() }
    val dismissDistance = with(density) { 190.dp.toPx() }
    val velocityThreshold = with(density) { 700.dp.toPx() }
    var dragOffset by remember(currentIndex, characters.size) { mutableFloatStateOf(0f) }
    var dragDirection by remember(currentIndex, characters.size) { mutableIntStateOf(1) }
    var transitionRunning by remember(currentIndex, characters.size) { mutableStateOf(false) }
    val draggableState = rememberDraggableState { delta ->
        if (!transitionRunning) {
            val nextOffset = (dragOffset + delta).coerceIn(-dismissDistance, dismissDistance)
            if (nextOffset < 0f) dragDirection = 1
            if (nextOffset > 0f) dragDirection = -1
            dragOffset = nextOffset
        }
    }
    val current = characters[currentIndex]
    val previous = characters[(currentIndex - 1 + characters.size) % characters.size]
    val next = characters[(currentIndex + 1) % characters.size]
    val movingForward = dragDirection > 0
    val nearCharacter = if (movingForward) next else previous
    val farCharacter = if (movingForward) previous else next
    val direction = if (movingForward) 1f else -1f
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        DashboardCharacterPortrait(
            character = farCharacter,
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .height(212.dp)
                .graphicsLayer {
                    translationX = -direction * 13.dp.toPx()
                    rotationZ = -direction * 5f
                    scaleX = 0.82f
                    scaleY = 0.82f
                    alpha = 0.55f
                },
            elevation = 4.dp
        )
        DashboardCharacterPortrait(
            character = nearCharacter,
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .height(212.dp)
                .graphicsLayer {
                    val revealFraction =
                        (dragOffset.absoluteValue / dismissDistance).coerceIn(0f, 1f)
                    translationX = direction * 13.dp.toPx() * (1f - revealFraction)
                    rotationZ = direction * 5f * (1f - revealFraction)
                    scaleX = 0.87f + revealFraction * 0.13f
                    scaleY = 0.87f + revealFraction * 0.13f
                    alpha = 0.68f + revealFraction * 0.32f
                },
            elevation = 6.dp
        )
        DashboardCharacterPortrait(
            character = current,
            modifier = Modifier
                .fillMaxWidth(0.76f)
                .height(212.dp)
                .offset { IntOffset(dragOffset.roundToInt(), 0) }
                .graphicsLayer {
                    rotationZ = (dragOffset / dismissDistance) * 9f
                    alpha = 1f - (dragOffset.absoluteValue / dismissDistance).coerceIn(0f, 1f) * 0.28f
                }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    enabled = characters.size > 1 && !transitionRunning,
                    onDragStopped = { velocity ->
                        val switchDirection = when {
                            velocity <= -velocityThreshold || dragOffset <= -dismissThreshold -> 1
                            velocity >= velocityThreshold || dragOffset >= dismissThreshold -> -1
                            else -> 0
                        }
                        if (switchDirection == 0) {
                            animate(
                                initialValue = dragOffset,
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 150)
                            ) { value, _ -> dragOffset = value }
                        } else {
                            transitionRunning = true
                            animate(
                                initialValue = dragOffset,
                                targetValue = if (switchDirection > 0) -dismissDistance else dismissDistance,
                                animationSpec = tween(durationMillis = 180)
                            ) { value, _ -> dragOffset = value }
                            onCurrentIndexChange(
                                (currentIndex + switchDirection + characters.size) % characters.size
                            )
                            dragOffset = 0f
                            transitionRunning = false
                        }
                    }
                ),
            elevation = 14.dp
        )
    }
}

@Composable
private fun DashboardCharacterPortrait(
    character: DashboardCharacterItem,
    modifier: Modifier = Modifier,
    elevation: androidx.compose.ui.unit.Dp
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.16f),
                spotColor = Color.Black.copy(alpha = 0.20f)
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val portrait = resolveAvatarUrl(character.portraitUrl)
        if (portrait != null) {
            AsyncImage(
                model = portrait,
                contentDescription = character.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.50f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.RecentActors,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color.White.copy(alpha = 0.88f)
                )
            }
        }
    }
}

@Composable
private fun DashboardCharacterRankingToggle(
    rankingMode: CharacterRankingMode,
    onRankingModeChange: (CharacterRankingMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(3.dp)
    ) {
        DashboardCharacterRankingOption(
            text = stringResource(R.string.stats_sort_by_sessions),
            selected = rankingMode == CharacterRankingMode.SESSIONS,
            onClick = { onRankingModeChange(CharacterRankingMode.SESSIONS) },
            modifier = Modifier.weight(1f)
        )
        DashboardCharacterRankingOption(
            text = stringResource(R.string.stats_sort_by_tokens),
            selected = rankingMode == CharacterRankingMode.TOKENS,
            onClick = { onRankingModeChange(CharacterRankingMode.TOKENS) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DashboardCharacterRankingOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 2.dp else 0.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DashboardCharacterMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
}

@Composable
private fun DashboardStatTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardHeatmap(days: List<DashboardActivityDay>) {
    val max = days.maxOfOrNull(DashboardActivityDay::activity)?.coerceAtLeast(1L) ?: 1L
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DashboardWidgetHeader(stringResource(R.string.stats_heatmap_title), Icons.Filled.CalendarMonth, Modifier.weight(1f))
            Text(stringResource(R.string.stats_last_twelve_weeks), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            days.chunked(7).forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { day ->
                        val fraction = (day.activity.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                        val color = if (day.activity == 0L) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.24f + 0.76f * fraction)
                        }
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.stats_less), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(5.dp))
            listOf(0.12f, 0.35f, 0.58f, 0.82f, 1f).forEach { alpha ->
                Box(Modifier.padding(horizontal = 2.dp).size(9.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
            }
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.stats_more), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardTrend(days: List<DashboardActivityDay>, todayTokens: Long) {
    val useTokens = days.any { it.tokens > 0L }
    val max = days.maxOfOrNull { if (useTokens) it.tokens else it.activity }?.coerceAtLeast(1L) ?: 1L
    val formatter = remember { DateTimeFormatter.ofPattern("E", Locale.getDefault()) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DashboardWidgetHeader(stringResource(R.string.stats_trend_title), Icons.Filled.AutoGraph, Modifier.weight(1f))
            Text(
                stringResource(R.string.stats_today_tokens, formatCompactNumber(todayTokens)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(142.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            days.forEach { day ->
                val value = if (useTokens) day.tokens else day.activity
                val barHeight = (16f + 82f * (value.toFloat() / max.toFloat())).dp
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatCompactNumber(value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f))
                                )
                            )
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(day.date.format(formatter), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DashboardRanking(title: String, icon: ImageVector, items: List<DashboardRankItem>, valueIsTokens: Boolean) {
    val max = items.maxOfOrNull(DashboardRankItem::value)?.coerceAtLeast(1L) ?: 1L
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        DashboardWidgetHeader(title, icon)
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            DashboardEmptyData()
        } else {
            items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Surface(
                        modifier = Modifier.size(28.dp),
                        shape = CircleShape,
                        color = when (index) {
                            0 -> Color(0xFFFFC857).copy(alpha = 0.22f)
                            1 -> Color(0xFFB8C4D6).copy(alpha = 0.25f)
                            2 -> Color(0xFFD99A6C).copy(alpha = 0.22f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (valueIsTokens) formatCompactNumber(item.value) else item.value.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(
                                Modifier
                                    .fillMaxWidth((item.value.toFloat() / max.toFloat()).coerceIn(0.04f, 1f))
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardChannels(channels: List<DashboardChannelItem>, total: Int) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        DashboardWidgetHeader(stringResource(R.string.stats_channels_title), Icons.Filled.ViewModule)
        Spacer(Modifier.height(12.dp))
        if (channels.isEmpty()) {
            DashboardEmptyData()
        } else {
            channels.forEachIndexed { index, channel ->
                val fraction = if (total > 0) channel.count.toFloat() / total else 0f
                Row(modifier = Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary,
                                    MaterialTheme.colorScheme.tertiary,
                                    MaterialTheme.colorScheme.error
                                )[index % 4]
                            )
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(channel.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${channel.count} · ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DashboardWidgetHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(6.dp).size(18.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DashboardEmptyData() {
    Text(
        stringResource(R.string.stats_empty_data),
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
internal fun DashboardLayoutDialog(
    order: List<String>,
    hidden: Set<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>, Set<String>) -> Unit
) {
    val currentOrder = remember(order) {
        mutableStateListOf<String>().apply {
            addAll(order + PrefsManager.DEFAULT_STATS_DASHBOARD_WIDGET_ORDER.filterNot(order::contains))
        }
    }
    val hiddenState = remember(hidden) {
        mutableStateMapOf<String, Boolean>().apply {
            PrefsManager.DEFAULT_STATS_DASHBOARD_WIDGET_ORDER.forEach { put(it, it in hidden) }
        }
    }
    val descriptors = dashboardWidgetDescriptors()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_layout_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.stats_layout_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(currentOrder, key = { it }) { id ->
                        val descriptor = descriptors.getValue(id)
                        val index = currentOrder.indexOf(id)
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(descriptor.second, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(descriptor.first, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            currentOrder.removeAt(index)
                                            currentOrder.add(index - 1, id)
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.stats_move_up), modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = {
                                        if (index < currentOrder.lastIndex) {
                                            currentOrder.removeAt(index)
                                            currentOrder.add(index + 1, id)
                                        }
                                    },
                                    enabled = index < currentOrder.lastIndex,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.stats_move_down), modifier = Modifier.size(18.dp))
                                }
                                Switch(
                                    checked = hiddenState[id] != true,
                                    onCheckedChange = { visible -> hiddenState[id] = !visible },
                                    modifier = Modifier.size(width = 46.dp, height = 30.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(currentOrder.toList(), hiddenState.filterValues { it }.keys)
                }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        currentOrder.clear()
                        currentOrder.addAll(PrefsManager.DEFAULT_STATS_DASHBOARD_WIDGET_ORDER)
                        hiddenState.keys.toList().forEach { hiddenState[it] = false }
                    }
                ) { Text(stringResource(R.string.stats_reset_layout)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
}

@Composable
private fun dashboardWidgetDescriptors(): Map<String, Pair<String, ImageVector>> = linkedMapOf(
    "banner" to (stringResource(R.string.stats_widget_banner) to Icons.Filled.Bolt),
    "overview" to (stringResource(R.string.stats_overview_title) to Icons.Filled.ViewModule),
    "frequent_characters" to (stringResource(R.string.stats_frequent_characters_title) to Icons.Filled.RecentActors),
    "heatmap" to (stringResource(R.string.stats_heatmap_title) to Icons.Filled.CalendarMonth),
    "trend" to (stringResource(R.string.stats_trend_title) to Icons.Filled.AutoGraph),
    "session_ranking" to (stringResource(R.string.stats_session_ranking_title) to Icons.Filled.EmojiEvents),
    "model_ranking" to (stringResource(R.string.stats_model_ranking_title) to Icons.Filled.Memory),
    "channels" to (stringResource(R.string.stats_channels_title) to Icons.Filled.ViewModule)
)

internal fun formatCompactNumber(value: Long): String = when {
    value >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
