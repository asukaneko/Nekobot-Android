package app.lianxu.independent

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.InputStream

/** Original bytes stay owned by the existing attachment lease; no transcode, mute or audio extraction. */
internal object ChatVideos {
    data class Info(val mime:String,val width:Int,val height:Int,val durationMillis:Long,val rotation:Int){
        val displayWidth get()=if(rotation==90||rotation==270)height else width
        val displayHeight get()=if(rotation==90||rotation==270)width else height
        val frameCount get()=VideoPolicy.frameCount(durationMillis)
    }
    fun prepare(input:InputStream,directory:File=ImageDisk.directory(),extract:Boolean=false,valid:()->Unit={}):PendingChatImage {
        val root=ImageDisk.root(directory);val file=File.createTempFile("import-",".video",root)
        try{
            ImageDisk.copy(input,file,{valid();require(file.length()<=OriginalChunks.CHUNK.toLong()*OriginalChunks.MAX_CHUNKS){"原视频超过本机附件存储容量"};if(!extract&&file.length()>VideoPolicy.ORIGINAL_LIMIT)throw IllegalArgumentException(VideoPolicy.TOO_LARGE)})
            if(!extract)VideoPolicy.requireSize(file.length())
            val info=inspect(file)
            return PendingChatImage(info.mime,file,info.displayWidth,info.displayHeight)
        }catch(e:Throwable){file.delete();throw e}
    }
    fun inspect(file:File):Info=withRetriever(file){retriever->
        require(file.length()>0){"视频文件为空"}
        val mime=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        require(VideoPolicy.isVideo(mime)){"这个文件不是可读取的视频"}
        val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0
        val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0
        // Container duration can include an AAC encoder's padding past the last video frame.
        // Use the visual track's timeline; do not decode or extract the audio track.
        val extractor=MediaExtractor()
        val trackDuration=try{extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map{extractor.getTrackFormat(it)}.firstOrNull{VideoPolicy.isVideo(it.getString(MediaFormat.KEY_MIME).orEmpty())}
                ?.let{if(it.containsKey(MediaFormat.KEY_DURATION))it.getLong(MediaFormat.KEY_DURATION)else null}
        }finally{extractor.release()}
        val duration=trackDuration?.takeIf{it>0}?.let{it/1000+if(it%1000==0L)0 else 1}?:retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0
        require(width>0&&height>0){"无法读取视频画面尺寸"};VideoPolicy.requireDuration(duration)
        val rotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.takeIf{it in setOf(0,90,180,270)}?:0
        Info(mime,width,height,duration,rotation)
    }
    fun thumbnail(source:ImageSource,edge:Int=512):Bitmap=withRetriever(requireNotNull(source.file)){retriever->
        val info=inspect(requireNotNull(source.file))
        val scale=minOf(1.0,edge.toDouble()/maxOf(info.displayWidth,info.displayHeight))
        requireNotNull(frame(retriever,info,0,maxOf(1,(info.displayWidth*scale).toInt()),maxOf(1,(info.displayHeight*scale).toInt()))) { "无法读取视频预览" }
    }
    fun frame(retriever:MediaMetadataRetriever,info:Info,at:Long,width:Int,height:Int):Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 27) return legacyFrame(retriever,info,at,width,height)
        return readAround(at, info.durationMillis) { time, option ->
            retriever.getScaledFrameAtTime(time * 1000, option, width, height)
        }?.let { fitFrame(it,width,height) }
    }

    internal fun legacyFrame(retriever:MediaMetadataRetriever,info:Info,at:Long,width:Int,height:Int):Bitmap? {
        val runtime=Runtime.getRuntime()
        val pixels=info.width.toLong()*info.height
        require(pixels<=(runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory())/12){"本机内存不足以解码原视频画面"}
        return readAround(at, info.durationMillis) { time, option ->
            retriever.getFrameAtTime(time * 1000, option)
        }?.let { fitFrame(it,width,height) }
    }

    internal fun <T> readAround(at: Long, duration: Long, read: (Long, Int) -> T?): T? {
        val times = listOf(at, at - 250, at + 250, at - 500, at + 500)
            .map { it.coerceIn(0, duration - 1) }.distinct()
        for (time in times) read(time, MediaMetadataRetriever.OPTION_CLOSEST)?.let { return it }
        return read(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    private fun fitFrame(bitmap:Bitmap,width:Int,height:Int):Bitmap {
        if(bitmap.width==width&&bitmap.height==height)return bitmap
        return try{Bitmap.createScaledBitmap(bitmap,width,height,true)}finally{bitmap.recycle()}
    }
    inline fun <T> withRetriever(file:File,read:(MediaMetadataRetriever)->T):T {
        val retriever=MediaMetadataRetriever()
        try{retriever.setDataSource(file.absolutePath);return read(retriever)}finally{retriever.release()}
    }
}
