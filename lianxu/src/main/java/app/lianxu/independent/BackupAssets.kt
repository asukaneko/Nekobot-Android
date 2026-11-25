package app.lianxu.independent

import java.io.*
import java.nio.channels.FileLock
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Map compatibility is for small/legacy callers; backup production paths use streams. */
internal abstract class BackupAssets:AbstractMap<String,ByteArray>(),Closeable {
    abstract val names:Set<String>
    abstract fun length(name:String):Long
    abstract fun open(name:String):InputStream
    override val keys get()=names
    override val size get()=names.size
    override fun containsKey(key:String)=key in names
    override fun get(key:String):ByteArray?=if(key !in names)null else open(key).use{BackupArchive.bounded(it,BackupArchive.MAX_FILE)}
    override val entries:Set<Map.Entry<String,ByteArray>> get()=object:AbstractSet<Map.Entry<String,ByteArray>>(){
        override val size get()=names.size
        override fun iterator():Iterator<Map.Entry<String,ByteArray>> = names.map{n->object:Map.Entry<String,ByteArray>{override val key=n;override val value get()=this@BackupAssets.getValue(n)}}.iterator()
    }
}

internal object BackupAssetIO {
    const val BUFFER=64*1024
    fun length(files:Map<String,ByteArray>,name:String):Long=if(files is BackupAssets)files.length(name)else files.getValue(name).size.toLong()
    fun open(files:Map<String,ByteArray>,name:String):InputStream=if(files is BackupAssets)files.open(name)else files.getValue(name).inputStream()
    fun hash(files:Map<String,ByteArray>,name:String,valid:()->Unit={}):String=open(files,name).use{input->digest(input,valid)}
    fun digest(input:InputStream,valid:()->Unit={}):String {
        val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(BUFFER)
        while(true){valid();val n=input.read(buffer);if(n<0)break;if(n>0)md.update(buffer,0,n)}
        return md.digest().joinToString(""){"%02x".format(it)}
    }
    fun copy(input:InputStream,output:OutputStream,expected:Long,valid:()->Unit={}):String {
        val md=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(BUFFER);var total=0L
        while(true){valid();val n=input.read(buffer);if(n<0)break;if(n==0)continue
            require(n<=expected-total){"备份资源长度超过清单，处理已停止"};output.write(buffer,0,n);md.update(buffer,0,n);total+=n
        }
        require(total==expected){"备份资源读取不完整"};valid();return md.digest().joinToString(""){"%02x".format(it)}
    }
    fun close(files:Map<String,ByteArray>){if(files is BackupAssets)files.close()}
    fun total(files:Map<String,ByteArray>):Long=files.keys.fold(0L){n,k->Math.addExact(n,length(files,k))}
}

/** Only opaque files in this private, locked directory are removed, never archive entry paths. */
internal class BackupWorkspace private constructor(val directory:File,private val handle:RandomAccessFile,private val lock:FileLock):Closeable {
    val zip get()=File(directory,"archive.zip")
    @Volatile private var closed=false
    fun space(next:Long=0,available:Long=directory.usableSpace){
        require(next>=0&&available>=ImageDisk.RESERVE&&next<=available-ImageDisk.RESERVE){"备份可用空间不足，需保留64 MiB余量；没有改动原资料"}
    }
    override fun close(){synchronized(this){if(closed)return;closed=true
        directory.listFiles()?.filter{it.name!=".lease"}?.forEach{it.delete()};lock.release();handle.close();File(directory,".lease").delete();directory.delete()
    }}
    companion object {
        private val gate=Any()
        fun create(parent:File=ImageDisk.directory()):BackupWorkspace=synchronized(gate){
            val root=File(parent,"lianxu-backup-work").apply{check(mkdirs()||isDirectory){"无法创建备份临时目录"}}
            // A prior process can die between reads. Live workspaces keep an OS file lock.
            root.listFiles()?.filter{it.isDirectory&&it.name.startsWith("stage-")}?.forEach{old->
                runCatching{var acquired=false;RandomAccessFile(File(old,".lease"),"rw").use{f->f.channel.tryLock()?.use{
                    acquired=true;old.listFiles()?.filter{it.name!=".lease"}?.forEach{it.delete()}
                }};if(acquired&&old.listFiles()?.all{it.name==".lease"}==true){File(old,".lease").delete();old.delete()}}
            }
            val dir=Files.createTempDirectory(root.toPath(),"stage-").toFile();val file=RandomAccessFile(File(dir,".lease"),"rw")
            try{BackupWorkspace(dir,file,file.channel.lock()).also{it.space()}}catch(e:Throwable){file.close();File(dir,".lease").delete();dir.delete();throw e}
        }
    }
}

internal class ZipBackupAssets(private val workspace:BackupWorkspace,private val archive:ZipFile,private val paths:Map<String,String>,private val lengths:Map<String,Long>):BackupAssets(){
    override val names=paths.keys
    @Volatile private var closed=false
    override fun length(name:String)=lengths.getValue(name)
    override fun open(name:String):InputStream {check(!closed){"备份临时资料已关闭，请重新读取"};return archive.getInputStream(archive.getEntry(paths.getValue(name)))}
    fun temporary():File {check(!closed);return File.createTempFile("media-",".data",workspace.directory)}
    override fun close(){synchronized(this){if(!closed){closed=true;archive.close();workspace.close()}}}
}

internal data class BackupAssetSource(val size:Long,val read:()->InputStream)
internal class SourceBackupAssets(private val sources:Map<String,BackupAssetSource>):BackupAssets(){
    override val names=sources.keys
    override fun length(name:String)=sources.getValue(name).size
    override fun open(name:String)=sources.getValue(name).read()
    override fun close()=Unit
}
