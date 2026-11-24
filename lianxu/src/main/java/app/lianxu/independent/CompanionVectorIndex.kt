package app.lianxu.independent

import android.content.ContentValues
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal data class CompanionExperience(val source:SourceRef,val text:String,val quote:String) {
    val fingerprint get()=stableDigest("${source.characterId}:${source.turnId}:${source.version}:$text:$quote")
}

/** Rebuildable source-aware cache, never an authority and never a substitute for source checks. */
internal class CompanionVectorIndex(private val core:CompanionRepository,private val access:ModelAccess,
    private val client:MemoryEmbeddingClient=HttpsMemoryEmbeddingClient()) {
    private val repo=ProcessingRepository(core.store)
    private val gson=Gson()
    private data class Cached(val fingerprint:String,val dimension:Int,val vector:DoubleArray)
    companion object {private val locks=ConcurrentHashMap<String,Mutex>()}
    private suspend fun cached(role:String,stamp:String)=repo.read{db->
        db.rawQuery("SELECT source_id,revision,fingerprint,dimension,vector FROM companion_vectors WHERE role=? AND config_stamp=?",arrayOf(role,stamp)).use{c->buildMap<SourceRef,Cached>{while(c.moveToNext()){
            val vector=runCatching{gson.fromJson(c.getString(4),DoubleArray::class.java)}.getOrNull()
            if(vector!=null&&MemoryVectorIndex.valid(vector)&&vector.size==c.getInt(3))put(SourceRef(role,c.getLong(0),c.getLong(1)),Cached(c.getString(2),c.getInt(3),vector))
        }}}
    }
    suspend fun index(role:String){
        try{locks.getOrPut(role){Mutex()}.withLock{
            val credential=access.credential(ModelPurpose.EMBEDDING)?:return@withLock
            val head=core.snapshot(role);val facts=core.experiences(role);val cache=cached(role,credential.stamp)
            // A bounded indexing batch is resumed by later chats/recovery. It is not a recall quota.
            val missing=facts.filter{cache[it.source]?.fingerprint!=it.fingerprint}.take(32)
            for(batch in missing.chunked(16)){
                currentCoroutineContext().ensureActive()
                if(!core.current(head)||!access.current(ModelPurpose.EMBEDDING,credential.stamp))return@withLock
                val vectors=client.embed(credential,batch.map{it.text},5000)
                currentCoroutineContext().ensureActive()
                require(vectors.size==batch.size&&vectors.all(MemoryVectorIndex::valid)&&vectors.map{it.size}.distinct().size==1)
                val dimension=vectors.first().size
                repo.tx{db->
                    if(!core.current(db,head)||!access.current(ModelPurpose.EMBEDDING,credential.stamp))return@tx
                    val live=core.experiences(db,role).associateBy{it.source}
                    db.execSQL("DELETE FROM companion_vectors WHERE role=? AND (config_stamp<>? OR dimension<>?)",arrayOf<Any>(role,credential.stamp,dimension))
                    batch.zip(vectors).forEach{(fact,vector)->if(live[fact.source]?.fingerprint==fact.fingerprint){
                        db.insertWithOnConflict("companion_vectors",null,ContentValues().apply{
                            put("role",role);put("source_id",fact.source.turnId);put("revision",fact.source.version);put("config_stamp",credential.stamp)
                            put("fingerprint",fact.fingerprint);put("dimension",dimension);put("vector",gson.toJson(vector))
                        },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                    }}
                    Diagnostics.event("companion.embedding.indexed",mapOf("character" to role,"source_ids" to batch.map{it.source.turnId},"dimension" to dimension,"count" to batch.size))
                }
            }
        }}catch(e:CancellationException){throw e}catch(e:Exception){Diagnostics.failure("companion.embedding.index_failed",e,mapOf("character" to role))}
    }
    suspend fun scores(role:String,query:String,queryCache:QueryEmbeddingCache?=null):Map<SourceRef,Double> {
        if(query.isBlank())return emptyMap()
        try{
            val credential=access.credential(ModelPurpose.EMBEDDING)?:run{Diagnostics.event("companion.embedding.fallback",mapOf("reason" to "not_configured","character" to role));return emptyMap()}
            val head=core.snapshot(role);val facts=core.experiences(role);val cache=cached(role,credential.stamp)
            val valid=facts.mapNotNull{f->cache[f.source]?.takeIf{it.fingerprint==f.fingerprint}?.let{f.source to it}}
            if(valid.isEmpty()){Diagnostics.event("companion.embedding.fallback",mapOf("reason" to "no_valid_cache","character" to role));return emptyMap()}
            val vector=queryCache?.vector(credential,query,5000,client)?:client.embed(credential,listOf(query),5000).single()
            currentCoroutineContext().ensureActive();require(MemoryVectorIndex.valid(vector))
            if(!core.current(head)||!access.current(ModelPurpose.EMBEDDING,credential.stamp))return emptyMap()
            val latest=cached(role,credential.stamp)
            if(valid.any{(source,row)->latest[source]?.let{it.fingerprint==row.fingerprint&&it.dimension==row.dimension}!=true})return emptyMap()
            if(valid.any{it.second.dimension!=vector.size}){
                repo.tx{db->if(access.current(ModelPurpose.EMBEDDING,credential.stamp))db.execSQL("DELETE FROM companion_vectors WHERE role=? AND config_stamp=?",arrayOf<Any>(role,credential.stamp))}
                Diagnostics.event("companion.embedding.fallback",mapOf("reason" to "dimension_changed","character" to role));return emptyMap()
            }
            return valid.associate{(source,row)->source to MemoryVectorIndex.cosine(vector,row.vector).coerceIn(0.0,1.0)}
                .also{Diagnostics.event("companion.embedding.scored",mapOf("character" to role,"source_ids" to it.keys.map{r->r.turnId},"dimension" to vector.size))}
        }catch(e:CancellationException){throw e}catch(e:Exception){Diagnostics.failure("companion.embedding.query_fallback",e,mapOf("character" to role));return emptyMap()}
    }
    suspend fun search(role:String,query:String,limit:Int,queryCache:QueryEmbeddingCache?=null):List<AgentRecallCandidate> {
        val scores=scores(role,query,queryCache)
        return core.experiences(role).map{it to maxOf(scores[it.source]?:0.0,MemoryRecall.relevance(query,it.text))}
            .filter{it.second>0}.sortedWith(compareByDescending<Pair<CompanionExperience,Double>>{it.second}.thenByDescending{it.first.source.turnId})
            .take(limit).map{(f,_)->AgentRecallCandidate("experience-${f.source.turnId}-${f.source.version}","episode","有来源的经历",f.text,sourceSessionId=role,startMessageId=f.source.turnId.toString(),current=true,vectorScore=(scores[f.source]?:0.0).toFloat())}
    }
}
