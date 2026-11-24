package app.lianxu.independent

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal data class ChatMediaPreview(val source:ImageSource,val mime:String)

@Composable internal fun ChatMediaViewer(media:ChatMediaPreview,onClose:()->Unit){
    if(!VideoPolicy.isVideo(media.mime)){ChatSourceViewer(media.source,onClose);return}
    var view by remember(media){mutableStateOf<VideoView?>(null)}
    var error by remember(media){mutableStateOf("")}
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,media){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE)view?.pause()}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}
    }
    DisposableEffect(media){onDispose{view?.stopPlayback();view=null}}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("video-viewer"),color=Paper){
            Box(Modifier.fillMaxSize()){
                AndroidView(factory={context->VideoView(context).apply{
                    view=this
                    val controls=MediaController(context);controls.setAnchorView(this);setMediaController(controls)
                    contentDescription="视频原件，可使用播放控制"
                    setOnPreparedListener{seekTo(1);controls.show(0)}
                    setOnErrorListener{_,what,extra->error="此视频暂时无法播放（$what/$extra），原文件仍保留";true}
                    setVideoPath(requireNotNull(media.source.file).absolutePath)
                }},modifier=Modifier.fillMaxSize().padding(top=56.dp,bottom=24.dp).testTag("video-original-player"))
                if(error.isNotBlank())Text(error,Modifier.align(Alignment.BottomCenter).padding(24.dp),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onClose,modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().testTag("video-viewer-close")){Text("关闭")}
            }
        }
    }
}
