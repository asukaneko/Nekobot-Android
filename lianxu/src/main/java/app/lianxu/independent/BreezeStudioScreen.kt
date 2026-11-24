package app.lianxu.independent

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private enum class VoicePage(val title:String) {
    LIBRARY("AI 语音"), DESIGN("文字设计音色"), CLONE("导入已有录音克隆"), REMIX("重塑并另存")
}

private class StudioPlayer(private val cache:File) {
    private var player:MediaPlayer?=null
    private var temp:File?=null
    fun stop(){player?.release();player=null;temp?.delete();temp=null}
    private fun start(file:File,done:(Boolean)->Unit){
        val active=MediaPlayer()
        player=active
        try {
            active.setDataSource(file.absolutePath)
            active.setOnCompletionListener { stop();done(true) }
            active.setOnErrorListener { _,_,_->stop();done(false);true }
            active.prepare()
            active.start()
        } catch(e:Exception){stop();throw e}
    }
    fun playLocal(file:File,done:(Boolean)->Unit){stop();start(file,done)}
    fun playBytes(bytes:ByteArray,done:(Boolean)->Unit){
        stop()
        val file=File.createTempFile("lianxu-voice-preview-",".mp3",cache)
        temp=file
        try {file.writeBytes(bytes);start(file,done)} catch(e:Exception){stop();throw e}
    }
}

@Composable internal fun BreezeStudioScreen(onBack:()->Unit) {
    val account=rememberBreezeAccount()
    val catalog by account.catalogChanges.collectAsState(Triple(account.accountId(),0L,false))
    key(catalog.first) {
        var page by remember { mutableStateOf(VoicePage.LIBRARY) }
        var source by remember { mutableStateOf<SavedVoice?>(null) }
        val back={ if(page==VoicePage.LIBRARY)onBack() else page=VoicePage.LIBRARY }
        BackHandler { back() }
        key(page) {
            StudioPage(account,catalog,page,source,back,{choice,next->source=choice;page=next})
        }
    }
}

