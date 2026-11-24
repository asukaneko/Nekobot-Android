package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Local, bounded thumbnail; never stores a picker URI or changes the model-facing persona. */
internal object UserAvatarData {
    const val KEY="self-avatar-v1"
    const val MAX_BYTES=512*1024
    const val MAX_ENCODED=3+4*((MAX_BYTES+2)/3)
    fun encode(bytes:ByteArray):String {
        require(bytes.size in 1..MAX_BYTES){"头像图片太大，请换一张较小的图片。"}
        return "v1:"+Base64.getEncoder().encodeToString(bytes)
    }
    fun decode(raw:String):ByteArray? {
        if(raw.length>MAX_ENCODED||!raw.startsWith("v1:"))return null
        return runCatching{Base64.getDecoder().decode(raw.substring(3)).takeIf{it.size in 1..MAX_BYTES}}.getOrNull()
    }
    fun checked(raw:String):ByteArray? {
        val bytes=decode(raw)?:return null
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        return runCatching{
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            bytes.takeIf{bounds.outWidth in 1..512&&bounds.outHeight in 1..512&&bounds.outMimeType=="image/jpeg"}
        }.getOrNull()
    }
    /** A portable package may be edited outside the app: strip its photo metadata as at selection. */
    fun forRestore(raw:String):String?=checked(raw)?.let{bytes->runCatching{
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:return@runCatching null
        bitmap.recycle()
        if(orientation(bytes) in 2..8)prepare(bytes)else encode(withoutMetadata(bytes))
    }.getOrNull()}
    private fun orientation(bytes:ByteArray)=runCatching{ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    /** Preserve JPEG scan/tables and ICC colour profiles; remove private metadata even between scans. */
    fun withoutMetadata(bytes:ByteArray):ByteArray{
        fun value(i:Int)=bytes[i].toInt() and 255
        require(bytes.size>=4&&value(0)==255&&value(1)==216){"头像JPEG无效"}
        val out=ByteArrayOutputStream(bytes.size);out.write(bytes,0,2)
        var p=2;var scanned=false
        while(p<bytes.size){
            val start=p;require(value(p++)==255){"头像JPEG标记无效"}
            while(p<bytes.size&&value(p)==255)p++
            require(p<bytes.size);val code=value(p++)
            if(code==217){require(scanned);out.write(255);out.write(217);return out.toByteArray()}
            require(code!=0&&code!=216&&code !in 208..215)
            if(code==1){out.write(bytes,start,p-start);continue}
            require(p+2<=bytes.size);val length=(value(p) shl 8)+value(p+1)
            require(length>=2&&p+length<=bytes.size)
            val end=p+length
            val icc=code==226&&length>=16&&bytes.copyOfRange(p+2,p+14).contentEquals("ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII))
            if(code==224){
                // APP0 may hide an older JFXX picture or JFIF RGB thumbnail. Keep only JFIF density.
                if(length>=16&&bytes.copyOfRange(p+2,p+7).contentEquals("JFIF\u0000".toByteArray(Charsets.US_ASCII))){
                    out.write(byteArrayOf(255.toByte(),224.toByte(),0,16));out.write(bytes,p+2,12);out.write(0);out.write(0)
                }
            }else if(icc||code !in 225..239&&code!=254)out.write(bytes,start,end-start)
            p=end
            if(code==218){
                scanned=true;var next=p
                while(next+1<bytes.size){
                    if(value(next)!=255){next++;continue}
                    val following=value(next+1)
                    if(following==0||following in 208..215){next+=2;continue}
                    if(following==255){next++;continue}
                    break
                }
                require(next+1<bytes.size){"头像JPEG未完成"}
                out.write(bytes,p,next-p);p=next
            }
        }
        error("头像JPEG缺少结尾")
    }
    fun prepare(original:ByteArray):String {
        val orientation=orientation(original)
        val small=prepareRoleAvatar(original).second
        val matrix=Matrix()
        when(orientation){
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL->matrix.setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180->matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL->matrix.setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE->{matrix.setRotate(90f);matrix.postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_90->matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE->{matrix.setRotate(-90f);matrix.postScale(-1f,1f)}
            ExifInterface.ORIENTATION_ROTATE_270->matrix.setRotate(-90f)
            else->return encode(small)
        }
        val bitmap=BitmapFactory.decodeByteArray(small,0,small.size)?:error("无法识别这张图片")
        val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
        return try{
            val output=ByteArrayOutputStream()
            check(rotated.compress(Bitmap.CompressFormat.JPEG,90,output)){"无法保存这张图片"}
            encode(output.toByteArray())
        }finally{if(rotated!==bitmap)rotated.recycle();bitmap.recycle()}
    }
}
