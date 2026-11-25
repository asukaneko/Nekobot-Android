package app.lianxu.independent

import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal const val EXPORT_DETAIL_PREF = "diagnostic-export-detailed"

internal data class DiagnosticTraceState(val trace:String,val span:String,val parent:String?,val purpose:String?,val request:NetworkDiagnosticState?=null,val links:Map<String,Any?> = emptyMap())
internal class DiagnosticTrace(private val value:DiagnosticTraceState):ThreadContextElement<DiagnosticTraceState?>,AbstractCoroutineContextElement(Key){
    companion object Key:CoroutineContext.Key<DiagnosticTrace>
    override fun updateThreadContext(context:CoroutineContext):DiagnosticTraceState?=Diagnostics.traceState.get().also{Diagnostics.traceState.set(value)}
    override fun restoreThreadContext(context:CoroutineContext,oldState:DiagnosticTraceState?){Diagnostics.traceState.set(oldState)}
}

/** A field prepared before an asynchronous mode change must still be authorised when serialized. */
internal class DiagnosticPayload(private val session: Any, private val value: Any) {
    fun current(): Any = if (Diagnostics.detailSession === session) value else "[详细诊断已关闭或重新开启]"
    override fun toString() = "[诊断载荷]"
}

/** Best-effort local diagnostics. Logging must never change a business result or call a model. */
internal object Diagnostics {
    internal val traceState=ThreadLocal<DiagnosticTraceState?>()
    @Volatile private var journal:DiagnosticJournal?=null
    private var identity=""
    /** A recording session is established at process startup, independent of export format. */
    @Volatile internal var detailSession:Any?=null
        private set
    internal var detailed:Boolean
        get()=detailSession!=null
        @Synchronized set(value){
            if(value==detailed)return
            detailSession=if(value)Any() else null
            modeChanged?.invoke()
        }
    @Volatile internal var modeChanged:(()->Unit)?=null
    @Volatile internal var environment:(()->Map<String,Any?>)?=null
    private val knownCredentials=DiagnosticRedactor()
    internal val collectText get() = detailed && requestState()?.enabled != false

    @Synchronized fun detail(session:Any?,name:String,data:()->Map<String,Any?> = {emptyMap()}){
        if(session!=null && detailSession===session)event(name,data())
    }
    /** Same per-event reasoning budget the journal applies to any data row. */
    internal const val REASONING_LIMIT=32768
    /**
     * One reasoning row shared by both the network response and the shown panel. The status is always
     * recorded so a turn that produced no thinking is visibly "absent", never silently blank, and a
     * request that failed or was cancelled after thinking streamed in is "partial" rather than absent.
     */
    fun reasoning(value:String,incomplete:Boolean=false,hitLimit:Boolean=false,receivedChars:Int=value.length):Map<String,Any?>{
        val received=maxOf(receivedChars,value.length)
        if(received==0)return mapOf("reasoning_status" to "absent","reasoning_chars" to 0)
        val status=if(incomplete)"partial" else "returned"
        if(!collectText)return mapOf("reasoning_status" to status,"reasoning_chars" to received,"reasoning_text" to "[详细诊断未开启]")
        val truncated=hitLimit||received>REASONING_LIMIT
        val prefix = value.take(REASONING_LIMIT)
        val safe = if (truncated || incomplete) (journal?.redactor ?: knownCredentials).protectTail(prefix) else prefix
        return mapOf("reasoning_status" to status,"reasoning_chars" to received,
            "reasoning_text" to raw(safe),"reasoning_truncated" to truncated)
    }
    @Synchronized fun startLocalRecording(context:android.content.Context, namespace:String) {
        val directory = File(context.filesDir, "diagnostics/$namespace")
        if (identity == directory.absolutePath && detailed) return
        if (identity != directory.absolutePath && detailed) detailed = false
        configure(directory, context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(), ProcessDiagnostics.sessionId)
        detailed = true
    }

