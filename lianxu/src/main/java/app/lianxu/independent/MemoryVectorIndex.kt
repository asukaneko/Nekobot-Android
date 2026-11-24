package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import kotlin.math.sqrt

internal interface MemoryEmbeddingClient {suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray>}
/** Independent adapter using the project's HTTPS validation/auth rules; no upstream network client copied. */
internal class HttpsMemoryEmbeddingClient(private val factory:HttpsConnectionFactory=HttpsConnectionFactory{it.openConnection() as HttpsURLConnection}):MemoryEmbeddingClient {
    override suspend fun embed(credential:ModelCredential,texts:List<String>,timeout:Int):List<DoubleArray> = withContext(Diagnostics.networkContext("embedding")){
      val diagnosticRequest=Diagnostics.requestState()
      try{
       val result=withContext(Dispatchers.IO){
        Diagnostics.registerSecret(credential.key);val start=System.nanoTime()
        Diagnostics.event("embedding.http.request",mapOf("endpoint" to credential.config.baseUrl,"provider" to credential.config.providerId,"model" to credential.config.modelId,"input_count" to texts.size,"input_chars" to texts.map{it.length},"timeout_ms" to timeout),secrets=listOf(credential.key))
        require(ProviderCatalog.modelKind(credential.config)!=ProviderModelKind.OTHER_PROTOCOL){"embedding_protocol_not_adapted"}
        require(ConfigRules.validate(credential.config,credential.key)==null&&texts.size in 1..2048)
        diagnosticRequest?.phase("open_connection_object")
        val c=try{factory.open(URL(credential.config.baseUrl.trimEnd('/')+"/embeddings"))}catch(e:Exception){diagnosticRequest?.failure("embedding.http.open_failed",e);throw e}
        var failureStage="request"
        try{
            c.instanceFollowRedirects=false;c.connectTimeout=timeout;c.readTimeout=timeout;c.requestMethod="POST";c.doOutput=true
            val p=ProviderPresets.get(credential.config.providerId);c.setRequestProperty(p.authHeader,if(p.authHeader=="Authorization")"Bearer ${credential.key}" else credential.key)
            c.setRequestProperty("Content-Type","application/json; charset=utf-8")
            val bytes=JSONObject().put("model",credential.config.modelId).put("input",JSONArray(texts)).toString().toByteArray(Charsets.UTF_8)
            failureStage="request_size"
            require(bytes.size<=256*1024)
            failureStage="request"
            c.setFixedLengthStreamingMode(bytes.size)
            diagnosticRequest?.phase("acquire_output_stream",mapOf("planned_body_bytes" to bytes.size))
            try {
                c.outputStream.use { out ->
                    diagnosticRequest?.phase("write_body")
                    (diagnosticRequest?.observe(out) ?: out).write(bytes)
                    diagnosticRequest?.phase("close_output_stream")
                }
            } finally { bytes.fill(0) }
            diagnosticRequest?.stage("embedding.http.request_sent",mapOf("request_bytes" to bytes.size))
            diagnosticRequest?.phase("await_response_headers")
            val status=c.responseCode;Diagnostics.event("embedding.http.status",mapOf("status" to status),secrets=listOf(credential.key))
            diagnosticRequest?.phase("read_response_body",mapOf("http_status" to status))
            failureStage="status"
            require(status in 200..299){"embedding_response"}
            failureStage="read"
            val raw=(diagnosticRequest?.count(c.inputStream)?:c.inputStream).use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(out.size()<=16*1024*1024){val n=input.read(buffer,0,minOf(buffer.size,16*1024*1024+1-out.size()));if(n<0)break;out.write(buffer,0,n)};out.toByteArray()}
            failureStage="response_size";require(raw.size<=16*1024*1024)
            Diagnostics.event("embedding.http.response",mapOf("response_bytes" to raw.size),elapsed=(System.nanoTime()-start)/1_000_000,secrets=listOf(credential.key))
            failureStage="parse"
            val rows=JSONObject(String(raw,Charsets.UTF_8)).getJSONArray("data");require(rows.length()==texts.size)
            val indexed=(0 until rows.length()).map{rows.getJSONObject(it)}.associateBy{it.getInt("index")};require(indexed.keys==(texts.indices).toSet())
            texts.indices.map{i->val a=indexed.getValue(i).getJSONArray("embedding");DoubleArray(a.length()){a.getDouble(it)}.also{require(MemoryVectorIndex.valid(it))}}
                .also{require(it.map{v->v.size}.distinct().size==1);Diagnostics.event("embedding.http.parsed",mapOf("vectors" to it.size,"dimensions" to it.firstOrNull()?.size))}
        }catch(e:Exception){diagnosticRequest?.failure(when(failureStage){"parse"->"embedding.http.parse_failed";"status"->"embedding.http.status_rejected";"request_size"->"embedding.http.request_size_rejected";"response_size"->"embedding.http.response_size_rejected";else->"embedding.http.transport_failed"},e);throw e}finally{diagnosticRequest?.transferEnd(null);try{c.disconnect()}catch(e:Exception){diagnosticRequest?.failure("embedding.http.disconnect_failed",e)}}
       }
       if(diagnosticRequest?.finish("SUCCESS")!=false)Diagnostics.event("embedding.http.result",mapOf("status" to "success"))
       result
      }catch(e:CancellationException){diagnosticRequest?.cancel(e);if(diagnosticRequest?.finish("CANCELLED")!=false)Diagnostics.event("embedding.http.cancelled");throw e}
      catch(e:Exception){if(diagnosticRequest?.finish("EXCEPTION")!=false)Diagnostics.event("embedding.http.failed",mapOf("error_type" to e.javaClass.simpleName));throw e}
    }
}

