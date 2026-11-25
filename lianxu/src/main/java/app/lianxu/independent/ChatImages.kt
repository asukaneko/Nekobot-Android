package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/** Owned original bytes. EXIF and other embedded metadata are deliberately preserved. */
class PendingChatImage private constructor(val mime:String,internal val source:ImageSource,val width:Int,val height:Int) : java.io.Closeable {
    internal constructor(mime:String,bytes:ByteArray,width:Int,height:Int):this(mime,ImageSource(bytes),width,height)
    internal constructor(mime:String,file:java.io.File,width:Int,height:Int):this(mime,ImageSource(file),width,height)
    val hash:String=source.hash()
    internal val bytes:ByteArray get()=source.bytes()
    private var holders=1
    private var ownerClosed=false
    @Synchronized internal fun retain():java.io.Closeable {
        check(!ownerClosed){"待发送图片已移除，请重新选择"};holders++;var released=false
        return java.io.Closeable{synchronized(this){if(!released){released=true;release()}}}
    }
    private fun release(){holders--;if(holders==0)source.file?.delete()}
    @Synchronized override fun close(){if(!ownerClosed){ownerClosed=true;release()}}
    override fun toString()="PendingChatImage($mime,${source.size} bytes)"
}

data class ChatImageRef(val friendId:String,val messageId:Long,val path:String,val mime:String,val hash:String,val width:Int,val height:Int,val revision:Long=1,val storageKind:String="legacy-normalized")
class WireImage private constructor(val mime:String,private val data:ByteArray?,private val file:java.io.File?,val description:String,internal val videoFrame:Boolean=false) {
    constructor(mime:String,bytes:ByteArray,description:String=""):this(mime,bytes,null,description,false)
    internal val size:Long get()=data?.size?.toLong()?:requireNotNull(file).length()
    internal val bytes:ByteArray get()=data?:requireNotNull(file).readBytes()
    internal fun input():InputStream=data?.inputStream()?:requireNotNull(file).inputStream()
    internal companion object {
        fun file(mime:String,file:java.io.File,description:String)=WireImage(mime,null,file,description)
        fun videoFrame(file:java.io.File,description:String)=WireImage("image/jpeg",null,file,description,true)
    }
    override fun toString()="WireImage($mime,$size bytes)"
}

