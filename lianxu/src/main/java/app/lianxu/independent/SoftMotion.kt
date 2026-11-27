package app.lianxu.independent

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

@Composable internal fun <T> SoftPage(target:T,direction:(T,T)->Int={_,_->0},content:@Composable (T)->Unit){
    AnimatedContent(targetState=target,label="页面",transitionSpec={
        val sign=direction(initialState,targetState)
        val enter=fadeIn(tween(190))+slideInHorizontally(tween(220)){if(sign>0)it/6 else 0}
        val exit=fadeOut(tween(160))+slideOutHorizontally(tween(200)){if(sign<0)it/6 else 0}
        (enter togetherWith exit).using(null)
    }){content(it)}
}
@Composable internal fun SoftVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + expandVertically(tween(200)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(180))
    ) { content() }
}
@Composable internal fun softColor(color:Color)=animateColorAsState(color,tween(180),label="选中色").value
@Composable internal fun Modifier.pressScale(source:MutableInteractionSource):Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed).97f else 1f,tween(160),label="轻按")
    return graphicsLayer{scaleX=scale;scaleY=scale}
}
