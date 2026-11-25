package app.lianxu.independent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import kotlin.math.*

private val IconStroke=Stroke(1.65f,cap=StrokeCap.Round,join=StrokeJoin.Round)

/** Original regular 24-unit paths: one stroke, no texture or filled silhouettes. */
@Composable internal fun NavigationIcon(index:Int,selected:Boolean) {
    val color=if(selected)GreenText else Muted
    Canvas(Modifier.size(25.dp)) {
        withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}) {
            when(index) {
                0->{
                    val body=Path().apply {
                        moveTo(8f,4f);lineTo(16f,4f);cubicTo(18.76f,4f,21f,6.24f,21f,9f)
                        lineTo(21f,13f);cubicTo(21f,15.76f,18.76f,18f,16f,18f);lineTo(10f,18f)
                        lineTo(6.1f,20.3f);quadraticTo(5.5f,20.65f,5.75f,19.95f);lineTo(6.5f,17.75f)
                        cubicTo(4.4f,17.1f,3f,15.2f,3f,13f);lineTo(3f,9f)
                        cubicTo(3f,6.24f,5.24f,4f,8f,4f);close()
                    }
                    drawPath(body,color,style=IconStroke)
                    for(x in listOf(8f,12f,16f))drawCircle(color,.7f,Offset(x,11f))
                }
                1->{
                    val book=Path().apply {addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(4.5f,3.5f,20.5f,20.5f),3f,3f))}
                    drawPath(book,color,style=IconStroke)
                    for(y in listOf(7.5f,12f,16.5f))drawLine(color,Offset(2.5f,y),Offset(6f,y),IconStroke.width,cap=StrokeCap.Round)
                    drawCircle(color,2.1f,Offset(12.6f,9f),style=IconStroke)
                    drawPath(Path().apply {moveTo(9.2f,16.2f);cubicTo(9.7f,12.7f,15.5f,12.7f,16f,16.2f)},color,style=IconStroke)
                }
                2->{
                    val points=(0 until 24).map {i->val angle=-PI/2+(i-.5)*PI/12;val radius=if(i%4<2)9.4 else 7.5;Offset(12f+(cos(angle)*radius).toFloat(),12f+(sin(angle)*radius).toFloat())}
                    val gear=Path().apply {
                        val start=(points.last()+points.first())/2f;moveTo(start.x,start.y)
                        points.forEachIndexed {i,p->val end=(p+points[(i+1)%points.size])/2f;quadraticTo(p.x,p.y,end.x,end.y)};close()
                    }
                    drawPath(gear,color,style=IconStroke);drawCircle(color,3f,Offset(12f,12f),style=IconStroke)
                }
                else->{
                    drawCircle(color,3.5f,Offset(12f,7f),style=IconStroke)
                    drawPath(Path().apply {moveTo(3.5f,20.5f);cubicTo(4.3f,15.3f,7.7f,13f,12f,13f);cubicTo(16.3f,13f,19.7f,15.3f,20.5f,20.5f)},color,style=IconStroke)
                }
            }
        }
    }
}