@Composable private fun StudioPage(account:BreezeVoiceAccount,catalog:Triple<String,Long,Boolean>,page:VoicePage,
                                   source:SavedVoice?,back:()->Unit,open:(SavedVoice?,VoicePage)->Unit) {
    val owner=catalog.first
    val voices=remember(catalog){account.voices()}
    val connected=catalog.third
    val context=LocalContext.current.applicationContext
    val studio=remember(account){BreezeVoiceStudio(account)}
    val player=remember { StudioPlayer(context.cacheDir) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var playing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<ImportedVoiceRecording?>(null) }
    var heard by remember { mutableStateOf(false) }
    var rights by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<VoiceCandidate?>(null) }
    var name by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    DisposableEffect(lifecycle,player) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP){player.stop();playing=false}}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);player.stop()}
    }
    DisposableEffect(picked){val current=picked;onDispose{current?.close()}}
    fun runAction(action:suspend ()->Unit){
        if(busy)return
        busy=true;status="";player.stop();playing=false
        scope.launch {
            try{action()}catch(e:CancellationException){throw e}
            catch(_:Exception){status="操作未完成，请检查输入、连接或录音格式。"}
            finally{busy=false}
        }
    }
    val finished:(Boolean)->Unit={ok->playing=false;if(!ok)status="无法播放这段录音"}
    fun play(bytes:ByteArray){
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)){player.playBytes(bytes,finished);playing=true}
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runAction {
            val recording=ImportedVoiceRecording.open(context,uri)
            picked?.close();picked=recording;heard=false;rights=false;candidate=null;saved=false
        }
    }
    if (page == VoicePage.LIBRARY) {
        var refreshing by remember { mutableStateOf(false) }
        VoiceLibrary(voices, connected, busy, refreshing, playing, status, back,
            onRefresh = {
                if (owner.isNotEmpty()) runAction {
                    refreshing = true
                    try { account.refresh() } finally { refreshing = false }
                }
            },
            onPlay = { voice -> runAction { play(studio.audition(owner, voice, account.auditionLine())) } },
            onStop = { player.stop(); playing = false },
            onEdit = { open(it, VoicePage.REMIX) },
            onCreate = { clone -> open(null, if (clone) VoicePage.CLONE else VoicePage.DESIGN) },
            readLine = account::auditionLine, saveLine = account::saveAuditionLine)
        return
    }
    ToolboxPage(page.title,back,"voice-studio-page") {
            VoiceSection(if(page==VoicePage.CLONE)"参考录音" else "音色描述") {
                if(page==VoicePage.CLONE) {
                    OutlinedButton({picker.launch(arrayOf("audio/*"))},enabled=!busy){Text("选择本地录音")}
                    picked?.let { recording->
                        Text("已导入 · ${recording.durationMs/1000} 秒")
                        OutlinedButton({runAction{player.playLocal(recording.file,finished);playing=true;heard=true}},enabled=!busy){Text("本地试听")}
                        Row(Modifier.fillMaxWidth().toggleable(rights,enabled=heard&&!busy,onValueChange={rights=it}).heightIn(min=48.dp),
                            verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(rights,null,enabled=heard&&!busy);Spacer(Modifier.width(8.dp));Text("我有权使用这段录音")
                        }
                        OutlinedButton({confirm=true},enabled=connected&&heard&&rights&&!busy){Text("上传并生成预览")}
                    }
                    Text("选择文件仅在本机读取。录音至少 3 秒，最大 5 MB。",color=Muted)
                }else{
                    if(page==VoicePage.REMIX)Text("原音色 · ${source?.name.orEmpty()}")
                    OutlinedTextField(details,{details=it;candidate=null;saved=false},enabled=!busy,label={Text("描述想要的声音")},modifier=Modifier.fillMaxWidth())
                    val limit=if(page==VoicePage.DESIGN)500 else 1000
                    OutlinedButton({runAction{
                        candidate=if(page==VoicePage.DESIGN)studio.design(owner,details.trim()) else studio.remix(owner,requireNotNull(source),details.trim())
                        saved=false;name=""
                    }},enabled=connected&&!busy&&details.trim().length in 3..limit){Text("生成预览")}
                }
            }
            if(!connected)Text("请先在大模型配置连接 BreezeBlue 语音。",color=Muted)
            candidate?.let { result->
                VoiceSection("音色预览") {
                    if(result.needsVerification)Text("服务商要求验证此音色，完成验证后再保存。",color=Muted)
                    OutlinedButton({runAction{play(studio.preview(result))}},enabled=!busy&&!saved&&!result.needsVerification){Text("试听预览")}
                    OutlinedTextField(name,{name=it},enabled=!busy&&!saved,label={Text("新音色名称")},modifier=Modifier.fillMaxWidth())
                    OutlinedButton({runAction{
                        studio.saveAs(result,name.trim());saved=true
                        try{account.refresh();status="已另存为新音色"}
                        catch(e:CancellationException){throw e}
                        catch(_:Exception){status="已保存，返回我的音色后可刷新列表"}
                    }},enabled=!saved&&!busy&&!result.needsVerification&&name.trim().length in 1..80){Text(if(saved)"已保存" else "另存为新音色")}
                }
            }
        if(playing)TextButton({player.stop();playing=false}){Text("停止播放")}
        if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        if(status.isNotEmpty())Text(status,Modifier.testTag("voice-studio-status"),color=Muted)
        Text("在线试听和生成使用 BreezeBlue 账户额度。",style=MaterialTheme.typography.bodySmall,color=Muted)
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("上传参考录音？")},text={Text("选中的录音将发送给 BreezeBlue，用于生成克隆预览。")},
        dismissButton={TextButton({confirm=false}){Text("取消")}},confirmButton={TextButton({
            confirm=false
            val recording=picked
            if(heard&&rights&&recording!=null)runAction{candidate=studio.clone(owner,recording,true);saved=false;name=""}
        }){Text("确认上传到 BreezeBlue")}})
}
