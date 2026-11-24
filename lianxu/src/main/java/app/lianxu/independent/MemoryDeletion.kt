package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

internal fun deletedText(text:String)=Normalizer.normalize(text,Normalizer.Form.NFKC).trim().replace('。','.').replace('、',',')
/** 删掉的记忆可以因为新聊天重新记下（新编号，不复活墓碑）；只拦旧来源补回：目标就是已删那条，或同文本且证据用的是删除前的旧轮次。 */
internal fun deletionBlocks(m:StoredMemory,friend:String,id:String,text:String,proof:List<MemoryEvidence>)=m.userDeleted&&m.friendId==friend&&(m.id==id||deletedText(m.text)==deletedText(text)&&deletionStaleProof(m,proof))
/** 证据为空、和已删条目共用任一 SourceRef，或全部早于删除时间（旧数据没有删除时间就只看 SourceRef），都算旧来源。 */
internal fun deletionStaleProof(m:StoredMemory,proof:List<MemoryEvidence>):Boolean {
    val old=m.evidence.map{it.source}.toSet()
    return proof.isEmpty()||proof.any{it.source in old}||m.deletedAt?.let{at->proof.all{it.sourceAt<=at}}==true
}
/** 删记忆时同一来源的经历也真删：原话、经历、关系那一笔都清掉，以后她读不到，感觉里也撤回。没东西可清返回 null。 */
internal fun deletionExperience(raw:String):String? {
    val gson=com.google.gson.Gson()
    val p=runCatching{gson.fromJson(raw,ExperienceProposal::class.java)}.getOrNull()?:return null
    if(p.memory.isBlank()&&p.quote.isBlank()&&p.relationInput==null)return null
    return gson.toJson(p.copy(quote="",memory="",emotion="unknown",valence=0,relationship="none",relationInput=null))
}
internal fun deletionVersion(m:StoredMemory,at:Long=AppServices.companionClock())=m.copy(version=m.version+1,valid=false,life=MemoryLife.RETRACTED,userDeleted=true,deletedAt=at)
internal fun deletionVectors(raw:String,id:String)=JSONObject(raw).apply{remove(id)}.toString()
internal fun deletionEdges(raw:String,id:String):String {
    val graph=JSONArray(raw);val kept=JSONArray()
    for(i in 0 until graph.length()){val edge=graph.getJSONObject(i);if(edge.optString("source")!=id&&edge.optString("target")!=id)kept.put(edge)}
    return kept.toString()
}
internal fun ProcessingRepository.deletedMatch(db:SQLiteDatabase,friend:String,id:String,text:String,proof:List<MemoryEvidence>):StoredMemory? =
    db.rawQuery("SELECT json FROM memory_current WHERE friend_id=?",arrayOf(friend)).use{c->
        var found:StoredMemory?=null
        while(c.moveToNext()){
            val m=StoredMemory.from(JSONObject(c.getString(0)))
            if(deletionBlocks(m,friend,id,text,proof))found=m
        };found
    }

suspend fun ProcessingRepository.deleteMemory(friend:String,id:String)=tx{db->
    store.memorySummary.requireEditable(friend)
    val old=memory(db,friend,id)?:throw ProcessingRejected("SOURCE_CHANGED")
    rejectUnless(!old.userDeleted,"SOURCE_CHANGED")
    saveMemory(db,deletionVersion(old))
    db.delete("memory_recall_state","friend_id=? AND memory_id=?",arrayOf(friend,id))
    val vectorKey="memory-vectors-v1:$friend"
    val raw=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(vectorKey)).use{if(it.moveToFirst())it.getString(0)else null}
    if(raw!=null){val value=deletionVectors(raw,id);db.update("preferences",ContentValues().apply{put("value",value.toString())},"key=?",arrayOf(vectorKey))}
    val kept=deletionEdges(MemoryAssociations(this).raw(db,friend),id)
    db.update("preferences",ContentValues().apply{put("value",kept.toString())},"key=?",arrayOf(MemoryAssociations.key(friend)))
    candidates(db,friend).filter{it.targetId==id||deletedText(it.proposed.text)==deletedText(old.text)}.forEach{invalidateCandidate(db,friend,it.id)}
    var cleared=0
    old.evidence.map{it.source}.filter{it.characterId==friend}.distinct().forEach{ref->
        val args=arrayOf(friend,ref.turnId.toString(),ref.version.toString())
        val raw=db.rawQuery("SELECT proposal FROM companion_sources WHERE role=? AND source_id=? AND revision=?",args).use{if(it.moveToFirst())it.getString(0)else null}?:return@forEach
        val updated=deletionExperience(raw)?:return@forEach
        db.update("companion_sources",ContentValues().apply{put("proposal",updated)},"role=? AND source_id=? AND revision=?",args);cleared++
    }
    if(cleared>0){
        // 当场撤回那几笔带来的感觉变化（只撤这几笔，不重算）。
        CompanionRepository.bump(db,friend);CompanionRepository(store).relationship(db,friend)
        Diagnostics.event("memory.delete.experience",mapOf("count" to cleared))
    }
}
