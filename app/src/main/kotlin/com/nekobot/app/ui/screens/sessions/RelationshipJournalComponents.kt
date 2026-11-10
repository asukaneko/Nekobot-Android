package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.data.local.RelationshipBackground
import kotlin.math.roundToInt
import com.nekobot.app.ui.theme.isNekoDark

private val LocalJournalAtlas = staticCompositionLocalOf<ImageBitmap?> { null }

@Composable
internal fun RelationshipJournalBackdrop(background: RelationshipBackground, content: @Composable BoxScope.() -> Unit) {
    val atlas = ImageBitmap.imageResource(LocalJournalSkin.current.atlas)
    CompositionLocalProvider(LocalJournalAtlas provides atlas) {
        Box(Modifier.fillMaxSize()) {
            RelationshipScene(background, Modifier.matchParentSize())
            Box(Modifier.fillMaxWidth().height(280.dp).background(Brush.verticalGradient(
                0f to Color(0xFF122940).copy(alpha = .68f),
                .68f to Color(0xFF122940).copy(alpha = .62f),
                1f to Color.Transparent
            )))
            content()
        }
    }
}

internal val RelationshipBackground.resource: Int get() = when (this) {
    RelationshipBackground.SAKURA -> R.drawable.relationship_scene_sakura
    RelationshipBackground.STARLIGHT -> R.drawable.relationship_scene_starlight
    RelationshipBackground.COAST -> R.drawable.relationship_scene_coast
    RelationshipBackground.WATER_GARDEN -> R.drawable.relationship_scene_watergarden
    RelationshipBackground.WOODLAND -> R.drawable.relationship_scene_woodland
    RelationshipBackground.SUNSET -> R.drawable.relationship_scene_sunset
    RelationshipBackground.LANTERN -> R.drawable.relationship_scene_lantern
}

@Composable
internal fun RelationshipScene(background: RelationshipBackground, modifier: Modifier = Modifier) {
    var ready by remember(background) { mutableStateOf(false) }
    Box(modifier) {
        AsyncImage(model = background.resource, contentDescription = null, contentScale = ContentScale.Crop,
            onSuccess = { ready = true },
            modifier = Modifier.matchParentSize().testTag(if (ready) "relationship_scene_${background.id}_ready" else "relationship_scene_loading"))
        if (isNekoDark()) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .30f)))
    }
}

@Composable
internal fun journalPaperColor(): Color = LocalJournalSkin.current.paper

internal fun readableOn(color: Color): Color = if (color.luminance() > .179f) Color.Black else Color.White

/** A shallow deckle edge, not a mask over the text; content always has its own safe padding. */
internal object JournalPaperShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val edge = with(density) { 3.dp.toPx() }
        val count = (size.width / with(density) { 9.dp.toPx() }).roundToInt().coerceAtLeast(1)
        val path = Path().apply {
            moveTo(0f, edge * 2)
            for (i in 0..count) lineTo(size.width * i / count, edge + edge * ((i * 17 % 11) / 11f))
            lineTo(size.width, size.height - edge * 2)
            for (i in count downTo 0) lineTo(size.width * i / count, size.height - edge - edge * ((i * 13 % 7) / 7f))
            close()
        }
        return Outline.Generic(path)
    }
}

private fun Modifier.paperGrain() = drawWithCache {
    val step = 7.dp.toPx()
    val fibres = Path().apply {
        var y = 0f; var row = 0
        while (y < size.height) {
            var x = (row % 3) * step / 3
            while (x < size.width) {
                moveTo(x, y)
                lineTo(x + 1.4.dp.toPx(), y + .4.dp.toPx())
                x += step
            }
            y += step; row++
        }
    }
    val stroke = Stroke(width = .45.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawPath(fibres, Color(0xFF79684B).copy(alpha = .026f), style = stroke)
    }
}

@Composable
internal fun RelationshipPaper(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val skin = LocalJournalSkin.current
    Surface(modifier = modifier, shape = JournalPaperShape, color = journalPaperColor(),
        contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().paperGrain().journalFinish(skin, paper = true)
            .padding(horizontal = 18.dp, vertical = 22.dp), content = content)
    }
}

@Composable
internal fun JournalHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
}

/** The atlas has transparent independent cells. Source rectangles trim padding without resampling assets. */
@Composable
internal fun JournalEmblem(index: Int, modifier: Modifier = Modifier) {
    val atlas = LocalJournalAtlas.current ?: ImageBitmap.imageResource(LocalJournalSkin.current.atlas)
    Canvas(modifier) {
        val cell = atlas.width / 8
        val side = minOf(size.width, size.height).roundToInt()
        drawImage(atlas, srcOffset = IntOffset(index.coerceIn(0, 7) * cell, 0),
            srcSize = IntSize(cell, atlas.height), dstOffset = IntOffset(((size.width - side) / 2).roundToInt(), ((size.height - side) / 2).roundToInt()),
            dstSize = IntSize(side, side), filterQuality = FilterQuality.Medium)
    }
}

@Composable
internal fun JournalAction(title: String, subtitle: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val skin = LocalJournalSkin.current
    val prominent = subtitle != null
    val foreground = if (prominent) readableOn(skin.accent) else skin.ink
    Surface(onClick = onClick, modifier = modifier, shape = skin.buttonShape,
        color = if (prominent) skin.accent else skin.paper, border = BorderStroke(1.dp, skin.rim),
        contentColor = foreground, shadowElevation = if (prominent) 4.dp else 2.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).journalFinish(skin, selected = prominent)
            .padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (prominent) foreground else skin.accent)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = foreground.copy(alpha = .85f)) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
}
