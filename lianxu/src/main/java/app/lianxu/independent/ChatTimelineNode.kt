package app.lianxu.independent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A visual rail only: it never creates, groups or reorders a thinking/tool event. */
@Composable internal fun ChatTimelineNode(id:String,modifier:Modifier=Modifier,first:Boolean=true,last:Boolean=true,content:@Composable ()->Unit){
    val color=Muted.copy(alpha=.5f);val fontScale=LocalDensity.current.fontScale
    Column(modifier.testTag("timeline-node-$id").drawBehind{
        val x=3.dp.toPx();val y=(4+9*fontScale).dp.toPx().coerceAtMost(size.height)
        drawLine(color,Offset(x,if(first)y else 0f),Offset(x,if(last)y else size.height),1.dp.toPx())
        drawCircle(color,2.5.dp.toPx(),Offset(x,y))
    }.padding(start=16.dp,bottom=if(last)0.dp else 4.dp)){content()}
}