/** Port of our CompanionHybridSearch cache policy; target storage/client/cosine are independently adapted. */
internal class MemoryVectorIndex(private val repo:ProcessingRepository,private val access:ModelAccess,private val client:MemoryEmbeddingClient=HttpsMemoryEmbeddingClient()) {
    private fun key(friend:String)="memory-vectors-v1:$friend"
    private fun fingerprint(m:StoredMemory)=stableDigest(m.id+"@"+m.version+":"+m.text+":"+m.tags.joinToString(" ")+":"+m.eventDate.orEmpty())
    private fun text(m:StoredMemory)=(m.text+" "+m.tags.joinToString(" ")+" "+m.eventDate.orEmpty()).take(8000)
    internal fun semanticText(m:StoredMemory)=text(m)
    private fun rows(db:SQLiteDatabase,friend:String):JSONObject=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key(friend))).use{if(it.moveToFirst())runCatching{JSONObject(it.getString(0))}.getOrDefault(JSONObject()) else JSONObject()}
    /** One document pool supplies both new/old and new/new comparisons. No query embedding. */
    internal suspend fun semanticVectors(friend:String,facts:List<SemanticFact>,credential:ModelCredential,taskCurrent:()->Boolean):Map<String,DoubleArray>{
        val cached=repo.read{db->val cache=rows(db,friend);facts.filter{it.operation==null}.mapNotNull{f->
            val row=cache.optJSONObject(f.memory.id)?:return@mapNotNull null
            if(row.optString("model")!=credential.stamp||row.optString("fingerprint")!=fingerprint(f.memory))return@mapNotNull null
            decode(row.optJSONArray("vector"))?.let{text(f.memory) to it}
        }.toMap().toMutableMap()}
        val missing=facts.map{ text(it.memory) }.distinct().filter{it !in cached}
        val groups=MemoryEmbeddingLimits.of(credential.config).batches(credential.config,missing)
        for(batch in groups){
            rejectUnless(taskCurrent()&&access.current(ModelPurpose.EMBEDDING,credential.stamp),"CANCELLED")
            val vectors=client.embed(credential,batch,5000)
            require(vectors.size==batch.size&&vectors.all(::valid)&&vectors.map{it.size}.distinct().size==1)
            batch.zip(vectors).forEach{(input,vector)->cached[input]=vector}
        }
        rejectUnless(taskCurrent()&&access.current(ModelPurpose.EMBEDDING,credential.stamp),"CANCELLED")
        require(cached.values.map{it.size}.distinct().size<=1)
        Diagnostics.event("memory.semantic.vectors",mapOf("character" to friend,"documents" to facts.size,"missing_texts" to missing.size,"embedding_calls" to groups.size))
        return facts.associate{it.ref to cached.getValue(text(it.memory))}
    }
    internal suspend fun rememberSemantic(friend:String,snapshots:List<StoredMemory>,byText:Map<String,DoubleArray>,credential:ModelCredential,taskCurrent:()->Boolean)=repo.tx{db->
        if(!taskCurrent()||!access.current(ModelPurpose.EMBEDDING,credential.stamp))return@tx
        val latest=repo.usableMemories(db,friend).associateBy{it.id};val cache=rows(db,friend)
        snapshots.forEach{m->val vector=byText[text(m)]
            if(vector!=null&&latest[m.id]?.let(::fingerprint)==fingerprint(m))cache.put(m.id,JSONObject().put("model",credential.stamp).put("fingerprint",fingerprint(m)).put("vector",JSONArray(vector.toList())))
        }
        cache.keys().asSequence().filter{it !in latest}.toList().forEach{cache.remove(it)}
        db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key(friend));put("value",cache.toString())},SQLiteDatabase.CONFLICT_REPLACE)
    }
    suspend fun index(friend:String){
        try{withTimeoutOrNull(30000){mutexes.getOrPut(friend){Mutex()}.withLock{
            val credentials=access.credential(ModelPurpose.EMBEDDING)?:run{Diagnostics.event("embedding.index.skipped",mapOf("character" to friend,"reason" to "not_configured"));return@withLock}
            val initial=repo.read{db->repo.usableMemories(db,friend) to rows(db,friend)}
            val missing=initial.first.filter{m->val row=initial.second.optJSONObject(m.id);row==null||row.optString("model")!=credentials.stamp||row.optString("fingerprint")!=fingerprint(m)||decode(row.optJSONArray("vector"))==null}.take(32)
            Diagnostics.event("embedding.index.batch",mapOf("character" to friend,"missing_count" to missing.size,"cached_count" to initial.second.length()))
            for(batch in missing.chunked(16)){
                val vectors=client.embed(credentials,batch.map(::text),5000);require(vectors.size==batch.size&&vectors.all(::valid)&&vectors.map{it.size}.distinct().size==1)
                repo.tx{db->if(access.current(ModelPurpose.EMBEDDING,credentials.stamp)&&db.rawQuery("SELECT 1 FROM friends WHERE id=?",arrayOf(friend)).use{it.moveToFirst()}){
                    val latest=repo.usableMemories(db,friend).associateBy{it.id};val cache=rows(db,friend)
                    batch.zip(vectors).forEach{(m,v)->if(latest[m.id]?.let(::fingerprint)==fingerprint(m))cache.put(m.id,JSONObject().put("model",credentials.stamp).put("fingerprint",fingerprint(m)).put("vector",JSONArray(v.toList())))}
                    val remove=cache.keys().asSequence().filter{it !in latest}.toList();remove.forEach{cache.remove(it)}
                    db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key(friend));put("value",cache.toString())},SQLiteDatabase.CONFLICT_REPLACE)
                }}
            }
        }}.also{if(it==null)Diagnostics.event("embedding.index.timeout",mapOf("character" to friend))}}catch(e:CancellationException){Diagnostics.event("embedding.index.cancelled",mapOf("character" to friend));throw e}catch(e:Exception){Diagnostics.failure("embedding.index.fallback",e,mapOf("character" to friend))/* Rebuildable cache. Raw dialogue and memory transactions are unaffected. */}
    }
    /** Foreground: cached documents only; one query embedding at most; no fallback provider. */
    suspend fun scores(friend:String,query:String,queryCache:QueryEmbeddingCache?=null):Map<String,Double> {
        if(query.isBlank())return emptyMap()
        return try{withTimeoutOrNull(1500){
            val credentials=access.credential(ModelPurpose.EMBEDDING)?:run{Diagnostics.event("embedding.query.fallback",mapOf("character" to friend,"reason" to "not_configured"));return@withTimeoutOrNull emptyMap()}
            val snapshot=repo.read{db->repo.usableMemories(db,friend) to rows(db,friend)}
            val cached=snapshot.first.mapNotNull{m->val row=snapshot.second.optJSONObject(m.id)?:return@mapNotNull null
                if(row.optString("model")!=credentials.stamp||row.optString("fingerprint")!=fingerprint(m))return@mapNotNull null
                decode(row.optJSONArray("vector"))?.let{m to it}}
            if(cached.isEmpty()){Diagnostics.event("embedding.query.fallback",mapOf("character" to friend,"reason" to "no_valid_cache"));return@withTimeoutOrNull emptyMap()}
            val text=query.take(8000)
            val q=queryCache?.vector(credentials,text,1000,client)?:client.embed(credentials,listOf(text),1000).single();require(valid(q))
            if(!access.current(ModelPurpose.EMBEDDING,credentials.stamp))return@withTimeoutOrNull emptyMap()
            cached.filter{it.second.size==q.size}.associate{(m,v)->"${m.id}@${m.version}" to cosine(q,v).coerceIn(0.0,1.0)}.also{Diagnostics.event("embedding.query.scored",mapOf("character" to friend,"score_count" to it.size))}
        }.also{if(it==null)Diagnostics.event("embedding.query.fallback",mapOf("character" to friend,"reason" to "timeout"))}?:emptyMap()}catch(e:CancellationException){Diagnostics.event("embedding.query.cancelled",mapOf("character" to friend));throw e}catch(e:Exception){Diagnostics.failure("embedding.query.fallback",e,mapOf("character" to friend));emptyMap()}
    }
    companion object {
        private val mutexes=ConcurrentHashMap<String,Mutex>()
        internal fun valid(v:DoubleArray)=v.isNotEmpty()&&v.size<=8192&&v.all{it.isFinite()}&&v.any{it!=0.0}&&v.sumOf{it*it}.let{it.isFinite()&&it>0}
        private fun decode(a:JSONArray?):DoubleArray?=runCatching{a?:return null;DoubleArray(a.length()){a.getDouble(it)}.takeIf(::valid)}.getOrNull()
        // Independent numerical implementation; LocalKnowledgeSearch is not a dependency.
        internal fun cosine(a:DoubleArray,b:DoubleArray):Double {
            require(a.size==b.size&&valid(a)&&valid(b));val norm=sqrt(a.sumOf{it*it})*sqrt(b.sumOf{it*it});require(norm.isFinite()&&norm>0)
            return a.indices.sumOf{a[it]*b[it]}/norm
        }
    }
}
