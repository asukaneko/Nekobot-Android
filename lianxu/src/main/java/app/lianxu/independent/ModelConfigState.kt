package app.lianxu.independent

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.security.MessageDigest

enum class ProbePhase { UNTESTED, TESTING, REACHABLE, FAILED }
data class ProbeDisplay(val phase:ProbePhase=ProbePhase.UNTESTED,val text:String="未测试")
private class SharedCreationModel:Exception()
private class StaleModelEditor:Exception()

class ModelConfigState @JvmOverloads constructor(
    application:Application,
    private val client:ModelDirectoryClient=HttpsModelClient(),
    vaultNamespace:String=AppServices.vaultNamespace
):AndroidViewModel(application) {
    private val storage=Store(application,AppServices.databaseName)
    private val vault=ModelVault(application,vaultNamespace,storage)
    private val diagnosticClient=LoggedModelDirectoryClient(client)
    private val statusStore=ModelStatusStore(storage,vault.statusNamespace)
    internal val vaultVersion get()=ModelVaultCache.version(vault.statusNamespace)
    internal var embeddingClient:MemoryEmbeddingClient=HttpsMemoryEmbeddingClient()
    /** 连接测试走真发一句话；测试可注入脚本化实现，不打开网络。 */
    internal var testClient:TextChatClient=HttpsTextChatClient()
    internal var hostPurposes by mutableStateOf(emptyMap<String,String>()); private set
    var configs by mutableStateOf(emptyList<ModelConfig>());private set
    internal var loaded by mutableStateOf(false);private set
    internal var configRevisions by mutableStateOf(emptyMap<String,String>());private set
    var defaultId by mutableStateOf("");private set
    var auxiliaryVision by mutableStateOf(false);private set
    var purposes by mutableStateOf(emptyMap<ModelPurpose,String>());private set
    var saving by mutableStateOf(false);private set
    var testing by mutableStateOf(false);private set
    var fetching by mutableStateOf(false);private set
    val busy get()=saving||testing
    var status by mutableStateOf("");private set
    var statusError by mutableStateOf(false);private set
    var models by mutableStateOf(emptyList<String>());private set
    var probe by mutableStateOf(ProbeDisplay());private set
    var connections by mutableStateOf(emptyMap<String,ProbeDisplay>());private set
    /** Overview lines: last explicit test (time, model, result) and the latest real call result. */
    internal var tests by mutableStateOf(emptyMap<String,ModelTestRecord>());private set
    internal var calls by mutableStateOf(emptyMap<String,ModelCallRecord>());private set
    private var pendingTest:ModelTestRecord?=null
    private var requestVersion=0L
    private var requestJob:Job?=null
    private var probeStamp:String?=null
    init {viewModelScope.launch {try {reload()}catch(_:Exception){message("无法读取加密配置，请重试。",true)}}}
    private fun message(text:String,error:Boolean=false){status=text;statusError=error}
    fun refresh(){viewModelScope.launch{runCatching{reload()}.onFailure{message("无法读取恢复后的配置，请重试。",true)}}}
    private suspend fun reload()=withContext(Dispatchers.IO){val sources=HostModelSync.refresh(storage,vault);AppMutationGate.read{
        hostPurposes=sources
        val rows=vault.list();val selected=vault.defaultId()
        val selections=ModelPurpose.entries.associateWith{vault.purposeId(it)}
        val revisions=rows.associate{it.id to vault.recordStamp(it.id)}
        val readTests=rows.mapNotNull{c->statusStore.testNow(c.id)?.takeIf{it.baseUrl==c.baseUrl && it.model==c.modelId}?.let{c.id to it}}.toMap()
        val readCalls=rows.mapNotNull{c->statusStore.lastCallNow(c.id)?.let{c.id to it}}.toMap()
        // Publish the whole local snapshot before releasing the mutation gate; an older refresh cannot restore cleared tests.
        auxiliaryVision=ChatImages.enabled(storage.readableDatabase);configs=rows;defaultId=selected;purposes=selections;configRevisions=revisions;tests=readTests;calls=readCalls;loaded=true
    }}
    fun changeAuxiliaryVision(enabled:Boolean){
        if(busy)return
        saving=true
        viewModelScope.launch{try{withContext(Dispatchers.IO){AppMutationGate.read{ChatImages.setEnabled(storage.writableDatabase,enabled)}};reload()}
            catch(_:Exception){message("保存辅助看图开关失败，请重试。",true)}finally{saving=false}}
    }
    /** A test of the saved, unchanged configuration is its "last test"; a draft's result waits for a matching save. */
    private class RequestCredential(val key:String,val revision:String)
    private fun current(config:ModelConfig,credential:RequestCredential)=vault.recordStamp(config.id)==credential.revision
    private fun staleProbe(){pendingTest=null;probeStamp=null;probe=ProbeDisplay();message("配置已更改，刚才的结果未采用，请重新测试。",true)}
    private suspend fun tested(config:ModelConfig,key:String,credential:RequestCredential,display:ProbeDisplay,ok:Boolean,reason:String,version:Long){try{withContext(Dispatchers.IO){AppMutationGate.read{
        if(version!=requestVersion)return@read
        if(!current(config,credential)){staleProbe();return@read}
        val record=ModelTestRecord(System.currentTimeMillis(),config.baseUrl,config.modelId,ok,reason)
        // Persist and expose the result under the same gate as key rotation, with no provider call inside it.
        if(key.isEmpty()&&vault.list().any{it.probeTarget()==config.probeTarget()}){
            statusStore.saveTestNow(config.id,record);tests=tests+(config.id to record);connections=connections+(config.id to display)
        }
        pendingTest=record;probe=display;probeStamp=stamp(config,credential.key)
    }}}catch(e:CancellationException){throw e}catch(_:Exception){AppMutationGate.read{
        if(version==requestVersion){pendingTest=null;probeStamp=null;probe=ProbeDisplay();message("已收到接口结果，但无法核对或保存测试记录，请重试。",true)}
    }}}
    /** Invalidate UI requests on edits, back, disposal and background; keep an explicit disk commit. */
    fun clearResult()=AppMutationGate.read{requestVersion++;requestJob?.cancel();requestJob=null;testing=false;fetching=false;probe=ProbeDisplay();probeStamp=null;pendingTest=null;models=emptyList();status="";statusError=false}
    fun cancelFetch(){if(fetching){clearResult();message("已取消获取，当前模型未更改。")}}
    private suspend fun credential(config:ModelConfig,entered:String):RequestCredential = withContext(Dispatchers.IO){AppMutationGate.read{
        val resolved=ModelKeyDraft.resolve(vault.list().find{it.id==config.id},config,entered){vault.secret(config.id)}
        RequestCredential(resolved,vault.recordStamp(config.id))
    }}
    suspend fun savedKey(config:ModelConfig):String = withContext(Dispatchers.IO) {
        try { AppMutationGate.read{ModelKeyDraft.resolve(vault.list().find{it.id==config.id},config,""){vault.secret(config.id)}} } catch(e:CancellationException) { throw e }
        catch(_:Exception) { "" }
    }
    // Ephemeral fingerprint, never persisted or logged.
    private fun ModelConfig.probeTarget() = copy(name = "", memoryOutputTokens = 0)
    private fun stamp(config:ModelConfig,key:String)=MessageDigest.getInstance("SHA-256").digest(listOf(config.probeTarget().toString(),key).joinToString("\u0000").toByteArray()).joinToString(""){"%02x".format(it)}
    fun save(config:ModelConfig,key:String,purpose:ModelPurpose?=null,original:ModelConfig?=configs.find{it.id==config.id},originalRevision:String?=configRevisions[config.id],onSaved:()->Unit){
        saveConfig(config,key,purpose,false,original,originalRevision,onSaved)
    }
    internal fun saveChatFromCreation(config:ModelConfig,key:String,original:ModelConfig?=configs.find{it.id==config.id},originalRevision:String?=configRevisions[config.id],onSaved:()->Unit){
        saveConfig(config,key,ModelPurpose.CHAT,true,original,originalRevision,onSaved)
    }
    private fun saveConfig(config:ModelConfig,key:String,purpose:ModelPurpose?,protectOthers:Boolean,original:ModelConfig?,originalRevision:String?,onSaved:()->Unit){
        if(busy || config.id.isHostModel())return;saving=true;status=""
        viewModelScope.launch {
        var attempted=false;var configurationConfirmed=false;var selectionConfirmed=false
        try {
            var old:ModelConfig?=null;var oldKey="";var resolved="";var keep=false;var committedRevision=""
            val invalid=LocalStep.MODEL_CONFIG.observe(result = { result: String? -> if (result == null) LocalResult.COMPLETED else LocalResult.INVALID_INPUT }) { withContext(Dispatchers.IO){AppMutationGate.write<String?>{
                if(protectOthers&&ModelPurpose.entries.any{it!=ModelPurpose.CHAT&&vault.purposeId(it)==config.id})throw SharedCreationModel()
                old=vault.list().find{it.id==config.id}
                if(old!=original)throw StaleModelEditor()
                if(old!=null&&key.isNotEmpty()&&(originalRevision==null||vault.recordStamp(config.id)!=originalRevision))throw StaleModelEditor()
                oldKey=if(old!=null)vault.secret(config.id)else""
                resolved=ModelKeyDraft.resolve(old,config,key){oldKey}
                ConfigRules.validate(config,resolved)?.let{return@write it}
                val changedTarget=old?.probeTarget()!=config.probeTarget() || oldKey!=resolved
                // A failed status invalidation must leave the old key in force, never attach its test to a new key.
                if(changedTarget){statusStore.clearTestNow(config.id);tests=tests-config.id;connections=connections-config.id}
                attempted=true
                if(ModelKeyDraft.needsWrite(old,config,oldKey,resolved))vault.save(config,resolved)
                configurationConfirmed=true
                if(purpose!=null){HostModelSync.select(storage,vault,purpose,config.id);selectionConfirmed=true}
                keep=probeStamp==stamp(config,resolved)&&probe.phase in setOf(ProbePhase.REACHABLE,ProbePhase.FAILED)
                val pending=pendingTest
                // Configuration and its status commit together: neither an old save nor an old clear can run after a newer key.
                if(keep&&pending!=null)statusStore.saveTestNow(config.id,pending)
                committedRevision=vault.recordStamp(config.id)
                null
            }}
            }
            if(invalid!=null){message(invalid,true);return@launch}
            reload()
            AppMutationGate.read{connections=if(keep&&vault.recordStamp(config.id)==committedRevision)connections+(config.id to probe) else connections-config.id}
            val savedText=if(purpose!=null)"已保存并用于${purpose.title}；保存本身不发送请求。"else"已保存；保存本身不发送请求。"
            message(ProviderRequestMetadata.compatibilityNotice(config)?.let{"$savedText $it"}?:savedText)
            onSaved()
        }catch(e:CancellationException){throw e}
        catch(_:StaleModelEditor){
            try{reload();message("这份配置已在别处更改，请返回并重新打开后再保存。当前设置未被覆盖。",true)}
            catch(e:CancellationException){throw e}
            catch(_:Exception){configs=emptyList();configRevisions=emptyMap();message("当前设置未被覆盖，但无法重新读取配置。请退出配置页后重试。",true)}
        }
        catch(_:SharedCreationModel){runCatching{reload()};message("这份配置也用于其他用途，创建引导中不能修改。请新增全局聊天配置，或仅选择现有配置用于聊天。",true)}
        catch(_:Exception){message(when{
            configurationConfirmed&&(purpose==null||selectionConfirmed)->"配置${if(purpose!=null)"和${purpose.title}用途"else""}已保存，但状态刷新未完成。返回后可重新读取。"
            configurationConfirmed->"配置已保存，但用途写入未确认。请返回核对或重试。"
            attempted->"保存未完整完成，部分配置可能已写入。请返回核对或重试。"
            else->"保存失败，请重试。"
        },true)}finally{saving=false}}
    }
    fun testEmbedding(config:ModelConfig,key:String){
        if(busy)return;clearResult();val version=requestVersion;testing=true
        probe=ProbeDisplay(ProbePhase.TESTING,"正在测试向量接口…")
        requestJob=viewModelScope.launch {var resolved:RequestCredential?=null;try {
            val selected=credential(config,key);resolved=selected
            val invalid=ConfigRules.validate(config,selected.key)
            if(invalid!=null){probe=ProbeDisplay(ProbePhase.FAILED,invalid);return@launch}
            val vectors=withContext(Dispatchers.IO){embeddingClient.embed(ModelCredential(config,selected.key,"configuration-probe"),listOf("向量连接测试"),10000)}
            if(version!=requestVersion)return@launch
            val vector=vectors.single();require(MemoryVectorIndex.valid(vector))
            tested(config,key,selected,ProbeDisplay(ProbePhase.REACHABLE,"向量接口可用 · 返回 ${vector.size} 维（自动）"),true,"",version)
        }catch(e:CancellationException){throw e}
        catch(_:Exception){if(version==requestVersion){val failed=ProbeDisplay(ProbePhase.FAILED,"向量测试未通过，请核对模型 ID、权限和 /embeddings 兼容性。");val selected=resolved;if(selected==null)probe=failed else tested(config,key,selected,failed,false,"向量接口未通过",version)}}
        finally{if(version==requestVersion)testing=false}}
    }
    fun makeDefault(config:ModelConfig){if(busy)return;saving=true;viewModelScope.launch {try{withContext(Dispatchers.IO){HostModelSync.select(storage,vault,ModelPurpose.CHAT,config.id)};reload();message("已设为默认，没有发起网络请求。 ")}catch(_:Exception){message("默认配置保存失败。",true)}finally{saving=false}}}
    fun selectPurpose(purpose:ModelPurpose,id:String){
        if(busy)return;saving=true
        viewModelScope.launch{
            var attempted=false;var confirmed=false
            try{withContext(Dispatchers.IO){attempted=true;LocalStep.MODEL_PURPOSE.observe { HostModelSync.select(storage,vault,purpose,id) };confirmed=true};reload();message("用途选择已保存，没有发起网络请求。")}
            catch(e:CancellationException){throw e}
            catch(_:Exception){message(if(confirmed)"用途选择已保存，但状态刷新未完成。返回后可重新读取。"else if(attempted)"用途写入未确认，可能已改变。请返回核对或重试。"else"用途保存失败，请重试。",true)}finally{saving=false}
        }
    }
    fun delete(config:ModelConfig){if(config.id.isHostModel() || busy)return;clearResult();saving=true;viewModelScope.launch {try{withContext(Dispatchers.IO){vault.delete(config.id)};statusStore.clearAll(config.id);reload();connections=connections-config.id;message("配置和关联密钥已删除。")}catch(_:Exception){message("删除未完成，请重试。",true)}finally{saving=false}}}
    /**
     * 连接测试的探针：真让模型回一句话。
     * 输出上限直接用这份配置的「聊天生成上限 Token」，不再固定 256；
     * 给 16/32/64/128 时思考模型会把额度全花在思考上，正文是空的，测出来的"没通过"其实是假失败。
     * 额度是否合法由 request 在发送前按 ModelTokenPolicy 校验，非法时只显示校验文案，不调用服务。
     */
    internal fun probeRequest(config:ModelConfig):TextChatRequest {
        val token="OK-"+(0 until 4).joinToString(""){"%x".format((Math.random()*16).toInt())}
        return TextChatRequest(
            listOf(
                WireMessage("system","You are a connectivity probe. Reply with exactly one word."),
                WireMessage("user","Reply with the single word: $token")
            ),
            outputLimit=config.outputTokens,
            timeoutMillis=30000
        )
    }
    /** 测试失败时说人话：是密钥、网络，还是通了但没正文。 */
    internal fun probeFailure(result:TextChatResult.Failure):String=when(result.reason){
        ChatFailure.UNAUTHORIZED->"认证失败（401），请检查当前地域的 API Key。"
        ChatFailure.PAYMENT->"账户余额或付费状态有问题（402），充值或换一家再试。"
        ChatFailure.FORBIDDEN->"访问被拒绝（403），请检查这个模型有没有权限。"
        ChatFailure.RATE_LIMIT->"请求太频繁（429），过一会儿再测。"
        ChatFailure.TIMEOUT->"等太久没有回应，检查网络后再试。"
        ChatFailure.NETWORK->"连不上服务器，检查网络后再试。"
        ChatFailure.TLS->"HTTPS 证书校验失败，检查服务地址。"
        ChatFailure.REDIRECT->"服务返回重定向，已停下，没有把密钥发到新地址。"
        ChatFailure.SERVER->"服务暂时不可用，稍后再试。"
        ChatFailure.TRUNCATED->"回复被截断了，额度不够。把「聊天生成上限 Token」调大再试。"
        ChatFailure.GO_ENDPOINT_UNVERIFIED->ProviderRequestMetadata.GO_UNVERIFIED_NOTICE
        ChatFailure.INVALID_CONFIG->"配置不完整，请检查服务地址、模型和密钥。"
        ChatFailure.FORMAT->if(result.httpStatus in 200..299)"服务器通了，但模型没有回正文。可能是模型 ID 不对，或者思考把额度用光了。"
            else "服务没有返回可用的文本回复，检查模型 ID 和兼容接口地址。"
        else->result.reason.display
    }
    fun request(config:ModelConfig,key:String,fetch:Boolean){
        if(busy)return;clearResult();val version=requestVersion;testing=true;fetching=fetch
        if(!fetch)probe=ProbeDisplay(ProbePhase.TESTING,"正在让模型回一句话…") else message("正在获取模型…")
        requestJob=viewModelScope.launch {try {
            val resolved=credential(config,key)
            if(version!=requestVersion)return@launch
            val invalid=ConfigRules.urlError(config.baseUrl)?:if(resolved.key.isBlank())"请填写 API Key；地址或服务商改变后需重新输入。" else null
            if(invalid!=null){if(fetch)message(invalid,true) else probe=ProbeDisplay(ProbePhase.FAILED,invalid);return@launch}
            if(fetch){
                val result=diagnosticClient.request(config,resolved.key,true)
                if(version!=requestVersion)return@launch
                withContext(Dispatchers.IO){AppMutationGate.read{
                    if(version!=requestVersion)return@read
                    if(!current(config,resolved)){staleProbe();return@read}
                    when(result){
                        is ConfigResult.Success->{models=result.modelIds;message(if(models.isEmpty())"目录为空，可直接填写模型 ID。" else "已获取 ${models.size} 个条目，当前模型未更改。")}
                        is ConfigResult.Failure->message(result.error.messageText,true)
                    }
                }}
                return@launch
            }
            // 只有发送探针（!fetch）才依赖生成额度；目录获取保持原行为。
            val tokenError=ModelTokenPolicy.error(config)
            if(tokenError!=null){probe=ProbeDisplay(ProbePhase.FAILED,tokenError);return@launch}
            val request=probeRequest(config)
            val result=if(config.streamReplies)testClient.streamEvents(config,resolved.key,request){} else testClient.complete(config,resolved.key,request)
            if(version!=requestVersion)return@launch
            when(result){
                is TextChatResult.Success->{
                    val text=result.text.trim()
                    if(text.isEmpty()){val reason="服务器通了，但模型没有回正文。可能是模型 ID 不对，或者思考把额度用光了。";tested(config,key,resolved,ProbeDisplay(ProbePhase.FAILED,reason),false,reason,version)}
                    else tested(config,key,resolved,ProbeDisplay(ProbePhase.REACHABLE,"模型已回话：${text.take(24)}"),true,"",version)
                }
                is TextChatResult.Failure->{val reason=probeFailure(result);tested(config,key,resolved,ProbeDisplay(ProbePhase.FAILED,reason),false,reason,version)}
            }
        }catch(e:CancellationException){throw e}
        catch(_:Exception){if(version==requestVersion){if(fetch)message("获取失败，请重试。",true) else probe=ProbeDisplay(ProbePhase.FAILED,"连接失败，请重试。")}}
        finally{if(version==requestVersion){testing=false;fetching=false}}}
    }
}