@Composable internal fun ControlIcon(kind:String,color:Color=Muted,box:androidx.compose.ui.unit.Dp=24.dp) {
    val iconColor=color
    Canvas(Modifier.size(box)) {
        withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}) {
            when(kind) {
                "waveform"->drawPath(Path().apply { moveTo(2f,12f);lineTo(6f,12f);lineTo(9f,4f);lineTo(13f,20f);lineTo(16f,9f);lineTo(18f,12f);lineTo(22f,12f) },iconColor,style=IconStroke)
                "landscape"->{drawPath(Path().apply{addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(3f,4f,21f,20f),3f,3f));moveTo(4f,17f);lineTo(9f,11f);lineTo(14f,17f);lineTo(17f,13f);lineTo(20f,17f)},iconColor,style=IconStroke);drawCircle(iconColor,1.5f,Offset(16f,8f),style=IconStroke)}
                "camera"->{drawPath(Path().apply{addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(2f,6f,16f,19f),2.5f,2.5f));moveTo(16f,10f);lineTo(22f,7f);lineTo(22f,18f);lineTo(16f,15f)},iconColor,style=IconStroke)}
                "brain"->{drawPath(Path().apply{moveTo(12f,5f);cubicTo(9f,1f,5f,3f,5f,6f);cubicTo(1f,7f,1f,12f,4f,14f);cubicTo(2f,18f,6f,21f,9f,20f);cubicTo(11f,22f,12f,20f,12f,18f);lineTo(12f,5f);cubicTo(15f,1f,19f,3f,19f,6f);cubicTo(23f,7f,23f,12f,20f,14f);cubicTo(22f,18f,18f,21f,15f,20f);cubicTo(13f,22f,12f,20f,12f,18f);moveTo(5f,7f);cubicTo(8f,6f,9f,8f,8f,10f);moveTo(4f,14f);cubicTo(7f,12f,9f,14f,8f,17f);moveTo(19f,7f);cubicTo(16f,6f,15f,8f,16f,10f);moveTo(20f,14f);cubicTo(17f,12f,15f,14f,16f,17f)},iconColor,style=IconStroke)}
                "photo-camera"->{
                    drawPath(Path().apply{moveTo(3f,7f);lineTo(7f,7f);lineTo(9f,4f);lineTo(15f,4f);lineTo(17f,7f);lineTo(21f,7f);lineTo(21f,20f);lineTo(3f,20f);close()},iconColor,style=IconStroke)
                    drawCircle(iconColor,4f,Offset(12f,13f),style=IconStroke)
                }
                "eye","eye-off"->{
                    drawPath(Path().apply{moveTo(2f,12f);cubicTo(7f,4f,17f,4f,22f,12f);cubicTo(17f,20f,7f,20f,2f,12f);close()},iconColor,style=IconStroke)
                    drawCircle(iconColor,3f,Offset(12f,12f),style=IconStroke)
                    if(kind=="eye-off")drawLine(iconColor,Offset(3f,3f),Offset(21f,21f),IconStroke.width,cap=StrokeCap.Round)
                }
                "edit"->{drawPath(Path().apply{moveTo(4f,20f);lineTo(4.8f,16f);lineTo(15.5f,5.3f);cubicTo(16.4f,4.4f,17.8f,4.4f,18.7f,5.3f);cubicTo(19.6f,6.2f,19.6f,7.6f,18.7f,8.5f);lineTo(8f,19.2f);close()},iconColor,style=IconStroke);drawLine(iconColor,Offset(13.5f,7.3f),Offset(16.7f,10.5f),IconStroke.width,cap=StrokeCap.Round)}
                "heart"->{drawPath(Path().apply{moveTo(12f,20f);cubicTo(5f,15f,3f,11.5f,3f,8.5f);cubicTo(3f,5.8f,5.1f,4f,7.5f,4f);cubicTo(9.4f,4f,10.9f,5.1f,12f,6.8f);cubicTo(13.1f,5.1f,14.6f,4f,16.5f,4f);cubicTo(18.9f,4f,21f,5.8f,21f,8.5f);cubicTo(21f,11.5f,19f,15f,12f,20f);close()},iconColor,style=IconStroke)}
                "chat"->{drawPath(Path().apply{addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(4f,4.5f,20f,17f),3f,3f));moveTo(8f,17f);lineTo(7f,21f);lineTo(12f,17f)},iconColor,style=IconStroke);drawLine(iconColor,Offset(8f,9f),Offset(16f,9f),IconStroke.width,cap=StrokeCap.Round);drawLine(iconColor,Offset(8f,12.5f),Offset(13f,12.5f),IconStroke.width,cap=StrokeCap.Round)}
                "more"->{for(x in listOf(5f,12f,19f))drawCircle(iconColor,1.5f,Offset(x,12f))}
                "relationship"->{drawCircle(iconColor,4f,Offset(8f,11f),style=IconStroke);drawCircle(iconColor,4f,Offset(16f,13f),style=IconStroke)}
                "nodes"->{
                    drawLine(iconColor,Offset(10.5f,7f),Offset(6.5f,16f),IconStroke.width,cap=StrokeCap.Round)
                    drawLine(iconColor,Offset(8f,18f),Offset(16f,18f),IconStroke.width,cap=StrokeCap.Round)
                    drawLine(iconColor,Offset(13.5f,7f),Offset(17.5f,16f),IconStroke.width,cap=StrokeCap.Round)
                    for(center in listOf(Offset(12f,5f),Offset(5f,18f),Offset(19f,18f)))drawCircle(iconColor,2.8f,center,style=IconStroke)
                }
                "services"->{for(y in listOf(4f,13f)){
                    drawPath(Path().apply{addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(3f,y,21f,y+7f),3f,3f))},iconColor,style=IconStroke)
                    drawCircle(iconColor,.8f,Offset(15f,y+3.5f));drawCircle(iconColor,.8f,Offset(18f,y+3.5f))
                }}
                "memory"->{drawPath(Path().apply{moveTo(5f,4f);lineTo(19f,4f);lineTo(19f,21f);lineTo(12f,17.5f);lineTo(5f,21f);close()},iconColor,style=IconStroke);drawLine(iconColor,Offset(9f,9f),Offset(15f,9f),IconStroke.width,cap=StrokeCap.Round)}
                "plug"->{
                    drawPath(Path().apply {moveTo(7f,7f);lineTo(17f,7f);lineTo(17f,12f);cubicTo(17f,14.76f,14.76f,17f,12f,17f);cubicTo(9.24f,17f,7f,14.76f,7f,12f);close()},iconColor,style=IconStroke)
                    for(x in listOf(9f,15f))drawLine(iconColor,Offset(x,3f),Offset(x,7f),IconStroke.width,cap=StrokeCap.Round)
                    drawPath(Path().apply {moveTo(12f,17f);lineTo(12f,18.5f);cubicTo(12f,22f,6f,22f,6f,18.5f)},iconColor,style=IconStroke)
                }
                "search"->{drawCircle(iconColor,6.5f,Offset(10f,10f),style=IconStroke);drawLine(iconColor,Offset(14.6f,14.6f),Offset(20.5f,20.5f),IconStroke.width,cap=StrokeCap.Round)}
                "model"->{
                    drawPath(Path().apply {addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(6f,6f,18f,18f),2.5f,2.5f))},iconColor,style=IconStroke)
                    for(p in listOf(9f,15f)) {
                        drawLine(iconColor,Offset(p,3f),Offset(p,6f),IconStroke.width,cap=StrokeCap.Round);drawLine(iconColor,Offset(p,18f),Offset(p,21f),IconStroke.width,cap=StrokeCap.Round)
                        drawLine(iconColor,Offset(3f,p),Offset(6f,p),IconStroke.width,cap=StrokeCap.Round);drawLine(iconColor,Offset(18f,p),Offset(21f,p),IconStroke.width,cap=StrokeCap.Round)
                    };drawCircle(iconColor,2.5f,Offset(12f,12f),style=IconStroke)
                }
                "list"->{for(y in listOf(6f,12f,18f)){drawCircle(iconColor,.8f,Offset(4f,y));drawLine(iconColor,Offset(8f,y),Offset(20f,y),IconStroke.width,cap=StrokeCap.Round)}}
                "database"->{
                    drawPath(Path().apply{moveTo(4f,6f);cubicTo(4f,1f,20f,1f,20f,6f);cubicTo(20f,11f,4f,11f,4f,6f);lineTo(4f,18f);cubicTo(4f,23f,20f,23f,20f,18f);lineTo(20f,6f);moveTo(4f,12f);cubicTo(4f,17f,20f,17f,20f,12f)},iconColor,style=IconStroke)
                }
                "document","import-file"->{
                    drawPath(Path().apply{moveTo(5f,3f);lineTo(14f,3f);lineTo(19f,8f);lineTo(19f,21f);lineTo(5f,21f);close();moveTo(14f,3f);lineTo(14f,8f);lineTo(19f,8f)},iconColor,style=IconStroke)
                    if(kind=="document"){for(y in listOf(12f,16f))drawLine(iconColor,Offset(8f,y),Offset(16f,y),IconStroke.width,cap=StrokeCap.Round)}
                    else drawPath(Path().apply{moveTo(12f,19f);lineTo(12f,11f);moveTo(8.5f,14.5f);lineTo(12f,11f);lineTo(15.5f,14.5f)},iconColor,style=IconStroke)
                }
                "storage"->{
                    drawPath(Path().apply {addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(5f,8f,19f,21f),2f,2f));addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(3.5f,3.5f,20.5f,8f),1.5f,1.5f))},iconColor,style=IconStroke)
                    drawLine(iconColor,Offset(9.5f,12f),Offset(14.5f,12f),IconStroke.width,cap=StrokeCap.Round)
                }
                "info"->{drawCircle(iconColor,9f,Offset(12f,12f),style=IconStroke);drawCircle(iconColor,.8f,Offset(12f,7.5f));drawLine(iconColor,Offset(12f,11f),Offset(12f,16.5f),IconStroke.width,cap=StrokeCap.Round)}
                "attach"->{drawPath(Path().apply{moveTo(16f,6f);cubicTo(13f,3f,10f,5f,8f,7f);lineTo(5f,10f);cubicTo(1f,14f,4f,20f,8f,20f);cubicTo(10f,20f,12f,18f,13f,17f);lineTo(19f,11f);cubicTo(21f,8f,18f,4f,15f,7f);lineTo(8f,14f);cubicTo(6f,16f,8f,18f,10f,16f);lineTo(15f,11f)},iconColor,style=IconStroke)}
                "send"->{drawPath(Path().apply{moveTo(4.5f,4.5f);lineTo(21f,12f);lineTo(4.5f,19.5f);lineTo(7f,12f);close();moveTo(7f,12f);lineTo(20f,12f)},iconColor,style=IconStroke)}
                "chevron"->{drawPath(Path().apply{moveTo(9f,6f);lineTo(15f,12f);lineTo(9f,18f)},iconColor,style=IconStroke)}
                "up"->{drawPath(Path().apply{moveTo(6f,15f);lineTo(12f,9f);lineTo(18f,15f)},iconColor,style=IconStroke)}
                "plus"->{drawLine(iconColor,Offset(4f,12f),Offset(20f,12f),IconStroke.width,cap=StrokeCap.Round);drawLine(iconColor,Offset(12f,4f),Offset(12f,20f),IconStroke.width,cap=StrokeCap.Round)}
                "back"->drawPath(Path().apply {moveTo(14.5f,5f);lineTo(7.5f,12f);lineTo(14.5f,19f)},iconColor,style=IconStroke)
                else->{drawCircle(iconColor,9f,Offset(12f,12f),style=IconStroke);drawLine(iconColor,Offset(7f,12f),Offset(17f,12f),IconStroke.width,cap=StrokeCap.Round);drawLine(iconColor,Offset(12f,7f),Offset(12f,17f),IconStroke.width,cap=StrokeCap.Round)}
            }
        }
    }
}
