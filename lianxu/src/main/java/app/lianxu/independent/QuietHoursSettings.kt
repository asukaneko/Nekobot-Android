package app.lianxu.independent

import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private fun label(minutes:Int)=String.format(Locale.ROOT,"%02d:%02d",minutes/60,minutes%60)
@Composable internal fun ContactHoursSettings(vm:AppState,edit:Boolean,onGuide:()->Unit={}){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var hours by remember{mutableStateOf<QuietHours?>(null)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    LaunchedEffect(vm, vm.processingRevision){hours=vm.quietHours()}
    val q=hours?:return
    fun save(value:QuietHours){if(busy)return;busy=true;scope.launch{
        try{vm.saveQuietHours(value);hours=value;error=""}
        catch(e:CancellationException){throw e}
        catch(_:Exception){error="保存失败，请重试。"}
        finally{busy=false}
    }}
    Text(if(edit)"夜间免打扰" else "后台运行",style=MaterialTheme.typography.titleMedium)
    Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(UiSpace.radius)).padding(16.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text(if(edit)"免打扰" else "夜间免打扰",Modifier.weight(1f))
            if(edit)Switch(q.enabled,{save(q.copy(enabled=it))},enabled=!busy,modifier=Modifier.testTag("quiet-enabled"))
        }
        if(edit && q.enabled)for(start in listOf(true,false)){
            val minute=if(start)q.start else q.end
            Row(Modifier.fillMaxWidth().clickable(enabled=!busy){
                TimePickerDialog(context,{_,h,m->
                    val changed=h*60+m
                    if(changed==(if(start)q.end else q.start))error="开始和结束时间需要不同。"
                    else save(if(start)q.copy(start=changed)else q.copy(end=changed))
                },minute/60,minute%60,true).show()
            }.testTag(if(start)"quiet-start" else "quiet-end").heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
                Text(if(start)"开始时间" else "结束时间",Modifier.weight(1f));Text(label(minute)+" ›",color=GreenText)
            }
        }
        if(!edit){
            Text(if(!q.enabled)"已关闭" else label(q.start)+(if(q.start>q.end)" 至次日 " else " 至 ")+label(q.end),color=Muted,modifier=Modifier.padding(vertical=8.dp))
            val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            fun unrestricted()=context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            var allowed by remember{mutableStateOf(unrestricted())}
            DisposableEffect(lifecycle){val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)allowed=unrestricted()}
                lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onGuide)
                    .testTag("contact-battery-settings").heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("系统电池设置", Modifier.weight(1f))
                Text(if (allowed) "已不受电池限制" else "建议设置 ›", color = GreenText)
            }
        }
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    }
}
