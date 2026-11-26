package app.lianxu.independent

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.PathParser
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

private class BadgeArt(val paper:Color,val ink:Color,body:String,detail:String){
    val shape=PathParser().parsePathString(body).toPath()
    val lines=detail.takeIf{it.isNotEmpty()}?.let{PathParser().parsePathString(it).toPath()}
}
private val badgeArt=mapOf(
    "voice" to BadgeArt(Color(0xFFE3ECE5),Color(0xFF4C7759),"M4 11h2v2H4Z","M8 8v8 M12 4v16 M16 7v10 M20 10v4"),
    "play" to BadgeArt(Color(0xFFF5E9DA),Color(0xFFA66E38),"M8.5 6.4c0-1.15 1.26-1.85 2.25-1.24l8.35 5.1c.94.58.94 1.94 0 2.52l-8.35 5.1c-.99.61-2.25-.09-2.25-1.24V6.4Z",""),
    "design" to BadgeArt(Color(0xFFF5E9DA),Color(0xFFA66E38),"M10.5 4.2 Q11.5 9.5 16.5 11.5 Q11.5 13.5 10.5 19 Q9.5 13.5 4.5 11.5 Q9.5 9.5 10.5 4.2Z","M18 4v4.2 M15.9 6.1h4.2 M17.2 15.8v2.8 M15.8 17.2h2.8"),
    "clone" to BadgeArt(Color(0xFFE3ECE5),Color(0xFF4C7759),"M8.8 6.7a3.2 3.2 0 0 1 6.4 0v3.8a3.2 3.2 0 0 1 -6.4 0Z","M5.6 10.8a6.4 6.4 0 0 0 12.8 0 M12 17.2v3.3 M9.2 20.5h5.6"),
    "review" to BadgeArt(Color(0xFFEFE6F2),Color(0xFF7C588C),"M14.5 4.2a7.3 7.3 0 1 0 5.3 12.2A6.2 6.2 0 0 1 14.5 4.2Z","M17.5 5.2v3.6M15.7 7h3.6"),
    "embedding" to BadgeArt(Color(0xFFE2EAF0),Color(0xFF4B6B88),"M12 3.1a2.4 2.4 0 1 0 .01 0Z M6 15.1a2.4 2.4 0 1 0 .01 0Z M18 15.1a2.4 2.4 0 1 0 .01 0Z","M10.5 7.5 6.8 15.5M13.5 7.5l3.7 8M8.5 17.5h7"),
    "model" to BadgeArt(Color(0xFFF5E9DA),Color(0xFFA66E38),"M11 3 Q12 9 18 12 Q12 14 10 21 Q9 15 3 12 Q9 10 11 3Z","M18 3v5 M15.5 5.5h5"),
    "purposes" to BadgeArt(Color(0xFFEFE6F2),Color(0xFF7C588C),"M8 5a3 3 0 1 0 .1 0Z M16 13a3 3 0 1 0 .1 0Z","M3 8h2 M11 8h10 M3 16h10 M19 16h2"),
    "memory" to BadgeArt(Color(0xFFF4E5DC),Color(0xFFB4634A),"M6 4 Q6 3 8 3 H16 Q18 3 18 5 V21 L12 17 L6 21Z","M12 6l1 3 3 1-3 1-1 3-1-3-3-1 3-1Z"),
    "tools" to BadgeArt(Color(0xFFE3ECE5),Color(0xFF4C7759),"M4 4h6v6H4Q3 10 3 9V5Q3 4 4 4Z M15 4h4q1 0 1 1v4q0 1-1 1h-4q-1 0-1-1V5q0-1 1-1Z M4 14h5q1 0 1 1v4q0 1-1 1H4q-1 0-1-1v-4q0-1 1-1Z M17 14a3.2 3.2 0 1 0 .1 0Z",""),
    "archive" to BadgeArt(Color(0xFFE2EAF0),Color(0xFF4B6B88),"M3 5Q3 4 5 4H19Q21 4 21 6V9H3Z M5 9H19V18Q19 20 17 20H7Q5 20 5 18Z","M10 12v2h4v-2"),
    "updates" to BadgeArt(Color(0xFFEAE8E2),Color(0xFF6E655A),"M5 4Q5 3 7 3H14L19 8V19Q19 21 17 21H7Q5 21 5 19Z","M14 3v5h5 M8 12h8 M8 16h6"),
    "about" to BadgeArt(Color(0xFFF2EAE4),Color(0xFF8A6756),"M12 20C8 17 2 13 3 8C4 3 9 3 12 7C16 2 21 4 21 9C21 13 16 18 12 20Z","M6 9q0-2 2-2"),
    "edit" to BadgeArt(Color(0xFFF5E9DA),Color(0xFFA66E38),"M4 19l1-5L16 3q1-1 2 0l3 3L9 18Z","M14 5l4 4 M4 21h16"),
    "chat" to BadgeArt(Color(0xFFE3ECE5),Color(0xFF4C7759),"M4 4h15q2 0 2 2v9q0 2-2 2h-8l-5 4v-4H4q-2 0-2-2V6q0-2 2-2Z","M7 9h10 M7 12h7"),
    "list" to BadgeArt(Color(0xFFEFE6F2),Color(0xFF7C588C),"M3 5q5-2 9 1q5-3 9-1v14q-5-1-9 2q-4-3-9-2Z","M12 6v15 M5.5 9q2-.5 4 .5 M15 10l3-1")
)

@Composable internal fun DirectoryBadge(icon:String){
    val art=badgeArt.getValue(when(icon) { "heart" -> "about"; "playing" -> "voice"; else -> icon })
    val playback = icon == "play" || icon == "playing"
    val wave = if (icon == "playing" && android.animation.ValueAnimator.areAnimatorsEnabled())
        rememberInfiniteTransition(label = "试听").animateFloat(.75f, 1.1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "声波") else null
    val dark=Paper.luminance()<.3f
    val ground=if(dark)lerp(Paper,art.ink,.24f) else art.paper
    val ink=if(dark)lerp(art.ink,art.paper,.62f) else art.ink
    Box(Modifier.size(if (playback) 38.dp else 36.dp).testTag("badge-$icon").background(ground,RoundedCornerShape(if (playback) 12.dp else 11.dp)),contentAlignment=Alignment.Center){
        Canvas(Modifier.size(20.dp)){
            withTransform({scale(size.width/24f,size.height/24f,pivot=Offset.Zero)}){
                if (icon == "playing") {
                    val scale = wave?.value ?: 1f
                    listOf(5f * scale, 7.5f / scale, 4f * scale).forEachIndexed { index, half ->
                        val x = 7.3f + index * 4.7f
                        drawLine(ink, Offset(x,12f-half), Offset(x,12f+half), 2.6f, StrokeCap.Round)
                    }
                    return@withTransform
                }
                drawPath(art.shape,ink.copy(alpha=if (playback) .18f else .14f))
                drawPath(art.shape,ink,style=if (playback) Stroke(1.7f, cap=StrokeCap.Round, join=StrokeJoin.Round) else IconStroke)
                art.lines?.let{drawPath(it,ink,style=IconStroke)}
            }
        }
    }
}

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
