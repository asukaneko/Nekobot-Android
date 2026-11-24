package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Large owned originals use bounded SQLite resources, never a giant CursorWindow/BLOB. */
internal object OriginalChunks {
    const val MIME="application/x-lianxu-original-chunks"
    const val CHUNK=512*1024
    const val MAX_CHUNKS=4094
    data class Part(val path:String,val size:Int,val hash:String)
    data class Manifest(val size:Long,val hash:String,val parts:List<Part>)
    private fun asset(db:SQLiteDatabase,friend:String,path:String,mime:String,bytes:ByteArray){db.insertOrThrow("role_assets",null,ContentValues().apply{put("friend_id",friend);put("path",path);put("kind","attachment");put("mime",mime);put("data",bytes)})}
    fun put(db:SQLiteDatabase,friend:String,path:String,source:ImageSource,hash:String){
        val rows=JSONArray();val digest=MessageDigest.getInstance("SHA-256");var total=0L;var index=0
        source.input().use{input->val buffer=ByteArray(CHUNK);while(true){var n=0;while(n<buffer.size){val got=input.read(buffer,n,buffer.size-n);if(got<0)break;n+=got};if(n==0)break
            require(index<MAX_CHUNKS){"原件超过本机资源块清单容量，未保存不完整原件"};val data=buffer.copyOf(n);val name=path.substringBeforeLast('/')+"/part-${index.toString().padStart(5,'0')}.bin";asset(db,friend,name,"application/octet-stream",data)
            rows.put(JSONObject().put("path",name).put("size",n).put("hash",RoleArchive.hash(data)));digest.update(data);total+=n;index++
        }}
        check(total==source.size&&digest.digest().joinToString(""){"%02x".format(it)}==hash){"原件在保存期间已变化"}
        val raw=JSONObject().put("format","lianxu-original-chunks").put("version",1).put("size",total).put("hash",hash).put("parts",rows).toString().toByteArray()
        require(raw.size<=1024*1024);asset(db,friend,path,MIME,raw)
    }
    fun parse(bytes:ByteArray,path:String):Manifest {
        require(path.matches(Regex("chat-images/[a-f0-9-]{36}/original\\.json"))){"原件清单路径无效"}
        require(bytes.size in 1..1024*1024);val j=JSONObject(bytes.toString(Charsets.UTF_8));require(j.keys().asSequence().toSet()==setOf("format","version","size","hash","parts"))
        require(j.getString("format")=="lianxu-original-chunks"&&j.getInt("version")==1);val rows=j.getJSONArray("parts");require(rows.length() in 1..MAX_CHUNKS)
        val parts=(0 until rows.length()).map{i->val p=rows.getJSONObject(i);require(p.keys().asSequence().toSet()==setOf("path","size","hash"));val name=path.substringBeforeLast('/')+"/part-${i.toString().padStart(5,'0')}.bin";require(p.getString("path")==name);val n=p.getInt("size");require(n in 1..CHUNK&&(i==rows.length()-1||n==CHUNK));Part(name,n,p.getString("hash").also{require(it.matches(Regex("[a-f0-9]{64}")))})}
        val size=j.getLong("size");require(size>0&&parts.sumOf{it.size.toLong()}==size);val hash=j.getString("hash");require(hash.matches(Regex("[a-f0-9]{64}")));return Manifest(size,hash,parts)
    }
    fun extract(manifest:Manifest,target:File,read:(Part)->ByteArray){
        try{target.outputStream().use{out->for(p in manifest.parts){ImageDisk.space(target.parentFile!!,p.size.toLong());val bytes=read(p);check(bytes.size==p.size&&RoleArchive.hash(bytes)==p.hash){"原件资源块损坏或缺失"};out.write(bytes)}};check(ImageSource(target).hash()==manifest.hash){"原件整体校验失败"}}
        catch(e:Throwable){target.delete();throw e}
    }
}
