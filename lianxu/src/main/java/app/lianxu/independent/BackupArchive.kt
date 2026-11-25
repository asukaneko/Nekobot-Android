package app.lianxu.independent

import com.google.gson.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

/** v2 on disk, streaming resources; bounded JSON only. No ZIP path is used as a filesystem path. */
internal object BackupArchive {
    const val MAX_JSON=8*1024*1024
    const val MAX_FILE=ImagePolicy.LEGACY_ASSET_LIMIT
    const val MAX_ENTRIES=65536
    private const val MAX_MANIFEST=16*1024*1024
    private val assetPath=Regex("assets/[0-9]+\\.bin")
    data class Package(val root:JsonObject,val files:Map<String,ByteArray>,val digest:String,val legacy:RoleArchive.Package?=null):Closeable {
        val all get()=legacy==null&&root["scope"].asString=="all"
        val roles get()=root["roles"].asJsonArray
        val includesKeys get()=legacy==null&&root["includesKeys"].asBoolean
        override fun close()=BackupAssetIO.close(files)
        override fun toString()="BackupPackage(redacted)"
    }
    fun bounded(input:InputStream,max:Int):ByteArray {
        require(max>=0){"备份大小限制无效"};val out=ByteArrayOutputStream(minOf(max,8192));val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer,0,minOf(buffer.size,max-out.size()+1));if(n<0)break;if(n==0)continue
            require(out.size().toLong()+n<=max){"备份单项内容超过安全上限"};out.write(buffer,0,n)}
        return out.toByteArray()
    }
    fun read(input:InputStream,directory:File=ImageDisk.directory(),valid:()->Unit={}):Package {
        val work=try{BackupWorkspace.create(directory)}catch(e:Throwable){try{input.close()}catch(close:Throwable){e.addSuppressed(close)};throw e}
        try{input.use{source->FileOutputStream(work.zip).use{out->val buffer=ByteArray(BackupAssetIO.BUFFER)
            while(true){valid();val n=source.read(buffer);if(n<0)break;if(n==0)continue;work.space(n.toLong());out.write(buffer,0,n)};out.fd.sync()
        }};valid();return open(work,valid)}catch(e:Throwable){work.close();if(e is OutOfMemoryError)throw IllegalArgumentException("内存不足，无法校验备份；原资料未改动",e);throw e}
    }
    /** Finish and verify the private ZIP before offering a user destination. */
    fun snapshot(root:JsonObject,files:Map<String,ByteArray>,directory:File,valid:()->Unit):Package = LocalStep.SNAPSHOT.observe {
        val work=BackupWorkspace.create(directory)
        try{FileOutputStream(work.zip).use{out->
            val checked=object:FilterOutputStream(out){override fun write(b:Int){valid();work.space(1);out.write(b)};override fun write(b:ByteArray,off:Int,len:Int){valid();work.space(len.toLong());out.write(b,off,len)}}
            // ZipOutputStream owns its wrapper, not the FileOutputStream whose fd is synced below.
            val nonClosing=object:FilterOutputStream(checked){
                override fun write(b:ByteArray,off:Int,len:Int)=checked.write(b,off,len)
                override fun close(){flush()}
            }
            write(root,files,BufferedOutputStream(nonClosing,BackupAssetIO.BUFFER),valid);out.fd.sync()
        };open(work,valid,false)}catch(e:Throwable){work.close();throw e}
    }
    private fun small(z:ZipFile,e:ZipEntry,max:Int,valid:()->Unit):ByteArray {
        require(e.size in 0..max.toLong()){"备份单项内容超过安全上限"};valid()
        val raw=z.getInputStream(e).use{bounded(it,max)}
        require(raw.size.toLong()==e.size&&CRC32().apply{update(raw)}.value==e.crc){"备份文件长度或CRC错误"};return raw
    }
    private fun open(work:BackupWorkspace,valid:()->Unit,reserveRestore:Boolean=true):Package {
        BackupZipDirectory.check(work.zip,valid);val zip=ZipFile(work.zip)
        try{
            require(zip.size() in 2..MAX_ENTRIES){"备份文件数量无效或过多"}
            val entries=linkedMapOf<String,ZipEntry>();var expanded=0L
            val iterator=zip.entries();while(iterator.hasMoreElements()){
                valid();val e=iterator.nextElement();val name=RoleArchive.entryName(e.name)
                require(!e.isDirectory&&name.length<=128&&name !in entries&&(name in setOf("manifest.json","backup.json","role.json")||assetPath.matches(name))){"备份含未知、重复或无效路径"}
                val limit=when(name){"manifest.json"->MAX_MANIFEST;"backup.json"->MAX_JSON;"role.json"->RoleArchive.MAX_BYTES;else->MAX_FILE}
                require(e.method in setOf(ZipEntry.DEFLATED,ZipEntry.STORED)&&e.size in 0..limit.toLong()&&e.compressedSize>=0){"备份资源格式或单项大小无效"}
                expanded=Math.addExact(expanded,e.size);entries[name]=e
            }
            // Available working/storage capacity, not a fixed archive total. Reject expansion
            // bombs before inflating resources; allow normal highly compressible video padding.
            work.space(if(reserveRestore)Math.multiplyExact(expanded,2)else 0)
            require(expanded<=Math.addExact(Math.multiplyExact(work.zip.length(),4096),MAX_MANIFEST.toLong())){"备份压缩膨胀异常，未解压或改动资料"}
            val manifestEntry=entries["manifest.json"]?:error("备份缺少清单")
            val manifest=RoleArchive.parse(small(zip,manifestEntry,MAX_MANIFEST,valid))
            if(manifest["format"]?.asString==RoleArchive.FORMAT){
                require(work.zip.length()<=RoleArchive.MAX_BYTES&&expanded<=RoleArchive.MAX_BYTES&&entries.keys==setOf("manifest.json","role.json")&&manifestEntry.size<=8192){"旧角色包超过原格式安全范围或含额外文件"}
                require(manifest.keySet()==setOf("format","version","scope","size","sha256")&&RoleArchive.integer(manifest["version"])==1L){"旧角色清单字段或版本不支持"}
                val raw=small(zip,entries.getValue("role.json"),RoleArchive.MAX_BYTES,valid)
                require(RoleArchive.integer(manifest["size"])==raw.size.toLong()&&manifest["sha256"].asString==RoleArchive.hash(raw)){"角色资料校验失败"}
                val old=RoleArchive.validate(raw);require(old.scope==manifest["scope"].asString){"导出范围不一致"}
                zip.close();work.close();return Package(JsonObject().apply{add("roles",JsonArray());addProperty("scope","role")},emptyMap(),old.digest,old)
            }
            require(manifest.keySet()==setOf("format","version","files")&&manifest["format"].asString=="lianxu-backup"&&RoleArchive.integer(manifest["version"])==2L){"不支持的备份格式或版本"}
            val listed=manifest["files"].asJsonArray;require(listed.size()==entries.size-1&&listed.size() in 1 until MAX_ENTRIES){"备份清单数量不符"}
            val seen=hashSetOf<String>();val digests=linkedMapOf<String,String>()
            for(value in listed){valid();val f=value.asJsonObject;require(f.keySet()==setOf("path","size","sha256")){"备份清单字段不符"}
                val name=f["path"].asString;require(seen.add(name)&&(name=="backup.json"||assetPath.matches(name))){"备份清单路径无效或重复"}
                val e=entries[name]?:error("备份缺少文件");require(RoleArchive.integer(f["size"])==e.size&&f["sha256"].asString.matches(Regex("[a-f0-9]{64}"))){"备份清单长度或校验字段错误"}
                val crc=CRC32();val sink=object:OutputStream(){override fun write(b:Int){crc.update(b)};override fun write(b:ByteArray,off:Int,len:Int){crc.update(b,off,len)}}
                val hash=zip.getInputStream(e).use{BackupAssetIO.copy(it,sink,e.size,valid)}
                require(hash==f["sha256"].asString&&crc.value==e.crc){"备份校验失败"};digests[name]=hash
            }
            val root=RoleArchive.parse(small(zip,entries["backup.json"]?:error("缺少备份资料"),MAX_JSON,valid));validateRoot(root);RetiredRelationshipData.stripBackup(root)
            val assets=entries.filterKeys{assetPath.matches(it)}
            val files=ZipBackupAssets(work,zip,assets.mapValues{it.value.name},assets.mapValues{it.value.size})
            val hash=MessageDigest.getInstance("SHA-256");digests.toSortedMap().forEach{(n,v)->hash.update(n.toByteArray(Charsets.UTF_8));hash.update(0.toByte());hash.update(v.toByteArray(Charsets.US_ASCII))}
            valid();return Package(root,files,hash.digest().joinToString(""){"%02x".format(it)})
        }catch(e:Throwable){zip.close();throw e}
    }
    private fun validateRoot(root:JsonObject){
        val fields=setOf("scope","roles","preferences","models","selections","includesKeys")
        require(root.keySet()==fields||root.keySet()==fields+BackupKeySeal.FIELD){"备份资料字段不支持"}
        require(root["scope"].isJsonPrimitive&&root["scope"].asJsonPrimitive.isString&&root["scope"].asString in setOf("role","all")&&root["includesKeys"].isJsonPrimitive&&root["includesKeys"].asJsonPrimitive.isBoolean){"备份范围或密钥标识无效"}
        require(root["roles"].isJsonArray&&root["roles"].asJsonArray.size() in 0..100&&root["models"].isJsonArray&&root["models"].asJsonArray.size()<=100&&root["preferences"].isJsonObject&&root["selections"].isJsonObject){"备份资料类别或数量无效"}
        if(root["scope"].asString=="role")require(root["roles"].asJsonArray.size()==1&&root["models"].asJsonArray.isEmpty&&root["preferences"].asJsonObject.size()==0&&root["selections"].asJsonObject.size()==0&&!root["includesKeys"].asBoolean){"单角色包不能包含全局设置或密钥"}
        if(BackupKeySeal.sealed(root))require(root["includesKeys"].asBoolean&&root[BackupKeySeal.FIELD].isJsonObject){"备份密钥锁无效"}
    }
    private data class Prepared(val metadata:ByteArray,val manifest:ByteArray,val hashes:Map<String,String>)
    fun preflight(root:JsonObject,assets:Map<String,ByteArray>){prepare(root,assets,{})}
    private fun prepare(root:JsonObject,assets:Map<String,ByteArray>,valid:()->Unit):Prepared {
        validateRoot(root);require(assets.size<=MAX_ENTRIES-2&&assets.keys.all{it.length<=128&&assetPath.matches(it)}&&assets.keys.all{BackupAssetIO.length(assets,it) in 0..MAX_FILE.toLong()}){"备份资源路径、数量或单项大小无效"}
        val bytes=root.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=MAX_JSON){"备份文字资料超过8 MiB"};RoleArchive.parse(bytes)
        val hashes=linkedMapOf("backup.json" to RoleArchive.hash(bytes));for(name in assets.keys){valid();hashes[name]=BackupAssetIO.hash(assets,name,valid)}
        val manifest=JsonObject().apply{addProperty("format","lianxu-backup");addProperty("version",2);add("files",JsonArray().apply{hashes.forEach{(name,hash)->add(JsonObject().apply{addProperty("path",name);addProperty("size",if(name=="backup.json")bytes.size.toLong()else BackupAssetIO.length(assets,name));addProperty("sha256",hash)})}})}.toString().toByteArray(Charsets.UTF_8)
        require(manifest.size<=MAX_MANIFEST){"备份清单超过安全上限"};RoleArchive.parse(manifest);return Prepared(bytes,manifest,hashes)
    }
    fun write(root:JsonObject,assets:Map<String,ByteArray>,output:OutputStream,valid:()->Unit={}) {
        val prepared=prepare(root,assets,valid)
        ZipOutputStream(output).use{zip->zip.putNextEntry(ZipEntry("manifest.json"));zip.write(prepared.manifest);zip.closeEntry()
            zip.putNextEntry(ZipEntry("backup.json"));zip.write(prepared.metadata);zip.closeEntry()
            for(name in assets.keys){valid();zip.putNextEntry(ZipEntry(name));val hash=BackupAssetIO.open(assets,name).use{BackupAssetIO.copy(it,zip,BackupAssetIO.length(assets,name),valid)};require(hash==prepared.hashes.getValue(name)){"备份资源在导出期间发生变化，未完成"};zip.closeEntry()}
        }
    }
}
