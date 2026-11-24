package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class ProcessingRepository(val store:Store) {
    internal fun memoryRules(db:SQLiteDatabase):Pair<MemoryRules,Int> = db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(MemoryRules.KEY)).use{c->if(c.moveToFirst()){val j=JSONObject(c.getString(0));MemoryRules.from(j) to j.getInt("version")}else MemoryRules() to 0}
    suspend fun memoryRules()=read{memoryRules(it)}
    suspend fun saveMemoryRules(value:MemoryRules){saveMemorySetting { value }}
    private fun saveRules(db:SQLiteDatabase,value:MemoryRules):LocalResult {
        require(value.valid());val old=memoryRules(db)
        return if(old.first!=value||old.second==0)LocalResult.insert(db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",MemoryRules.KEY);put("value",value.json().put("version",old.second+1).toString())},SQLiteDatabase.CONFLICT_REPLACE)) else LocalResult.COMPLETED
    }
    /** Separate settings pages update only the fields they own, in the same transaction. */
    internal suspend fun saveMemoryCounts(value:MemoryRules)=saveMemorySetting { db ->
        memoryRules(db).first.copy(recentRounds=value.recentRounds,summaryRounds=value.summaryRounds,relatedCount=value.relatedCount,reviewRounds=value.reviewRounds)
    }
    internal suspend fun saveMemoryTools(recall:Boolean,write:Boolean)=saveMemorySetting { db ->
        memoryRules(db).first.copy(proactiveRecall=recall,immediateWrite=write)
    }
    private suspend fun saveMemorySetting(value: (SQLiteDatabase) -> MemoryRules): MemoryRules =
        LocalStep.MEMORY_RULES.observe(result = { it.second }) {
            tx { db -> value(db).let { it to saveRules(db, it) } }
        }.first
    internal suspend fun <T> read(block:(SQLiteDatabase)->T):T=withContext(Dispatchers.IO){AppMutationGate.read{block(store.readableDatabase)}}
    internal suspend fun <T> tx(block:(SQLiteDatabase)->T):T=Diagnostics.span("db.transaction"){withContext(Dispatchers.IO){AppMutationGate.read{
        val db=store.writableDatabase;val start=System.nanoTime();var success=false;Diagnostics.event("db.transaction.begin");db.beginTransaction()
        try{val value=block(db);db.setTransactionSuccessful();success=true;value}
        catch(e:Exception){Diagnostics.failure("db.transaction.failed",e);throw e}finally{
            try{db.endTransaction();Diagnostics.event(if(success)"db.transaction.commit" else "db.transaction.rollback",elapsed=(System.nanoTime()-start)/1_000_000)}
            catch(e:Exception){Diagnostics.failure("db.transaction.end_failed",e);throw e}
        }
    }}}
    internal fun policy(db:SQLiteDatabase,friendId:String):Pair<ProcessingPolicy,Int> = db.rawQuery("SELECT json,version FROM processing_policies WHERE friend_id=?",arrayOf(friendId)).use{if(it.moveToFirst())ProcessingPolicy.from(JSONObject(it.getString(0))) to it.getInt(1) else ProcessingPolicy() to 0}
    suspend fun policy(friendId:String)=read{policy(it,friendId)}
    internal fun savePolicy(db:SQLiteDatabase,friendId:String,p:ProcessingPolicy){
        require(p.valid());val old=policy(db,friendId)
        db.insertWithOnConflict("processing_policies",null,ContentValues().apply{put("friend_id",friendId);put("json",p.json().toString());put("version",old.second+1)},SQLiteDatabase.CONFLICT_REPLACE)
    }
    suspend fun savePolicy(friendId:String,p:ProcessingPolicy)=tx{db->if(policy(db,friendId).first.coreGoal!=p.coreGoal)store.memorySummary.requireEditable(friendId);savePolicy(db,friendId,p)}
    internal fun friend(db:SQLiteDatabase,id:String)=db.rawQuery("SELECT id,name,bio,prompt,tone FROM friends WHERE id=?",arrayOf(id)).use{c->check(c.moveToFirst());Friend(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4))}
    internal fun characterStamp(db:SQLiteDatabase,id:String):String {
        val raw=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(id))).use{if(it.moveToFirst())it.getString(0) else ""}
        return stableDigest(friend(db,id).toString()+"\u0000"+raw)
    }
    internal fun sources(db:SQLiteDatabase,friendId:String,after:Long=0,boundary:Long=Long.MAX_VALUE):List<SourceDialogue> = db.rawQuery("SELECT t.id,t.user_message_id,t.assistant_message_id,t.source_revision,t.source_zone,u.body,u.created,a.body,a.created FROM chat_turns t JOIN messages u ON u.id=t.user_message_id LEFT JOIN messages a ON a.id=t.assistant_message_id LEFT JOIN message_state us ON us.message_id=u.id LEFT JOIN message_state ats ON ats.message_id=a.id WHERE t.friend_id=? AND t.state IN ('COMPLETED','SUPERSEDED') AND t.user_message_id>? AND t.user_message_id<=? AND COALESCE(us.deleted,0)=0 AND COALESCE(ats.deleted,0)=0 ORDER BY t.user_message_id",arrayOf(friendId,after.toString(),boundary.toString())).use{c->buildList{while(c.moveToNext())if(CompanionRepository.aiAllowed(db,friendId,c.getLong(1)))add(SourceDialogue(c.getString(0),SourceRef(friendId,c.getLong(1),c.getLong(3)),Message(c.getLong(1),friendId,c.getString(5),true,c.getLong(6),image=ChatImages.ref(db,c.getLong(1))),if(c.isNull(2))null else Message(c.getLong(2),friendId,c.getString(7),false,c.getLong(8)),c.getString(4),ImageMemory.observations(db,SourceRef(friendId,c.getLong(1),c.getLong(3)))))}}
    internal fun recentSources(db:SQLiteDatabase,friendId:String):List<SourceDialogue> = sources(db,friendId).filter{source->!db.rawQuery("SELECT 1 FROM memory_retired WHERE friend_id=? AND source_user_id=? AND source_version=?",arrayOf(friendId,source.source.turnId.toString(),source.source.version.toString())).use{it.moveToFirst()}}
    suspend fun recentSources(friendId:String)=read{recentSources(it,friendId)}
    suspend fun sources(friendId:String,after:Long=0,boundary:Long=Long.MAX_VALUE)=read{sources(it,friendId,after,boundary)}
    internal fun sourcesMatch(db:SQLiteDatabase,targets:List<SourceDialogue>):Boolean {
        if(targets.isEmpty())return false
        val current=sources(db,targets.first().source.characterId).associateBy{it.source.turnId}
        return targets.all{current[it.source.turnId]==it}
    }
    internal fun sourceValid(db:SQLiteDatabase,ref:SourceRef):Boolean=validSourceOnly(db,ref)
    internal fun evidenceValid(db:SQLiteDatabase,e:MemoryEvidence)=sourceValid(db,e.source)&&ImageMemory.valid(db,e)
    internal fun cursor(db:SQLiteDatabase,friendId:String)=db.rawQuery("SELECT cursor FROM memory_progress WHERE friend_id=?",arrayOf(friendId)).use{if(it.moveToFirst())it.getLong(0) else 0L}
    suspend fun cursor(friendId:String)=read{cursor(it,friendId)}
    data class TaskStatus(val state:String="IDLE",val attempt:Int=0,val error:String="")
    suspend fun memoryStatus(friendId:String)=read{db->db.rawQuery("SELECT state,attempt,error FROM memory_batches WHERE friend_id=? ORDER BY rowid DESC LIMIT 1",arrayOf(friendId)).use{if(it.moveToFirst())TaskStatus(it.getString(0),it.getInt(1),if(it.isNull(2))"" else it.getString(2)) else TaskStatus()}}
    internal fun memory(db:SQLiteDatabase,friendId:String,id:String):StoredMemory?=db.rawQuery("SELECT json FROM memory_current WHERE id=? AND friend_id=?",arrayOf(id,friendId)).use{if(it.moveToFirst())StoredMemory.from(JSONObject(it.getString(0))) else null}
    internal fun memories(db:SQLiteDatabase,friendId:String)=db.rawQuery("SELECT json FROM memory_current WHERE friend_id=? ORDER BY id",arrayOf(friendId)).use{c->buildList{while(c.moveToNext())StoredMemory.from(JSONObject(c.getString(0))).let{if(!it.userDeleted)add(it)}}}
    suspend fun memories(friendId:String)=read{memories(it,friendId)}
    internal fun usableMemories(db:SQLiteDatabase,friendId:String):List<StoredMemory> {
        val disputed=candidates(db,friendId).filter{it.valid}.mapNotNull{it.targetId}.toSet()
        return memories(db,friendId).filter{m->m.friendId==friendId&&m.id !in disputed&&m.valid&&m.life==MemoryLife.ACTIVE&&(m.expiresAt==null||m.expiresAt>System.currentTimeMillis())&&
            (m.manual||m.evidence.isNotEmpty())&&m.evidence.all{e->e.source.characterId==friendId&&e.quote.isNotBlank()&&e.quote in e.snapshot&&evidenceValid(db,e)}}
    }
    internal fun saveMemory(db:SQLiteDatabase,m:StoredMemory){
        val old=memory(db,m.friendId,m.id);rejectUnless(old?.userDeleted!=true,"SOURCE_CHANGED");require(m.version==(old?.version?:0)+1)
        val now=AppServices.companionClock()
        val recall=MemoryForgetting.ensure(db,old?:m,now)
        if(old!=null&&MemoryForgetting.importance(old)!=MemoryForgetting.importance(m))
            MemoryForgetting.put(db,m,MemoryForgetting.reclassify(recall,MemoryForgetting.importance(m),now))
        db.insertOrThrow("memory_history",null,ContentValues().apply{put("memory_id",m.id);put("version",m.version);put("json",m.json().toString())})
        db.insertWithOnConflict("memory_current",null,ContentValues().apply{put("id",m.id);put("friend_id",m.friendId);put("json",m.json().toString());put("version",m.version);put("valid",if(m.valid)1 else 0)},SQLiteDatabase.CONFLICT_REPLACE)
        Diagnostics.event("memory.write.staged",mapOf("character" to m.friendId,"memory" to m.id,"version" to m.version,"text" to Diagnostics.raw(m.text),"valid" to m.valid,"manual" to m.manual,"locked" to m.locked))
    }
    suspend fun manualSave(friendId:String,id:String?,text:String,category:MemoryCategory,core:Boolean,locked:Boolean,about:MemoryAbout?=null,tags:List<String> = emptyList(),dueDate:String?=null,life:MemoryLife?=null,birthday:String?=null)=tx{db->
        store.memorySummary.requireEditable(friendId)
        require(text.isNotBlank()&&text.length<=8000);val old=id?.let{memory(db,friendId,it)?:error("Unknown memory")}
        require(!category.modern||about!=null);require(old==null||old.category.modern==category.modern){"旧记录本轮不重分类"}
        val due=if(category.modern)MemoryFacets.dueDate(dueDate.orEmpty()) else null
        val annual=birthday?.trim()?.takeIf{it.isNotEmpty()}?.let{MemoryDates.monthDay(it)}
        val value=StoredMemory(id?:"manual-${UUID.randomUUID()}",friendId,category,text,life?:old?.life?:MemoryLife.ACTIVE,core,locked,true,(old?.version?:0)+1,about=about,tags=if(category.modern)MemoryFacets.tags(tags) else emptyList(),dueExpression=due,dueDate=due,dateType=if(birthday==null)old?.dateType else if(annual!=null)"birthday" else null,annualMonthDay=if(birthday==null)old?.annualMonthDay else annual,eventDate=if(birthday==null)old?.eventDate else null,dateExpression=if(birthday==null)old?.dateExpression else annual,createdAt=old?.createdAt?:AppServices.companionClock(),importance=old?.importance)
        saveMemory(db,value);value
    }
    suspend fun setLock(friendId:String,id:String,locked:Boolean)=tx{db->store.memorySummary.requireEditable(friendId);val old=memory(db,friendId,id)?:error("Unknown memory");saveMemory(db,old.copy(locked=locked,version=old.version+1))}
    suspend fun history(friendId:String,id:String)=read{db->
        check(memory(db,friendId,id)!=null)
        db.rawQuery("SELECT json FROM memory_history WHERE memory_id=? ORDER BY version DESC",arrayOf(id)).use{c->buildList{while(c.moveToNext())add(StoredMemory.from(JSONObject(c.getString(0))))}}
    }
    suspend fun restore(friendId:String,id:String,version:Int)=tx{db->
        store.memorySummary.requireEditable(friendId)
        val current=memory(db,friendId,id)?:error("Unknown memory");rejectUnless(!current.userDeleted,"SOURCE_CHANGED")
        val old=db.rawQuery("SELECT json FROM memory_history WHERE memory_id=? AND version=?",arrayOf(id,version.toString())).use{check(it.moveToFirst());StoredMemory.from(JSONObject(it.getString(0)))}
        rejectUnless(old.evidence.all{evidenceValid(db,it)},"SOURCE_CHANGED")
        val deniedHistory=db.rawQuery("SELECT json FROM memory_history WHERE memory_id=?",arrayOf(id)).use{c->var denied=false;while(c.moveToNext()){
            val prior=StoredMemory.from(JSONObject(c.getString(0)))
            if(prior.evidence.any{!CompanionRepository.aiAllowed(db,friendId,it.source.turnId)||(it.image!=null&&!CompanionRepository.aiAllowed(db,friendId,it.image.imageSource.turnId))})denied=true
        };denied}
        rejectUnless(!deniedHistory,"SOURCE_CHANGED")
        check(old.friendId==friendId);saveMemory(db,old.copy(version=current.version+1,manual=true,locked=current.locked,valid=true,evidence=emptyList(),createdAt=current.createdAt))
    }
    internal fun candidate(db:SQLiteDatabase,friendId:String,targetId:String?,proposed:StoredMemory,reason:String,id:String){
        check(proposed.friendId==friendId)
        val current=targetId?.let{memory(db,friendId,it)}
        val data=JSONObject().put("proposed",proposed.json()).put("reason",reason).put("sourceValid",proposed.valid&&proposed.evidence.all{it.source.characterId==friendId&&evidenceValid(db,it)}).put("targetVersion",current?.version?:JSONObject.NULL).toString()
        db.insertWithOnConflict("memory_candidates",null,ContentValues().apply{put("id",id);put("friend_id",friendId);if(targetId==null)putNull("target_id") else put("target_id",targetId);put("json",data);put("created",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_IGNORE)
        Diagnostics.event("memory.candidate.staged",mapOf("character" to friendId,"candidate" to id,"target" to targetId,"reason" to Diagnostics.raw(reason),"proposed" to Diagnostics.raw(proposed.text)))
    }
    data class Candidate(val id:String,val targetId:String?,val proposed:StoredMemory,val reason:String,val valid:Boolean=true,val targetVersion:Int?=null)
    internal fun candidates(db:SQLiteDatabase,friendId:String)=db.rawQuery("SELECT id,target_id,json FROM memory_candidates WHERE friend_id=? AND resolved=0 ORDER BY created,id",arrayOf(friendId)).use{c->buildList{while(c.moveToNext()){
        val j=JSONObject(c.getString(2));val p=StoredMemory.from(j.getJSONObject("proposed"));val target=if(c.isNull(1))null else c.getString(1)
        val valid=j.optBoolean("sourceValid",true)&&p.valid&&(p.expiresAt==null||p.expiresAt>System.currentTimeMillis())&&p.friendId==friendId&&p.evidence.all{it.source.characterId==friendId&&evidenceValid(db,it)}
        add(Candidate(c.getString(0),target,p,if(valid)j.getString("reason") else "来源已编辑、撤回或失效；仅供历史核对，不能注入或采用。",valid,if(j.isNull("targetVersion"))null else j.getInt("targetVersion")))
    }}}
    internal fun invalidateCandidate(db:SQLiteDatabase,friendId:String,id:String){
        val raw=db.rawQuery("SELECT json FROM memory_candidates WHERE id=? AND friend_id=?",arrayOf(id,friendId)).use{if(it.moveToFirst())it.getString(0) else return}
        db.update("memory_candidates",ContentValues().apply{put("json",JSONObject(raw).put("sourceValid",false).toString())},"id=? AND friend_id=?",arrayOf(id,friendId))
    }
    suspend fun candidates(friendId:String)=read{candidates(it,friendId)}
    internal fun referenceStamp(db:SQLiteDatabase,friendId:String):String {
        return stableDigest(memoryRules(db).toString()+policy(db,friendId).first.coreGoal+memories(db,friendId).joinToString{it.json().toString()}+candidates(db,friendId).joinToString{it.id+it.proposed.json()}+usableMemories(db,friendId).joinToString{it.id+"@"+it.version}+MemoryAssociations(this).raw(db,friendId)+sources(db,friendId).joinToString{it.json().toString()})
    }
    internal fun referenceCurrent(friendId:String,stamp:String)=referenceStamp(store.readableDatabase,friendId)==stamp
    data class References(val valid:List<PromptReference>,val pending:List<String>,val stamp:String,val at:Long=AppServices.companionClock())
    suspend fun references(friendId:String,input:String,userMessageId:Long,vectors:Map<String,Double> = emptyMap()):References=tx{db->
        // Expiry can advance while ranking references. Capture the guard first so a
        // reference that expires during assembly cannot inherit an already-expired stamp.
        val stamp=referenceStamp(db,friendId)
        val at=AppServices.companionClock();val memory=reference(db,friendId,input,userMessageId,vectors,at)
        References(memory.first,memory.second,stamp,at)
    }
    suspend fun resolveCandidate(friendId:String,id:String,accept:Boolean,expectedTargetVersion:Int?=null)=tx{db->
        store.memorySummary.requireEditable(friendId)
        Diagnostics.event("memory.candidate.resolve",mapOf("character" to friendId,"candidate" to id,"accept" to accept,"expected_version" to expectedTargetVersion))
        val value=candidates(db,friendId).firstOrNull{it.id==id}?:error("Unknown candidate")
        if(accept){
            rejectUnless(value.valid&&value.proposed.evidence.all{it.source.characterId==friendId&&evidenceValid(db,it)},"CANDIDATE_SOURCE_INVALID")
            val old=value.targetId?.let{memory(db,friendId,it)}
            rejectUnless(value.targetId==null||old!=null&&old.version==(expectedTargetVersion?:value.targetVersion),"SOURCE_CHANGED")
            val accepted=value.proposed.copy(id=old?.id?:"manual-${UUID.randomUUID()}",version=(old?.version?:0)+1,manual=true,locked=old?.locked?:true,valid=true)
            saveMemory(db,accepted)
        }
        db.update("memory_candidates",ContentValues().apply{put("resolved",1)},"id=? AND friend_id=?",arrayOf(id,friendId))
    }
    suspend fun reference(friendId:String,input:String,userMessageId:Long):Pair<List<PromptReference>,List<String>> = tx{db->reference(db,friendId,input,userMessageId)}
    private fun reference(db:SQLiteDatabase,friendId:String,input:String,userMessageId:Long,vectors:Map<String,Double> = emptyMap(),at:Long=AppServices.companionClock()):Pair<List<PromptReference>,List<String>> {
        val values=usableMemories(db,friendId)
        val clarity=values.associate{it.id to MemoryForgetting.ensure(db,it,at).clarity(at)}
        fun body(m:StoredMemory)=MemoryForgetting.text(m,clarity.getValue(m.id)){describe(m)}
        val core=MemoryForgetting.core(this,db,friendId,values,clarity).map{PromptReference("核心记忆 ${it.id}@${it.version}",body(it),true,it.id,sources=it.evidence.map{e->e.source}.toSet())}
        val rules=memoryRules(db).first
        val limit=if(rules.adaptiveContext)values.size.coerceAtLeast(1)else rules.relatedCount
        val graph=MemoryAssociations(this)
        val coreIds=core.map{it.memoryId}.toSet()
        val recalled=MemoryRecall.select(friendId,input,values.map{it.copy(core=it.id in coreIds)},graph.verified(db,friendId,values),limit,vectors).filter{clarity.getValue(it.memory.id)>=20&&body(it.memory).isNotBlank()}.sortedWith(compareByDescending<MemoryRecall.Hit>{it.score}.thenByDescending{clarity.getValue(it.memory.id)}).map{hit->
            val m=hit.memory
            PromptReference("${if(hit.via==null)"关键词记忆" else "关联记忆"} ${m.id}@${m.version}",body(m),false,m.id,true,m.evidence.map{it.source}.toSet(),hit.score)
        }
        val retainedIds=MemoryReferenceHistory.retain(db,friendId,recentSources(db,friendId).flatMap{it.messages.map{m->m.id}}.toSet())
        val already=(core+recalled).map{it.memoryId}.toSet()
        val retained=retainedIds.mapNotNull{id->values.firstOrNull{it.id==id&&id !in already&&clarity.getValue(id)>=20&&body(it).isNotBlank()}}
            .take((limit-recalled.size).coerceAtLeast(0))
            .map{PromptReference("近期原话引用的记忆 ${it.id}@${it.version}",body(it),false,it.id,true,it.evidence.map{e->e.source}.toSet(),0.2)}
        MemoryReferenceHistory.snapshot(db,friendId,userMessageId,recalled.map{it.memoryId})
        val choices=candidates(db,friendId).filter{it.valid}.filter{candidate->
            val seen=db.rawQuery("SELECT user_message_id FROM candidate_injections WHERE candidate_id=?",arrayOf(candidate.id)).use{c->buildList{while(c.moveToNext())add(c.getLong(0))}}
            seen.size<3||userMessageId in seen||keywordScore(input,candidate.proposed.text+" "+candidate.proposed.tags.joinToString(" "))>0
        }.take(3)
        var remaining=500
        val selected=choices.filter{val cost=ConservativeTokens.tokens(it.proposed.text)+32;if(cost<=remaining){remaining-=cost;true}else false}
        selected.forEach{c->db.insertWithOnConflict("candidate_injections",null,ContentValues().apply{put("candidate_id",c.id);put("user_message_id",userMessageId);put("friend_id",friendId)},SQLiteDatabase.CONFLICT_IGNORE)}
        return (core+recalled+retained) to selected.map{"候选 ${it.id}（${it.reason}）：${describe(it.proposed)}"}
    }
    internal fun describe(m:StoredMemory)=buildString{append(MemoryFacets.categoryLabel(m)+"：${MemoryDates.display(m.text,m.eventDate)}");m.annualMonthDay?.let{append("\n年度生日：$it（不推测出生年份）")};if(m.tags.isNotEmpty())append("\n主题："+m.tags.joinToString("、"));m.dueDate?.let{append("\n到期提醒：$it；到期不等于已完成，当前状态${m.life}。")} ;m.eventDate?.let{append("\n明确事件日期：$it")};if(m.eventDate==null&&m.dateExpression!=null)append("\n事件日期未明确，原表达：${m.dateExpression}");if(m.manual)append("\n用户手动确认。") else append("\n来源："+m.evidence.joinToString{ "${it.source.turnId}@${it.source.version}(${it.side})" })}
    companion object {
        internal fun validSourceOnly(db:SQLiteDatabase,ref:SourceRef):Boolean = CompanionRepository.aiAllowed(db,ref.characterId,ref.turnId)&&db.rawQuery("SELECT 1 FROM chat_turns t LEFT JOIN message_state us ON us.message_id=t.user_message_id LEFT JOIN message_state ats ON ats.message_id=t.assistant_message_id WHERE t.friend_id=? AND t.user_message_id=? AND t.source_revision=? AND t.state IN ('COMPLETED','SUPERSEDED') AND COALESCE(us.deleted,0)=0 AND COALESCE(ats.deleted,0)=0",arrayOf(ref.characterId,ref.turnId.toString(),ref.version.toString())).use{it.moveToFirst()}
        fun keywordScore(query:String,text:String):Int {
            val q=query.lowercase();val t=text.lowercase();val tokens=Regex("[a-z0-9]{2,}|[\\p{IsHan}]{2,}").findAll(q).flatMap{m->if(m.value.first().code>127)m.value.windowed(2).asSequence() else sequenceOf(m.value)}.toSet()
            return tokens.count{it in t}
        }
    }
}

