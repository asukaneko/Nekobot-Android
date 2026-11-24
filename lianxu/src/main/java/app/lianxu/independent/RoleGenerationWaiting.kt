package app.lianxu.independent

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/** Presentation only: request ownership and cancellation stay in AppState. */
@Composable internal fun RoleGenerationWaiting(compact:Boolean=false,onFrame:((Float,Boolean)->Unit)?=null){
    val resolver=LocalContext.current.contentResolver
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember{mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    fun reduced()=Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
    var reduce by remember{mutableStateOf(reduced())}
    DisposableEffect(lifecycle,resolver){
        val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        val setting=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){reduce=reduced()}}
        lifecycle.addObserver(observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,setting)
        onDispose{lifecycle.removeObserver(observer);resolver.unregisterContentObserver(setting)}
    }
    val animate=resumed&&!reduce
    var phase by remember{mutableFloatStateOf(0f)}
    LaunchedEffect(animate){
        if(animate){
            var previous=SystemClock.uptimeMillis()
            while(true){
                delay(50)
                val now=SystemClock.uptimeMillis()
                phase=(phase+(now-previous)/9600f)%1f
                previous=now
            }
        }
    }
    if(onFrame!=null){val p=phase;SideEffect{onFrame(p,animate)}}
    Column(Modifier.fillMaxWidth().padding(vertical=if(compact)8.dp else 40.dp).testTag("role-generation-waiting").semantics(mergeDescendants=true){liveRegion=LiveRegionMode.Polite},horizontalAlignment=Alignment.CenterHorizontally){
     val ink=Muted
     Canvas(Modifier.size(if(compact)168.dp else 240.dp,if(compact)133.dp else 190.dp)){
      val phase=if(animate)phase else .15f
      val u=size.width/240f
      fun card(index:Int){
       val t=(phase+index*.5f)%1f
       val wave=(.5f-.5f*cos(t*2f*PI.toFloat()))
       val alpha=.18f+.82f*wave
       val x=(if(index==0)50f else 92f)+sin(t*2f*PI.toFloat())*5f
       val y=33f+(1f-wave)*17f
       val c=ink.copy(alpha=alpha*.75f)
       translate(x*u,y*u){
        rotate(if(index==0)-5f else 5f,pivot=Offset(48*u,62*u)){
         drawRoundRect(Color.White.copy(alpha=alpha),size=Size(96*u,124*u),cornerRadius=CornerRadius(15*u))
         drawRoundRect(c.copy(alpha=alpha*.42f),size=Size(96*u,124*u),cornerRadius=CornerRadius(15*u),style=Stroke(1.2f*u))
         drawCircle(c,10*u,Offset(48*u,35*u),style=Stroke(1.5f*u))
         drawArc(c,195f,150f,false,Offset(29*u,46*u),Size(38*u,26*u),style=Stroke(1.5f*u,cap=StrokeCap.Round))
         drawLine(c.copy(alpha=alpha*.48f),Offset(24*u,88*u),Offset(72*u,88*u),2*u,StrokeCap.Round)
         drawLine(c.copy(alpha=alpha*.27f),Offset(32*u,99*u),Offset(64*u,99*u),2*u,StrokeCap.Round)
        }
       }
      }
      if(phase<.5f){card(1);card(0)}else{card(0);card(1)}
     }
        Text("正在构思角色…",style=MaterialTheme.typography.bodyMedium,color=Muted)
        if(compact)Text("上一组候选仍保留",style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.padding(top=4.dp))
    }
}
