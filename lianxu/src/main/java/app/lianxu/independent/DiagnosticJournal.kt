package app.lianxu.independent

import com.google.gson.*
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Redaction is applied before enqueueing, including exceptions and response bodies. */
internal class DiagnosticRedactor {
    private val strictJson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val secrets=linkedSetOf<String>()
    private var saturated=false
    private var longestFirst = emptyList<String>()
    /** A bounded or interrupted stream can stop part way through a registered credential. */
    @Synchronized fun protectTail(value: String): String {
        if (saturated) return "[凭据脱敏容量已满]"
        var overlap = 0
        for (secret in longestFirst) {
            for (size in minOf(secret.length, value.length) downTo overlap + 1) {
                if (value.regionMatches(value.length-size, secret, 0, size)) {
                    overlap = size
                    break
                }
            }
        }
        return if (overlap == 0) value else value.dropLast(overlap) + "[已脱敏]"
    }
    @Synchronized fun register(secret:String){if(secret.isNotEmpty()&&!saturated&&secret !in secrets){
        val variants=listOf(secret,URLEncoder.encode(secret,"UTF-8"),JsonPrimitive(secret).toString().removeSurrounding("\""),Gson().toJson(secret).removeSurrounding("\"")).distinct()
        variants.forEach{secrets.remove(it);secrets.add(it)}
        if(secrets.size>2048){saturated=true;secrets.clear()}
        longestFirst=secrets.sortedByDescending { it.length }
    }}
    private val credentialName = """(?:(?:[a-z0-9]+[_-])*(?:(?:x[_-]?)?api[_-]?key|(?:access|refresh|auth|bearer|session|id)[_-]?token|(?:client[_-]?)?secret(?:[_-]?key)?|password|credentials?|private[_-]?key)|token|key)"""
    private val protectedName = "(?:$credentialName|reasoning(?:_content)?|thinking(?:_content)?|displayReasoning|(?:proxy-)?authorization|(?:set-)?cookies?)"
    private val textField = "(?:$protectedName|b64_json|(?:image_|audio_|video_)?base64)"
    private val sensitive = Regex(protectedName, RegexOption.IGNORE_CASE)
    private val pem=Regex("""(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)""")
    private val simpleName=Regex("[a-zA-Z_][a-zA-Z_0-9.-]*")
    private val encodedCredential=Regex("(?i)(?<![a-z0-9_])(?:$credentialName|(?:proxy-)?authorization|(?:set-)?cookies?)(?![a-z0-9_])")
    private val encodedStructure=Regex("(?i)%(?:7b|5b|22|25(?:7b|5b|22))")
    private val opaqueJson=Regex("""\beyJ[A-Za-z0-9_+/=-]{8,}""")
    private val binaryField=setOf("b64_json","base64","image_base64","video_base64","audio_base64")
    private val mediaTypeField = Regex("""(?i)"(?:mime_type|mimeType|format)"\s*:""")
    private val mediaPayloadField = Regex(""""data"\s*:""")
    private val mediaData=Regex("""(?i)data(?:\:|\\u003a)(?:video|audio|application)(?:\\*/|\\u002f)[^\s"'<>]*""")
    private val tokenPattern=Regex("""(?i)\b(?:sk|sess|ghp|gho|github_pat)[-_][A-Za-z0-9_-]+""")
    @Synchronized fun text(raw:String):String {
        if(saturated)return "[凭据脱敏容量已满]"
        var value=raw
        longestFirst.forEach{value=value.replace(it,"[已脱敏]")}
        value=opaqueJson.replace(value,"[编码载荷已省略]")
        if(simpleName.matches(value))return tokenPattern.replace(value,"[已脱敏]")
        if (encodedStructure.containsMatchIn(value)) {
            // Only decode into memory. Ambiguous fragments can contain credentials in encoded field names.
            return "[百分号编码片段已省略]"
        }
        // Structured JSON is decoded in clean(). A fragment inside an exception or prose may
        // start halfway through an escaped object, where individual field boundaries are ambiguous.
        if (value.contains("\\\"") && encodedCredential.containsMatchIn(value)) return "[含凭据的编码片段已省略]"
        if (mediaTypeField.containsMatchIn(value) && mediaPayloadField.containsMatchIn(value)) return "[媒体载荷已省略]"
        // Embedded or incomplete JSON can have a whole object/array as its credential value.
        if (Regex("""(?i)(?<![a-z0-9_])$textField["']?\s*[:=]\s*(?:\{|\[(?!已脱敏\]))""").containsMatchIn(value))
            return "[含凭据的结构片段已省略]"
        value=ChatImages.redact(value).replace(mediaData,"[媒体数据已省略]").replace(pem,"[私钥已脱敏]")
        value=value.replace(Regex("""(?is)<think(?:ing)?\b[^>]*>.*?(?:</think(?:ing)?\s*>|$)"""),"[模型思考内容已省略]")
        value=value.replace(Regex("""(?im)(authorization|proxy-authorization|cookie|set-cookie|x-api-key)\s*["']?\s*[:=]\s*[^\r\n]*"""),"$1: [已脱敏]")
        value=value.replace(Regex("""(?i)((?<![a-z0-9_])["']?$textField["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;&}]+)"""),"$1[已脱敏]")
        value=value.replace(Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9+/=._~-]+"""),"$1 [已脱敏]")
        value=value.replace(tokenPattern,"[已脱敏]")
        return value.replace(Regex("""(?i)https?://[^\s"<>]+""")){m->runCatching{val u=URI(m.value);require(u.host!=null);u.scheme+"://"+u.rawAuthority.substringAfterLast('@')+u.rawPath+if(u.rawQuery!=null||u.rawFragment!=null)"?[参数已脱敏]" else ""}.getOrDefault("[地址已脱敏]")}
    }
    fun clean(value:JsonElement,encodedDepth:Int=0):JsonElement=when{
        value.isJsonObject->JsonObject().apply{
            val obj=value.asJsonObject
            val inlineMedia=obj.has("mime_type") || obj.has("mimeType") || obj.has("format") && obj.has("data")
            obj.entrySet().forEach { (k,v) ->
                val safe=when {
                    sensitive.matches(k) -> JsonPrimitive("[已脱敏]")
                    k.lowercase() in binaryField || inlineMedia && k=="data" && v.isJsonPrimitive ->
                        JsonPrimitive("[媒体载荷已省略，共${if(v.isJsonPrimitive)v.asString.length else 0}字符]")
                    else -> clean(v,encodedDepth)
                }
                add(text(k),safe)
            }
        }
        value.isJsonArray->JsonArray().apply{value.asJsonArray.forEach{add(clean(it,encodedDepth))}}
        value.isJsonPrimitive&&value.asJsonPrimitive.isString->{
            val raw=value.asString
            val input=if(raw.trimStart().startsWith("%") && encodedStructure.containsMatchIn(raw) && encodedDepth<4)
                runCatching{java.net.URLDecoder.decode(raw,"UTF-8")}.getOrDefault(raw) else raw
            val nested=if(input.trimStart().firstOrNull() in listOf('{','['))runCatching{strictJson.fromJson(input,JsonElement::class.java)}.getOrNull() else null
            if(nested!=null && (nested.isJsonArray||nested.isJsonObject)){
                if(encodedDepth>=4)JsonPrimitive("[深层编码载荷已省略]") else JsonPrimitive(clean(nested,encodedDepth+1).toString())
            }else JsonPrimitive(text(raw))
        }
        else->value.deepCopy()
    }
}

