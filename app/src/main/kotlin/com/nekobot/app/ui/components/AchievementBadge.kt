package com.nekobot.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.AchievementManager

@Composable
fun AchievementBadge(
    achievementId: String,
    unlocked: Boolean,
    modifier: Modifier = Modifier.size(72.dp)
) {
    val context = LocalContext.current
    val atlas = ImageBitmap.imageResource(context.resources, R.drawable.achievement_badges_atlas)
    val target = AchievementManager.targetFor(achievementId)
    val categoryIndex = when (target?.metric) {
        AchievementManager.Target.Metric.TOKENS -> 0
        AchievementManager.Target.Metric.MESSAGES -> 1
        AchievementManager.Target.Metric.SESSIONS -> 2
        AchievementManager.Target.Metric.HIGH_AFFECTION_CHARACTERS -> 3
        null -> 0
    }
    val tileWidth = atlas.width / 2
    val tileHeight = atlas.height / 2
    val sourceOffset = IntOffset(
        x = (categoryIndex % 2) * tileWidth,
        y = (categoryIndex / 2) * tileHeight
    )
    val tierColor = achievementTierColor(target?.tier)

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF171923))
            .border(2.dp, tierColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        ) {
            drawImage(
                image = atlas,
                srcOffset = sourceOffset,
                srcSize = IntSize(tileWidth, tileHeight),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                alpha = if (unlocked) 1f else 0.34f
            )
            if (!unlocked) {
                drawCircle(Color.Black.copy(alpha = 0.48f))
            }
        }
        if (!unlocked) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier
                    .fillMaxSize(0.34f)
            )
        }
    }
}

fun achievementTierColor(tier: AchievementManager.Target.Tier?): Color = when (tier) {
    AchievementManager.Target.Tier.BRONZE -> Color(0xFFBD7953)
    AchievementManager.Target.Tier.SILVER -> Color(0xFFC8D1DD)
    AchievementManager.Target.Tier.GOLD -> Color(0xFFFFC857)
    AchievementManager.Target.Tier.PLATINUM -> Color(0xFF72DED7)
    AchievementManager.Target.Tier.DIAMOND -> Color(0xFFB497FF)
    null -> Color(0xFF9BA3B2)
}

@StringRes
fun achievementTitleRes(id: String): Int = when (id) {
    AchievementManager.Id.TOKEN_1000 -> R.string.achievement_token_1000
    AchievementManager.Id.TOKEN_10000 -> R.string.achievement_token_10000
    AchievementManager.Id.TOKEN_100000 -> R.string.achievement_token_100000
    AchievementManager.Id.TOKEN_1000000 -> R.string.achievement_token_1000000
    AchievementManager.Id.TOKEN_10000000 -> R.string.achievement_token_10000000
    AchievementManager.Id.MESSAGES_10 -> R.string.achievement_messages_10
    AchievementManager.Id.MESSAGES_100 -> R.string.achievement_messages_100
    AchievementManager.Id.MESSAGES_1000 -> R.string.achievement_messages_1000
    AchievementManager.Id.MESSAGES_5000 -> R.string.achievement_messages_5000
    AchievementManager.Id.MESSAGES_10000 -> R.string.achievement_messages_10000
    AchievementManager.Id.SESSIONS_1 -> R.string.achievement_sessions_1
    AchievementManager.Id.SESSIONS_10 -> R.string.achievement_sessions_10
    AchievementManager.Id.SESSIONS_50 -> R.string.achievement_sessions_50
    AchievementManager.Id.SESSIONS_100 -> R.string.achievement_sessions_100
    AchievementManager.Id.SESSIONS_500 -> R.string.achievement_sessions_500
    AchievementManager.Id.FIRST_AFFECTION_100 -> R.string.achievement_first_affection_100
    AchievementManager.Id.HIGH_AFFECTION_CHARACTERS_3 -> R.string.achievement_high_affection_characters_3
    AchievementManager.Id.HIGH_AFFECTION_CHARACTERS_5 -> R.string.achievement_high_affection_characters_5
    AchievementManager.Id.HIGH_AFFECTION_CHARACTERS_10 -> R.string.achievement_high_affection_characters_10
    AchievementManager.Id.HIGH_AFFECTION_CHARACTERS_20 -> R.string.achievement_high_affection_characters_20
    else -> R.string.achievements_unknown
}

@Composable
fun achievementTitle(id: String): String = stringResource(achievementTitleRes(id))

@Composable
fun achievementDescription(target: AchievementManager.Target): String {
    val formattedTarget = java.text.NumberFormat.getIntegerInstance().format(target.target)
    return when (target.metric) {
        AchievementManager.Target.Metric.TOKENS ->
            stringResource(R.string.achievement_requirement_tokens, formattedTarget)
        AchievementManager.Target.Metric.MESSAGES ->
            stringResource(R.string.achievement_requirement_messages, formattedTarget)
        AchievementManager.Target.Metric.SESSIONS ->
            stringResource(R.string.achievement_requirement_sessions, formattedTarget)
        AchievementManager.Target.Metric.HIGH_AFFECTION_CHARACTERS ->
            stringResource(R.string.achievement_requirement_high_affection_characters, formattedTarget)
    }
}

@Composable
fun achievementTierLabel(tier: AchievementManager.Target.Tier): String = stringResource(
    when (tier) {
        AchievementManager.Target.Tier.BRONZE -> R.string.achievement_tier_bronze
        AchievementManager.Target.Tier.SILVER -> R.string.achievement_tier_silver
        AchievementManager.Target.Tier.GOLD -> R.string.achievement_tier_gold
        AchievementManager.Target.Tier.PLATINUM -> R.string.achievement_tier_platinum
        AchievementManager.Target.Tier.DIAMOND -> R.string.achievement_tier_diamond
    }
)

@Composable
fun achievementCategoryLabel(metric: AchievementManager.Target.Metric): String = stringResource(
    when (metric) {
        AchievementManager.Target.Metric.TOKENS -> R.string.achievement_category_tokens
        AchievementManager.Target.Metric.MESSAGES -> R.string.achievement_category_messages
        AchievementManager.Target.Metric.SESSIONS -> R.string.achievement_category_sessions
        AchievementManager.Target.Metric.HIGH_AFFECTION_CHARACTERS -> R.string.achievement_category_affection
    }
)