    @Synchronized fun configure(directory:File,version:String,sessionId:String?=null){
        val key=directory.absolutePath
        if(journal!=null&&identity==key)return
        journal?.close();journal=DiagnosticJournal(directory,version,redactor=knownCredentials);identity=key
        event("diagnostic.session",mapOf("retention_days" to 7,"segments" to 5,"segment_bytes" to 2*1024*1024,"queue_capacity" to 128,"queue_bytes" to 2*1024*1024), trace=sessionId)
    }
    fun registerSecret(secret:String){(journal?.redactor?:knownCredentials).register(secret)}
    /** Chat words, memory text, prompts and replies reach the journal only with 详细诊断 on; otherwise just how long they were. */
    fun raw(value:String?):Any? {
        if (value == null) return null
        val session=detailSession
        return if(session!=null && collectText) DiagnosticPayload(session,value) else "[详细诊断未开启，共${value.length}字]"
    }
    /** Same rule for structured payloads (tool arguments and results), counted on their JSON text. */
    fun rawJson(value:Any?):Any? {
        if (value == null) return null
        val session=detailSession
        return if(session!=null && collectText) DiagnosticPayload(session,value) else "[详细诊断未开启，共${com.google.gson.Gson().toJson(value).length}字]"
    }
    fun protectTail(prefix:String)=(journal?.redactor ?: knownCredentials).protectTail(prefix)
    /** Same masking as the journal, for text shown to the user (raw provider errors). */
    fun redact(text:String,secrets:List<String> = emptyList()):String {
        val shared=journal?.redactor?:knownCredentials
        return synchronized(shared){secrets.forEach{shared.register(it)};shared.text(text)}
    }
    fun traceId():String=traceState.get()?.trace?:UUID.randomUUID().toString()
    fun event(name:String,data:Map<String,Any?> = emptyMap(),trace:String?=null,elapsed:Long?=null,secrets:List<String> = emptyList()){
        val state=traceState.get()
        val wall=System.currentTimeMillis()
        val mono=if(detailed)System.nanoTime() else null
        runCatching{journal?.record(name,trace?:state?.trace?:UUID.randomUUID().toString(),state?.links.orEmpty()+state?.request?.fields().orEmpty()+data+mapOf("span" to state?.span,"parent_span" to state?.parent,"purpose" to state?.purpose)+(if(detailed)environment?.invoke().orEmpty() else emptyMap()),elapsed,secrets,wall,mono)}
    }
    fun localStep(stage: LocalStep, result: LocalResult, start: Long, turn: ChatTurn?) {
        val links = traceState.get()?.links.orEmpty()
        val turnId = (turn?.id ?: links["turn"] as? String)?.takeIf(ChatVoiceDiagnostics::validTurn)
        val trace = turnId ?: traceState.get()?.trace?.takeIf(ChatVoiceDiagnostics::validTurn) ?: UUID.randomUUID().toString()
        journal?.record(stage.event, trace, buildMap {
            put("stage", stage.name)
            put("result", result.name)
            if (turnId != null) {
                put("turn", turnId)
                (turn?.attempt ?: links["attempt"] as? Int)?.takeIf { it > 0 }?.let { put("attempt", it) }
                (links["request_number"] as? Long)?.takeIf { it > 0 }?.let { put("request_number", it) }
            }
        }, elapsed = (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000)
    }
    // The delivery job and the UI need no inherited trace or network payload.
    fun voice(trace:VoiceTrace, stage:VoiceStage, status:VoiceStatus, reason:VoiceReason?, elapsed:Long?) {
        if (!ChatVoiceDiagnostics.validTurn(trace.turn) || trace.attempt < 1) return
        runCatching {
            journal?.record(ChatVoiceDiagnostics.EVENT, trace.turn, buildMap {
                (traceState.get()?.links?.get("request_number") as? Long)?.let { put("request_number",it) }
                put("turn",trace.turn); put("attempt",trace.attempt); put("provider",trace.provider.name);trace.partIndex?.let { put("part_index",it) }
                put("stage",stage.name); put("status",status.name); reason?.let { put("reason",it.name) }
            }, elapsed)
        }
    }
    fun failure(name:String,error:Throwable,data:Map<String,Any?> = emptyMap(),trace:String?=null,secrets:List<String> = emptyList()){
        synchronized(this) {
            val details = if (detailed) mapOf("error" to error.message, "stack" to error.stackTraceToString()) else emptyMap()
            event(name, data + ("error_type" to error.javaClass.name) + details, trace, secrets=secrets)
        }
    }
    fun context(purpose:String,trace:String=UUID.randomUUID().toString(),request:NetworkDiagnosticState?=traceState.get()?.request):DiagnosticTrace {
        val old=traceState.get();return DiagnosticTrace(DiagnosticTraceState(old?.trace?:trace,UUID.randomUUID().toString(),old?.span,purpose,request,old?.links.orEmpty()))
    }
    fun networkContext(purpose:String)=context(purpose,request=NetworkDiagnosticState(UUID.randomUUID().toString()))
    fun requestState()=traceState.get()?.request
    fun linkedContext(links:Map<String,Any?>):DiagnosticTrace {
        val old=traceState.get()?:DiagnosticTraceState(UUID.randomUUID().toString(),UUID.randomUUID().toString(),null,null)
        val safe=links.filter{(key,value)->key in setOf("turn","request_role","request_number","batch","attempt","attempt_id","stage","model_attempt")&&(value is Number||value is String&&value.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))}
        return DiagnosticTrace(old.copy(links=old.links+safe))
    }
    inline fun <T> onThread(state:DiagnosticTraceState?,block:()->T):T {
        val previous=traceState.get();traceState.set(state)
        try{return block()}finally{traceState.set(previous)}
    }
    suspend fun <T> span(name:String,data:Map<String,Any?> = emptyMap(),block:suspend()->T):T {
        val old=traceState.get();val state=DiagnosticTraceState(old?.trace?:UUID.randomUUID().toString(),UUID.randomUUID().toString(),old?.span,old?.purpose?:name.substringBefore('.'),old?.request,old?.links.orEmpty())
        return withContext(DiagnosticTrace(state)){
            val start=System.nanoTime();event("$name.start",data)
            try{block().also{event("$name.complete",data,elapsed=(System.nanoTime()-start)/1_000_000)}}
            catch(e:CancellationException){event("$name.cancelled",data,elapsed=(System.nanoTime()-start)/1_000_000);throw e}
            catch(e:Exception){failure("$name.failed",e,data);throw e}
        }
    }
    suspend fun export(detailed:Boolean):ByteArray=withContext(Dispatchers.IO){
        val active=journal?:error("诊断记录尚未初始化，请返回页面后重试")
        ProcessDiagnostics.refresh()
        event("diagnostic.export",mapOf("detailed" to detailed));active.export(detailed)
    }
    internal fun installForTest(value:DiagnosticJournal?){journal=value;identity="";detailed=false}
}

