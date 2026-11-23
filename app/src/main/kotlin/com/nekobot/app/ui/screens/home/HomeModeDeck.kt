package com.nekobot.app.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nekobot.app.R
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.components.nekoTextSize
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 卡片尺寸与叠放参数：改这几个数就能调整 3D 的强弱。 */
private val CardMaxWidth = 296.dp
private val CardAspect = 1.26f
private val CardSideScale = 0.86f
private val CardSideRotation = 9f
private val CardSideSink = 18.dp
private val CardCenterLift = 6.dp

/** 卡片之外还要占的高度：下沉留白 + 圆点 + 滑动提示。 */
private val DeckChrome = 66.dp

/** 拖过卡宽的多少比例算切换一张：越小越跟手。 */
private const val DragStepRatio = 0.55f

/** 松手判定切换的速度/位移阈值。 */
private const val FlingVelocity = 320f
private const val FlingDistance = 64f

/**
 * 三张模式卡叠成一副牌：中间最大、两侧缩小后退并轻微转向。
 *
 * 高度按剩余空间自适应：首页不滚动，卡片会缩小而不是把页面撑出屏幕。
 * 左右滑动切换，松手用弹簧收敛；点中间卡进入该模式，点两侧卡先把它滑到中间。
 */
@Composable
fun HomeModeDeck(
    cards: List<ModeCardUi>,
    activeIndex: Int,
    onActiveIndexChange: (Int) -> Unit,
    onOpenMode: (HomeMode) -> Unit,
    onOpenModeList: (HomeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    if (cards.isEmpty()) return
    val scope = rememberCoroutineScope()
    val lastIndex = cards.lastIndex
    val offset = remember { Animatable(activeIndex.coerceIn(0, lastIndex).toFloat()) }
    val settledIndex by remember {
        derivedStateOf { offset.value.roundToInt().coerceIn(0, lastIndex) }
    }
    val velocityTracker = remember { VelocityTracker() }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val cardShape = remember { RoundedCornerShape(26.dp) }

    val snapTo: (Int) -> Unit = { target ->
        val clamped = target.coerceIn(0, lastIndex)
        scope.launch {
            offset.animateTo(
                targetValue = clamped.toFloat(),
                // 弹簧按剩余距离自行决定时长，手指刚离开时不会突然换一个固定速度
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.001f
                )
            )
        }
        onActiveIndexChange(clamped)
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardWidth = minOf(CardMaxWidth, maxWidth * 0.88f)
        val heightBudget = if (maxHeight == Dp.Infinity) {
            cardWidth * CardAspect
        } else {
            (maxHeight - DeckChrome).coerceAtLeast(200.dp)
        }
        val cardHeight = minOf(cardWidth * CardAspect, heightBudget)
        val sideOffset = cardWidth * 0.185f

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight + 30.dp)
                    .pointerInput(lastIndex, cardWidth) {
                        val stepPx = cardWidth.toPx() * DragStepRatio
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragAccum = 0f
                                velocityTracker.resetTracking()
                            },
                            onDragCancel = {
                                dragAccum = 0f
                                snapTo(settledIndex)
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                dragAccum += dragAmount
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                scope.launch {
                                    offset.snapTo(
                                        (offset.value - dragAmount / stepPx)
                                            .coerceIn(0f, lastIndex.toFloat())
                                    )
                                }
                            },
                            onDragEnd = {
                                val velocity = velocityTracker.calculateVelocity().x
                                velocityTracker.resetTracking()
                                val direction = when {
                                    velocity <= -FlingVelocity || dragAccum <= -FlingDistance -> 1
                                    velocity >= FlingVelocity || dragAccum >= FlingDistance -> -1
                                    else -> 0
                                }
                                val target = (settledIndex + direction).coerceIn(0, lastIndex)
                                dragAccum = 0f
                                // 把手指速度交给弹簧，滑动结束不会「停一下再补一段」；
                                // 速度做上限截断，避免快速甩动把卡片带出边界
                                val handOff = (-velocity / stepPx).coerceIn(-5f, 5f)
                                scope.launch {
                                    offset.animateTo(
                                        targetValue = target.toFloat(),
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                            visibilityThreshold = 0.001f
                                        ),
                                        initialVelocity = handOff
                                    )
                                }
                                onActiveIndexChange(target)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                cards.forEachIndexed { index, card ->
                    val isCenter = index == settledIndex
                    ModeCard(
                        card = card,
                        width = cardWidth,
                        height = cardHeight,
                        modifier = Modifier
                            // 用吸附后的下标排序，滑动过程中不必每帧重组
                            .zIndex(10f - abs(index - settledIndex))
                            .graphicsLayer {
                                val delta = index - offset.value
                                val clamped = delta.coerceIn(-1f, 1f)
                                val near = abs(delta).coerceAtMost(1f)
                                translationX = clamped * sideOffset.toPx()
                                // 下沉与抬起按 near 连续插值：吸附时不会突然跳一格
                                translationY = CardSideSink.toPx() * near -
                                    CardCenterLift.toPx() * (1f - near)
                                val scale = 1f - (1f - CardSideScale) * near
                                scaleX = scale
                                scaleY = scale
                                rotationY = -clamped * CardSideRotation
                                cameraDistance = 20f * density
                                // 第三张卡在第二张后面淡入淡出，不会「突然出现」
                                alpha = (2f - abs(delta)).coerceIn(0f, 1f)
                                // 阴影保持恒定：逐帧改 elevation 会重新计算阴影，滑动时明显掉帧
                                shadowElevation = 18.dp.toPx()
                                shape = cardShape
                                clip = false
                            }
                            .clickable(
                                onClickLabel = card.title,
                                onClick = {
                                    if (isCenter) onOpenMode(card.mode) else snapTo(index)
                                }
                            ),
                        onOpenList = { onOpenModeList(card.mode) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                cards.forEachIndexed { index, _ ->
                    val active = index == settledIndex
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .height(6.dp)
                            .width(if (active) 18.dp else 6.dp)
                            .background(
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape
                            )
                    )
                }
            }
            Text(
                text = stringResource(R.string.home_mode_swipe),
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ModeCard(
    card: ModeCardUi,
    width: Dp,
    height: Dp,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(26.dp)
    val accent = MaterialTheme.colorScheme.primary
    val container = when (card.mode) {
        HomeMode.CHARACTER -> Brush.linearGradient(
            listOf(
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, if (dark) 0.30f else 0.62f),
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, if (dark) 0.10f else 0.26f)
            )
        )
        HomeMode.AGENT -> Brush.linearGradient(
            listOf(Color(0xFF1C262B), Color(0xFF131A1E))
        )
        HomeMode.COMPANION -> Brush.linearGradient(
            listOf(
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.tertiaryContainer, if (dark) 0.32f else 0.66f),
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.secondaryContainer, if (dark) 0.14f else 0.32f)
            )
        )
    }
    val ink = if (card.mode == HomeMode.AGENT) {
        Color(0xFFF2F6F4)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val muted = ink.copy(alpha = 0.62f)
    // 小屏上压缩内边距，保证内容不溢出卡片
    val compact = height < 330.dp
    val padding = if (compact) 12.dp else 18.dp

    Column(
        modifier = modifier
            .width(width)
            .height(height)
            .background(brush = container, shape = shape)
            .border(1.dp, ink.copy(alpha = 0.06f), shape)
            .padding(padding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.order.toString().padStart(2, '0'),
                style = nekoCaptionStyle(),
                color = muted,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(16.dp)
                    .width(1.dp)
                    .background(ink.copy(alpha = 0.12f))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = card.badge,
                style = nekoCaptionStyle(),
                color = muted,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!card.meta.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = card.meta,
                    style = nekoCaptionStyle(),
                    color = muted,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(if (compact) 10.dp else 12.dp))
        // 封面吃掉卡片的剩余高度：图片可以更高，卡片下方不会空出一截
        ModeCardHero(
            card = card,
            ink = ink,
            accent = accent,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        Spacer(Modifier.height(if (compact) 10.dp else 13.dp))
        Text(
            text = card.title,
            style = nekoRowTitleStyle().copy(
                fontSize = nekoTextSize(if (compact) 17f else 18f),
                lineHeight = nekoTextSize(24f),
                fontWeight = FontWeight.SemiBold
            ),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = card.subtitle?.takeIf { it.isNotBlank() }
        // 极矮的卡片先牺牲副标题，保证标题与底部提示不被挤出去
        if (subtitle != null && height >= 230.dp) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = nekoRowSubtitleStyle().copy(
                    fontSize = nekoTextSize(12.5f),
                    lineHeight = nekoTextSize(18f)
                ),
                color = if (card.mode == HomeMode.AGENT) ink.copy(alpha = 0.74f) else muted,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 点击提示：明确告诉用户卡身可点
            Text(
                text = stringResource(R.string.home_mode_card_tap),
                style = nekoCaptionStyle(),
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .background(
                        color = if (card.mode == HomeMode.AGENT) Color(0xFF8FD3B6) else accent,
                        shape = CircleShape
                    )
                    .clickable(onClickLabel = card.action, onClick = onOpenList)
                    .padding(start = 13.dp, end = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.action,
                        style = nekoCaptionStyle(),
                        color = if (card.mode == HomeMode.AGENT) Color(0xFF10181C) else MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(1.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (card.mode == HomeMode.AGENT) Color(0xFF10181C) else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeCardHero(card: ModeCardUi, ink: Color, accent: Color, modifier: Modifier = Modifier) {
    val heroShape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .clip(heroShape)
            .background(ink.copy(alpha = 0.04f))
            .border(1.dp, ink.copy(alpha = 0.06f), heroShape),
        contentAlignment = Alignment.Center
    ) {
        when (card.mode) {
            HomeMode.CHARACTER -> CharacterCover(card = card, ink = ink, accent = accent)
            HomeMode.AGENT -> AgentHero(lines = card.agentLines, ink = ink)
            HomeMode.COMPANION -> CompanionHero(initial = card.companionInitial, ink = ink, accent = accent)
        }
    }
}

/** 角色聊天卡封面：最近一段角色会话的立绘；没有立绘时用首字兜底。 */
@Composable
private fun CharacterCover(card: ModeCardUi, ink: Color, accent: Color) {
    val context = LocalContext.current
    val request = remember(context, card.coverUrl) {
        card.coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context).data(url).crossfade(true).build()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 图片底部压一点同色暗角，和下方文字衔接不生硬
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.62f to Color.Transparent,
                            1f to ink.copy(alpha = 0.12f)
                        )
                    )
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.04f))
                        )
                    )
            )
            if (!card.coverInitial.isNullOrBlank()) {
                Text(
                    text = card.coverInitial,
                    style = nekoRowTitleStyle().copy(
                        fontSize = nekoTextSize(44f),
                        fontWeight = FontWeight.Medium
                    ),
                    color = ink.copy(alpha = 0.50f)
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PersonOutline,
                    contentDescription = null,
                    tint = ink.copy(alpha = 0.35f),
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

/** Agent 卡封面：两行终端记录 + 一条进度。 */
@Composable
private fun AgentHero(lines: List<String>, ink: Color) {
    val mint = Color(0xFF8FD3B6)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.take(2).forEachIndexed { index, line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(if (index == 0) mint else ink.copy(alpha = 0.35f), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = line,
                        style = nekoRowSubtitleStyle().copy(
                            fontSize = nekoTextSize(11.5f),
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (index == 0) ink.copy(alpha = 0.88f) else ink.copy(alpha = 0.58f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth(0.72f)
                .height(4.dp)
                .background(ink.copy(alpha = 0.12f), CircleShape)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .height(4.dp)
                    .background(mint, CircleShape)
            )
        }
    }
}

/** 单角色卡封面：一圈关系环，中间是角色的首字。 */
@Composable
private fun CompanionHero(initial: String?, ink: Color, accent: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            // 关系环按可用尺寸缩放，但不超过 120dp，卡片变高时不会占满整块
            val diameter = minOf(size.width, size.height).coerceAtMost(120.dp.toPx())
            val stroke = (diameter * 0.055f).coerceAtLeast(3.dp.toPx())
            val radius = (diameter - stroke) / 2f
            drawCircle(
                color = ink.copy(alpha = 0.10f),
                radius = radius,
                center = center,
                style = Stroke(width = stroke)
            )
            drawArc(
                color = accent.copy(alpha = 0.85f),
                startAngle = -90f,
                sweepAngle = 360f * 0.62f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }
        if (!initial.isNullOrBlank()) {
            Text(
                text = initial,
                style = nekoRowTitleStyle().copy(
                    fontSize = nekoTextSize(28f),
                    fontWeight = FontWeight.Medium
                ),
                color = ink.copy(alpha = 0.82f)
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.PersonOutline,
                contentDescription = null,
                tint = ink.copy(alpha = 0.45f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
