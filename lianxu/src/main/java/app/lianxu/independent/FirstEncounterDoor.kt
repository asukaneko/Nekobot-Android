package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

internal val FirstEncounterPaper=Color(0xFFFFEED6)
internal val FirstEncounterSaver=listSaver<FirstEncounterState,String>(
    save={listOf(it.phase.name,it.destination?.name.orEmpty(),it.dismissed.toString())},
    restore={values->runCatching{FirstEncounterState(FirstEncounterPhase.valueOf(values[0]),values[1].takeIf{it.isNotEmpty()}?.let(FirstEncounterDestination::valueOf),dismissed=values.getOrNull(2)=="true").restored()}.getOrNull()}
)

/** Uses the two approved paintings unchanged; only their drawing transforms are animated. */
@Composable internal fun FirstEncounterDoor(
    state:FirstEncounterState,
    onState:(FirstEncounterState)->Unit,
    notice: @Composable () -> Unit,
    onReturnToHost: () -> Unit,
    onChoose:(FirstEncounterDestination)->Unit
){
    val resources=LocalContext.current.resources
    val paintings=remember(resources){
        val options=BitmapFactory.Options().apply{inScaled=false}
        BitmapFactory.decodeResource(resources,R.drawable.first_encounter_closed,options) to
            BitmapFactory.decodeResource(resources,R.drawable.first_encounter_approved,options)
    }
    val renderer=remember(paintings){DoorPaintingRenderer(paintings.first,paintings.second)}
    val scope=rememberCoroutineScope()
    val reduced=(scope.coroutineContext[MotionDurationScale]?.scaleFactor?:1f)<=0f
    val open=remember{Animatable(if(state.phase>=FirstEncounterPhase.ENTERING||reduced)1f else 0f)}
    val push=remember{Animatable(if(state.phase==FirstEncounterPhase.READY||reduced)1f else 0f)}
    val reveal=remember{Animatable(if(state.phase==FirstEncounterPhase.READY||reduced)1f else 0f)}
    val revealComplete by remember{derivedStateOf{reveal.value>=1f}}
    val currentOnState by rememberUpdatedState(onState)
    LaunchedEffect(state.phase,reduced){
        if(reduced){open.snapTo(1f);push.snapTo(1f);reveal.snapTo(1f);currentOnState(state.withoutMotion())}
        else when(state.phase){
            FirstEncounterPhase.CLOSED->Unit
            FirstEncounterPhase.OPENING->{open.animateTo(1f,tween(1800,easing=CubicBezierEasing(.3f,0f,.2f,1f)));currentOnState(state.doorOpened())}
            FirstEncounterPhase.ENTERING->{open.snapTo(1f);push.animateTo(1f,tween(1700,easing=CubicBezierEasing(.25f,0f,.2f,1f)));currentOnState(state.entered())}
            FirstEncounterPhase.READY->{open.snapTo(1f);push.snapTo(1f);reveal.animateTo(1f,tween(400))}
        }
    }
    val invitation=if(state.phase==FirstEncounterPhase.CLOSED)Modifier.semantics{contentDescription="轻触，开始相遇"}.clickable(
        role=Role.Button,onClickLabel="轻触，开始相遇"
    ){onState(state.start())}else Modifier
    Box(Modifier.fillMaxSize().testTag("first-encounter").then(invitation)){
        Canvas(Modifier.fillMaxSize()){
            drawIntoCanvas{renderer.draw(it.nativeCanvas,size.width,size.height,if(reduced)1f else open.value,if(reduced)1f else push.value)}
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            notice()
            ReturnHomeButton(
                onReturnToHost, Modifier.padding(start = 12.dp, top = 4.dp),
                background = FirstEncounterPaper.copy(alpha = 0.78f),
                textColor = Color(0xFF6B5847), borderColor = Color(0xFFD4C8B9)
            )
            if (state.phase == FirstEncounterPhase.READY) {
                val enabled = state.canChoose && (reduced || revealComplete)
                val ink = Color(0xFF6B5847)
                val white = Color(0xFFFFFDF9)
                val fade = Modifier.graphicsLayer { alpha = if (reduced) 1f else reveal.value }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp).then(fade).testTag("first-encounter-actions"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        HorizontalDivider(Modifier.width(46.dp), thickness = 1.dp, color = Color(0xFFB79B79))
                        Spacer(Modifier.height(20.dp))
                        Text("初次相遇", style = BrandStyle.copy(fontSize = 36.sp, letterSpacing = 3.sp),
                            color = Color(0xFF584535), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Text("写下她的性格与你们的关系，\n之后的每一次相处都会慢慢留下来。",
                            color = Color(0xFF786654), fontSize = 14.sp, lineHeight = 25.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = { onChoose(FirstEncounterDestination.CREATE) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth(.90f).height(56.dp).testTag("first-encounter-create"),
                            shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(
                                containerColor = ink, contentColor = white, disabledContainerColor = ink, disabledContentColor = white)) {
                            Text("创建角色", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        val milk = white.copy(alpha = 0.62f)
                        val secondaryInk = Color(0xFF514235)
                        OutlinedButton(onClick = { onChoose(FirstEncounterDestination.RESTORE) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth(.90f).height(52.dp).testTag("first-encounter-restore"),
                            shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, Color(0xFFD4C4B2)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = milk, contentColor = secondaryInk,
                                disabledContainerColor = milk, disabledContentColor = secondaryInk)) {
                            Text("恢复已有数据")
                        }
                    }
                }
                TextButton(onClick = { onState(state.dismiss()) }, enabled = enabled,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)
                        .then(fade).testTag("first-encounter-enter-home")) {
                    Text("先进入主页", color = Color(0xFF786654))
                }
            }
        }
    }
}

/** Native drawing objects belong to this remembered canvas, not to an individual animation frame. */
private class DoorPaintingRenderer(private val closed:Bitmap,private val approved:Bitmap){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val paper=BitmapShader(closed,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
    private val warmSource=Rect(540,40,1010,630)
    private val viewport=RectF()
    private val doorway=RectF(207f,283f,718f,1310f)
    private val face=ProjectedDoorPart(closed,Rect(208,284,718,1307))
    private val edge=ProjectedDoorPart(approved,Rect(225,235,244,1195))
    private val faceQuad=floatArrayOf(208f,284f,718f,284f,718f,1307f,208f,1307f)
    private val edgeQuad=FloatArray(8)

    fun draw(canvas:android.graphics.Canvas,width:Float,height:Float,open:Float,push:Float){
    viewport.set(0f,0f,width,height)
    // A blank section of the approved warm-light panel, excluding its painted button labels.
    canvas.drawBitmap(approved,warmSource,viewport,paint)
    if(push>=1f)return
    // Fade the advancing painting into this same background before the camera settles.
    val blend=((push-.5f)*2f).coerceIn(0f,1f)
    // Alpha 255 needs no offscreen group; reserve it for the actual crossfade only.
    if(blend>0f)canvas.saveLayerAlpha(0f,0f,width,height,((1f-blend)*255).toInt())else canvas.save()
    val fit=minOf(width/closed.width,height/closed.height)
    canvas.save()
    canvas.translate((width-closed.width*fit)/2f,(height-closed.height*fit)/2f)
    canvas.scale(fit,fit)
    // Advance into the opening, centring the view on light instead of revealing any room.
    canvas.translate((closed.width/2f-590f)*push,(closed.height/2f-810f)*push)
    canvas.scale(1f+5.5f*push,1f+5.5f*push,590f,810f)
    // Keep the complete original paper and its painted invitation; semantics supply accessibility.
    // Extend the painting's own edge paper into wide windows, without a pasted-image seam.
    paint.shader=paper
    canvas.drawPaint(paint)
    paint.shader=null
    if(open>0f){
    canvas.drawBitmap(approved,warmSource,doorway,paint)
    val freeX=718f-300f*open
    val top=284f-150f*open
    val bottom=1307f+150f*open
    // The thick free edge is sampled directly from the approved open door, not the frame.
    val thickness=1f+30f*open
    edgeQuad[0]=freeX;edgeQuad[1]=top;edgeQuad[2]=freeX+thickness;edgeQuad[3]=top+6f*open
    edgeQuad[4]=freeX+thickness;edgeQuad[5]=bottom-6f*open;edgeQuad[6]=freeX;edgeQuad[7]=bottom
    faceQuad[2]=freeX;faceQuad[3]=top;faceQuad[4]=freeX;faceQuad[5]=bottom
    edge.draw(canvas,edgeQuad,paint)
    face.draw(canvas,faceQuad,paint)
    }
    canvas.restore()
    canvas.restore()
}
}

private class ProjectedDoorPart(private val bitmap:Bitmap,private val source:Rect){
    private val target=RectF(0f,0f,source.width().toFloat(),source.height().toFloat())
    private val corners=floatArrayOf(0f,0f,target.right,0f,target.right,target.bottom,0f,target.bottom)
    private val matrix=Matrix()
    fun draw(canvas:android.graphics.Canvas,points:FloatArray,paint:Paint){
    matrix.setPolyToPoly(corners,0,points,0,4)
    canvas.save();canvas.concat(matrix)
    canvas.drawBitmap(bitmap,source,target,paint)
    canvas.restore()
}
}
