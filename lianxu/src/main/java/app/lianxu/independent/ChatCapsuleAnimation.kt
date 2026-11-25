package app.lianxu.independent

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

internal val LocalChatCapsuleMotion=staticCompositionLocalOf{emptyMap<String,ChatCapsuleMotionEvent>()}
internal val LocalChatCapsuleOwner=staticCompositionLocalOf{""}
internal fun capsuleMotionNow()=System.nanoTime()/1_000_000

/** Only this display coroutine waits. No tool, reply, data or click handler awaits it. */
@Composable internal fun chatCapsuleReveal(id:String,ready:Boolean=true):Float {
    val event=LocalChatCapsuleMotion.current[id]
    val progress=remember(id,event,ready){Animatable(capsuleInitialReveal(event,ready,capsuleMotionNow()))}
    LaunchedEffect(id,event,ready){
        if(event==null){progress.snapTo(1f);return@LaunchedEffect}
        if(!ready){progress.snapTo(0f);return@LaunchedEffect}
        delay(event.waitBeforeReveal(capsuleMotionNow()))
        val now=capsuleMotionNow();val remaining=event.remaining(now)
        progress.snapTo(event.progress(now))
        // remaining is real scaled time; Compose applies that scale to the tween once.
        if(remaining>0)progress.animateTo(1f,tween(kotlin.math.ceil(remaining/event.scale.toDouble()).toInt(),easing=LinearEasing))else progress.snapTo(1f)
    }
    return if(event==null)1f else if(ready)progress.value else 0f
}

@Composable internal fun ChatCapsuleLabel(result:String,reveal:Float,style:TextStyle,color:Color,modifier:Modifier=Modifier,loading:String="正在处理…",loadingColor:Color=Muted,textAlign:TextAlign=TextAlign.Start){
    if(reveal>=1f){Text(result,modifier,style=style,color=color,textAlign=textAlign);return}
    Box(modifier){
        Text(loading,Modifier.graphicsLayer{alpha=1f-reveal}.then(if(reveal>0f)Modifier.clearAndSetSemantics{}else Modifier),style=style,color=loadingColor,textAlign=textAlign)
        Text(result,Modifier.graphicsLayer{alpha=reveal}.then(if(reveal<=0f)Modifier.clearAndSetSemantics{}else Modifier),style=style,color=color,textAlign=textAlign)
    }
}
