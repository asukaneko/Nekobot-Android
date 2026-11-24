package app.lianxu.independent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelCredential(val config:ModelConfig,val key:String,val stamp:String) {override fun toString()="ModelCredential(redacted)"}
class ModelAccess(private val vault:ModelVault){
    internal val statusNamespace get()=vault.statusNamespace
    suspend fun credential(purpose:ModelPurpose):ModelCredential?=withContext(Dispatchers.IO){AppMutationGate.read{
        val id=vault.purposeId(purpose);val config=vault.list().firstOrNull{it.id==id}?:return@read null
        val key=vault.secret(id);if(key.isBlank())return@read null
        ModelCredential(config,key,vault.selectionStamp(purpose))
    }}
    /** Explicit memory selection wins. Only an unset memory purpose falls back to chat. */
    suspend fun memoryCredential():ModelCredential? = credential(if(vault.purposeId(ModelPurpose.MEMORY).isBlank())ModelPurpose.CHAT else ModelPurpose.MEMORY)
    fun memoryCurrent(stamp:String)=current(if(vault.purposeId(ModelPurpose.MEMORY).isBlank())ModelPurpose.CHAT else ModelPurpose.MEMORY,stamp)
    fun current(purpose:ModelPurpose,stamp:String)=stamp.isNotEmpty()&&vault.selectionStamp(purpose)==stamp
}