/** Covers mocked and HTTPS clients alike; stream chunks are aggregated, not individually enqueued. */
internal class LoggedTextChatClient(private val delegate:TextChatClient):TextChatClient {
    private fun request(config:ModelConfig,key:String,value:TextChatRequest,id:String,stream:Boolean){
        Diagnostics.registerSecret(key)
        Diagnostics.event("network.request",mapOf(
            "request_id" to id, "stream" to stream, "model" to config.modelId, "provider" to config.providerId,
            "endpoint" to config.baseUrl, "context_limit" to config.contextTokens, "output_limit" to value.outputLimit,
            "timeout_ms" to value.timeoutMillis, "messages" to value.messages.map { message ->
                val media = if (Diagnostics.collectText && (message.images.isNotEmpty() || message.videos.isNotEmpty())) mapOf(
                    "images" to message.images.size, "video_frames" to message.images.count { it.videoFrame },
                    "image_bytes" to message.images.sumOf { it.size }, "videos" to message.videos.size,
                    "video_bytes" to message.videos.sumOf { it.size },
                    "media_types" to (message.images.map { it.mime } + message.videos.map { it.mime }).distinct()
                ) else emptyMap()
                mapOf("role" to message.role, "content" to Diagnostics.raw(message.content), "tool_calls" to message.toolCalls.map {
                    mapOf("id" to it.id, "name" to it.name, "arguments" to Diagnostics.raw(it.arguments))
                }) + media
            }, "tools" to value.tools
        ),secrets=listOf(key))
    }
    private fun response(id:String,result:TextChatResult,start:Long,key:String,extra:Map<String,Any?> = emptyMap(),reasoning:Map<String,Any?>?=null){
        if(Diagnostics.requestState()?.finish(if(result is TextChatResult.Success)"SUCCESS"else(result as TextChatResult.Failure).reason.name)==false)return
        val value=when(result){
            is TextChatResult.Success->mapOf("status" to "success","text" to Diagnostics.raw(result.text),"tool_calls" to result.toolCalls.map{mapOf("id" to it.id,"name" to it.name,"arguments" to Diagnostics.raw(it.arguments))})
            is TextChatResult.Failure->mapOf("status" to "failure","reason" to result.reason.name,"http_status" to result.httpStatus)
        }
        Diagnostics.event("network.response",value+extra+(reasoning?:Diagnostics.reasoning((result as? TextChatResult.Success)?.displayReasoning.orEmpty()))+mapOf("request_id" to id),elapsed=(System.nanoTime()-start)/1_000_000,secrets=listOf(key))
    }
    private fun cancelled(error:CancellationException,id:String,data:Map<String,Any?> = emptyMap()){
        val state=Diagnostics.requestState();state?.cancel(error)
        if(state?.finish("CANCELLED")==false)return
        if(state!=null)state.stage("network.cancelled",data+mapOf("request_id" to id))else Diagnostics.event("network.cancelled",data+mapOf("request_id" to id))
    }
    private fun failed(error:Exception,id:String,data:Map<String,Any?> = emptyMap()){
        if(Diagnostics.requestState()?.finish("EXCEPTION")==false)return
        Diagnostics.event("network.failed",data+mapOf("request_id" to id,"error_type" to error.javaClass.name))
    }
    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult=withContext(Diagnostics.networkContext(Diagnostics.traceState.get()?.purpose?:"network")){
        val id=requireNotNull(Diagnostics.requestState()).id;val start=System.nanoTime();request(config,key,request,id,false)
        return@withContext try{delegate.complete(config,key,request).also{response(id,it,start,key)}}
        catch(e:CancellationException){cancelled(e,id);throw e}
        catch(e:Exception){failed(e,id);throw e}
    }
    override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult=withContext(Diagnostics.networkContext(Diagnostics.traceState.get()?.purpose?:"network")){
        val id=requireNotNull(Diagnostics.requestState()).id;val start=System.nanoTime();request(config,key,request,id,true)
        var chunks=0;var chars=0;var omitted=false;val text=StringBuilder()
        return@withContext try{
            delegate.stream(config,key,request){chunk->chunks++;chars+=chunk.length;if(Diagnostics.collectText&&!omitted){if(text.length+chunk.length<=32768)text.append(chunk)else{omitted=true;text.clear()}};onChunk(chunk)}
                .also{result->response(id,result,start,key,mapOf("chunks" to chunks,"stream_chars" to chars,"stream_text" to if(result is TextChatResult.Success&&!omitted)Diagnostics.raw(text.toString()) else "[流式正文已省略]","stream_text_omitted" to (omitted||result !is TextChatResult.Success),"stream_omitted_reason" to if(omitted)"超过正文限额" else if(result !is TextChatResult.Success)"响应未完整成功" else ""))}
        }catch(e:CancellationException){cancelled(e,id,mapOf("chunks" to chunks,"stream_chars" to chars));throw e}
        catch(e:Exception){failed(e,id,mapOf("chunks" to chunks,"stream_chars" to chars));throw e}
    }
    override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult=withContext(Diagnostics.networkContext(Diagnostics.traceState.get()?.purpose?:"network")){
        val id=requireNotNull(Diagnostics.requestState()).id;val start=System.nanoTime();request(config,key,request,id,true)
        var chunks=0;var chars=0;var reasoningChunks=0;var reasoningChars=0;var omitted=false;val text=StringBuilder()
        // Thinking that already streamed in is a fact even when the request later fails or is cancelled.
        val streamedReasoning=StringBuilder();var reasoningOmitted=false
        fun reasoningMap()=Diagnostics.reasoning(streamedReasoning.toString(),incomplete=true,hitLimit=reasoningOmitted,receivedChars=reasoningChars)
        fun counts()=mapOf("request_id" to id,"chunks" to chunks,"stream_chars" to chars,"reasoning_chunks" to reasoningChunks,"reasoning_chars" to reasoningChars)
        return@withContext try{
            delegate.streamEvents(config,key,request){event->
                when(event){
                    is TextStreamEvent.Content->{chunks++;chars+=event.text.length;if(Diagnostics.collectText&&!omitted){if(text.length+event.text.length<=32768)text.append(event.text)else{omitted=true;text.clear()}}}
                    is TextStreamEvent.Reasoning->{reasoningChunks++;reasoningChars+=event.text.length
                        // Keep a bounded prefix that fills the remaining budget instead of dropping a
                        // whole oversized chunk (which would make a long thought look "absent").
                        if(Diagnostics.collectText&&!reasoningOmitted){
                            val room=Diagnostics.REASONING_LIMIT-streamedReasoning.length
                            if(event.text.length<=room)streamedReasoning.append(event.text)
                            else{streamedReasoning.append(event.text,0,room);reasoningOmitted=true}
                        }}
                }
                onEvent(event)
            }.also{result->response(id,result,start,key,counts()+mapOf("stream_text" to if(result is TextChatResult.Success&&!omitted)Diagnostics.raw(text.toString()) else "[流式正文已省略]","stream_text_omitted" to (omitted||result !is TextChatResult.Success)),
                reasoning=if(result is TextChatResult.Success)Diagnostics.reasoning(result.displayReasoning) else reasoningMap())}
        }catch(e:CancellationException){cancelled(e,id,counts()+reasoningMap());throw e}
        // An observer exception may echo the supplied reasoning; retain type/counts, never its message.
        catch(e:Exception){failed(e,id,counts()+reasoningMap());throw e}
    }
}

