package com.nekobot.app.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.request.ImageRequest
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.SessionActivityPhase
import com.nekobot.app.data.local.ai.toolDisplayFallbackName
import com.nekobot.app.ui.components.FramedPortraitImage
import com.nekobot.app.ui.components.PortraitFramingDialog
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle
import com.nekobot.app.ui.components.nekoTextSize
import com.nekobot.app.ui.components.rememberPortraitFraming
import com.nekobot.app.ui.components.toolNameResId
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 卡片尺寸与叠放参数：改这几个数就能调整 3D 的强弱。 */
private val CardMaxWidth = 296.dp
/** 没有高度约束（被放进可滚动容器）时的兜底宽高比。 */
private val CardMinAspect = 1.26f
/** 卡片最高宽高比：卡片会拉长吃满剩余高度，但不会在超高屏上变成细长条。 */
private val CardMaxAspect = 1.72f
private val CardSideScale = 0.86f
private val CardSideRotation = 9f
private val CardSideSink = 18.dp
private val CardCenterLift = 6.dp

/** 卡片之外还要占的高度：叠卡下沉留白 + 底部圆点。 */
private val DeckChrome = 40.dp

/** 拖过卡宽的多少比例算切换一张：越小越跟手。 */
private const val DragStepRatio = 0.55f

/** 松手判定切换的速度/位移阈值。 */
private const val FlingVelocity = 320f
private const val FlingDistance = 64f

