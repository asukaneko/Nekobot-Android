package app.lianxu.independent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

internal data class BubbleAction(val anchor:Any,val message:Message,val rect:Rect,val text:String,val kind:String,
    val expanded:Boolean,val transcript:(()->Unit)?)
internal val LocalBubbleMessage=compositionLocalOf<Message?> { null }
internal val LocalOpenBubble=staticCompositionLocalOf<(BubbleAction)->Unit> { {} }
internal val LocalFocusedBubble=compositionLocalOf<Any?> { null }

internal fun Modifier.bubbleActions(text:String,kind:String="text",expanded:Boolean=false, transcript:(()->Unit)?=null,
    fallback:(()->Unit)?=null, click:()->Unit={}):Modifier=composed {
    val message=LocalBubbleMessage.current
    val open=LocalOpenBubble.current
    val haptic=LocalHapticFeedback.current
    val anchor=remember { Any() }
    var rect by remember { mutableStateOf(Rect.Zero) }
    val focused=LocalFocusedBubble.current===anchor
    val bounce=remember { Animatable(1f) }
    val motion=rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor!=0f
    LaunchedEffect(focused) {
        if(focused && motion) { bounce.snapTo(.965f);bounce.animateTo(1f,spring(dampingRatio = .48f, stiffness = 560f)) }
        else bounce.snapTo(1f)
    }
    val longPress:(()->Unit)?=when {
        message==null -> fallback
        text=="【已撤回】" || kind=="text" && text.isBlank() -> null
        else -> ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            open(BubbleAction(anchor,message,rect,text,kind,expanded,transcript)) })
    }
    this.onGloballyPositioned { rect=it.boundsInWindow() }
        .graphicsLayer { scaleX=bounce.value;scaleY=bounce.value }
        .then(if(focused) Modifier.shadow(3.dp,RoundedCornerShape(14.dp)) else Modifier)
        .combinedClickable(role=Role.Button,onClick=click,onLongClick=longPress,hapticFeedbackEnabled=false)
}

@Composable internal fun MessageMenuOverlay(target:BubbleAction?, viewport:Rect, dismiss:()->Unit, act:(String,BubbleAction)->Unit) {
    var shown by remember { mutableStateOf<BubbleAction?>(null) }
    val progress=remember { Animatable(0f) }
    LaunchedEffect(target) {
        if(target!=null) { shown=target;progress.snapTo(0f);progress.animateTo(1f,spring(.68f,520f)) }
        else { progress.animateTo(0f,tween(130));shown=null }
    }
    val item=shown?:return
    BackHandler { dismiss() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val dark=Paper.luminance()<.5f
    val surface=if(dark) MaterialTheme.colorScheme.surfaceContainerHigh else Paper
    val veil=if(dark) Color(0xFF0A0806).copy(alpha=.30f) else Color(0xFF2A2119).copy(alpha=.11f)
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin=it.positionInWindow() }) {
        val rect=item.rect.translate(-origin)
        val gap=with(density){8.dp.toPx()}
        val margin=with(density){12.dp.roundToPx()}
        val above=item.rect.bottom+gap+measured.height>viewport.bottom
        val x=(if(item.message.mine) rect.right-measured.width else rect.left).roundToInt()
            .coerceIn(margin,(constraints.maxWidth-margin-measured.width).coerceAtLeast(margin))
        val y=(if(above) rect.top-gap-measured.height else rect.bottom+gap).roundToInt()
            .coerceIn(0,(constraints.maxHeight-measured.height).coerceAtLeast(0))
        Box(Modifier.fillMaxSize().drawBehind {
            clipPath(Path().apply { addRoundRect(RoundRect(rect,CornerRadius(12.dp.toPx()))) },ClipOp.Difference) {
                drawRect(veil.copy(alpha=veil.alpha*progress.value.coerceIn(0f,1f)))
            }
        }.clickable(interactionSource=remember { MutableInteractionSource() },indication=null,onClick=dismiss).testTag("chat-menu-dismiss"))
        val shape=RoundedCornerShape(50)
        Row(Modifier.offset { IntOffset(x,y) }.widthIn(max=(maxWidth-24.dp).coerceAtLeast(1.dp)).onSizeChanged { measured=it }
            .graphicsLayer {
                transformOrigin=TransformOrigin(if(item.message.mine).85f else .15f,if(above)1f else 0f)
                scaleX=.82f+.18f*progress.value;scaleY=scaleX;alpha=progress.value.coerceIn(0f,1f)
                translationY=(if(above)1 else -1)*6.dp.toPx()*(1-progress.value)
            }.shadow(10.dp,shape).background(surface.copy(alpha=.97f),shape).border(.5.dp,Line,shape).clip(shape)
            .horizontalScroll(rememberScrollState()).padding(4.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            val buttons=buildList {
                if(item.transcript!=null)add("transcript" to if(item.expanded)"收起" else "转文字")
                if(item.kind=="image"||item.kind=="video")add("save" to "保存")
                if(item.text.isNotBlank())add("copy" to "复制")
                add("quote" to "引用")
            }
            buttons.forEach { (id,label) ->
                val interaction=remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                Row(Modifier.heightIn(min=40.dp).scale(if(pressed).95f else 1f).clip(shape)
                    .background(if(pressed) Accent else Color.Transparent)
                    .clickable(interactionSource=interaction,indication=null,role=Role.Button) { if(target!=null) { dismiss();act(id,item) } }
                    .testTag("chat-menu-$id").padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    MenuIcon(id);Spacer(Modifier.width(5.dp));Text(label,maxLines=1,style=MaterialTheme.typography.labelLarge,color=Ink)
                }
            }
        }
    }
}

@Composable private fun MenuIcon(id:String) {
    val ink=GreenText
    val path=remember(id) { androidx.compose.ui.graphics.vector.PathParser().parsePathString(when(id) {
        "copy" -> "M8 8h12v13H8z M5 16H3V3h12v2"
        "quote" -> "M10 5 3 11l7 6 M4 11h10q6 0 7 8"
        "save" -> "M12 3v12m-5-5 5 5 5-5 M4 16v5h16v-5"
        else -> "M3 5h18v12H9l-5 4v-4H3Z M7 9h10 M7 13h6"
    }).toPath() }
    Canvas(Modifier.size(15.dp)) { withTransform({scale(size.width/24,size.height/24,pivot=Offset.Zero)}) {
        drawPath(path,ink,style=Stroke(1.75f,cap=StrokeCap.Round,join=StrokeJoin.Round))
    } }
}

@Composable internal fun QuoteStrip(quote: ChatReplyQuote, name: String, modifier: Modifier, composer: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val tint = when {
        composer -> GreenText.copy(alpha = .08f)
        Paper.luminance() < .5f -> Color(0xFFFFF5EB).copy(alpha = .09f)
        else -> Color(0xFF3C3027).copy(alpha = .07f)
    }
    Row(
        modifier.then(if (composer) Modifier.fillMaxWidth() else Modifier)
            .clip(shape).background(tint)
            .then(if (composer) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(2.5.dp).height(24.dp).background(GreenText.copy(alpha = .7f), RoundedCornerShape(50)))
        Text("${if (composer) "回复 " else ""}${if (quote.mine) "我" else name}：${quote.label()}",
            Modifier.weight(1f, fill = composer).padding(horizontal = 8.dp),
            maxLines = if (composer) 1 else 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = Muted)
        if (composer) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "取消引用" }.testTag("chat-composer-quote-clear"),
                contentAlignment = Alignment.Center) { Text("×", color = GreenText) }
        }
    }
}
