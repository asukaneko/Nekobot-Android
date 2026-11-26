package app.lianxu.independent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelCredential(val config:ModelConfig,val key:String,val stamp:String) {override fun toString()="ModelCredential(redacted)"}
class ModelAccess(private val vault:ModelVault){
    internal val statusNamespace get()=vault.statusNamespace
    private fun storedCredential(purpose:ModelPurpose):ModelCredential? {
        val id=vault.purposeId(purpose);val config=vault.list().firstOrNull{it.id==id}?:return null
        val key=vault.secret(id);if(key.isBlank())return null
        return ModelCredential(config,key,vault.selectionStamp(purpose))
    }
    suspend fun credential(purpose:ModelPurpose)=readSelectedCredential({purpose},::storedCredential)
    /** Explicit memory selection wins. Only an unset memory purpose falls back to chat. */
    suspend fun memoryCredential():ModelCredential? = credential(if(vault.purposeId(ModelPurpose.MEMORY).isBlank())ModelPurpose.CHAT else ModelPurpose.MEMORY)
    fun memoryCurrent(stamp:String)=current(if(vault.purposeId(ModelPurpose.MEMORY).isBlank())ModelPurpose.CHAT else ModelPurpose.MEMORY,stamp)
    private fun reviewPurpose()=reviewPurposeFor(vault.purposeId(ModelPurpose.REVIEW))
    suspend fun reviewCredential()=readSelectedCredential(::reviewPurpose){purpose->
        storedCredential(purpose)?.let{ModelCredential(it.config,it.key,purpose.key+":"+it.stamp)}
    }
    fun reviewCurrent(stamp:String):Boolean=AppMutationGate.read {
        val purpose=reviewPurpose()
        val selected=vault.selectionStamp(purpose)
        selected.isNotEmpty() && stamp==purpose.key+":"+selected
    }
    fun current(purpose:ModelPurpose,stamp:String)=stamp.isNotEmpty()&&vault.selectionStamp(purpose)==stamp
}
internal fun reviewPurposeFor(selection:String?)=if(selection.isNullOrBlank()) ModelPurpose.CHAT else ModelPurpose.REVIEW

internal suspend fun readSelectedCredential(select:()->ModelPurpose,read:(ModelPurpose)->ModelCredential?):ModelCredential? =
    withContext(Dispatchers.IO){AppMutationGate.read{read(select())}}