/**
 * 三张模式卡叠成一副牌：中间最大、两侧缩小后退并轻微转向。
 *
 * 高度按剩余空间自适应：首页不滚动，卡片会「拉长」吃满剩余高度（而不是留一大块空白），
 * 只在非常高的屏幕上用 [CardMaxAspect] 封顶。
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
        val heightBudget = if (maxHeight == Dp.Infinity) {
            CardMaxWidth * CardMinAspect
        } else {
            (maxHeight - DeckChrome).coerceAtLeast(220.dp)
        }
        // 卡片拉长吃满剩余高度：屏幕越高卡片越长，底部不留大块空白。
        // 很宽的屏幕（平板）同时放宽卡宽，让叠卡比例保持在 CardMaxAspect 以内。
        val cardWidth = minOf(
            maxWidth * 0.88f,
            maxOf(CardMaxWidth, heightBudget / CardMaxAspect)
        )
        val cardHeight = minOf(heightBudget, cardWidth * CardMaxAspect)
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
                        // 只有正面卡提供立绘取景编辑：侧卡叠在后面且半透明，弹窗会显得错位
                        showCoverEditor = isCenter,
                        // 运行状态动画只在正面卡播放：另两张卡还在叠放里可见，
                        // 但没必要让三个无限动画一起占 GPU
                        animateRunning = isCenter,
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
                        onOpenList = {
                            if (card.mode == HomeMode.COMPANION) {
                                if (isCenter) onOpenMode(card.mode) else snapTo(index)
                            } else {
                                onOpenModeList(card.mode)
                            }
                        }
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
        }
    }
}

@Composable
private fun ModeCard(
    card: ModeCardUi,
    width: Dp,
    height: Dp,
    onOpenList: () -> Unit,
    /** 角色卡封面是否显示立绘取景编辑按钮（仅当前正面卡）。 */
    showCoverEditor: Boolean,
    /** 是否播放运行状态动画（仅当前正面卡）。 */
    animateRunning: Boolean,
    modifier: Modifier = Modifier
) {
    if (card.mode == HomeMode.COMPANION) {
        SingleChatCard(card, onOpenList, modifier.width(width).height(height), compact = height < 330.dp)
        return
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(26.dp)
    val accent = MaterialTheme.colorScheme.primary
    val container = if (card.mode == HomeMode.AGENT) {
        Brush.linearGradient(listOf(Color(0xFF1C262B), Color(0xFF131A1E)))
    } else {
        Brush.linearGradient(
            listOf(
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, if (dark) 0.30f else 0.62f),
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, if (dark) 0.10f else 0.26f)
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
            showCoverEditor = showCoverEditor,
            animateRunning = animateRunning,
            compact = compact,
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
private fun ModeCardHero(
    card: ModeCardUi,
    ink: Color,
    accent: Color,
    showCoverEditor: Boolean,
    animateRunning: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val heroShape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .clip(heroShape)
            .background(ink.copy(alpha = 0.04f))
            .border(1.dp, ink.copy(alpha = 0.06f), heroShape),
        contentAlignment = Alignment.Center
    ) {
        if (card.mode == HomeMode.AGENT) {
            AgentHero(
                runs = card.agentRuns,
                lines = card.agentLines,
                ink = ink,
                animate = animateRunning,
                compact = compact
            )
        } else {
            CharacterCover(
                card = card,
                ink = ink,
                accent = accent,
                showEditor = showCoverEditor
            )
        }
    }
}

/** 角色聊天卡封面：最近一段角色会话的立绘；没有立绘时用首字兜底。 */
@Composable
private fun CharacterCover(
    card: ModeCardUi,
    ink: Color,
    accent: Color,
    /** 是否在立绘右下角显示取景编辑按钮。 */
    showEditor: Boolean
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var coverSize by remember { mutableStateOf(IntSize.Zero) }
    val coverUrl = card.coverUrl?.takeIf { it.isNotBlank() }
    val characterId = card.coverCharacterId
    // 与首页 banner 立绘共用同一份"按角色"的取景：在这里调完，banner 同步生效
    val framing = rememberPortraitFraming(characterId)
    val editLabel = stringResource(R.string.home_portrait_framing_edit)
    val request = remember(context, coverUrl) {
        coverUrl?.let { url ->
            ImageRequest.Builder(context).data(url).crossfade(true).build()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { coverSize = it },
        contentAlignment = Alignment.Center
    ) {
        if (request != null) {
            FramedPortraitImage(
                url = coverUrl,
                framing = framing,
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
            // 立绘右下角：取景编辑入口，点了只开对话框，不会顺带进入模式
            if (showEditor && characterId != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.42f))
                        .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                        .clickable { editing = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = editLabel,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
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

    if (editing && coverUrl != null && characterId != null) {
        // 预览框按封面的实际比例，所见即卡片里最终的样子
        val previewAspect = if (coverSize.height > 0) {
            coverSize.width.toFloat() / coverSize.height
        } else {
            0.9f
        }
        PortraitFramingDialog(
            imageUrl = coverUrl,
            characterName = card.coverCharacterName ?: card.title,
            previewAspect = previewAspect,
            initial = framing,
            onDismiss = { editing = false },
            onConfirm = { confirmed ->
                ServiceContainer.prefs.setPortraitFraming(characterId, confirmed.serialise())
                editing = false
            }
        )
    }
}

/**
 * Agent 卡封面。
 *
 * - 有会话在跑：逐行列出「会话 + 当前阶段」（思考中 / 调用某个工具 / 等待处理），
 *   行内指示器是动画（思考=呼吸圆点，调用工具=旋转圆环），底部进度条改为来回扫描；
 * - 没有会话在跑：保持原来的两行终端记录 + 静态进度条。
 *
 * 多个会话同时运行时按最近活跃排序，卡片高度不够时只显示前几行，其余折成一行提示。
 */
@Composable
private fun AgentHero(
    runs: List<AgentRunUi>,
    lines: List<String>,
    ink: Color,
    animate: Boolean,
    compact: Boolean
) {
    val mint = Color(0xFF8FD3B6)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (runs.isEmpty()) {
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
            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
            AgentBar(mint = mint, ink = ink, sweep = null)
        } else {
            val pulse = rememberAgentPulse(animate)
            val visible = runs.take(if (compact) 2 else 3)
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)) {
                visible.forEach { run ->
                    AgentRunRow(run = run, ink = ink, mint = mint, animate = animate, pulse = pulse)
                }
            }
            val hidden = runs.size - visible.size
            if (hidden > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_mode_card_agent_run_more, hidden),
                    style = nekoCaptionStyle(),
                    color = ink.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            AgentBar(
                mint = mint,
                ink = ink,
                sweep = if (animate) rememberAgentSweep() else null
            )
        }
    }
}

/** 运行中的一行：阶段指示器 + 会话标题 + 阶段文案。 */
@Composable
private fun AgentRunRow(
    run: AgentRunUi,
    ink: Color,
    mint: Color,
    animate: Boolean,
    pulse: Float
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgentPhaseIndicator(
            phase = run.phase,
            mint = mint,
            animate = animate,
            pulse = pulse
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = run.title,
            style = nekoRowSubtitleStyle().copy(fontSize = nekoTextSize(11.5f)),
            color = ink.copy(alpha = 0.90f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.58f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = agentPhaseLabel(run),
            style = nekoCaptionStyle().copy(
                fontSize = nekoTextSize(10.5f),
                fontFamily = FontFamily.Monospace
            ),
            color = if (run.phase == SessionActivityPhase.TOOL) mint else ink.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.42f)
        )
    }
}

/** 阶段文案：思考中 / 调用工具名 / 等待处理 / 运行中。 */
@Composable
private fun agentPhaseLabel(run: AgentRunUi): String = when (run.phase) {
    SessionActivityPhase.THINKING -> stringResource(R.string.home_mode_card_agent_run_thinking)
    SessionActivityPhase.WAITING -> stringResource(R.string.home_mode_card_agent_run_waiting)
    SessionActivityPhase.TOOL -> {
        val tool = run.toolName?.takeIf { it.isNotBlank() }
        if (tool == null) {
            stringResource(R.string.home_mode_card_agent_run_running)
        } else {
            stringResource(R.string.home_mode_card_agent_run_tool, agentToolLabel(tool))
        }
    }
    // 还没有阶段上报（如服务器模式）：只说明在运行
    null -> stringResource(R.string.home_mode_card_agent_run_running)
}

/** 工具 id → 本地化名称，缺失时回退为去掉 MCP 前缀的工具名。 */
@Composable
private fun agentToolLabel(toolName: String): String {
    val resId = remember(toolName) { toolNameResId(toolName) }
    return if (resId != 0) stringResource(resId) else toolDisplayFallbackName(toolName)
}

/**
 * 阶段指示器。
 *
 * 思考用呼吸圆点（节奏感=在等模型），调用工具用旋转圆环（明确=有外部动作在跑），
 * 两者形状不同，扫一眼就能区分；等待用户处理用暖色圆点提示需要人介入。
 */
@Composable
private fun AgentPhaseIndicator(
    phase: SessionActivityPhase?,
    mint: Color,
    animate: Boolean,
    pulse: Float
) {
    when (phase) {
        SessionActivityPhase.TOOL -> SpinningRing(color = mint, animate = animate)
        SessionActivityPhase.WAITING -> PulsingDot(
            color = Color(0xFFE9B36A),
            animate = animate,
            pulse = pulse
        )
        else -> PulsingDot(color = mint, animate = animate, pulse = pulse)
    }
}

/** 呼吸圆点：外圈随 pulse 扩散淡出，内点保持可见。 */
@Composable
private fun PulsingDot(color: Color, animate: Boolean, pulse: Float) {
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (animate) {
                drawCircle(
                    color = color.copy(alpha = 0.30f * (1f - pulse)),
                    radius = size.minDimension / 2f * (0.40f + 0.60f * pulse)
                )
            }
        }
        Box(
            Modifier
                .size(6.dp)
                .background(
                    color = color.copy(alpha = if (animate) 0.55f + 0.45f * (1f - pulse) else 0.85f),
                    shape = CircleShape
                )
        )
    }
}

