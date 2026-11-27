package app.lianxu.independent

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import java.util.Calendar

internal enum class MoodSky(val label:String,val a:Color,val b:Color,val c:Color,val x:Float,val outline:String) {
    MISSING("想念",Color(0xFFEFD3A7),Color(0xFFD8C7B4),Color(0xFFE8C9BE),0.18f,"M27 7C15 7 8 16 10 26S25 42 36 32C20 36 13 18 27 7Z M36 9Q36 14 40 15Q36 16 35 21Q35 16 31 15Q35 14 36 9Z M43 5l.2 .2"),
    HAPPY("开心",Color(0xFFF4DAB0),Color(0xFFF5E5C8),Color(0xFFEAD4B8),0.08f,"M25 16C36 15 38 31 28 33C15 36 12 18 25 16Z M26 4l-.6 6 M39 11l-4 4 M41 23l4-1 M37 35l4 3 M27 39l1 5 M15 35l-4 5 M10 26l-6 1 M14 14l-4-4"),
    CALM("平静",Color(0xFFE8E2D6),Color(0xFFD9DFD5),Color(0xFFEFE8DC),0.42f,"M3 17Q7 18 9 14Q10 10 14 12Q18 4 23 12Q26 12 28 15 M5 37Q23 40 43 32 M19 24Q24 27 28 22C31 15 37 16 38 22L42 24L38 25C35 33 27 35 19 24Z M28 33l1 3 M31 32l2 3 M25 26q4 3 7-1"),
    HURT("低落",Color(0xFFCED5D6),Color(0xFFDCD6CE),Color(0xFFD4C9C2),0.67f,"M19 5C19 11 13 16 15 19Q19 23 20 17Q20 12 19 5Z M32 15C32 19 28 23 29 26Q32 29 33 25Q33 21 32 15Z M25 32C11 32 5 35 8 38C12 43 43 40 42 35Q40 32 34 32 M18 36q6 2 13 0"),
    ANGRY("生气",Color(0xFFEAC4B5),Color(0xFFE5D2C3),Color(0xFFDFC0B4),0.27f,"M3 23C12 27 20 18 18 13C16 8 10 12 13 16 M9 33C19 36 29 23 35 26C41 29 36 36 32 32 M27 17Q26 8 40 6Q38 16 27 17Z M25 20l9-9"),
    KNOT("有心结",Color(0xFFD7CBC0),Color(0xFFCBBFB6),Color(0xFFE4D7CB),0.59f,"M3 37C12 38 18 28 23 22C32 10 42 10 40 18C39 24 27 29 21 25C13 20 12 9 18 10Q25 10 26 20 M27 26C28 32 34 39 44 36")
}

internal fun moodSky(s:RelationState):MoodSky {
    val strongest=RelationshipSummary.mood(s).primary
    if ((s.tension>=.3 && s.tension>=strongest.value) || (s.repairable.isNotEmpty() && strongest.value<.05)) return MoodSky.KNOT
    if (strongest.value<.05) return MoodSky.CALM
    return when(strongest.key) {
        "missing"->MoodSky.MISSING
        "happy"->MoodSky.HAPPY
        "unhappy"->MoodSky.ANGRY
        else->MoodSky.HURT
    }
}

@Composable internal fun MoodHero(view:RelationshipView, correct:(SourceRef)->Unit) {
    val sky=if(view.attributionIncomplete)MoodSky.CALM else moodSky(view.state)
    val morning=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val period=when(morning){in 5..10->"清晨";in 11..16->"午后";in 17..19->"薄暮";else->"夜间"}
    val base=Chrome
    val dark=base.luminance()<.3f
    val ink=Ink
    val outline=remember(sky){PathParser().parsePathString(sky.outline).toPath()}
    BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(.42f).heightIn(min=130.dp).testTag("mood-hero")) {
        Canvas(Modifier.matchParentSize()) {
            listOf(sky.a to Offset(sky.x,.36f),sky.b to Offset(.85f,.30f),sky.c to Offset(.50f,.58f)).forEach { (color,point) ->
                val center=Offset(size.width*point.x,size.height*point.y)
                drawRect(Brush.radialGradient(listOf(color.copy(alpha=if(dark).20f else .86f),color.copy(alpha=0f)), center, size.width*.76f))
            }
            drawRect(Brush.verticalGradient(listOf(base.copy(alpha=0f),base),startY=size.height*.48f))
            drawRect(Brush.verticalGradient(0f to base, .28f to base.copy(alpha=.72f),
                .62f to base.copy(alpha=.24f), 1f to base.copy(alpha=0f), endY=size.height*.36f))
        }
        Canvas(Modifier.align(Alignment.TopEnd).padding(14.dp).size(46.dp)) {
            val factor=size.width/48f
            scale(factor,factor,Offset.Zero){drawPath(outline, ink.copy(alpha=.7f),style=Stroke(1.4.dp.toPx()/factor,cap=StrokeCap.Round,join=StrokeJoin.Round))}
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min=maxHeight).padding(horizontal=20.dp,vertical=12.dp),
            verticalArrangement=Arrangement.SpaceBetween) {
            Text(if(view.attributionIncomplete)period else "$period · "+(if(sky==MoodSky.HURT)RelationshipSummary.mood(view.state).primary.label else sky.label),color=Muted,style=MaterialTheme.typography.labelSmall)
            Text(when{
                view.attributionIncomplete->RelationshipPrompt.UNVERIFIED_STATUS
                sky==MoodSky.KNOT->"心里还有件事放不下"
                sky==MoodSky.CALM->"此刻，心里很平静"
                sky==MoodSky.MISSING->RelationshipSummary.degree(view.state.missing)+"想你"
                else->"心里"+RelationshipSummary.mood(view.state).sentence
            },fontFamily=FontFamily.Serif,fontSize=26.sp,color=ink)
            if (!view.attributionIncomplete) {
                val summary = listOf(RelationshipSummary.trust(view.state.trust), RelationshipSummary.closeness(view.state.closeness))
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (summary.isNotEmpty()) Text(summary, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            val visibleNote=view.innerNote?.takeUnless{view.attributionIncomplete}
            val savedNote=remember{mutableStateOf(visibleNote)}
            if(visibleNote!=null)SideEffect{savedNote.value=visibleNote}
            SoftVisibility(visibleNote!=null){ val note=visibleNote?:savedNote.value?:return@SoftVisibility
                Row(Modifier.fillMaxWidth().background(Paper.copy(alpha=.8f)).height(IntrinsicSize.Min).testTag("mood-inner-note"),verticalAlignment=Alignment.CenterVertically) {
                    Spacer(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFFC49352)))
                    Column(Modifier.weight(1f).padding(10.dp)) {
                        Text("小心事",style=MaterialTheme.typography.labelSmall,color=Muted)
                        Text(note.text,fontFamily=FontFamily.Serif,color=ink)
                    }
                    TextButton({correct(note.source)},modifier=Modifier.testTag("inner-note-correct")){Text("记错了",style=MaterialTheme.typography.labelSmall,color=Muted)}
                }
            }
        }
    }
}