internal object ChatImages {
    const val SWITCH="aux-vision-enabled"
    const val SWITCH_REVISION="aux-vision-revision"
    /** Upper bound shared between auxiliary observation capture and backup validation. */
    const val MAX_OBSERVATION_CHARS=100_000
    fun create(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_images(friend_id TEXT NOT NULL REFERENCES friends(id),message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,path TEXT NOT NULL,mime TEXT NOT NULL,hash TEXT NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,revision INTEGER NOT NULL,storage_kind TEXT NOT NULL DEFAULT 'legacy-normalized',FOREIGN KEY(friend_id,path) REFERENCES role_assets(friend_id,path))")
        db.execSQL("CREATE TABLE IF NOT EXISTS image_observations(id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id),message_id INTEGER NOT NULL REFERENCES chat_images(message_id) ON DELETE CASCADE,question_message_id INTEGER NOT NULL REFERENCES messages(id),question_revision INTEGER NOT NULL,source_revision INTEGER NOT NULL,image_hash TEXT NOT NULL,model_id TEXT NOT NULL,body TEXT NOT NULL,created INTEGER NOT NULL)")
    }
    fun migrateOriginals(db:SQLiteDatabase){
        create(db)
        val columns=db.rawQuery("PRAGMA table_info(chat_images)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
        if("storage_kind" !in columns)db.execSQL("ALTER TABLE chat_images ADD COLUMN storage_kind TEXT NOT NULL DEFAULT 'legacy-normalized'")
    }
    fun prepare(input:InputStream,directory:java.io.File=ImageDisk.directory()):PendingChatImage {
        val root=ImageDisk.root(directory);val file=java.io.File.createTempFile("import-",".image",root)
        try {
            ImageDisk.copy(input,file,{require(file.length()<=OriginalChunks.CHUNK.toLong()*OriginalChunks.MAX_CHUNKS){"原件超过本机资源清单容量，未截断保存"}})
            val source=ImageSource(file);val info=inspect(source);ImagePixels.preview(source,1024).recycle()
            return PendingChatImage(info.mime,file,info.width,info.height)
        }catch(e:Throwable){file.delete();if(e is OutOfMemoryError)throw IllegalArgumentException("本机内存不足，原件未压缩覆盖或发送",e);throw e}
    }
    data class Info(val mime:String,val width:Int,val height:Int,val orientation:Int){
        val displayWidth get()=if(orientation in 5..8)height else width
        val displayHeight get()=if(orientation in 5..8)width else height
    }
    fun inspect(raw:ByteArray):Info=inspect(ImageSource(raw))
    fun inspect(source:ImageSource):Info {
        require(source.size>0){"图片文件大小无效"}
        val info=BitmapFactory.Options().apply{inJustDecodeBounds=true};source.decode(info)
        require(info.outMimeType in ImagePolicy.mimes){"仅支持静态JPEG、PNG、WebP原图；其他格式不会自动转换"}
        require(info.outWidth in 1..ImagePolicy.MAX_SIDE&&info.outHeight in 1..ImagePolicy.MAX_SIDE&&info.outWidth.toLong()*info.outHeight<=ImagePolicy.MAX_PIXELS){"图片尺寸过大或已损坏"}
        require(!ImagePixels.animated(source,info.outMimeType)){"暂不支持动态图片，不会只取首帧发送"}
        val orientation=runCatching{source.input().use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}}.getOrDefault(1).takeIf{it in 1..8}?:1
        return Info(info.outMimeType,info.outWidth,info.outHeight,orientation)
    }
    fun put(db:SQLiteDatabase,friendId:String,messageId:Long,image:PendingChatImage){
        require(image.source.size>0&&image.hash==image.source.hash())
        image.source.file?.parentFile?.let{ImageDisk.space(it,image.source.size*2)}
        val video=VideoPolicy.isVideo(image.mime)
        if(video&&ToolboxPreferences.videoMode(db)==ToolboxPreferences.VideoMode.ORIGINAL)VideoPolicy.requireSize(image.source.size)
        val large=video||image.source.size>ImagePolicy.ORIGINAL_LIMIT
        val path="chat-images/${UUID.randomUUID()}/"+if(large)"original.json"else "original."+when(image.mime){"image/png"->"png";"image/webp"->"webp";else->"jpg"}
        if(large)OriginalChunks.put(db,friendId,path,image.source,image.hash)
        else db.insertOrThrow("role_assets",null,ContentValues().apply{put("friend_id",friendId);put("path",path);put("kind","attachment");put("mime",image.mime);put("data",image.source.bytes())})
        db.insertOrThrow("chat_images",null,ContentValues().apply{put("friend_id",friendId);put("message_id",messageId);put("path",path);put("mime",image.mime);put("hash",image.hash);put("width",image.width);put("height",image.height);put("revision",1);put("storage_kind",if(large)"chunked-original"else"original")})
    }
    fun ref(db:SQLiteDatabase,messageId:Long):ChatImageRef?=db.rawQuery("SELECT friend_id,message_id,path,mime,hash,width,height,revision,storage_kind FROM chat_images WHERE message_id=?",arrayOf(messageId.toString())).use{c->if(!c.moveToFirst())null else ChatImageRef(c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getInt(6),c.getLong(7),c.getString(8))}
    private val cacheGate=Any()
    fun source(db:SQLiteDatabase,ref:ChatImageRef):ImageSource=synchronized(cacheGate){
        check(ref(db,ref.messageId)==ref){"图片来源已变化"}
        val root=ImageDisk.root();val file=java.io.File(root,"${ref.hash}.image")
        if(file.isFile&&ImageSource(file).hash()==ref.hash)return@synchronized ImageSource(file)
        val temp=java.io.File.createTempFile("read-",".image",root)
        try{
            fun size(path:String)=db.rawQuery("SELECT length(data) FROM role_assets WHERE friend_id=? AND path=?",arrayOf(ref.friendId,path)).use{check(it.moveToFirst());it.getInt(0)}
            if(ref.storageKind=="chunked-original"){
                val m=OriginalChunks.parse(RoleAssets.read(db,ref.friendId,ref.path,size(ref.path)),ref.path);check(m.hash==ref.hash)
                OriginalChunks.extract(m,temp){p->RoleAssets.read(db,ref.friendId,p.path,p.size)}
            }else{
                val length=size(ref.path);require(length in 1..BackupArchive.MAX_FILE);ImageDisk.space(root,length.toLong())
                temp.outputStream().use{out->var at=0;while(at<length){val n=minOf(OriginalChunks.CHUNK,length-at);val bytes=db.rawQuery("SELECT substr(data,?,?) FROM role_assets WHERE friend_id=? AND path=?",arrayOf((at+1).toString(),n.toString(),ref.friendId,ref.path)).use{check(it.moveToFirst());it.getBlob(0)};check(bytes.size==n);out.write(bytes);at+=n}}
            }
            check(ImageSource(temp).hash()==ref.hash){"图片文件已损坏"};check(temp.renameTo(file));ImageSource(file)
        }catch(e:Throwable){temp.delete();throw e}
    }
    fun read(db:SQLiteDatabase,ref:ChatImageRef):ByteArray=source(db,ref).bytes()
    fun setting(db:SQLiteDatabase,key:String)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0)else ""}
    fun enabled(db:SQLiteDatabase)=setting(db,SWITCH)=="1"
    fun routeStamp(db:SQLiteDatabase)=listOf(SWITCH,SWITCH_REVISION,ToolboxPreferences.VIDEO_KEY,ToolboxPreferences.VIDEO_REVISION).joinToString(":"){setting(db,it)}
    fun setEnabled(db:SQLiteDatabase,value:Boolean){
        LocalStep.AUX_VISION.observe(result = { it }) {
            var result = LocalResult.COMPLETED
            db.beginTransaction()
            try {
                for ((k,v) in listOf(SWITCH to if(value)"1"else"0", SWITCH_REVISION to UUID.randomUUID().toString())) {
                    val row = db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",k);put("value",v)},SQLiteDatabase.CONFLICT_REPLACE)
                    if (row == -1L) result = LocalResult.WRITE_REJECTED
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            result
        }
    }
    fun redact(value:String)=value.replace(Regex("""data(?::|\\u003a)image(?:\\*/|\\u002f)[^\s"'<>]*""",RegexOption.IGNORE_CASE),"[图片数据已省略]")
        .replace(Regex("""data(?::|\\u003a)video(?:\\*/|\\u002f)[^\s"'<>]*""",RegexOption.IGNORE_CASE),"[视频数据已省略]")
}
