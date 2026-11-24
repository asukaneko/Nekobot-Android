package app.lianxu.independent

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FilterInputStream
import java.io.BufferedReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** Transport never retries or redirects; optional tool definitions are supplied by the caller. */
class HttpsTextChatClient(
    private val factory:HttpsConnectionFactory=HttpsConnectionFactory{it.openConnection() as HttpsURLConnection},
    private val timeoutMillis:Int?=null,
):TextChatClient {
    companion object {
        internal const val DETAIL_LIMIT=16384
        /** What the user sees for an HTTP error: the status line and the body exactly as sent (keys masked by the caller). */
        internal fun providerError(status:Int,message:String?,body:String,complete:Boolean)=buildString{
            append("HTTP $status");message?.takeIf{it.isNotBlank()}?.let{append(" ").append(it)}
            append('\n').append(body.ifBlank{"（服务商没有附带错误正文）"})
            if(!complete&&body.isNotBlank())append("\n（错误正文太长或没读完，只显示了前面部分）")
        }
        private val workers=Executors.newFixedThreadPool(4){r->Thread(r,"lianxu-text-http").apply{isDaemon=true}}}
    override suspend fun complete(config:ModelConfig,key:String,request:TextChatRequest):TextChatResult =
        withTimeoutOrNull((timeoutMillis?:request.timeoutMillis).toLong()){awaitReply(config,key,request,timeoutMillis?:request.timeoutMillis)}?:overallTimeout(timeoutMillis?:request.timeoutMillis)
    override suspend fun stream(config:ModelConfig,key:String,request:TextChatRequest,onChunk:suspend(String)->Unit):TextChatResult=
        streamReply(config,key,request,true){event->if(event is TextStreamEvent.Content)onChunk(event.text)}
    override suspend fun streamEvents(config:ModelConfig,key:String,request:TextChatRequest,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult=
        streamReply(config,key,request,false,onEvent)
    private suspend fun streamReply(config:ModelConfig,key:String,request:TextChatRequest,legacyContentResult:Boolean,onEvent:suspend(TextStreamEvent)->Unit):TextChatResult=coroutineScope{
        val chunks=Channel<TextStreamEvent>(Channel.RENDEZVOUS)
        val consumer=launch{for(chunk in chunks)onEvent(chunk)}
        val result=try{withTimeoutOrNull((timeoutMillis?:request.timeoutMillis).toLong()){
            awaitReply(config,key,request,timeoutMillis?:request.timeoutMillis,legacyContentResult){chunk->runBlocking{chunks.send(chunk)}}
        }?:overallTimeout(timeoutMillis?:request.timeoutMillis)}finally{chunks.close()}
        consumer.join();result
    }
    private suspend fun awaitReply(config:ModelConfig,key:String,request:TextChatRequest,budget:Int,legacyContentResult:Boolean=false,onEvent:((TextStreamEvent)->Unit)?=null):TextChatResult = suspendCancellableCoroutine {continuation->
        Diagnostics.registerSecret(key);val diagnosticTrace=Diagnostics.traceId()
        val capturedTrace=Diagnostics.traceState.get();val requestDiagnostics=Diagnostics.requestState()
        fun transportFailure(name:String,error:Throwable,timeout:NetworkTimeoutDiagnostic?=null,extra:Map<String,Any?> = emptyMap()){
            if(requestDiagnostics!=null)requestDiagnostics.failure(name,error,timeout,extra)else Diagnostics.event(name,mapOf("error_type" to error.javaClass.simpleName)+extra,trace=diagnosticTrace)
        }
        if(ConfigRules.validate(config,key)!=null||!ModelTokenPolicy.acceptsRequest(config,request.outputLimit)){continuation.resume(TextChatResult.Failure(ChatFailure.INVALID_CONFIG));return@suspendCancellableCoroutine}
        if(ProviderRequestMetadata.blockedGoPreset(config)){continuation.resume(TextChatResult.Failure(ChatFailure.GO_ENDPOINT_UNVERIFIED));return@suspendCancellableCoroutine}
        if(!ImageWire.fits(request)){continuation.resume(TextChatResult.Failure(if(ImageWire.hasVideo(request))ChatFailure.VIDEO_PREPARATION else ChatFailure.IMAGE_LIMIT));return@suspendCancellableCoroutine}
        val connection=AtomicReference<HttpsURLConnection?>(null)
        val deadline=System.nanoTime()+budget*1_000_000L
        val task=workers.submit {
          Diagnostics.onThread(capturedTrace){
            var c:HttpsURLConnection?=null
            val guard=SseStreamGuard()
            // Whether the provider answered at all: only "no answer" is reported as 网络连不上.
            var responded:Int?=null
            var requestSent=false
            val initialBgCount=DiagnosticEnvironment.backgroundCount()
            var sawBackground=DiagnosticEnvironment.isBackground()
            fun noteBackground(){if(DiagnosticEnvironment.isBackground()||DiagnosticEnvironment.backgroundCount()!=initialBgCount){sawBackground=true;requestDiagnostics?.markBackground()}}
            fun shown(text:String)=Diagnostics.redact(text,listOf(key)).take(DETAIL_LIMIT)
            fun failureMeta(stage:String="",inBg:Boolean=false,detailSource:String="local")=ChatFailureMeta(
                stage=stage,
                sawBackground=inBg,
                modelConfigId=config.id,
                modelId=Diagnostics.redact(config.modelId,listOf(key)),
                outputTokens=request.outputLimit,
                streamReplies=onEvent!=null,
                detailSource=detailSource,
            )
            var sawVoiceCall=false
            val result=try {
                if(!continuation.isActive)return@submit
                requestDiagnostics?.phase("prepare_body",mapOf("dns_tls_timing" to "not_exposed_by_urlconnection","server_received_bytes" to "unobservable","timeout_ms" to budget))
                val body=ImageWire.body(config,request,onEvent!=null)
                val messages=body.getJSONArray("messages")
                val requestBytes=ImageWire.size(body,request)
                val requestLimit=ImageWire.requestLimit(request)
                if(requestBytes>requestLimit)throw RequestTooLarge()
                requestDiagnostics?.phase("open_connection_object",mapOf("planned_body_bytes" to requestBytes))
                val active=factory.open(URL(config.baseUrl.trimEnd('/')+"/chat/completions"));c=active;connection.set(active)
                if(!continuation.isActive)return@submit
                val provider=ProviderPresets.get(config.providerId)
                active.instanceFollowRedirects=false;active.connectTimeout=minOf(budget,15000);active.readTimeout=budget;active.requestMethod="POST";active.doOutput=true
                active.setRequestProperty(provider.authHeader,if(provider.authHeader=="Authorization")"Bearer $key" else key)
                ProviderRequestMetadata.headers(config,request).forEach{(name,value)->active.setRequestProperty(name,value)}
                active.setRequestProperty("Content-Type","application/json; charset=utf-8");active.setRequestProperty("Accept",if(onEvent==null)"application/json" else "text/event-stream, application/json")
                // ImageWire's metadata tree contains media descriptions and sizes, never the binary.
                // Private provider continuation must stay out of both journal formats.
                val session = Diagnostics.detailSession
                if (session != null && Diagnostics.collectText) {
                    Diagnostics.detail(session,"http.request.body") {
                        val summary = JSONObject()
                        body.keys().forEach { field -> if (field != "messages") summary.put(field,body.get(field)) }
                        val visible = JSONArray()
                        for (i in 0 until messages.length()) {
                            val source = messages.getJSONObject(i)
                            val item = JSONObject()
                            source.keys().forEach { field -> if (field != "reasoning_content") item.put(field,source.get(field)) }
                            visible.put(item)
                        }
                        summary.put("messages",visible)
                        mapOf("method" to "POST","endpoint" to config.baseUrl.trimEnd('/')+"/chat/completions","body" to summary.toString())
                    }
                } else {
                    Diagnostics.event("http.request.body",mapOf("method" to "POST","planned_body_bytes" to requestBytes),trace=diagnosticTrace)
                }
                active.setFixedLengthStreamingMode(requestBytes)
                requestDiagnostics?.phase("acquire_output_stream",mapOf("connect_timeout_ms" to active.connectTimeout,"read_timeout_ms" to active.readTimeout))
                active.outputStream.use{out->
                    requestDiagnostics?.phase("write_body")
                    ImageWire.write(body,request,requestDiagnostics?.observe(out)?:out)
                    requestDiagnostics?.phase("close_output_stream")
                }
                requestSent=true;noteBackground()
                requestDiagnostics?.let { diagnostic ->
                    val sent = buildMap<String,Any> {
                        put("request_bytes",requestBytes); put("stream",onEvent!=null)
                        if (capturedTrace?.links?.get("request_role") == "ordinary_chat") {
                            val tools = body.optJSONArray("tools")
                            put("voice_tool_provided", (0 until (tools?.length() ?: 0)).any { i ->
                                tools?.optJSONObject(i)?.optJSONObject("function")?.optString("name") == "send_short_voice"
                            })
                        }
                    }
                    diagnostic.stage("http.request_sent",sent)
                }
                requestDiagnostics?.phase("await_response_headers")
                val status=active.responseCode;responded=status
                requestDiagnostics?.phase("read_response_body",mapOf("http_status" to status))
                Diagnostics.event("http.response",mapOf("status" to status,"content_type" to active.contentType,"content_encoding" to active.contentEncoding,"content_length" to active.contentLengthLong,"stream" to (onEvent!=null)),trace=diagnosticTrace,secrets=listOf(key))
                val error=if(status !in 200..299)diagnosticErrorBody(active,deadline,diagnosticTrace,key) else ErrorBody("",true)
                val errorBody=error.text
                noteBackground()
                if(status !in 200..299)TextChatResult.Failure(if(request.tools.isNotEmpty()&&status in setOf(400,422)&&explicitToolsUnsupported(errorBody))ChatFailure.UNSUPPORTED_TOOLS else when(status){401->ChatFailure.UNAUTHORIZED;402->ChatFailure.PAYMENT;403->ChatFailure.FORBIDDEN;413->if(ImageWire.hasVideo(request))ChatFailure.VIDEO_PROVIDER_LIMIT else ChatFailure.IMAGE_PROVIDER_LIMIT;429->ChatFailure.RATE_LIMIT;in 300..399->ChatFailure.REDIRECT;in 500..599->ChatFailure.SERVER;else->ChatFailure.REJECTED},status,
                    shown(providerError(status,active.responseMessage,errorBody,error.complete)),meta=failureMeta("http_status",sawBackground||requestDiagnostics?.experiencedBackground==true,detailSource="provider_http"))
                else if(onEvent!=null&&active.contentType.orEmpty().substringBefore(';').trim().equals("text/event-stream",true)){
                    (requestDiagnostics?.count(active.inputStream)?:active.inputStream).use{input->
                        val limited=object:FilterInputStream(input){
                            private val one=ByteArray(1)
                            override fun read():Int{val n=`in`.read(one,0,1);if(n>0){guard.read(one,0,1)?.let{throw StreamRejected(it)};return one[0].toInt() and 0xff};return -1}
                            override fun read(b:ByteArray,off:Int,len:Int):Int{val n=`in`.read(b,off,len);if(n>0)guard.read(b,off,n)?.let{throw StreamRejected(it)};return n}
                        }
                        val streamed=readSse(limited.bufferedReader(),onEvent,key,request.tools.map(::toolNameOf).toSet(),StructuredResponsePolicy.supportsReasoningContinuation(config),legacyContentResult,guard,observeVoice={sawVoiceCall=true},
                            beforeRead={
                                noteBackground()
                                if(!continuation.isActive)throw StreamRejected("interrupted",reason=ChatFailure.INTERRUPTED)
                                val remaining=((deadline-System.nanoTime())/1_000_000L).toInt();if(remaining<=0)throw SocketTimeoutException();active.readTimeout=minOf(budget,remaining)
                            },
                            onProgress={seen->Diagnostics.event("http.stream_progress",seen.snapshot(),trace=diagnosticTrace,secrets=listOf(key))},
                            outputLimit=request.outputLimit)
                        // The final content check rejects after a readable stream; name it instead of leaving a bare FORMAT.
                        if(streamed is TextChatResult.Failure)Diagnostics.event("http.stream_rejected",mapOf("reason" to streamed.reason.name,"check" to "content_check","detail" to "")+guard.snapshot(),trace=diagnosticTrace,secrets=listOf(key))
                        if(streamed is TextChatResult.Failure) {
                            noteBackground()
                            streamed.copy(httpStatus=status,meta=failureMeta("read_response_body",sawBackground))
                        } else streamed
                    }
                }else {
                    val response=(requestDiagnostics?.count(active.inputStream)?:active.inputStream).use {input->val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(output.size()<=2*1024*1024){
                            val remaining=((deadline-System.nanoTime())/1_000_000L).toInt();if(remaining<=0)throw SocketTimeoutException()
                            active.readTimeout=minOf(budget,remaining)
                            if(!continuation.isActive)return@submit
                            val n=input.read(buffer,0,minOf(buffer.size,2*1024*1024+1-output.size()));if(n<0)break;output.write(buffer,0,n)
                        };output.toByteArray()}
                    noteBackground()
                    if(response.size>2*1024*1024)TextChatResult.Failure(ChatFailure.FORMAT,status,
                        "HTTP $status\n响应正文超过本机读取上限（2 MiB）。",meta=failureMeta("read_response_body",sawBackground)) else {
                        val raw=String(response,Charsets.UTF_8)
                        JSONObject(raw).optJSONObject("usage")?.let{usage->
                            val counts=listOf("prompt_tokens","completion_tokens","total_tokens").map{usage.optLong(it,-1)}
                            if(counts.all{it>=0})Diagnostics.event("http.usage",mapOf("input_count" to counts[0],"output_count" to counts[1],"total_count" to counts[2]),trace=diagnosticTrace)
                        }
                        parse(raw,key,request.tools.map(::toolNameOf).toSet(),StructuredResponsePolicy.supportsReasoningContinuation(config),outputLimit=request.outputLimit){sawVoiceCall=true}.let{parsed->
                            if(parsed is TextChatResult.Failure&&parsed.meta==null)parsed.copy(httpStatus=status,meta=failureMeta("read_response_body",sawBackground||requestDiagnostics?.experiencedBackground==true)) else parsed
                        }.also{result->
                            if(result is TextChatResult.Success&&onEvent!=null){
                                if(result.displayReasoning.isNotEmpty())onEvent(TextStreamEvent.Reasoning(result.displayReasoning))
                                onEvent(TextStreamEvent.Content(result.text))
                            }
                        }
                    }
                }
            }catch(e:RequestTooLarge){TextChatResult.Failure(ChatFailure.IMAGE_LIMIT,detail="完整JSON请求体超过本机发送上限，本次未连接或发送；没有删图、降质或换服务")}
            catch(e:SocketTimeoutException){
                noteBackground()
                val diagnostic=requestDiagnostics?.timeout(System.nanoTime()>=deadline)
                transportFailure("http.timeout",e,diagnostic)
                val status=responded
                val inBg=sawBackground||requestDiagnostics?.experiencedBackground==true
                val stage=if(status!=null)"read_response_body" else if(requestSent)"await_response_headers" else "connect_or_write"
                if(status==null)TextChatResult.Failure(ChatFailure.TIMEOUT,detail=shown("等服务商响应超时，没有收到任何回应。\n${e.javaClass.simpleName}: ${e.message.orEmpty()}"),meta=failureMeta(stage,inBg))
                else TextChatResult.Failure(ChatFailure.TIMEOUT,status,shown("HTTP $status\n已经收到响应，但读取回复时超时。"),meta=failureMeta(stage,inBg))}
            catch(e:StreamRejected){
                noteBackground()
                val inBg=sawBackground||requestDiagnostics?.experiencedBackground==true
                Diagnostics.event("http.stream_rejected",mapOf("reason" to e.reason.name,"check" to e.check,"detail" to e.detail)+guard.snapshot(),trace=diagnosticTrace,secrets=listOf(key))
                TextChatResult.Failure(e.reason,responded,shown((responded?.let{"HTTP $it\n"}?:"")+"回复流没有通过检查：${e.check}"+(if(e.detail.isNotBlank())"\n${e.detail}" else "")),meta=failureMeta("read_response_body",inBg))}
            catch(e:SSLException){
                noteBackground()
                transportFailure("http.tls_failed",e)
                val stage=if(responded!=null)"read_response_body" else if(requestSent)"await_response_headers" else "connect_or_write"
                TextChatResult.Failure(ChatFailure.TLS,responded,shown("${e.javaClass.simpleName}: ${e.message.orEmpty()}"),meta=failureMeta(stage,sawBackground))
            }
            // A parser exception can quote the raw payload, including native reasoning: its message is never shown.
            catch(e:org.json.JSONException){Diagnostics.event("http.format_failed",mapOf("error_type" to e.javaClass.name),trace=diagnosticTrace);TextChatResult.Failure(ChatFailure.FORMAT,responded,shown((responded?.let{"HTTP $it\n"}?:"")+"服务返回的内容不是有效的 JSON。"),meta=failureMeta("read_response_body",sawBackground||requestDiagnostics?.experiencedBackground==true))}
            catch(e:Exception){
                noteBackground()
                val status=responded
                val inBg=sawBackground||requestDiagnostics?.experiencedBackground==true
                val stage=if(status!=null)"read_response_body" else if(requestSent)"await_response_headers" else "connect_or_write"
                val netBlocked=DiagnosticEnvironment.networkBlockedState()
                transportFailure("http.network_failed",e,extra=buildMap{
                    put("failure_stage",stage)
                    put("saw_background",inBg)
                    if(netBlocked!=null)put("network_blocked",netBlocked)
                })
                val raw="${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                val bgHint=if(inBg)"（请求期间应用切到过后台）" else ""
                val meta=failureMeta(stage,inBg)
                // Only a real "cannot reach the service" is 网络连不上; only provable socket/EOF aborts are CONNECTION_INTERRUPTED.
                if(status==null){
                    when {
                        unreachable(e)->TextChatResult.Failure(ChatFailure.NETWORK,detail=shown("没有收到服务商的任何回应。\n$raw"),meta=meta)
                        connectionInterrupted(e)->TextChatResult.Failure(ChatFailure.CONNECTION_INTERRUPTED,detail=shown(
                            "阶段：${if(requestSent)"请求已发出，等待服务商响应时连接中断" else "发送请求时连接中断"}$bgHint\n$raw"),meta=meta)
                        else->TextChatResult.Failure(ChatFailure.UNKNOWN,detail=shown(raw),meta=meta)
                    }
                } else TextChatResult.Failure(ChatFailure.RESPONSE_BROKEN,status,shown("HTTP $status\n阶段：已收到响应，读取回复中途连接中断$bgHint\n$raw"),meta=meta)}
            finally{requestDiagnostics?.transferEnd(responded);diagnosticDisconnect(connection.getAndSet(null));diagnosticDisconnect(c)}
            if(result is TextChatResult.Failure && sawVoiceCall) result.rejectedVoiceCall=true
            if(continuation.isActive)continuation.resume(result)
          }
        }
        continuation.invokeOnCancellation{cause->Diagnostics.onThread(capturedTrace){
            requestDiagnostics?.cancel(cause);requestDiagnostics?.stage("http.abort_requested")
            task.cancel(true);connection.getAndSet(null)?.let{open->Thread({Diagnostics.onThread(capturedTrace){diagnosticDisconnect(open)}},"lianxu-text-close").apply{isDaemon=true}.start()}
        }}
    }
    private fun diagnosticDisconnect(connection:HttpsURLConnection?){
        try{connection?.disconnect()}catch(e:Exception){
            Diagnostics.requestState()?.failure("http.disconnect_failed",e)?:Diagnostics.event("http.disconnect_failed",mapOf("error_type" to e.javaClass.simpleName))
        }
    }
    private class ErrorBody(val text:String,val complete:Boolean)
    private class RequestTooLarge:Exception()
    private fun overallTimeout(budget:Int):TextChatResult.Failure {
        Diagnostics.requestState()?.let{it.stop("total_timeout");it.stage("http.total_timeout",mapOf("timeout_ms" to budget))}
        return TextChatResult.Failure(ChatFailure.TIMEOUT,detail="等了 ${budget/1000} 秒仍没有完成回复，已停止等待。")
    }
    private fun diagnosticErrorBody(connection:HttpsURLConnection,deadline:Long,trace:String,key:String):ErrorBody {
        var complete=false
        val bytes=runCatching{connection.errorStream?.let{Diagnostics.requestState()?.count(it)?:it}?.use{input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(2048)
            while(out.size()<16384){val remaining=((deadline-System.nanoTime())/1_000_000L).toInt();if(remaining<=0)break;connection.readTimeout=minOf(2000,remaining);val n=input.read(buffer,0,minOf(buffer.size,16384-out.size()));if(n<0){complete=true;break};out.write(buffer,0,n)}
            out.toByteArray()
        }?:ByteArray(0)}.getOrElse{Diagnostics.requestState()?.failure("http.error_body.unavailable",it)?:Diagnostics.event("http.error_body.unavailable",mapOf("error_type" to it.javaClass.simpleName),trace=trace);ByteArray(0)}
        return ErrorBody(String(bytes,Charsets.UTF_8).also{Diagnostics.event("http.error_body",mapOf("body" to if(!complete)"[错误响应未完整读取或达到限额，正文已省略]" else Diagnostics.raw(it),"limited" to !complete),trace=trace,secrets=listOf(key))},complete)
    }
    private class StreamRejected(val check:String,val detail:String="",val reason:ChatFailure=streamCheckFailure(check)):Exception()
    /** Choice zero only. Native reasoning has its own event and is never promoted to dialogue. */
    private fun readSse(reader:BufferedReader,onEvent:(TextStreamEvent)->Unit,key:String,allowedTools:Set<String>,retainContinuation:Boolean,legacyContentResult:Boolean,guard:SseStreamGuard,observeVoice:()->Unit,beforeRead:()->Unit,onProgress:(SseStreamGuard)->Unit,outputLimit:Int?=null):TextChatResult {
        data class Partial(val id:StringBuilder=StringBuilder(),val name:StringBuilder=StringBuilder(),val args:StringBuilder=StringBuilder())
        val data=StringBuilder();val text=StringBuilder();val reasoning=StringBuilder();val protocolReasoning=StringBuilder();val calls=sortedMapOf<Int,Partial>()
        val events=TextStreamEventCoalescer(onEvent);var reasoningField:String?=null
        var finished=false;var done=false;var finish=""
        // Every rejection names the check that fired, so the log never has to guess "format error".
        fun reject(check:String,detail:String=""):Nothing=throw StreamRejected(check,detail)
        fun event(){
            if(data.isEmpty())return
            val raw=data.toString().removeSuffix("\n");data.clear()
            if(done)reject("after_done")
            if(raw=="[DONE]"){if(!finished)throw StreamRejected("done_before_finish",reason=ChatFailure.TRUNCATED);done=true;return}
            val obj=runCatching{JSONObject(raw)}.getOrElse{reject("bad_json")}
            if(obj.has("error"))reject("service_error")
            val choices=obj.optJSONArray("choices")?:reject("no_choices");if(choices.length()==0)return
            val choice=runCatching{choices.getJSONObject(0)}.getOrElse{reject("choice_shape")}
            if(choice.optInt("index",0)!=0)reject("choice_index")
            val delta=choice.optJSONObject("delta")?:JSONObject()
            val native=reasoningField?.let{field->(delta.opt(field) as? String)?.let{field to it}}?:if(reasoningField==null)nativeReasoning(delta)else null
            native?.let{(field,part)->if(part.isNotEmpty()){
                if(finished)reject("after_finish")
                reasoningField=field;reasoning.append(part);guard.parsed(text.length,reasoning.length)
                if(reasoning.length>256*1024)reject("reasoning_limit","chars=${reasoning.length}")
                if(reasoning.contains(key))reject("secret_leak")
                events.append(TextStreamEvent.Reasoning(part))
            }}
            if(retainContinuation&&allowedTools.isNotEmpty())(delta.opt("reasoning_content") as? String)?.let{
                protocolReasoning.append(it)
                if(protocolReasoning.length>256*1024)reject("reasoning_limit","chars=${protocolReasoning.length}")
                if(protocolReasoning.contains(key))reject("secret_leak")
            }
            if(!delta.isNull("function_call"))throw StreamRejected("tools_unavailable",reason=ChatFailure.TOOLS_UNAVAILABLE)
            val fragments=delta.optJSONArray("tool_calls")
            if(fragments!=null&&fragments.length()>0){
                if(allowedTools.isEmpty()){
                    if(hasVoiceName(fragments)) observeVoice()
                    throw StreamRejected("tools_unavailable",reason=ChatFailure.TOOLS_UNAVAILABLE)
                }
                if(finished)reject("after_finish")
                for(i in 0 until fragments.length()){
                    val part=runCatching{fragments.getJSONObject(i)}.getOrElse{reject("tool_shape")};val index=part.opt("index")
                    if(index !is Int||index !in 0..7)reject("tool_shape")
                    if(!part.isNull("type")&&part.getString("type")!="function")reject("tool_shape")
                    val pending=calls.getOrPut(index){Partial()}
                    if(!part.isNull("id")){val id=part.getString("id");if(pending.id.isNotEmpty()&&pending.id.toString()!=id)reject("tool_shape");if(pending.id.isEmpty())pending.id.append(id)}
                    part.optJSONObject("function")?.let{f->if(!f.isNull("name"))pending.name.append(f.getString("name"));if(!f.isNull("arguments"))pending.args.append(f.getString("arguments"))}
                    if(pending.id.length>160||pending.name.length>160||pending.args.length>16000)reject("tool_limit")
                }
            }
            val content=delta.opt("content")
            if(content is String&&content.isNotEmpty()){
                if(finished)reject("after_finish")
                text.append(content);guard.parsed(text.length,reasoning.length)
                if(text.length>100000)reject("content_limit","chars=${text.length}")
                // Typed consumers preview draft content; legacy tool streams still wait for validation.
                // Tool arguments are a different field and never enter either display channel.
                if(!legacyContentResult||allowedTools.isEmpty())events.append(TextStreamEvent.Content(content))
            }
            if(!choice.isNull("finish_reason")){
                if(calls.values.any { "send_short_voice".contentEquals(it.name) }) observeVoice()
                val reason=choice.getString("finish_reason")
                if(reason in setOf("length","max_tokens","max_output_tokens"))throw StreamRejected("finish_length",detail=outputLimit?.let{"模型达到了当前配置的输出上限（$it token），回复未生成完。"}?:"模型达到了输出上限，回复未生成完。",reason=ChatFailure.TRUNCATED)
                if(reason !in setOf("stop","end_turn","tool_calls"))reject("finish_value")
                if(finished)reject("after_finish")
                finish=reason;finished=true
            }
        }
        var firstLine=true
        while(true){beforeRead();if(guard.progressDue()){onProgress(guard);guard.progressed()}
            var line=reader.readLine()?:break
            // A UTF-8 BOM belongs to the body, not to the first `data:` field.
            if(firstLine){firstLine=false;if(line.isNotEmpty()&&line[0]=='\uFEFF')line=line.removePrefix("\uFEFF")}
            guard.line(line)?.let{reject(it)}
            if(line.isEmpty())event() else if(line.startsWith("data:")){data.append(line.substring(5).removePrefix(" ")).append('\n');if(data.length>256*1024)reject("data_limit","chars=${data.length}")}
        }
        event();events.flush();if(!done||!finished)throw StreamRejected("incomplete",reason=ChatFailure.TRUNCATED)
        if(calls.isEmpty()&&finish=="tool_calls")reject("tool_calls_missing")
        if(calls.isNotEmpty()&&(finish!="tool_calls"||calls.keys.toList()!=(0 until calls.size).toList()))reject("tool_calls_shape")
        val message=JSONObject().put("content",text.toString())
        if(calls.isNotEmpty())message.put("tool_calls",JSONArray().apply{calls.values.forEach{p->put(JSONObject().put("id",p.id.toString()).put("type","function").put("function",JSONObject().put("name",p.name.toString()).put("arguments",p.args.toString())))}})
        val final=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason",finish).put("message",message)))
        val result=parse(final.toString(),key,allowedTools,retainContinuation)
        if(result !is TextChatResult.Success)return result
        if(legacyContentResult&&allowedTools.isNotEmpty()&&result.toolCalls.isEmpty())onEvent(TextStreamEvent.Content(result.text))
        return result.copy(text=if(legacyContentResult&&allowedTools.isEmpty())"stream-complete" else result.text,
            displayReasoning=reasoning.toString(),continuation=protocolReasoning.takeIf{retainContinuation&&result.toolCalls.isNotEmpty()&&it.isNotEmpty()}?.let{ProviderContinuation(it.toString())})
    }
    internal fun explicitToolsUnsupported(raw:String):Boolean = runCatching {
        val error=JSONObject(raw).optJSONObject("error")?:return false
        val parameter=error.optString("param").lowercase()
        val code=error.optString("code").lowercase()
        val message=error.optString("message").lowercase()
        (parameter=="tools"&&code in setOf("unsupported_parameter")) ||
            (Regex("model.{0,100}(?:does not support|doesn’t support|cannot support) (?:the )?(?:tools?|function calling)\\b|(?:tools?|function calling) (?:is|are) not supported (?:by|for|with) (?:this |the )?model").containsMatchIn(message))
    }.getOrDefault(false)
    private fun hasVoiceName(calls:JSONArray) = (0 until minOf(calls.length(),8)).any {
        calls.optJSONObject(it)?.optJSONObject("function")?.optString("name")=="send_short_voice"
    }
    internal fun parse(raw:String,key:String,allowedTools:Set<String> = emptySet(),retainContinuation:Boolean=false,outputLimit:Int?=null,observeVoice:()->Unit={}):TextChatResult {
        val choice=JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?:return TextChatResult.Failure(ChatFailure.FORMAT)
        val message=choice.optJSONObject("message")?:return TextChatResult.Failure(ChatFailure.FORMAT)
        message.optJSONArray("tool_calls")?.let { if(hasVoiceName(it)) observeVoice() }
        val reason=choice.optString("finish_reason")
        if(reason in setOf("length","max_tokens","max_output_tokens"))
            return TextChatResult.Failure(ChatFailure.TRUNCATED,detail="模型返回 finish_reason=$reason，" +
                (outputLimit?.let{"达到了当前配置的输出上限（$it token）。"}?:"达到了输出上限。"))
        val displayReasoning=nativeReasoning(message)?.second.orEmpty()
        if(displayReasoning.length>256*1024||displayReasoning.isNotEmpty()&&displayReasoning.contains(key))return TextChatResult.Failure(ChatFailure.FORMAT)
        if(!message.isNull("function_call"))return TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE)
        val calls=message.optJSONArray("tool_calls")
        if(calls!=null&&calls.length()>0){
            if(allowedTools.isEmpty())return TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE)
            if(choice.optString("finish_reason")!="tool_calls"||calls.length()>8)return TextChatResult.Failure(ChatFailure.FORMAT)
            val parsed=(0 until calls.length()).map {i->
                val c=calls.getJSONObject(i);val f=c.getJSONObject("function");val id=c.getString("id");val name=f.getString("name");val args=f.getString("arguments")
                if(c.optString("type")!="function"||!id.matches(Regex("[A-Za-z0-9_-]{1,160}"))||name !in allowedTools||args.length>16000||args.contains(key))return TextChatResult.Failure(ChatFailure.FORMAT)
                JSONObject(args);ChatToolCall(id,name,args)
            }
            if(parsed.map{it.id}.distinct().size!=parsed.size)return TextChatResult.Failure(ChatFailure.FORMAT)
            val content=(message.opt("content") as? String).orEmpty().replace(Regex("(?is)<think>.*?</think>"),"").trim()
            if(content.contains(key)||content.contains("<think>",true)||content.length>100000)return TextChatResult.Failure(ChatFailure.FORMAT)
            val reasoning=if(retainContinuation)(message.opt("reasoning_content") as? String).orEmpty()else""
            if(reasoning.length>256*1024||reasoning.isNotEmpty()&&reasoning.contains(key))return TextChatResult.Failure(ChatFailure.FORMAT)
            return TextChatResult.Success(content,parsed,reasoning.takeIf{it.isNotEmpty()}?.let{ProviderContinuation(it)},displayReasoning)
        }
        if(reason !in setOf("stop","end_turn"))return TextChatResult.Failure(ChatFailure.FORMAT)
        // Some compatible providers embed thinking in content. Never show or retain it as dialogue.
        val text=message.opt("content") as? String ?:return TextChatResult.Failure(ChatFailure.FORMAT)
        val visible=text.replace(Regex("(?is)<think>.*?</think>"),"").trim()
        if(visible.contains("<think>",true)||visible.isBlank()||visible.length>100000||visible.contains(key))return TextChatResult.Failure(ChatFailure.FORMAT)
        return TextChatResult.Success(visible,displayReasoning=displayReasoning)
    }
    /** Field priority adapted from Xingban OpenAIChatProtocol/ThinkingSourceSettings; see provenance. */
    private fun nativeReasoning(value:JSONObject):Pair<String,String>? {
        val candidates=listOf("reasoning_content","thinking_content","reasoning","thinking").mapNotNull{field->
            (value.opt(field) as? String)?.let{field to it}
        }
        // Keep whitespace-only stream pieces intact. Select one alias, never concatenate aliases.
        return candidates.firstOrNull{it.second.isNotBlank()}?:candidates.firstOrNull{it.second.isNotEmpty()}
    }
}
