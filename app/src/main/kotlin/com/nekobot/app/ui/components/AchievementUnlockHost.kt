package com.nekobot.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AchievementManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AchievementUnlockHost() {
    val eventQueue = remember { Channel<AchievementManager.UnlockEvent>(Channel.UNLIMITED) }
    val dismissSignals = remember { Channel<Unit>(Channel.CONFLATED) }
    var currentEvent by remember { mutableStateOf<AchievementManager.UnlockEvent?>(null) }
    val dataSourceRevision by ServiceContainer.dataSourceRevision.collectAsState()

    LaunchedEffect(Unit) {
        AchievementManager.unlockEvents.collect { event ->
            if (AchievementManager.isScopeCurrent(event.scopeId)) {
                eventQueue.send(event)
            }
        }
    }
    LaunchedEffect(dataSourceRevision) {
        while (eventQueue.tryReceive().isSuccess) {
            // 数据库或模式切换后丢弃旧数据源尚未展示的弹窗。
        }
        currentEvent = null
        dismissSignals.trySend(Unit)
    }
    LaunchedEffect(Unit) {
        for (event in eventQueue) {
            if (!AchievementManager.isScopeCurrent(event.scopeId)) continue
            while (dismissSignals.tryReceive().isSuccess) {
                // 清除上一个弹窗遗留的关闭信号。
            }
            currentEvent = event
            withTimeoutOrNull(4_800L) {
                dismissSignals.receive()
            }
            currentEvent = null
            delay(240L)
        }
    }

    currentEvent?.let { event ->
        AchievementUnlockDialog(
            event = event,
            onDismiss = { dismissSignals.trySend(Unit) }
        )
    }
}

@Composable
private fun AchievementUnlockDialog(
    event: AchievementManager.UnlockEvent,
    onDismiss: () -> Unit
) {
    val target = AchievementManager.targetFor(event.id) ?: return
    val tierColor = achievementTierColor(event.tier)
    val entryScale = remember(event.id, event.unlockedAt) { Animatable(0.62f) }
    val entryAlpha = remember(event.id, event.unlockedAt) { Animatable(0f) }
    val infiniteTransition = rememberInfiniteTransition(label = "achievement-celebration")
    val badgePulse by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "achievement-badge-pulse"
    )

    LaunchedEffect(event.id, event.unlockedAt) {
        entryAlpha.animateTo(1f, tween(180))
    }
    LaunchedEffect(event.id, event.unlockedAt) {
        entryScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.66f, stiffness = 360f)
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AchievementBurst(
                eventKey = "${event.id}-${event.unlockedAt}",
                tierColor = tierColor,
                modifier = Modifier.size(390.dp)
            )
            GlassCard(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .graphicsLayer {
                        scaleX = entryScale.value
                        scaleY = entryScale.value
                        alpha = entryAlpha.value
                    }
                    .clickable(enabled = false) {},
                cornerRadius = 28,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                borderWidth = 2,
                borderColor = tierColor.copy(alpha = 0.82f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 24.dp,
                    vertical = 28.dp
                )
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = tierColor,
                    modifier = Modifier
                        .size(26.dp)
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.achievement_unlocked_title),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = tierColor,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(18.dp))
                AchievementBadge(
                    achievementId = event.id,
                    unlocked = true,
                    modifier = Modifier
                        .size(136.dp)
                        .align(Alignment.CenterHorizontally)
                        .graphicsLayer {
                            scaleX = badgePulse
                            scaleY = badgePulse
                        }
                )
                Spacer(Modifier.size(18.dp))
                Text(
                    text = achievementTierLabel(event.tier),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelMedium,
                    color = tierColor,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = achievementTitle(event.id),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = achievementDescription(target),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(22.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.achievement_unlocked_continue))
                }
            }
        }
    }
}

@Composable
private fun AchievementBurst(
    eventKey: String,
    tierColor: Color,
    modifier: Modifier = Modifier
) {
    val burstProgress = remember(eventKey) { Animatable(0f) }
    val infiniteTransition = rememberInfiniteTransition(label = "achievement-sparkle")
    val shimmer by infiniteTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "achievement-sparkle-alpha"
    )
    val colors = remember(tierColor) {
        listOf(
            tierColor,
            Color.White,
            Color(0xFFFFD166),
            Color(0xFF72E6FF),
            Color(0xFFFF8DC7)
        )
    }

    LaunchedEffect(eventKey) {
        burstProgress.snapTo(0f)
        burstProgress.animateTo(1f, tween(1_650, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier) {
        val center = center
        val progress = burstProgress.value
        repeat(32) { index ->
            val angle = (2.0 * PI * index / 32.0).toFloat()
            val startRadius = size.minDimension * (0.15f + (index % 4) * 0.012f)
            val travel = size.minDimension * (0.21f + (index % 5) * 0.015f)
            val easedDistance = startRadius + travel * progress
            val particleCenter = androidx.compose.ui.geometry.Offset(
                x = center.x + cos(angle) * easedDistance,
                y = center.y + sin(angle) * easedDistance
            )
            val alpha = ((1f - progress) * 0.78f + shimmer * 0.22f)
                .coerceIn(0f, 1f)
            drawCircle(
                color = colors[index % colors.size].copy(alpha = alpha),
                radius = (2.5f + index % 4) * (0.65f + (1f - progress) * 0.35f),
                center = particleCenter
            )
        }
        repeat(12) { index ->
            val angle = (2.0 * PI * index / 12.0).toFloat() + 0.16f
            val inner = size.minDimension * 0.26f
            val outer = size.minDimension * (0.32f + shimmer * 0.035f)
            drawLine(
                color = colors[index % colors.size].copy(alpha = 0.26f * shimmer),
                start = androidx.compose.ui.geometry.Offset(
                    center.x + cos(angle) * inner,
                    center.y + sin(angle) * inner
                ),
                end = androidx.compose.ui.geometry.Offset(
                    center.x + cos(angle) * outer,
                    center.y + sin(angle) * outer
                ),
                strokeWidth = 2.2f
            )
        }
    }
}
