package app.lianxu.independent

import androidx.compose.animation.core.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.exp

@Composable
internal fun TypingLabel(
    modifier: Modifier,
    style: TextStyle = MaterialTheme.typography.titleSmall,
    text: String = "对方正在输入",
    maxLines: Int = 1,
) {
    val dark = Paper.luminance() < .5f
    val ink = if (dark) Muted else Color(0xFF695B4E)
    val gold = if (dark) Color(0xFFEDD6B9) else Color(0xFFBC9B70)
    val reduced = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val phase = if (reduced) rememberUpdatedState(1f) else rememberInfiniteTransition(label="输入状态").animateFloat(
        initialValue=0f, targetValue=1f,
        animationSpec=infiniteRepeatable(keyframes { durationMillis=2670; 0f at 0 using LinearEasing; 1f at 2130 }),
        label="文字柔光"
    )
    val colors = remember(ink,gold) {
        (0..16).map { i ->
            val x = (i - 8) / 2.6
            lerp(ink,gold,(.87 * exp(-x*x/2)).toFloat())
        }
    }
    var textWidth by remember { mutableIntStateOf(0) }
    val progress = phase.value
    val radius = textWidth * .38f
    val paint = if (progress > 0f && progress < 1f && radius > 0f) {
        val center = progress * (textWidth + 2 * radius) - radius
        style.copy(brush=Brush.horizontalGradient(colors,center-radius,center+radius))
    } else style.copy(color=ink)
    Text(text, modifier=modifier.onSizeChanged { textWidth=it.width },
        style=paint,maxLines=maxLines,overflow=TextOverflow.Ellipsis)
}
