package app.lianxu.independent

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class PageTabs(val pager:PagerState,private val scope:CoroutineScope){
    val selected get()=pager.currentPage
    fun select(index:Int){scope.launch{pager.animateScrollToPage(index)}}
}

@Composable internal fun rememberPageTabs(count:Int):PageTabs {
    val pager=rememberPagerState{count}
    val scope=rememberCoroutineScope()
    return remember(pager,scope){PageTabs(pager,scope)}
}

@Composable internal fun PageTabContent(tabs:PageTabs,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable (Int)->Unit){
    val density=LocalDensity.current
    val insets=WindowInsets.systemGestures
    val edge=with(density){24.dp.toPx()}
    val left=maxOf(edge,insets.getLeft(density,LocalLayoutDirection.current).toFloat())
    val right=maxOf(edge,insets.getRight(density,LocalLayoutDirection.current).toFloat())
    // Reserve horizontal drags starting at the screen edges for Android Back.
    val edges=Modifier.pointerInput(left,right){
        awaitEachGesture{
            val down=awaitFirstDown(false,PointerEventPass.Initial)
            if(down.position.x>left && down.position.x<size.width-right)return@awaitEachGesture
            var horizontal=false
            do {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                val change=event.changes.firstOrNull{it.id==down.id}?:break
                val delta=change.position-down.position
                if(!horizontal && abs(delta.y)>viewConfiguration.touchSlop && abs(delta.y)>=abs(delta.x))break
                if(abs(delta.x)>viewConfiguration.touchSlop)horizontal=true
                if(horizontal)change.consume()
            }while(change.pressed)
        }
    }
    val saved=rememberSaveableStateHolder()
    HorizontalPager(state=tabs.pager,modifier=modifier.fillMaxSize().then(edges),userScrollEnabled=enabled,
        verticalAlignment=androidx.compose.ui.Alignment.Top){page->
        Box(Modifier.fillMaxSize().testTag("tab-panel-$page")){
            // A settled pager may keep a neighbour measured. Remove its focusable controls
            // until the next gesture, retaining saveable fields and the per-page list state.
            if(tabs.pager.isScrollInProgress || page==tabs.selected){
                saved.SaveableStateProvider(page){content(page)}
            }
        }
    }
}
