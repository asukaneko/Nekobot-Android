package app.lianxu.independent

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class TimelineMarkerKind {
    THINKING_IDLE, THINKING_LIVE, TOOL_LIVE, TOOL_SUCCESS, TOOL_FAILURE, TOOL_NEUTRAL
}

@Composable internal fun TimelineMarkerKind.tone(): Color = when (this) {
    TimelineMarkerKind.THINKING_LIVE, TimelineMarkerKind.TOOL_LIVE, TimelineMarkerKind.TOOL_SUCCESS ->
        if (Paper.luminance() < .5f) Color(0xFF9DC5A7) else Color(0xFF4E7058)
    TimelineMarkerKind.TOOL_FAILURE ->
        if (Paper.luminance() < .5f) Color(0xFFE9B6B1) else Color(0xFF915753)
    TimelineMarkerKind.THINKING_IDLE -> GreenText.copy(alpha = .72f)
    TimelineMarkerKind.TOOL_NEUTRAL -> Muted.copy(alpha = .75f)
}

internal val timelinePaper: Color @Composable get() = Paper.copy(alpha = if (Paper.luminance() < .5f) .62f else .68f)

@Composable internal fun ChatTimelineNode(
    id: String, kind: TimelineMarkerKind, modifier: Modifier = Modifier,
    first: Boolean = true, last: Boolean = true, content: @Composable () -> Unit
) {
    val rail = GreenText.copy(alpha = .24f)
    val ink = kind.tone()
    val thinking = kind == TimelineMarkerKind.THINKING_IDLE || kind == TimelineMarkerKind.THINKING_LIVE
    val live = kind == TimelineMarkerKind.THINKING_LIVE || kind == TimelineMarkerKind.TOOL_LIVE
    val reduced = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val breath = if (live && !reduced) {
        rememberInfiniteTransition(label = "节点呼吸").animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (thinking) 900 else 725, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "光晕"
        )
    } else null
    Column(modifier.testTag("timeline-node-$id").drawBehind {
        val x = 8.dp.toPx()
        // Both headers use an 18sp line and 5dp of padding on each side.
        val y = maxOf(14.dp.toPx(), 5.dp.toPx() + 9.sp.toPx())
        val center = Offset(x, y)
        val gap = 8.dp.toPx()
        if (!first) drawLine(rail, Offset(x, 0f), Offset(x, y - gap), 1.dp.toPx())
        if (!last) drawLine(rail, Offset(x, y + gap), Offset(x, size.height), 1.dp.toPx())
        if (live) {
            val phase = breath?.value ?: .45f
            val radius = 8.dp.toPx() * (.72f + .50f * phase)
            val halo = ink.copy(alpha = .30f * (.25f + .65f * phase))
            if (thinking) drawCircle(halo, radius - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            else drawCircle(halo, radius, center)
        }
        when {
            thinking -> drawCircle(ink, 2.8.dp.toPx(), center, style = Stroke(1.4.dp.toPx()))
            live -> drawCircle(ink, 3.dp.toPx(), center)
            else -> {
                val neutral = kind == TimelineMarkerKind.TOOL_NEUTRAL
                drawCircle(ink.copy(alpha = if (neutral) .10f else .22f), 5.5.dp.toPx(), center)
                drawCircle(ink, (if (neutral) 2.5.dp else 2.75.dp).toPx(), center)
            }
        }
    }.padding(start = 24.dp, bottom = if (last) 2.dp else 10.dp)) { content() }
}

@Composable internal fun TimelineChevron(expanded: Boolean, color: Color) {
    val angle by animateFloatAsState(if (expanded) 0f else 180f, tween(180), label = "展开箭头")
    Box(Modifier.rotate(angle)) { ControlIcon("up", color, 14.dp) }
}

@Composable internal fun TimelineToolCapsule(
    tool: String?, expanded: Boolean, color: Color, modifier: Modifier, toggle: () -> Unit,
    label: @Composable RowScope.() -> Unit
) {
    Row(modifier.clip(CircleShape).background(timelinePaper).border(.5.dp, Line, CircleShape)
        .clickable(role = Role.Button, onClick = toggle).heightIn(min = 28.dp)
        .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when (tool) {
            in ChatToolActivity.RECALL_TOOLS -> ControlIcon("search", color, 12.dp)
            "agent_memory_update", CompanionRecord.NAME -> ControlIcon("edit", color, 12.dp)
            ImmediateContact.NAME -> Canvas(Modifier.size(12.dp)) {
                drawCircle(color, 4.4.dp.toPx(), style = Stroke(1.dp.toPx()))
                drawLine(color, center.copy(y = 3.dp.toPx()), center, 1.dp.toPx(), StrokeCap.Round)
                drawLine(color, center, center + Offset(2.dp.toPx(), 1.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
            }
            else -> Canvas(Modifier.size(12.dp)) { drawCircle(color, 1.8.dp.toPx()) }
        }
        label()
        TimelineChevron(expanded, color)
    }
}
