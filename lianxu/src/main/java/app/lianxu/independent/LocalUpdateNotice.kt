package app.lianxu.independent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun LocalUpdateNotice(vm:AppState){
    val context=LocalContext.current
    val repository=remember(vm){LocalUpdateRepository(vm.processing.store)}
    val scope=rememberCoroutineScope()
    var note by remember{mutableStateOf<LocalReleaseNote?>(null)}
    var saving by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    LaunchedEffect(vm.ready){if(vm.ready)try{note=repository.pending(InstalledBuild.read(context).versionCode)}catch(e:CancellationException){throw e}catch(_:Exception){/* Failure never blocks opening local data. */}}
    fun close(after:()->Unit={}){val current=note?:return;if(saving)return;if(error.isNotBlank()){note=null;after();return};saving=true;scope.launch{try{repository.acknowledge(current.code);note=null;after()}catch(e:CancellationException){throw e}catch(_:Exception){error="阅读记录未保存。再次关闭可继续使用，下次启动仍会提醒。"}finally{saving=false}}}
    note?.let{n->AlertDialog(
        modifier=Modifier.testTag("update-notice"),
        onDismissRequest={close()},
        title={Text("${n.title} 更新了")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(UiSpace.small)){
            NumberedUpdates(UpdatesPresentation.noticeSummary(n),"notice")
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton({close()},enabled=!saving){Text("知道了")}},
        dismissButton={TextButton({close{vm.open("settings-updates")}},enabled=!saving){Text("查看完整更新")}}
    )}
}
