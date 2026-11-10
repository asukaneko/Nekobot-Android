package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.RelationshipBackground
import com.nekobot.app.ui.theme.isNekoDark

/** Appearance selection owns this palette; the application's theme never selects a journal skin. */
internal data class JournalSkin(
    val scene: RelationshipBackground,
    val accent: Color,
    val paper: Color,
    val ink: Color,
    val metal: Color,
    val atlas: Int,
) {
    val muted: Color get() = lerp(ink, paper, .34f)
    val rim: Color get() = lerp(accent, paper, .52f)
    val buttonShape: Shape get() = when (scene) {
        RelationshipBackground.SAKURA -> RoundedCornerShape(15.dp)
        RelationshipBackground.STARLIGHT -> RoundedCornerShape(topStart = 21.dp, topEnd = 9.dp, bottomEnd = 21.dp, bottomStart = 9.dp)
        RelationshipBackground.COAST -> RoundedCornerShape(12.dp)
        RelationshipBackground.WATER_GARDEN -> RoundedCornerShape(25.dp)
        RelationshipBackground.WOODLAND -> RoundedCornerShape(10.dp)
        RelationshipBackground.SUNSET -> CutCornerShape(9.dp)
        RelationshipBackground.LANTERN -> RoundedCornerShape(8.dp)
    }
}

internal fun journalSkin(scene: RelationshipBackground, dark: Boolean): JournalSkin {
    val palette = when (scene) {
        RelationshipBackground.SAKURA -> Triple(Color(0xFFC15D83), Color(0xFFFFF2F5), R.drawable.relationship_icons_sakura)
        RelationshipBackground.STARLIGHT -> Triple(Color(0xFF7655BC), Color(0xFFF4EFFF), R.drawable.relationship_icons_starlight)
        RelationshipBackground.COAST -> Triple(Color(0xFF327CBC), Color(0xFFF4F9FF), R.drawable.relationship_icons_coast)
        RelationshipBackground.WATER_GARDEN -> Triple(Color(0xFF247E7D), Color(0xFFEDF9F5), R.drawable.relationship_icons_watergarden)
        RelationshipBackground.WOODLAND -> Triple(Color(0xFF4F7356), Color(0xFFF3F5EC), R.drawable.relationship_icons_woodland)
        RelationshipBackground.SUNSET -> Triple(Color(0xFFB87529), Color(0xFFFFF5E6), R.drawable.relationship_icons_sunset)
        RelationshipBackground.LANTERN -> Triple(Color(0xFFA7484C), Color(0xFFFFEFEB), R.drawable.relationship_icons_lantern)
    }
    return JournalSkin(scene, palette.first,
        if (dark) lerp(Color(0xFF161B23), palette.first, .17f) else palette.second,
        if (dark) Color(0xFFF2F0F7) else Color(0xFF232534),
        if (scene == RelationshipBackground.STARLIGHT) Color(0xFFAD9CCC) else Color(0xFFAD8648), palette.third)
}

internal val LocalJournalSkin = staticCompositionLocalOf { journalSkin(RelationshipBackground.COAST, false) }

@Composable
internal fun RelationshipSkinTheme(background: RelationshipBackground, content: @Composable () -> Unit) {
    val dark = isNekoDark()
    val skin = remember(background, dark) { journalSkin(background, dark) }
    val colors = MaterialTheme.colorScheme.copy(
        primary = skin.accent, onPrimary = readableOn(skin.accent),
        primaryContainer = lerp(skin.paper, skin.accent, .14f), onPrimaryContainer = skin.ink,
        secondary = skin.accent, onSecondary = readableOn(skin.accent),
        secondaryContainer = lerp(skin.paper, skin.accent, .10f), onSecondaryContainer = skin.ink,
        surface = skin.paper, onSurface = skin.ink, onSurfaceVariant = skin.muted,
        background = skin.paper, onBackground = skin.ink, surfaceVariant = lerp(skin.paper, skin.accent, .05f),
        surfaceContainerLowest = skin.paper, surfaceContainerLow = skin.paper,
        surfaceContainer = lerp(skin.paper, skin.accent, .03f),
        surfaceContainerHigh = lerp(skin.paper, skin.accent, .05f),
        surfaceContainerHighest = lerp(skin.paper, skin.accent, .08f),
        outline = skin.rim, outlineVariant = skin.rim.copy(alpha = .45f),
    )
    CompositionLocalProvider(LocalJournalSkin provides skin) {
        MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
    }
}

