package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF

/** Render copies only. The owned original, including transparency and EXIF, is never rewritten. */
internal object ImagePixels {
    // Visible list cells retain thumbnails, and do not decode many large originals in parallel.
    val thumbnailGate=Any()
    fun animated(raw:ByteArray,mime:String)=animated(ImageSource(raw),mime)
    fun animated(source:ImageSource,mime:String):Boolean=source.input().use{input->
        if(mime !in setOf("image/png","image/webp"))return@use false
        val stream=java.io.DataInputStream(input)
        fun skip(n:Long){var left=n;while(left>0){val got=stream.skip(left);if(got>0)left-=got else {check(stream.read()!=-1){"图片文件不完整"};left--}}}
        var at=if(mime=="image/png")8L else 12L;skip(at)
        while(at+(if(mime=="image/png")12 else 8)<=source.size){
            val head=ByteArray(8);stream.readFully(head)
            val type=String(head,if(mime=="image/png")4 else 0,4,Charsets.US_ASCII)
            val n=if(mime=="image/png")(0..3).fold(0L){v,k->(v shl 8)+(head[k].toInt() and 255)}else (0..3).fold(0L){v,k->v+((head[4+k].toLong() and 255) shl (8*k))}
            val trailing=if(mime=="image/png")4L else n and 1
            require(n<=source.size-at-8-trailing){"图片文件不完整"}
            if(type in setOf("acTL","ANIM","ANMF"))return@use true
            if(type=="VP8X"&&n>0){val flag=stream.read();if(flag and 2!=0)return@use true;skip(n-1+trailing)}else skip(n+trailing)
            at+=8+n+trailing
        };false
    }
    fun matrix(orientation:Int)=Matrix().apply{when(orientation){
        2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
        5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f)
        7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)
    }}
    fun oriented(bitmap:Bitmap,orientation:Int):Bitmap {
        val m=matrix(orientation)
        return if(m.isIdentity)bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,m,false).also{if(it!==bitmap)bitmap.recycle()}
    }
    fun sample(width:Int,height:Int,side:Int):Int {var sample=1;while(maxOf(width,height).toLong()>side.toLong()*sample)sample*=2;return sample}
    fun preview(raw:ByteArray,side:Int):Bitmap=preview(ImageSource(raw),side)
    fun preview(source:ImageSource,side:Int):Bitmap {
        val info=ChatImages.inspect(source)
        return oriented(source.decode(BitmapFactory.Options().apply{
            inSampleSize=sample(info.width,info.height,side);inPreferredConfig=Bitmap.Config.ARGB_8888
        })?:error("无法完整读取图片"),info.orientation)
    }
    data class ViewRegion(val bitmap:Bitmap,val display:RectF)
    private fun displayMatrix(info:ChatImages.Info):Matrix {
        val m=matrix(info.orientation);val full=RectF(0f,0f,info.width.toFloat(),info.height.toFloat());m.mapRect(full);m.postTranslate(-full.left,-full.top)
        return m
    }
    fun sourceRegion(info:ChatImages.Info,visible:RectF):Rect {
        val m=displayMatrix(info)
        val inverse=Matrix();check(m.invert(inverse));val source=RectF(visible);inverse.mapRect(source)
        // EXIF transforms are integer right-angle transforms; round tiny Matrix float error.
        return Rect(kotlin.math.round(source.left).toInt().coerceIn(0,info.width-1),kotlin.math.round(source.top).toInt().coerceIn(0,info.height-1),kotlin.math.round(source.right).toInt().coerceIn(1,info.width),kotlin.math.round(source.bottom).toInt().coerceIn(1,info.height))
    }
    fun viewRegion(raw:ByteArray,info:ChatImages.Info,visible:RectF):ViewRegion=viewRegion(ImageSource(raw),info,visible)
    fun viewRegion(source:ImageSource,info:ChatImages.Info,visible:RectF):ViewRegion {
        val r=sourceRegion(info,visible)
        require(r.width()>0&&r.height()>0)
        val decoder=source.region()
        val bitmap=try{decoder.decodeRegion(r,BitmapFactory.Options().apply{inSampleSize=sample(r.width(),r.height(),2048);inPreferredConfig=Bitmap.Config.ARGB_8888})?:error("图片区域读取失败")}finally{decoder.recycle()}
        val display=RectF(r);displayMatrix(info).mapRect(display)
        return ViewRegion(oriented(bitmap,info.orientation),display)
    }
}