/** 旋转圆环：工具执行中的指示器。 */
@Composable
private fun SpinningRing(color: Color, animate: Boolean) {
    val angle = rememberRingAngle(animate)
    Canvas(
        Modifier
            .size(12.dp)
            .rotate(angle)
    ) {
        val stroke = 1.6.dp.toPx()
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** 运行中的进度条；[sweep] 非空时是一段来回扫描的高亮，为空则是静态 62% 进度。 */
@Composable
private fun AgentBar(mint: Color, ink: Color, sweep: Float?) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth(0.72f)
            .height(4.dp)
            .background(ink.copy(alpha = 0.12f), CircleShape)
    ) {
        if (sweep == null) {
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .height(4.dp)
                    .background(mint, CircleShape)
            )
        } else {
            val segment = maxWidth * 0.34f
            Box(
                Modifier
                    .offset(x = (maxWidth - segment) * sweep)
                    .width(segment)
                    .height(4.dp)
                    .background(mint, CircleShape)
            )
        }
    }
}

/** 呼吸动画 0→1→0；不打动画时返回 1（圆点保持最亮）。 */
@Composable
private fun rememberAgentPulse(enabled: Boolean): Float {
    if (!enabled) return 1f
    val transition = rememberInfiniteTransition(label = "agentRunPulse")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    return value
}

/**
 * 进度条高亮位置 0↔1 往复。
 *
 * 用 [RepeatMode.Reverse] 而不是 Restart：Restart 会在亮条抵达末尾后瞬间回到开头，
 * 视觉上像「闪一下」；Reverse 让它原路折返，再配合缓入缓出，两端自然减速换向。
 */
@Composable
private fun rememberAgentSweep(): Float {
    val transition = rememberInfiniteTransition(label = "agentRunSweep")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweep"
    )
    return value
}

/** 圆环旋转角度；不打动画时停在 40°，看起来仍是「转了一圈的一半」。 */
@Composable
private fun rememberRingAngle(enabled: Boolean): Float {
    if (!enabled) return 40f
    val transition = rememberInfiniteTransition(label = "agentRunRing")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring"
    )
    return value
}

@Composable
private fun SingleChatCard(card: ModeCardUi, onEnter: () -> Unit, modifier: Modifier, compact: Boolean) {
    val shape = RoundedCornerShape(26.dp)
    val ink = Color(0xFF443A30)
    val edge = Color(0xFFD4C8B9)
    Box(
        modifier.clip(shape).background(Color(0xFFF4EEE4)).border(1.dp, edge, shape)
            .padding(if (compact) 12.dp else 18.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Text("03 ${card.badge}", color = ink, style = nekoCaptionStyle())
            Image(
                painter = painterResource(R.drawable.lianxu_card_companion_mark),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentScale = ContentScale.Fit
            )
            Text(card.title, style = nekoRowTitleStyle().copy(fontSize = nekoTextSize(if (compact) 22f else 25f)),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold, color = ink)
            Text(card.subtitle.orEmpty(), style = nekoRowSubtitleStyle(), color = Color(0xFF685D51))
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Box(
                Modifier.align(Alignment.End).clip(CircleShape).border(1.dp, edge, CircleShape)
                    .clickable(onClick = onEnter).padding(horizontal = 13.dp, vertical = 6.dp)
            ) {
                Text(card.action, color = Color(0xFF6B5847), style = nekoCaptionStyle())
            }
        }
    }
}
