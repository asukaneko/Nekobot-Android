package app.lianxu.independent

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-config AES-GCM keys stay in AndroidKeyStore. Entire records live encrypted in noBackupFilesDir. */
class ModelVault(context:Context,private val namespace:String="model-configs",private val store:Store?=null) {
    private val root=File(context.noBackupFilesDir,namespace).apply {mkdirs()}
    /** Non-secret scope for per-configuration status lines. */
    internal val statusNamespace get()=namespace
    internal val generationKey get()="model-vault-generation:$namespace"
    private val directory:File get(){
        val generation=store?.readableDatabase?.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(generationKey))?.use{if(it.moveToFirst())it.getString(0) else ""}.orEmpty()
        require(generation.isEmpty()||generation.matches(Regex("[a-f0-9-]{36}")))
        return if(generation.isEmpty())root else File(root,"generations/$generation")
    }
    private val keyStore=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    init { AppMutationGate.read {
        // Remove only the retired purpose selection; keep every model configuration/key.
        val locations=listOf(root)+File(root,"generations").listFiles().orEmpty().filter{it.isDirectory}
        for(location in locations){val file=File(location,"purpose-selections");if(!file.exists())continue
            val atomic=AtomicFile(file)
            // Broken selections must not prevent local role creation. Preserve them for recovery;
            // explicit model reads still report the error rather than pretending to be unconfigured.
            val value=runCatching{readSelections(atomic)}.getOrNull()?:continue
            if(!value.has("relationship"))continue
            value.remove("relationship")
            runCatching{val stream=atomic.startWrite()
                try{stream.write(value.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
            }
        }
    } }
    private fun file(id:String):File { require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))); return File(directory,"$id.enc") }
    private fun alias(id:String)="lianxu.$namespace.$id"
    private fun key(id:String):SecretKey = (keyStore.getKey(alias(id),null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias(id),KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    }.generateKey()
    private fun read(id:String):JSONObject =
        decryptRecord(AtomicFile(file(id)).readFully(), id, alias(id), keyStore).also { Diagnostics.registerSecret(it.getString("key")) }

    companion object {
        /** Read a single standalone encrypted record without creating a vault or migrating selections. */
        internal fun existingSecret(noBackupDir: File, namespace: String, id: String): String? = AppMutationGate.read {
            val safeName = Regex("[a-zA-Z0-9-]{1,80}")
            require(namespace.matches(safeName) && id.matches(safeName))
            val record = File(File(noBackupDir, namespace), "$id.enc")
            // Android AtomicFile regards a legacy .bak, when present, as the last committed version.
            val backup = File(record.parentFile, record.name + ".bak")
            val source = if (backup.isFile) backup else record
            if (!source.isFile) return@read null
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (decryptRecord(source.readBytes(), id, "lianxu.$namespace.$id", store).opt("key") as? String)?.also(Diagnostics::registerSecret)
        }

        private fun decryptRecord(bytes: ByteArray, id: String, alias: String, keyStore: KeyStore): JSONObject {
            require(bytes.size>28 && bytes[0].toInt()==1)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            val stored=keyStore.getKey(alias,null) as? SecretKey ?: error("Key unavailable")
            cipher.init(Cipher.DECRYPT_MODE,stored,GCMParameterSpec(128,bytes.copyOfRange(1,13)))
            cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
            return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8))
        }
    }
    private fun onMainThread():Boolean {
        val main=Looper.getMainLooper()?:return false
        return Looper.myLooper()===main
    }
    private fun snapshot():ModelVaultCache.Snapshot = ModelVaultCache.getOrLoad(namespace,directory.canonicalPath,onMainThread()) {
        val dir=directory
        val loaded=dir.listFiles().orEmpty().filter {it.extension=="enc"}.map {f ->
            val j=read(f.nameWithoutExtension)
            val provider=j.getString("provider");val base=j.getString("base");val model=j.getString("model")
            val context=j.optInt("contextTokens",ModelTokenPolicy.DEFAULT_CONTEXT)
            val output=j.optInt("outputTokens",ModelTokenPolicy.DEFAULT_CHAT)
            // Old builds pre-filled exactly 16384/2048. That pair is indistinguishable from an untouched
            // default, so it is lifted to the current default (capped by a checked model's real ceiling);
            // any other saved value stays exactly as the user set it.
            val (liftedContext,liftedOutput)=if(f.nameWithoutExtension.isHostModel()) context to output else ModelTokenPolicy.liftLegacy(context,output,provider,base,model)
            val config=ModelConfig(f.nameWithoutExtension,j.getString("name"),provider,base,model,liftedContext,liftedOutput,j.optInt("memoryOutputTokens",0),j.optString("thinkingMode",ThinkingMode.DEFAULT.key),j.optString("thinkingCustom",""),j.optBoolean("streamReplies",true))
            config to j.getString("key")
        }
        ModelVaultCache.Snapshot(dir.canonicalPath,loaded.map{it.first}.sortedBy {it.name},loaded.associate{it.first.id to it.second})
    }
    internal fun matchesCached(config:ModelConfig):Boolean=AppMutationGate.read {
        ModelVaultCache.peek(namespace,directory.canonicalPath)?.configs?.any{it==config}==true
    }
    fun list():List<ModelConfig> = AppMutationGate.read { snapshot().configs.toList() }
    fun secret(id:String)=AppMutationGate.read { snapshot().secrets[id]?.also{Diagnostics.registerSecret(it)} ?: read(id).getString("key").also{Diagnostics.registerSecret(it)} }
    fun save(config:ModelConfig,secret:String):Unit = AppMutationGate.write {
        Diagnostics.registerSecret(secret)
        require(ConfigRules.validate(config,secret)==null)
        writeAt(directory,config,secret)
        ModelVaultCache.invalidate(namespace)
        // New configurations require an explicit purpose selection; an embedding must not become chat by saving it.
    }
    private fun writeAt(destination:File,config:ModelConfig,secret:String){
        Diagnostics.registerSecret(secret)
        destination.mkdirs()
        val data=JSONObject().put("name",config.name).put("provider",config.providerId).put("base",config.baseUrl).put("model",config.modelId).put("key",secret).put("contextTokens",config.contextTokens).put("outputTokens",config.outputTokens).put("memoryOutputTokens",config.memoryOutputTokens).put("thinkingMode",config.thinkingMode).put("thinkingCustom",config.thinkingCustom).put("streamReplies",config.streamReplies).toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(config.id));cipher.updateAAD(config.id.toByteArray(Charsets.UTF_8))
        val encrypted=byteArrayOf(1)+cipher.iv+cipher.doFinal(data); data.fill(0)
        val atomic=AtomicFile(File(destination,"${config.id}.enc"));val stream=atomic.startWrite()
        try {stream.write(encrypted);atomic.finishWrite(stream)} catch(e:Exception) {atomic.failWrite(stream);throw e}
    }
    fun delete(id:String) = AppMutationGate.write {
        keyStore.deleteEntry(alias(id)); AtomicFile(file(id)).delete()
        val selections=ModelPurpose.entries.associateWith{purposeId(it)}
        selections.filterValues{it==id}.keys.forEach{setPurpose(it, "")}
        ModelVaultCache.invalidate(namespace)
    }
    // Existing default-id is read as the chat purpose until first explicit purpose write.
    // Saving/selecting never contacts a provider. A deleted selection is cleared, not switched silently.
    private fun readSelections(file:AtomicFile):JSONObject {
        val raw=String(file.readFully(),Charsets.UTF_8);require('\u0000' !in raw)
        val parser=JSONTokener(raw);val value=parser.nextValue()
        require(value is JSONObject)
        while(parser.more())require(parser.next() in charArrayOf(' ','\t','\r','\n'))
        return value
    }
    private fun purposeValues():JSONObject {
        val file=File(directory,"purpose-selections")
        return if(file.exists())readSelections(AtomicFile(file))
        else JSONObject().put(ModelPurpose.CHAT.key,File(directory,"default-id").takeIf{it.exists()}?.readText().orEmpty())
    }
    fun purposeId(purpose:ModelPurpose):String = AppMutationGate.read {purposeValues().optString(purpose.key)}
    fun setPurpose(purpose:ModelPurpose,id:String) = AppMutationGate.write {
        require(id.isEmpty() || file(id).exists())
        // A -> B -> A is still a changed selection: old in-flight work must not become current again.
        val value=purposeValues().put(purpose.key,id).put(purpose.key+".revision",java.util.UUID.randomUUID().toString())
        val atomic=AtomicFile(File(directory,"purpose-selections"));val stream=atomic.startWrite()
        try{stream.write(value.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
    }
    fun defaultId():String = purposeId(ModelPurpose.CHAT)
    fun setDefault(id:String) {setPurpose(ModelPurpose.CHAT,id)}
    internal fun recordStamp(id:String):String = AppMutationGate.read {
        if(!file(id).exists())"" else stableDigest(java.util.Base64.getEncoder().encodeToString(AtomicFile(file(id)).readFully()))
    }
    fun selectionStamp(purpose:ModelPurpose):String = AppMutationGate.read {
        val selected=purposeValues();val id=selected.optString(purpose.key)
        if(id.isEmpty()||!file(id).exists()) "" else stableDigest(id+":"+selected.optString(purpose.key+".revision")+":"+stableDigest(java.util.Base64.getEncoder().encodeToString(AtomicFile(file(id)).readFully())))
    }
    fun hasKey(id:String)=AppMutationGate.read {keyStore.containsAlias(alias(id))}
    internal fun backup(includeKeys:Boolean):Pair<List<BackupModel>,Map<ModelPurpose,String>> = AppMutationGate.read {
        list().filterNot { it.id.isHostModel() }.map{BackupModel(it,if(includeKeys)secret(it.id) else null)} to
            backupPurposeIds(::purposeId)
    }
    internal class Prepared(val generation:String,val directory:File,val createdAliases:List<String>)
    internal fun prepareRestore(incoming:List<BackupModel>,selections:Map<ModelPurpose,String>,includeKeys:Boolean):Prepared = AppMutationGate.read {
        check(store!=null){"全量恢复需要绑定数据库"}
        val generation=java.util.UUID.randomUUID().toString();val dir=File(root,"generations/$generation")
        require(incoming.none { it.config.id.isHostModel() })
        val old=list().filterNot { it.id.isHostModel() };val aliases=mutableListOf<String>()
        try {
            check(dir.isDirectory||dir.mkdirs()){ "无法创建模型恢复暂存目录" }
            val restored=incoming.map{row->
                val match=old.firstOrNull{it.id==row.config.id&&it.providerId==row.config.providerId&&it.baseUrl==row.config.baseUrl}
                BackupModel(row.config,if(includeKeys)row.secret.orEmpty() else match?.let{secret(it.id)}.orEmpty())
            }.toMutableList()
            if(!includeKeys)old.filter{oldConfig->incoming.none{it.config.id==oldConfig.id&&it.config.providerId==oldConfig.providerId&&it.config.baseUrl==oldConfig.baseUrl}}.forEach{oldConfig->
                val key=secret(oldConfig.id)
                if(key.isNotEmpty())restored+=BackupModel(oldConfig.copy(id="retained-"+java.util.UUID.randomUUID(),name=oldConfig.name+"（保留原密钥，未自动选择）"),key)
            }
            restored.forEach{row->if(!keyStore.containsAlias(alias(row.config.id)))aliases+=alias(row.config.id);writeAt(dir,row.config,row.secret.orEmpty())}
            val selected=JSONObject();ModelPurpose.entries.forEach{p->val id=selections[p].orEmpty();selected.put(p.key,if(incoming.any{it.config.id==id})id else "")}
            File(dir,"purpose-selections").writeText(selected.toString(),Charsets.UTF_8)
            Prepared(generation,dir,aliases)
        }catch(e:Exception){dir.deleteRecursively();aliases.forEach{keyStore.deleteEntry(it)};throw e}
    }
    internal fun isActive(prepared:Prepared):Boolean? = runCatching{
        if(store?.readableDatabase?.inTransaction()==true)null else directory.canonicalFile==prepared.directory.canonicalFile
    }.getOrNull()
    internal fun abort(prepared:Prepared){
        check(isActive(prepared)==false){"不能清理已启用或提交状态不确定的密钥记录"}
        prepared.directory.deleteRecursively();prepared.createdAliases.forEach{keyStore.deleteEntry(it)}
    }
    internal fun finishRestore(prepared:Prepared){
        check(isActive(prepared)==true){"密钥记录尚未完成切换"}
        ModelVaultCache.invalidate(namespace)
        // Cleanup is best-effort after publication. Failure cannot turn a successful restore into failure.
        runCatching{
            val active=prepared.directory.canonicalFile
            File(root,"generations").listFiles().orEmpty().filter{it.isDirectory&&it.canonicalFile!=active}.forEach{it.deleteRecursively()}
            root.listFiles().orEmpty().filter{it.isFile&&(it.name.endsWith(".enc")||it.name.endsWith(".enc.bak")||it.name in setOf("default-id","purpose-selections","purpose-selections.bak"))}.forEach{it.delete()}
        }.onFailure{Diagnostics.failure("backup.vault.cleanup.failed",it)}
    }
    internal fun stamp():String=AppMutationGate.read {stableDigest(list().joinToString("|"){it.toString()+":"+java.util.Base64.getEncoder().encodeToString(AtomicFile(file(it.id)).readFully())}+purposeValues().toString())}

}

/** A portable backup may intentionally contain plaintext credentials; never log this object. */
internal class BackupModel(val config:ModelConfig,val secret:String?) {override fun toString()="BackupModel(redacted)"}

internal fun backupPurposeIds(selected: (ModelPurpose) -> String) =
    ModelPurpose.entries.associateWith { selected(it).takeUnless(String::isHostModel).orEmpty() }