/** Cached fine engraving lives on the rim, away from the text and its touch target. */
internal fun Modifier.journalFinish(skin: JournalSkin, selected: Boolean = false, paper: Boolean = false) = drawWithCache {
    val inset = if (paper) 7.dp.toPx() else 2.dp.toPx()
    val line = .65.dp.toPx()
    val rim = if (selected) Color.White.copy(alpha = .38f) else skin.rim.copy(alpha = .6f)
    val ornament = skin.accent.copy(alpha = if (paper) .24f else if (selected) .14f else .09f)
    val highlight = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (selected) .16f else .22f), Color.Transparent), endY = size.height * .45f)
    val span = if (paper) 28.dp.toPx() else 16.dp.toPx()
    val corner = Offset(size.width - inset - span, inset + span)
    onDrawWithContent {
        drawContent()
        if (!paper) {
            drawRect(highlight)
            drawLine(rim, Offset(inset + 10.dp.toPx(), inset), Offset(size.width - inset - 10.dp.toPx(), inset), line)
        }
        when (skin.scene) {
            RelationshipBackground.SAKURA -> {
                repeat(5) { i ->
                    val angle = i * Math.PI * 2 / 5
                    drawOval(ornament, Offset(corner.x + kotlin.math.cos(angle).toFloat() * span * .3f,
                        corner.y + kotlin.math.sin(angle).toFloat() * span * .3f), Size(span * .30f, span * .22f))
                }
            }
            RelationshipBackground.STARLIGHT -> {
                drawArc(ornament, 70f, 235f, false, Offset(corner.x, corner.y - span), Size(span, span), style = Stroke(span * .13f))
                val star = corner + Offset(-span * .23f, -span * .60f)
                drawLine(ornament, star - Offset(span * .12f, 0f), star + Offset(span * .12f, 0f), line)
                drawLine(ornament, star - Offset(0f, span * .12f), star + Offset(0f, span * .12f), line)
            }
            RelationshipBackground.COAST -> repeat(2) { i ->
                drawArc(ornament, 15f, 150f, false, Offset(corner.x - span * .2f, corner.y - span + i * span * .26f),
                    Size(span * 1.2f, span * .55f), style = Stroke(line))
            }
            RelationshipBackground.WATER_GARDEN -> {
                drawOval(ornament, Offset(corner.x + span * .35f, corner.y - span * .8f), Size(span * .4f, span * .65f), style = Stroke(line))
                drawCircle(ornament, span * .09f, corner + Offset(span * .1f, -span * .15f))
            }
            RelationshipBackground.WOODLAND -> {
                drawOval(ornament, Offset(corner.x, corner.y - span * .65f), Size(span * .8f, span * .4f), style = Stroke(line))
                drawLine(ornament, corner + Offset(span * .1f, -span * .3f), corner + Offset(span * .8f, -span * .55f), line)
                if (!paper) repeat(5) { i -> drawLine(skin.metal.copy(alpha = .42f),
                    Offset(9.dp.toPx() + i * 5.dp.toPx(), size.height - 4.dp.toPx()),
                    Offset(11.dp.toPx() + i * 5.dp.toPx(), size.height - 4.dp.toPx()), line) }
            }
            RelationshipBackground.SUNSET -> {
                drawCircle(ornament, span * .23f, corner + Offset(span * .4f, -span * .5f), style = Stroke(line))
                drawLine(ornament, corner + Offset(0f, -span * .14f), corner + Offset(span * .85f, -span * .14f), line)
            }
            RelationshipBackground.LANTERN -> {
                val p = Path().apply {
                    moveTo(size.width - inset - span, inset)
                    lineTo(size.width - inset - span, inset + span * .45f)
                    lineTo(size.width - inset - span * .45f, inset + span * .45f)
                    lineTo(size.width - inset - span * .45f, inset + span)
                    lineTo(size.width - inset, inset + span)
                }
                drawPath(p, ornament, style = Stroke(line))
            }
        }
    }
}
