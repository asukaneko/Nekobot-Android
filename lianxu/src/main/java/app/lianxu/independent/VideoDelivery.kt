package app.lianxu.independent

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** Prepare the entire selected representation before any request. */
internal class VideoDelivery private constructor(private val root:File):Closeable {
    data class Original(val ref:ChatImageRef,val source:ImageSource,val info:ChatVideos.Info)
    data class Frame(val file:File,val atMillis:Long,val source:Original)
    val originals=linkedMapOf<ChatImageRef,Original>()
    val frames=linkedMapOf<ChatImageRef,List<Frame>>()
    override fun close(){root.listFiles()?.forEach{it.delete()};root.delete()}
    companion object {
        fun prepare(context:Context,refs:List<ChatImageRef>,extract:Boolean,read:(ChatImageRef)->ImageSource,validateCapacity:(List<ChatVideos.Info>)->Unit,valid:()->Unit):VideoDelivery =
            (if (extract) LocalStep.VIDEO_FRAMES else LocalStep.VIDEO_ORIGINALS).observe(enabled = refs.isNotEmpty()) {
            val parent=ImageDisk.root(context.cacheDir)
            val result=VideoDelivery(Files.createTempDirectory(parent.toPath(),"video-send-").toFile())
            try{
                refs.forEach{ref->
                    valid();val source=read(ref);if(!extract)VideoPolicy.requireSize(source.size);val info=ChatVideos.inspect(requireNotNull(source.file))
                    check(source.hash()==ref.hash&&info.mime==ref.mime){"视频来源已变化或文件已损坏"}
                    result.originals[ref]=Original(ref,source,info)
                }
                validateCapacity(result.originals.values.map{it.info})
                if(extract)result.originals.forEach{(ref,original)->
                    val frames=mutableListOf<Frame>()
                    var encodedBytes=0L
                    ChatVideos.withRetriever(requireNotNull(original.source.file)){retriever->
                        for(at in VideoPolicy.frameTimes(original.info.durationMillis)){
                            valid()
                            val info=original.info
                            val scale=minOf(1.0,VideoPolicy.FRAME_MAX_LONG_EDGE.toDouble()/maxOf(info.displayWidth,info.displayHeight),VideoPolicy.FRAME_MAX_SHORT_EDGE.toDouble()/minOf(info.displayWidth,info.displayHeight))
                            val width=maxOf(1,(info.displayWidth*scale).toInt())
                            val height=maxOf(1,(info.displayHeight*scale).toInt())
                            ImageDisk.space(result.root,width.toLong()*height*4)
                            val bitmap=ChatVideos.frame(retriever,info,at,width,height) ?: continue
                            try{
                                val target=File(result.root,"${result.frames.size}-${frames.size}.jpg")
                                target.outputStream().use{out->check(bitmap.compress(Bitmap.CompressFormat.JPEG,VideoPolicy.FRAME_JPEG_QUALITY,out)){"视频画面写入失败，未发送"}}
                                require(target.length() in 1..ImagePolicy.ORIGINAL_LIMIT.toLong()){"单帧画面超过可发送图片大小，未缩图、跳帧或发送"}
                                encodedBytes+=ImagePolicy.encodedLength(target.length())
                                require(encodedBytes<=ImagePolicy.REQUEST_LIMIT){"全部 JPEG 编码后超过本机单次图片请求的 32 MiB 上限"}
                                frames+=Frame(target,at,original)
                            }finally{bitmap.recycle()}
                        }
                    }
                    require(frames.isNotEmpty()){"无法读取视频画面，未发送"}
                    result.frames[ref]=frames
                }
                valid();result
            }catch(e:Throwable){result.close();if(e is OutOfMemoryError)throw IllegalArgumentException("本机内存不足，无法准备完整视频画面；未跳帧或发送",e);throw e}
        }
    }
}
