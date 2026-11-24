package app.lianxu.independent

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Minimal persistent owned resources for portable backups, not attachment sending or image understanding. */
internal class RoleAssets(private val repo:ProcessingRepository){
    companion object {
        /** Each SQLite window and Java buffer is bounded even for a legacy 40 MB resource. */
        internal fun stream(db:android.database.sqlite.SQLiteDatabase,id:String,path:String,size:Int,valid:()->Unit={}):java.io.InputStream {
            require(size in 0..BackupArchive.MAX_FILE)
            return object:java.io.InputStream(){
                var offset=0;var bytes=ByteArray(0);var at=0
                override fun read():Int {val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
                override fun read(b:ByteArray,off:Int,len:Int):Int {
                    require(off>=0&&len>=0&&off<=b.size-len);if(len==0)return 0;valid()
                    if(at==bytes.size){if(offset==size)return -1
                        val count=minOf(OriginalChunks.CHUNK,size-offset)
                        bytes=db.rawQuery("SELECT substr(data,?,?) FROM role_assets WHERE friend_id=? AND path=?",arrayOf((offset+1).toString(),count.toString(),id,path)).use{check(it.moveToFirst());it.getBlob(0)}
                        check(bytes.size==count){"资源读取不完整"};offset+=count;at=0
                    }
                    val n=minOf(len,bytes.size-at);bytes.copyInto(b,off,at,at+n);at+=n;return n
                }
            }
        }
        internal fun restoreStream(db:android.database.sqlite.SQLiteDatabase,id:String,path:String,kind:String,mime:String,input:java.io.InputStream,size:Long,expectedHash:String,valid:()->Unit){
            require(size in 0..BackupArchive.MAX_FILE.toLong());valid()
            db.insertOrThrow("role_assets",null,ContentValues().apply{put("friend_id",id);put("path",path);put("kind",kind);put("mime",mime);put("data",ByteArray(0))})
            val statement=db.compileStatement("UPDATE role_assets SET data=CAST(data || ? AS BLOB) WHERE friend_id=? AND path=?")
            try{
                val sink=object:java.io.OutputStream(){override fun write(b:Int){write(byteArrayOf(b.toByte()),0,1)}
                    override fun write(b:ByteArray,off:Int,len:Int){valid();ImageDisk.space(ImageDisk.root(),len.toLong()*2)
                        statement.clearBindings();statement.bindBlob(1,if(off==0&&len==b.size)b else b.copyOfRange(off,off+len));statement.bindString(2,id);statement.bindString(3,path);check(statement.executeUpdateDelete()==1)
                    }}
                val hash=java.io.BufferedOutputStream(sink,OriginalChunks.CHUNK).use{BackupAssetIO.copy(input,it,size,valid)}
                require(hash==expectedHash){"资源在恢复期间变化，未完成导入"}
            }finally{statement.close()}
        }
        // Keep each BLOB window comfortably below Android's typical 2 MiB CursorWindow.
        internal fun read(db:android.database.sqlite.SQLiteDatabase,id:String,path:String,size:Int):ByteArray{
            require(size in 0..BackupArchive.MAX_FILE){"单个资源超过40,000,000字节"}
            val bytes=ByteArray(size);var offset=0
            while(offset<size){val count=minOf(512*1024,size-offset)
                val chunk=db.rawQuery("SELECT substr(data,?,?) FROM role_assets WHERE friend_id=? AND path=?",arrayOf((offset+1).toString(),count.toString(),id,path)).use{check(it.moveToFirst());it.getBlob(0)}
                check(chunk.size==count){"资源读取不完整"};chunk.copyInto(bytes,offset);offset+=count
            }
            return bytes
        }
    }
    data class Asset(val path:String,val kind:String,val mime:String,val bytes:ByteArray){override fun toString()="RoleAsset($kind, ${bytes.size} bytes)"}
    suspend fun put(id:String,value:Asset)=repo.tx{db->
        require(RoleArchive.entryName(value.path)==value.path&&value.path.length<=240)
        require(value.kind in setOf("avatar","attachment")&&value.mime.length in 1..100&&value.bytes.size<=BackupArchive.MAX_FILE)
        db.insertWithOnConflict("role_assets",null,ContentValues().apply{put("friend_id",id);put("path",value.path);put("kind",value.kind);put("mime",value.mime);put("data",value.bytes)},android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }
    suspend fun list(id:String)=repo.read{db->db.rawQuery("SELECT path,kind,mime,length(data) FROM role_assets WHERE friend_id=? ORDER BY path",arrayOf(id)).use{c->buildList{while(c.moveToNext())add(Asset(c.getString(0),c.getString(1),c.getString(2),read(db,id,c.getString(0),c.getInt(3))))}}}
    suspend fun avatar(id:String)=repo.read{db->db.rawQuery("SELECT path,kind,mime,length(data) FROM role_assets WHERE friend_id=? AND kind=? ORDER BY path LIMIT 1",arrayOf(id,"avatar")).use{c->if(c.moveToFirst())Asset(c.getString(0),c.getString(1),c.getString(2),read(db,id,c.getString(0),c.getInt(3))) else null}}
}

/** Downscale a picked photo so the avatar stays small enough to store with the role. */
internal fun prepareRoleAvatar(bytes:ByteArray):Pair<String,ByteArray>{
    require(bytes.isNotEmpty()){"无法识别这张图片"}
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth>0&&bounds.outHeight>0){"无法识别这张图片"}
    val maxSide=512
    val longest=maxOf(bounds.outWidth,bounds.outHeight)
    val sample=maxOf(1,longest/maxSide)
    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})?:error("无法识别这张图片")
    val scaled=if(maxOf(bitmap.width,bitmap.height)>maxSide){
        val ratio=maxSide.toFloat()/maxOf(bitmap.width,bitmap.height)
        val w=(bitmap.width*ratio).toInt().coerceAtLeast(1)
        val h=(bitmap.height*ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(bitmap,w,h,true).also{if(it!==bitmap)bitmap.recycle()}
    }else bitmap
    val out=ByteArrayOutputStream()
    check(scaled.compress(Bitmap.CompressFormat.JPEG,90,out)){"无法保存这张图片"}
    if(scaled!==bitmap)scaled.recycle() else scaled.recycle()
    val data=out.toByteArray()
    require(data.size in 1..BackupArchive.MAX_FILE){"图片太大，请换一张较小的"}
    return "image/jpeg" to data
}
