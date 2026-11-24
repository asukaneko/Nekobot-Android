package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Derived graph in an existing private preference row per character; no schema or old-data migration. */
internal class MemoryAssociations(private val repo:ProcessingRepository) {
    companion object {fun key(friendId:String)="memory-associations-v1:$friendId"}
    fun raw(db:SQLiteDatabase,friendId:String)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key(friendId))).use{if(it.moveToFirst())it.getString(0) else "[]"}
    fun read(db:SQLiteDatabase,friendId:String):List<MemoryRecall.Edge> = runCatching {
        val a=JSONArray(raw(db,friendId));(0 until a.length()).mapNotNull{i->runCatching{
            val j=a.getJSONObject(i);MemoryRecall.Edge(j.getString("character"),j.getString("source"),j.getInt("source_version"),j.getString("target"),j.getInt("target_version"),j.getString("kind"),j.getString("reason"),j.getString("quote"),j.getString("target_quote"),j.getString("batch"))
        }.getOrNull()}.filter{it.friendId==friendId}
    }.getOrDefault(emptyList())
    fun verified(db:SQLiteDatabase,friendId:String,values:List<StoredMemory>):List<MemoryRecall.Edge> {
        val valid=values.associateBy{it.id}
        return read(db,friendId).filter{e->val s=valid[e.sourceId];val t=valid[e.targetId]
            s!=null&&t!=null&&s.version==e.sourceVersion&&t.version==e.targetVersion&&e.targetQuote.isNotBlank()&&e.targetQuote in t.text&&
                e.quote.isNotBlank()&&s.evidence.any{e.quote in it.quote&&e.quote in it.snapshot}}
    }
    private fun json(e:MemoryRecall.Edge)=JSONObject().put("character",e.friendId).put("source",e.sourceId).put("source_version",e.sourceVersion)
        .put("target",e.targetId).put("target_version",e.targetVersion).put("kind",e.kind).put("reason",e.reason)
        .put("quote",e.quote).put("target_quote",e.targetQuote).put("batch",e.batchId)
    /** Only a complete, source-checked summary receipt can publish links. */
    fun commit(db:SQLiteDatabase,work:MemoryWork){
        val valid=repo.usableMemories(db,work.friendId).associateBy{it.id}
        val saved=verified(db,work.friendId,valid.values.toList()).toMutableList()
        var added=0
        db.rawQuery("SELECT json,result_id,result_version FROM memory_operations WHERE batch_id=? ORDER BY op_key",arrayOf(work.id)).use{c->while(c.moveToNext()&&added<MemoryRecall.LINKS_PER_BATCH){
            val source=valid[c.getString(1)]?:continue
            if(source.version!=c.getInt(2))continue
            val links=JSONObject(c.getString(0)).optJSONArray("links")?:continue
            for(i in 0 until minOf(links.length(),MemoryRecall.LINKS_PER_OPERATION)){
                if(added>=MemoryRecall.LINKS_PER_BATCH)break
                val edge=runCatching{
                    val j=links.getJSONObject(i);val target=valid[j.getString("target")]?:return@runCatching null
                    if(target.id==source.id||work.associationVersions[target.id]!=target.version||j.getInt("version")!=target.version)return@runCatching null
                    val kind=j.getString("kind");if(kind !in setOf("same_event","explicit_reference","explicit_cause"))return@runCatching null
                    val reason=j.getString("reason").trim();val quote=j.getString("quote");val oldQuote=j.getString("target_quote")
                    if(reason.length !in 4..240||quote.length !in 4..1000||oldQuote.length !in 4..800)return@runCatching null
                    if(source.evidence.none{it.source.characterId==work.friendId&&quote in it.quote&&quote in it.snapshot}||oldQuote !in target.text)return@runCatching null
                    if(kind=="explicit_cause"&&!Regex("因为|所以|由于|导致|因此|为了|because|therefore",RegexOption.IGNORE_CASE).containsMatchIn(quote))return@runCatching null
                    MemoryRecall.Edge(work.friendId,source.id,source.version,target.id,target.version,kind,reason,quote,oldQuote,work.id)
                }.getOrNull()?:continue
                if(saved.none{it.sourceId==edge.sourceId&&it.sourceVersion==edge.sourceVersion&&it.targetId==edge.targetId&&it.targetVersion==edge.targetVersion}){
                    saved+=edge;added++
                }
            }
        }}
        if(added==0)return
        val value=JSONArray().apply{saved.forEach{put(json(it))}}.toString()
        db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key(work.friendId));put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)
        Diagnostics.event("memory.associations.staged",mapOf("character" to work.friendId,"batch" to work.id,"added" to added,"edges" to saved.map{mapOf("source" to it.sourceId,"target" to it.targetId,"kind" to it.kind,"reason" to Diagnostics.raw(it.reason))}))
    }
}