internal class LoggedModelDirectoryClient(private val delegate:ModelDirectoryClient):ModelDirectoryClient {
    override suspend fun request(config:ModelConfig,key:String,fetchModels:Boolean):ConfigResult=withContext(Diagnostics.networkContext("model")){
        Diagnostics.registerSecret(key)
        Diagnostics.event("model.directory.start",mapOf("provider" to config.providerId,"endpoint" to config.baseUrl,"model" to config.modelId,"fetch_models" to fetchModels),secrets=listOf(key))
        try{delegate.request(config,key,fetchModels).also{result->if(Diagnostics.requestState()?.finish(if(result is ConfigResult.Success)"SUCCESS"else(result as ConfigResult.Failure).error.name)!=false)Diagnostics.event("model.directory.result",when(result){is ConfigResult.Success->mapOf("status" to "success","models" to result.modelIds);is ConfigResult.Failure->mapOf("status" to "failure","reason" to result.error.name,"http_status" to result.httpStatus)},secrets=listOf(key))}}
        catch(e:CancellationException){Diagnostics.requestState()?.cancel(e);if(Diagnostics.requestState()?.finish("CANCELLED")!=false)Diagnostics.event("model.directory.cancelled");throw e}
        catch(e:Exception){if(Diagnostics.requestState()?.finish("EXCEPTION")!=false)Diagnostics.event("model.directory.failed",mapOf("error_type" to e.javaClass.simpleName));throw e}
    }
}
