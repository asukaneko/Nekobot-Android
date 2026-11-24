package app.lianxu.independent

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.min

/** Approved v5 acting poses: planted-foot anticipation, tucked toes, landing absorption and delayed tail. */
@Composable internal fun CompanionBirds(modifier:Modifier=Modifier){
    val resolver=LocalContext.current.contentResolver
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun allowsMotion()=Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
    var motion by remember{mutableStateOf(allowsMotion())}
    var resumed by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(resolver){
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){motion=allowsMotion()}}
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose{resolver.unregisterContentObserver(observer)}
    }
    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer)}
    }
    val phase=if(motion&&resumed)rememberInfiniteTransition(label="共栖").animateFloat(0f,1f,infiniteRepeatable(tween(24000,easing=LinearEasing),RepeatMode.Restart),label="错时靠近")else rememberUpdatedState(0f)
    val artwork=ImageBitmap.imageResource(R.drawable.companion_birds_approved_v4)
    Canvas(modifier.width(104.dp).height(42.dp).testTag("companion-birds").clearAndSetSemantics{}){
        val t=phase.value*24f
        val leftStarts=LeftHopStarts;val rightStarts=RightHopStarts
        fun progress(starts:FloatArray)=starts.map{companionEase((t-it)/HopDurationSeconds)}
        val lp=progress(leftStarts);val rp=progress(rightStarts)
        val active=motion&&resumed
        val lx=if(active)-3f+3f*(lp[0]-lp[1])else 0f
        val rx=if(active)3f-3f*(rp[0]-rp[1])else 0f
        fun lift(starts:FloatArray)=starts.sumOf{start->val u=(t-start)/HopDurationSeconds;if(u>0f&&u<1f)4.0*u*(1.0-u)*4.5 else 0.0}.toFloat()
        fun talk(start:Float):Float{val x=t-start;return if(x>0f&&x<1.15f)(sin(PI*x/1.15)*(.35+.65*sin(PI*x*3.2)*sin(PI*x*3.2))).toFloat() else 0f}
        val cuddle=if(t in 9.8f..13.4f)sin(PI*(t-9.8f)/3.6).toFloat()else 0f
        val sourceScale=104f/1190f
        withTransform({scale(size.width/104f,size.height/42f,pivot=Offset.Zero);translate(0f,6f);scale(sourceScale,sourceScale,pivot=Offset.Zero);translate(-390f,-30f)}){
            clipRect(left=390f,top=30f,right=1580f,bottom=370f){
                drawPath(PerchUnderFeet,Color(0xFFA68B59),style=Stroke(9f,cap=StrokeCap.Round))
                clipPath(LeftBird,ClipOp.Difference){clipPath(RightBird,ClipOp.Difference){drawImage(artwork)}}
            }
            fun DrawScope.bird(left:Boolean,x:Float,y:Float,speech:Float,starts:FloatArray){
                val pose=companionMechanics(t,starts)
                val delayed=companionMechanics(t-.065f,starts)
                val direction=if(left)1f else -1f
                val outline=if(left)LeftBird else RightBird
                val head=if(left)LeftHead else RightHead
                val tail=if(left)LeftTail else RightTail
                val hipPivot=if(left)Offset(818f,264f)else Offset(1157f,273f)
                val neck=if(left)Offset(885f,159f)else Offset(1080f,171f)
                val tailRoot=if(left)Offset(709f,254f)else Offset(1250f,251f)
                val bodyAngle=direction*(11f*pose.crouch-4f*pose.tuck)
                val bodyY=26f*pose.crouch-3f*pose.tuck
                fun bodyPoint(point:Offset):Offset{
                    val angle=bodyAngle*PI/180;val v=point-hipPivot
                    return hipPivot+Offset((v.x*cos(angle)-v.y*sin(angle)).toFloat(),(v.x*sin(angle)+v.y*cos(angle)).toFloat()+bodyY)
                }
                withTransform({translate(x/sourceScale,y/sourceScale)}){
                    // Feet stay planted while the breast lowers; in flight the toes fold under the belly.
                    val hips=if(left)arrayOf(Offset(769f,267f),Offset(865f,265f))else arrayOf(Offset(1117f,275f),Offset(1190f,273f))
                    val feet=if(left)arrayOf(Offset(790f,309f),Offset(884f,299f))else arrayOf(Offset(1087f,305f),Offset(1159f,315f))
                    val legColor=if(left)Color(0xFF554F45)else Color(0xFF62533C)
                    for(i in hips.indices){
                        val hip=bodyPoint(hips[i]);val grounded=feet[i]
                        val folded=hip+Offset(-direction*5f,14f+i*3f)
                        val foot=grounded+(folded-grounded)*pose.tuck
                        val vector=foot-hip;val length=vector.getDistance().coerceAtLeast(1f)
                        val bend=sqrt((32f*32f-length*length/4).coerceAtLeast(0f)).coerceAtMost(25f)
                        val knee=(hip+foot)*.5f+Offset(-direction*bend,3f)
                        drawPath(Path().apply{moveTo(hip.x,hip.y);quadraticTo(knee.x,knee.y,foot.x,foot.y)},legColor,style=Stroke(7f,cap=StrokeCap.Round))
                        for(toe in 0..2){
                            val spread=toe*4f;val curl=pose.tuck
                            drawPath(Path().apply{
                                moveTo(foot.x-spread*.35f,foot.y)
                                cubicTo(foot.x+direction*(8f-spread),foot.y+5f,foot.x+direction*(12f-spread)*(1f-curl),foot.y+12f-spread*.3f,foot.x+direction*(5f-spread)*(1f-curl),foot.y+9f-spread*.4f)
                            },legColor,style=Stroke(4.8f,cap=StrokeCap.Round))
                        }
                    }
                    withTransform({translate(0f,bodyY);rotate(bodyAngle,hipPivot)}){
                        clipPath(outline){clipRect(left=390f,top=30f,right=1580f,bottom=if(left)280f else 284f){
                            clipPath(tail,ClipOp.Difference){clipPath(head,ClipOp.Difference){drawImage(artwork)}}
                        }}
                        // Tail follows the mass rather than leading or stopping with it.
                        withTransform({rotate(direction*(-11f*delayed.crouch+6f*delayed.tuck),tailRoot)}){
                            clipPath(outline){clipPath(tail){drawImage(artwork)}}
                        }
                        // Head leads attention, counterbalances the torso, then softly settles.
                        withTransform({rotate(-bodyAngle*.65f+direction*(3.5f*speech+2f*cuddle),neck)}){
                            val beak=if(left)LeftBeak else RightBeak
                            val hinge=if(left)Offset(946f,105f)else Offset(1017f,122f)
                            clipPath(outline){clipPath(head){clipPath(beak,ClipOp.Difference){drawImage(artwork)}}}
                            withTransform({rotate(-direction*22f*speech,hinge)}){clipPath(beak){clipRect(left=0f,top=0f,right=1971f,bottom=hinge.y+.7f){drawImage(artwork)}}}
                            withTransform({rotate(direction*22f*speech,hinge)}){clipPath(beak){clipRect(left=0f,top=hinge.y-.7f,right=1971f,bottom=399f){drawImage(artwork)}}}
                        }
                    }
                }
            }
            bird(true,lx,-lift(leftStarts),talk(.4f)+talk(11.8f),leftStarts)
            bird(false,rx,-lift(rightStarts),talk(1.85f)+talk(13.4f),rightStarts)
        }
    }
}
private const val HopDurationSeconds=.32f
private val LeftHopStarts=floatArrayOf(5f,21.3f)
private val RightHopStarts=floatArrayOf(8.5f,18.3f)
private fun companionEase(x:Float)=((1-cos(PI*x.coerceIn(0f,1f)))/2).toFloat()
private data class CompanionMechanics(val crouch:Float,val tuck:Float)
private fun companionMechanics(t:Float,starts:FloatArray):CompanionMechanics{
    var crouch=0f;var tuck=0f
    for(start in starts){
        val dt=t-start
        // Prepare on planted feet, release through takeoff, absorb the landing and recover.
        crouch+=when{
            dt in -.18f..0f->companionEase((dt+.18f)/.18f)
            dt>0f&&dt<.075f->1f-companionEase(dt/.075f)
            dt in HopDurationSeconds..HopDurationSeconds+.16f->sin(PI*(dt-HopDurationSeconds)/.16f).toFloat()
            else->0f
        }
        if(dt>0f&&dt<HopDurationSeconds)tuck+=sin(PI*dt/HopDurationSeconds).toFloat()
    }
    return CompanionMechanics(crouch.coerceIn(0f,1f),tuck.coerceIn(0f,1f))
}
// Masks articulate the approved bitmap; source pixels, palette and outline stay intact.
private val LeftBird=PathParser().parsePathString("M590 312 L640 260 L641 235 L750 151 L804 112 C840 55 899 43 945 86 L984 90 L984 123 L946 126 C943 174 940 232 917 255 L895 279 L897 294 L911 312 L890 329 L866 324 L852 303 L832 283 L814 280 L819 316 L795 335 L770 331 L768 302 L747 280 L714 270 L695 272 L635 314Z").toPath()
private val RightBird=PathParser().parsePathString("M976 104 L1012 104 C1041 66 1103 62 1142 110 L1182 152 L1334 244 L1350 279 L1328 291 L1276 269 L1244 273 L1201 290 L1178 309 L1185 327 L1150 334 L1133 321 L1145 299 L1158 285 L1125 280 L1104 301 L1111 319 L1080 324 L1060 310 L1073 290 L1087 280 C1040 263 1025 225 1021 186 L1009 145 L976 134Z").toPath()
private val LeftHead=PathParser().parsePathString("M775 40 L1000 40 L1000 165 L931 184 L878 181 L816 156 L775 139Z").toPath()
private val RightHead=PathParser().parsePathString("M960 50 L1155 50 L1170 153 L1123 177 L1075 183 L1015 166 L960 150Z").toPath()
private val LeftBeak=PathParser().parsePathString("M943 91 L984 91 L984 120 L943 120Z").toPath()
private val RightBeak=PathParser().parsePathString("M976 107 L1021 107 L1021 140 L976 140Z").toPath()
private val PerchUnderFeet=PathParser().parsePathString("M752 317 C805 314 864 304 923 298 M1052 302 C1098 313 1147 326 1202 325").toPath()

private val LeftTail=PathParser().parsePathString("M594 304 L649 243 L723 244 L715 266 L635 309Z").toPath()
private val RightTail=PathParser().parsePathString("M1230 241 L1275 235 L1352 268 L1351 291 L1320 292 L1242 267Z").toPath()