internal class DiagnosticJournal(private val directory:File,private val version:String,private val segmentBytes:Int=2*1024*1024,private val segments:Int=5,private val keepMillis:Long=7L*24*60*60*1000,queueCapacity:Int=128,val redactor:DiagnosticRedactor=DiagnosticRedactor()):AutoCloseable {
    private data class Pending(val event:String,val trace:String,val data:JsonObject,val bytes:Long,val elapsed:Long?,val wall:Long=0,val mono:Long?=null,val barrier:CountDownLatch?=null)
    private companion object {
        /** Small fields that tie an event to its turn, attempt and request; never truncated away. */
        val CORRELATION_KEYS=setOf("request_number","request_role","voice_tool_provided","http_status","request_id","span","parent_span","purpose","turn","attempt","step","status",
            "batch","attempt_id","model_attempt","stage","cancel_source","terminal_status","cause_relation","after_terminal_decision","response_bytes","first_byte_ms","last_byte_ms","commit_state","transport_phase","client_write_completed_bytes","write_attempted_bytes","cancel_origin","default_network","activity_visibility","elapsed_realtime_ns","version_code","apk_sha256",
            "reasoning_status","reasoning_chars","reasoning_truncated","chunks","stream_chars","reasoning_chunks","saved","outcome",
            "call_id","name","failure","reason","error_type",
            "final_reasoning_status","final_reasoning_chars","final_reasoning_truncated")
    }
    private val truncated=AtomicLong()
    private val queue=ArrayBlockingQueue<Pending>(queueCapacity)
    private val pendingBytes=AtomicLong();val dropped=AtomicLong();private var reportedDrops=0L
    private val rotations=AtomicLong();private val expired=AtomicLong();private val started=System.currentTimeMillis()
    private var currentStartedAt = 0L
    @Volatile private var nextExpiry = Long.MAX_VALUE
    private val lock=Any();@Volatile private var closed=false
    private val gson=GsonBuilder().registerTypeAdapter(DiagnosticPayload::class.java,JsonSerializer<DiagnosticPayload>{field,_,context->context.serialize(field.current())}).registerTypeAdapter(ByteArray::class.java,JsonSerializer<ByteArray>{bytes,_,_->JsonPrimitive("[二进制数据已省略，共${bytes.size}字节]")}).create();private val worker:Thread
    init{require(segmentBytes>=1024&&segments in 2..10&&queueCapacity>0);worker=Thread({consume()},"lianxu-diagnostics").apply{isDaemon=true;start()}}
    fun record(event:String,trace:String=UUID.randomUUID().toString(),data:Map<String,Any?> = emptyMap(),elapsed:Long?=null,secrets:List<String> = emptyList(),wall:Long=System.currentTimeMillis(),mono:Long?=null){
        if(closed)return
        secrets.forEach(redactor::register)
        val original=gson.toJsonTree(data).asJsonObject
        if(original.toString().length>1024*1024){dropped.incrementAndGet();return}
        val safe=synchronized(redactor){secrets.forEach{redactor.register(it)};Triple(redactor.clean(original).asJsonObject,redactor.text(event),redactor.text(trace))}
        val json=safe.first;val size=json.toString().length.toLong()*2
        if(size>1024*1024||pendingBytes.addAndGet(size)>2*1024*1024){if(size<=1024*1024)pendingBytes.addAndGet(-size);dropped.incrementAndGet();return}
        if(!queue.offer(Pending(safe.second,safe.third,json,size,elapsed,wall,mono))){pendingBytes.addAndGet(-size);dropped.incrementAndGet()}
    }
    private fun consume(){
        directory.mkdirs()
        synchronized(lock){prune()}
        while(!closed||queue.isNotEmpty()){
            val untilExpiry = nextExpiry
            val p=try {
                if (untilExpiry == Long.MAX_VALUE) queue.take()
                else queue.poll((untilExpiry-System.currentTimeMillis()).coerceAtLeast(1),TimeUnit.MILLISECONDS)
            }catch(_:InterruptedException){continue}
            if (p == null) { synchronized(lock){prune()}; continue }
            if(p.barrier!=null){p.barrier.countDown();continue}
            try {
                val now=System.currentTimeMillis();val lost=dropped.get()
                if(lost>reportedDrops){write(JsonObject().apply{addProperty("at",now);addProperty("event","diagnostic.dropped");addProperty("count",lost-reportedDrops)});reportedDrops=lost}
                val clean=p.data;val raw=clean.toString();val excerptLimit=minOf(32768,segmentBytes/16)
                val row=JsonObject().apply{addProperty("redaction_version",2);addProperty("at",p.wall);p.mono?.let{addProperty("monotonic_ns",it)};addProperty("event",p.event);addProperty("trace",p.trace);addProperty("version",redactor.text(version));p.elapsed?.let{addProperty("elapsed_ms",it)}
                    // A long field (usually the reasoning text) must never take the turn/request correlation
                    // fields down with it: keep those in a structured part and excerpt only the payload.
                    if(raw.length>excerptLimit){truncated.incrementAndGet();addProperty("truncated",true);addProperty("original_chars",raw.length);addProperty("omitted_chars",raw.length-excerptLimit)
                        add("data",JsonObject().apply{
                            clean.entrySet().forEach{(k,v)->if(k in CORRELATION_KEYS&&v.toString().length<=512)add(k,v)}
                            BasicDiagnosticFields.project(p.event,clean)?.entrySet()?.forEach { (k,v) -> if(!has(k)) add(k,v) }
                        })
                        addProperty("data_excerpt",raw.take(excerptLimit))
                    }else add("data",clean)
                }
                write(row)
            } catch(_:Exception){dropped.incrementAndGet()}finally{pendingBytes.addAndGet(-p.bytes)}
        }
    }
    private fun prune(){
        val now=System.currentTimeMillis()
        ProcessEvidence.prune(File(directory,"process"),now)
        var next=Long.MAX_VALUE
        currentStartedAt=0
        directory.listFiles().orEmpty().forEach { file ->
            val index=Regex("""events-([0-9]+)\.jsonl""").matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val first=runCatching { file.bufferedReader().use { JsonParser.parseString(it.readLine()).asJsonObject["at"].asLong } }.getOrNull()
            val start=minOf(first ?: file.lastModified(), file.lastModified(), now)
            if(index>=segments || now-start>=keepMillis) {
                if(file.delete()) expired.incrementAndGet() else next=minOf(next,now+60000)
            } else {
                if(index==0) currentStartedAt=start
                next=minOf(next,start+keepMillis)
            }
        }
        nextExpiry=next
    }
    private fun write(row:JsonObject)=synchronized(lock){
        val now=System.currentTimeMillis()
        if(now>=nextExpiry) prune()
        val current=File(directory,"events-0.jsonl");val text=row.toString()+"\n"
        check(text.toByteArray(Charsets.UTF_8).size<=segmentBytes)
        if(current.length()+text.toByteArray().size>segmentBytes || currentStartedAt>0 && now-currentStartedAt>=keepMillis/segments){File(directory,"events-${segments-1}.jsonl").delete();for(i in segments-2 downTo 0){val f=File(directory,"events-$i.jsonl");if(f.exists())check(f.renameTo(File(directory,"events-${i+1}.jsonl")))};rotations.incrementAndGet();currentStartedAt=0}
        if(currentStartedAt==0L) { currentStartedAt=minOf(now,row["at"].asLong); nextExpiry=minOf(nextExpiry,currentStartedAt+keepMillis) }
        current.appendText(text,Charsets.UTF_8)
    }
    fun flush():Boolean {if(closed)return queue.isEmpty();val latch=CountDownLatch(1);return queue.offer(Pending("","",JsonObject(),0,null,barrier=latch),2,TimeUnit.SECONDS)&&latch.await(3,TimeUnit.SECONDS)}
    fun export(detailed:Boolean):ByteArray {
        val complete=flush()
        return synchronized(lock){prune();buildString{
            append(gson.toJson(mapOf("format" to "lianxu-diagnostics","version" to 1,"detailed" to detailed,"flush_complete" to complete,"dropped_events" to dropped.get(),"session_truncated_events" to truncated.get(),"drop_count_scope" to "current_process", "prior_process_unflushed_events" to "unknown", "session_started" to started,"session_rotations" to rotations.get(),"session_removed_segments" to expired.get(),"history_window_bounded" to true,"process_record_max_bytes" to 4096,"retention_days" to keepMillis/(24*60*60*1000),"max_disk_bytes" to segmentBytes.toLong()*segments,"notice" to "仅包含限额内仍保留的本机记录；轮转前历史可能已清理，截断或丢失均有标记。"))).append('\n')
            for(i in segments-1 downTo 0)File(directory,"events-$i.jsonl").takeIf{it.exists()}?.forEachLine(Charsets.UTF_8){line->
                val row=runCatching{JsonParser.parseString(line).asJsonObject}.getOrNull()
                if(row==null)append("{\"event\":\"diagnostic.corrupt_line\"}\n") else {
                    if(!detailed){
                        val data=row.remove("data");row.remove("data_excerpt")
                        if (data?.isJsonObject == true) BasicDiagnosticFields.project(row["event"]?.asString.orEmpty(),data.asJsonObject)?.let { row.add("data",it) }
                    }
                    if (row.has("data_excerpt") && !row.has("redaction_version")) {
                        row.addProperty("data_excerpt","[旧版截断片段已省略]")
                    }
                    // Existing segments can predate the current redactor or credential registration.
                    append(redactor.clean(row)).append('\n')
                }
            }
            ProcessEvidence.rows(File(directory,"process")).forEach {
                if (!detailed) it.remove("error_summary")
                append(redactor.clean(it)).append('\n')
            }
        }.toByteArray(Charsets.UTF_8)}
    }
    override fun close(){flush();closed=true;worker.interrupt();worker.join(4000)}
}
