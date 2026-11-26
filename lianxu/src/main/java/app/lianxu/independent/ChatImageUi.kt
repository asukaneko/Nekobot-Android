package app.lianxu.independent

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable internal fun ChatPicture(bytes:ByteArray,modifier:Modifier=Modifier,description:String="图片",contentScale:ContentScale=ContentScale.Fit)=ChatSourcePicture(remember(bytes){ImageSource(bytes)},modifier,description,contentScale)
@Composable internal fun ChatSourcePicture(source:ImageSource,modifier:Modifier=Modifier,description:String="图片",contentScale:ContentScale=ContentScale.Fit,video:Boolean=false){
    val bitmap by produceState<android.graphics.Bitmap?>(null,source,video){value=withContext(Dispatchers.IO){runCatching{if(video)ChatVideos.thumbnail(source)else ImagePixels.preview(source,512)}.getOrNull()}}
    if(video)Box(modifier,contentAlignment=Alignment.Center){
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),description,Modifier.fillMaxSize(),contentScale=contentScale)
        else Text("视频",style=MaterialTheme.typography.labelSmall,color=Muted)
    }else bitmap?.let{Image(it.asImageBitmap(),description,modifier,contentScale=contentScale)}
}

@Composable internal fun PendingPicture(image:PendingChatImage,expanded:Boolean,onExpand:()->Unit,onView:()->Unit,onRemove:()->Unit){
    val video=VideoPolicy.isVideo(image.mime);val kind=if(video)"视频"else"图片"
    val size by animateDpAsState(if(expanded)88.dp else 34.dp,tween(if(expanded)350 else 300,easing=CubicBezierEasing(.0f,.0f,.2f,1f)),label="pending-picture-size")
    Box(Modifier.fillMaxWidth().padding(start=56.dp,top=8.dp,bottom=4.dp).testTag(if(expanded)"pending-picture-expanded"else"pending-picture-collapsed")){
        Box(Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)){
            ChatSourcePicture(image.source,Modifier.size(size).clip(RoundedCornerShape(8.dp)).border(1.dp,Paper,RoundedCornerShape(8.dp)).clickable(role=Role.Button){if(expanded)onView()else onExpand()}.testTag("pending-picture"),"待发送$kind，点击${if(expanded)"放大"else"展开"}",ContentScale.Crop,video)
            if(expanded)IconButton(onClick=onRemove,modifier=Modifier.align(Alignment.TopEnd).offset(16.dp,(-16).dp).size(48.dp).testTag("remove-picture").semantics{contentDescription="移除待发送图片"}){
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(Ink),contentAlignment=Alignment.Center){Text("×",color=Paper,style=MaterialTheme.typography.labelLarge)}
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun MessagePicture(vm:AppState,ref:ChatImageRef,onControl:(()->Unit)?=null,onView:(ImageSource)->Unit){
    val video=VideoPolicy.isVideo(ref.mime);val kind=if(video)"视频"else"图片"
    val loaded by produceState<Result<android.graphics.Bitmap>?>(null,ref){value=runCatching{vm.processing.read{db->synchronized(ImagePixels.thumbnailGate){val source=ChatImages.source(db,ref);if(video)ChatVideos.thumbnail(source)else ImagePixels.preview(source,512)}}}}
    val bitmap=loaded?.getOrNull();val scope=rememberCoroutineScope();var opening by remember(ref){mutableStateOf(false)};var failed by remember(ref){mutableStateOf(false)}
    // Size the placeholder from stored metadata, before IO. Avoid aspectRatio's unconstrained
    // fallback when a fixed width and a tall bitmap cannot both satisfy the parent's height.
    BoxWithConstraints(Modifier.widthIn(max=180.dp)){
        val sourceRatio=ref.width.coerceAtLeast(1).toFloat()/ref.height.coerceAtLeast(1)
        val ratio=sourceRatio.coerceIn(2f/3f,3f);val edge=minOf(maxWidth,180.dp)
        Box(Modifier.size(edge*minOf(1f,ratio),edge/maxOf(1f,ratio)).clip(RoundedCornerShape(12.dp)).testTag("message-image-frame-${ref.messageId}"),contentAlignment=Alignment.Center){
            if(bitmap!=null)Image(bitmap.asImageBitmap(),"消息$kind，点击${if(video)"播放"else"放大"}，长按打开更多操作",Modifier.fillMaxSize().bubbleActions(LocalBubbleMessage.current?.text.orEmpty(),if(video)"video" else "image",fallback=onControl,click={if(!opening){opening=true;scope.launch{try{val result=runCatching{vm.processing.read{db->ChatImages.source(db,ref)}};result.onSuccess(onView).onFailure{failed=true}}finally{opening=false}}}}).testTag("message-image-${ref.messageId}"),contentScale=if(sourceRatio==ratio)ContentScale.Fit else ContentScale.Crop,alignment=Alignment.TopStart)
            else Text(if(loaded==null)"正在读取$kind…"else"${kind}暂时无法读取",Modifier.testTag(if(loaded==null)"message-image-loading-${ref.messageId}"else"message-image-failed-${ref.messageId}"),style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=3,overflow=TextOverflow.Ellipsis)
            if(failed)Text("原图暂时无法读取，保存文件仍保留",Modifier.align(Alignment.BottomCenter),style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=3,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun ChatPictureViewer(bytes:ByteArray,onClose:()->Unit)=ChatSourceViewer(remember(bytes){ImageSource(bytes)},onClose)
@Composable internal fun ChatSourceViewer(source:ImageSource,onClose:()->Unit){
    var scale by remember(source){mutableFloatStateOf(1f)};var x by remember(source){mutableFloatStateOf(0f)};var y by remember(source){mutableFloatStateOf(0f)}
    var viewport by remember{mutableStateOf(IntSize.Zero)}
    val info=remember(source){ChatImages.inspect(source)}
    val fit=if(viewport.width>0&&viewport.height>0)minOf(viewport.width.toFloat()/info.displayWidth,viewport.height.toFloat()/info.displayHeight)else 1f
    val factor=fit*scale
    val left=(viewport.width-info.displayWidth*factor)/2+x;val top=(viewport.height-info.displayHeight*factor)/2+y
    val visible=RectF((-left/factor).coerceIn(0f,info.displayWidth.toFloat()),(-top/factor).coerceIn(0f,info.displayHeight.toFloat()),((viewport.width-left)/factor).coerceIn(0f,info.displayWidth.toFloat()),((viewport.height-top)/factor).coerceIn(0f,info.displayHeight.toFloat()))
    val preview by produceState<android.graphics.Bitmap?>(null,source){value=withContext(Dispatchers.IO){runCatching{ImagePixels.preview(source,1024)}.getOrNull()}}
    val region by produceState<Result<ImagePixels.ViewRegion>?>(null,source,viewport,scale,x,y){
        if(viewport.width>0&&visible.width()>0&&visible.height()>0){delay(100);val next=withContext(Dispatchers.IO){runCatching{ImagePixels.viewRegion(source,info,visible)}};ensureActive();value=next}
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("image-viewer"),color=Paper){
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))){
                Canvas(Modifier.fillMaxSize().padding(16.dp).clipToBounds().onSizeChanged{viewport=it}.semantics{contentDescription="图片，可双指缩放和拖动"}.pointerInput(source,viewport){detectTransformGestures{centroid,pan,zoom,_->
                    val next=(scale*zoom).coerceIn(1f,maxOf(5f,1f/fit));val change=next/scale
                    x=(x-(centroid.x-size.width/2f)) * change+(centroid.x-size.width/2f)+pan.x
                    y=(y-(centroid.y-size.height/2f)) * change+(centroid.y-size.height/2f)+pan.y
                    scale=next
                    val maxX=maxOf(0f,(info.displayWidth*fit*scale-size.width)/2);val maxY=maxOf(0f,(info.displayHeight*fit*scale-size.height)/2)
                    x=x.coerceIn(-maxX,maxX);y=y.coerceIn(-maxY,maxY)
                }}){
                    fun paint(bitmap:android.graphics.Bitmap,r:RectF){drawImage(bitmap.asImageBitmap(),dstOffset=IntOffset((left+r.left*factor).roundToInt(),(top+r.top*factor).roundToInt()),dstSize=IntSize(maxOf(1,(r.width()*factor).roundToInt()),maxOf(1,(r.height()*factor).roundToInt())))}
                    preview?.let{paint(it,RectF(0f,0f,info.displayWidth.toFloat(),info.displayHeight.toFloat()))}
                    region?.getOrNull()?.let{paint(it.bitmap,it.display)}
                }
                if(region?.isFailure==true)Text("此原图区域无法读取，原文件仍保留",modifier=Modifier.align(Alignment.BottomCenter).padding(24.dp),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=onClose,modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding().testTag("image-viewer-close")){Text("关闭")}
            }
        }
    }
}
