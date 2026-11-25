package app.lianxu.independent

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Owned byte source; large originals are never materialized as a ByteArray in production. */
internal class ImageSource private constructor(private val data:ByteArray?,val file:File?) {
    constructor(bytes:ByteArray):this(bytes,null)
    constructor(file:File):this(null,file)
    val size:Long get()=data?.size?.toLong()?:requireNotNull(file).length()
    fun input():InputStream=data?.inputStream()?:requireNotNull(file).inputStream()
    fun decode(options:BitmapFactory.Options)=if(data!=null)BitmapFactory.decodeByteArray(data,0,data.size,options)else BitmapFactory.decodeFile(requireNotNull(file).path,options)
    fun region():BitmapRegionDecoder=if(data!=null)BitmapRegionDecoder.newInstance(data,0,data.size,false)else BitmapRegionDecoder.newInstance(requireNotNull(file).path,false)
    fun hash():String=input().use{stream->val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().joinToString(""){"%02x".format(it)}}
    fun bytes():ByteArray {require(size<=48L*1024*1024){"原件过大，需要磁盘读取"};return input().use{it.readBytes()}} // Image materialization guard, not a backup total.
    override fun toString()="ImageSource($size bytes)"
}

internal object ImageDisk {
    @Volatile var cacheDirectory:File?=null
    fun directory()=cacheDirectory?:File(System.getProperty("java.io.tmpdir"))
    const val RESERVE=64L*1024*1024
    fun clearReadCache(){root().listFiles()?.filter{it.name.matches(Regex("[a-f0-9]{64}\\.image"))}?.forEach{it.delete()}}
    fun root(directory:File=directory()):File=File(directory,"lianxu-owned-chat-images").apply{check(mkdirs()||isDirectory){"无法建立私有图片目录"}}
    fun space(directory:File,next:Long,available:Long=directory.usableSpace){require(next>=0&&available>=next+RESERVE){"本机存储空间不足，需保留64 MiB余量；原件没有被压缩覆盖"}}
    fun copy(input:InputStream,target:File,valid:()->Unit={}){
        try{input.use{source->target.outputStream().use{out->val buffer=ByteArray(512*1024);while(true){valid();val n=source.read(buffer);if(n<0)break;if(n==0)continue;space(target.parentFile!!,n.toLong());out.write(buffer,0,n)}}}}
        catch(e:Throwable){target.delete();throw e}
    }
}
