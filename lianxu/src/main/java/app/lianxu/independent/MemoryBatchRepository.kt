package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MemoryWork(val id:String,val friendId:String,val targets:List<SourceDialogue>,val policyVersion:Int,val characterStamp:String,val configStamp:String,val existingVersions:Map<String,Int> = emptyMap(),val runId:String=UUID.randomUUID().toString(),val rulesVersion:Int=0,val associationVersions:Map<String,Int> = emptyMap(),val resultVersions:Map<String,Int> = emptyMap(),val gistFill:List<JSONObject> = emptyList()) {
    fun json()=JSONObject().put("id",id).put("character",friendId).put("targets",JSONArray().apply{targets.forEach{put(it.json())}}).put("policyVersion",policyVersion).put("characterStamp",characterStamp).put("configStamp",configStamp).put("existingVersions",JSONObject().apply{existingVersions.toSortedMap().forEach{(id,version)->put(id,version)}}).put("runId",runId).put("rulesVersion",rulesVersion).put("associationVersions",JSONObject().apply{associationVersions.toSortedMap().forEach{(id,version)->put(id,version)}}).apply{if(resultVersions.isNotEmpty())put("resultVersions",JSONObject().apply{resultVersions.toSortedMap().forEach{(id,version)->put(id,version)}})}
    companion object {fun from(j:JSONObject):MemoryWork {val a=j.getJSONArray("targets");return MemoryWork(j.getString("id"),j.getString("character"),(0 until a.length()).map{SourceDialogue.from(a.getJSONObject(it))},j.getInt("policyVersion"),j.getString("characterStamp"),j.getString("configStamp"),j.optJSONObject("existingVersions")?.let{v->v.keys().asSequence().associateWith{v.getInt(it)}}?:emptyMap(),j.optString("runId","legacy"),j.optInt("rulesVersion",0),j.optJSONObject("associationVersions")?.let{v->v.keys().asSequence().associateWith{v.getInt(it)}}?:emptyMap(),j.optJSONObject("resultVersions")?.let{v->v.keys().asSequence().associateWith{v.getInt(it)}}?:emptyMap())}}
}
/** [detail] carries model-facing validation feedback; the message stays the stable code. */
class ProcessingRejected(val code:String,val detail:String=""):Exception(code)
internal fun rejectUnless(value:Boolean,code:String="FORMAT"){if(!value)throw ProcessingRejected(code)}
internal fun canonicalJson(value:Any?):String=when(value){
    null,JSONObject.NULL->"null"
    is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonicalJson(value.get(it))}
    is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonicalJson(value.get(it))}
    is String->JSONObject.quote(value)
    else->value.toString()
}

class MemoryBatchRepository(private val repo:ProcessingRepository) {
    data class Review(val batchId:String,val current:StoredMemory,val savedVersion:Int,val acknowledged:Boolean)
    private fun reviewDigest(db:SQLiteDatabase,work:MemoryWork,m:StoredMemory):String {
        val ops=db.rawQuery("SELECT op_key,digest,result_version FROM memory_operations WHERE batch_id=? AND result_id=? ORDER BY op_key",arrayOf(work.id,m.id)).use{c->buildString{while(c.moveToNext())append("${c.getString(0)}:${c.getString(1)}:${c.getInt(2)};")}}
        return stableDigest(m.json().toString()+work.targets.joinToString{it.json().toString()}+ops)
    }
    private fun acknowledged(db:SQLiteDatabase,work:MemoryWork,m:StoredMemory)=db.rawQuery("SELECT 1 FROM memory_batch_reviews WHERE batch_id=? AND memory_id=? AND version=? AND digest=? AND decision='KEEP_CURRENT'",arrayOf(work.id,m.id,m.version.toString(),reviewDigest(db,work,m))).use{it.moveToFirst()}
    suspend fun reviews(friendId:String):List<Review> = repo.read{db->
        val work=pendingWork(db,friendId)?:return@read emptyList()
        db.rawQuery("SELECT result_id,MAX(result_version) FROM memory_operations WHERE batch_id=? GROUP BY result_id",arrayOf(work.id)).use{c->buildList{while(c.moveToNext()){val m=repo.memory(db,friendId,c.getString(0))?:continue;if(!m.userDeleted&&m.version!=c.getInt(1))add(Review(work.id,m,c.getInt(1),acknowledged(db,work,m)))}}}
    }
    /** Explicit local decision only. A valid model receipt is still required to finish this batch. */
    suspend fun keepCurrent(friendId:String,batchId:String,memoryId:String,expectedVersion:Int)=repo.tx{db->
        repo.store.memorySummary.requireEditable(friendId)
        val work=pendingWork(db,friendId)?:throw ProcessingRejected("STALE_TASK")
        rejectUnless(work.id==batchId&&repo.sourcesMatch(db,work.targets),"SOURCE_CHANGED")
        val m=repo.memory(db,friendId,memoryId)?:throw ProcessingRejected("SOURCE_CHANGED")
        rejectUnless(m.version==expectedVersion&&m.valid&&m.evidence.all{repo.evidenceValid(db,it)},"SOURCE_CHANGED")
        rejectUnless(db.rawQuery("SELECT 1 FROM memory_operations WHERE batch_id=? AND result_id=?",arrayOf(batchId,memoryId)).use{it.moveToFirst()},"STALE_TASK")
        db.insertWithOnConflict("memory_batch_reviews",null,ContentValues().apply{put("batch_id",batchId);put("memory_id",memoryId);put("version",m.version);put("digest",reviewDigest(db,work,m));put("decision","KEEP_CURRENT");put("created",System.currentTimeMillis())},SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun pendingWork(db:SQLiteDatabase,friendId:String):MemoryWork?=db.rawQuery("SELECT json FROM memory_batches WHERE friend_id=? AND state NOT IN('COMMITTED','ARCHIVED','INVALIDATED') ORDER BY rowid",arrayOf(friendId)).use{c->var found:MemoryWork?=null;while(found==null&&c.moveToNext()){val w=MemoryWork.from(JSONObject(c.getString(0)));if(repo.sourcesMatch(db,w.targets))found=w};found}
    suspend fun prepare(friendId:String,targets:List<SourceDialogue>,configStamp:String,existingIds:Set<String>?=null,expectedRulesVersion:Int?=null,associationIds:Set<String> = emptySet(),expectedExistingVersions:Map<String,Int>?=null,taskCurrent:()->Boolean={true}):MemoryWork=repo.tx{db->
        rejectUnless(taskCurrent(),"CANCELLED")
        rejectUnless(targets.isNotEmpty()&&targets.all{it.source.characterId==friendId})
        rejectUnless(repo.sourcesMatch(db,targets),"SOURCE_CHANGED")
        val id="batch-"+stableDigest(friendId+targets.joinToString{ "${it.source.turnId}@${it.source.version}" }).take(40)
        val policy=repo.policy(db,friendId)
        val rulesVersion=repo.memoryRules(db).second;rejectUnless(expectedRulesVersion==null||rulesVersion==expectedRulesVersion,"SOURCE_CHANGED")
        val ownIds=db.rawQuery("SELECT DISTINCT result_id FROM memory_operations WHERE batch_id=?",arrayOf(id)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        val versions=repo.memories(db,friendId).filter{it.valid}.associate{it.id to it.version}
        val selected=versions.filterKeys{existingIds==null||it in existingIds}
        rejectUnless(expectedExistingVersions==null||selected==expectedExistingVersions,"SOURCE_CHANGED")
        // Only selected keys define M numbers. Omitted partial results are validation-only.
        val work=MemoryWork(id,friendId,targets,policy.second,repo.characterStamp(db,friendId),configStamp,selected,rulesVersion=rulesVersion,associationVersions=repo.usableMemories(db,friendId).filter{it.id in associationIds}.take(MemoryRecall.RELATION_CANDIDATE_LIMIT).associate{it.id to it.version},resultVersions=versions.filterKeys{it in ownIds})
        val old=db.rawQuery("SELECT state FROM memory_batches WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0) else null}
        rejectUnless(old!="RUNNING","BUSY")
        rejectUnless(old!="COMMITTED","ALREADY_COMMITTED")
        db.insertWithOnConflict("memory_batches",null,ContentValues().apply{put("id",id);put("friend_id",friendId);put("json",work.json().toString());put("state","PENDING");put("attempt",0);put("config_stamp",configStamp)},SQLiteDatabase.CONFLICT_IGNORE)
        db.update("memory_batches",ContentValues().apply{put("json",work.json().toString());put("config_stamp",configStamp);put("state","PENDING");putNull("error")},"id=?",arrayOf(id))
        work
    }
    suspend fun beginAttempt(work:MemoryWork,session:String,taskCurrent:()->Boolean={true})=repo.tx{db->
        rejectUnless(taskCurrent(),"CANCELLED")
        rejectUnless(repo.memoryRules(db).second==work.rulesVersion,"SOURCE_CHANGED")
        val count=db.rawQuery("SELECT attempt FROM memory_batches WHERE id=? AND json=? AND state NOT IN('COMMITTED','ARCHIVED')",arrayOf(work.id,work.json().toString())).use{rejectUnless(it.moveToFirst(),"STALE_TASK");it.getInt(0)+1}
        rejectUnless(count<=3,"MANUAL_RETRY")
        db.update("memory_batches",ContentValues().apply{put("state","RUNNING");put("attempt",count);put("owner_session",session);putNull("error")},"id=?",arrayOf(work.id));count
    }
    private fun validate(db:SQLiteDatabase,work:MemoryWork,configCurrent:()->Boolean){
        rejectUnless(repo.memoryRules(db).second==work.rulesVersion,"SOURCE_CHANGED")
        rejectUnless(repo.policy(db,work.friendId).second==work.policyVersion&&repo.characterStamp(db,work.friendId)==work.characterStamp,"SOURCE_CHANGED")
        rejectUnless(repo.sourcesMatch(db,work.targets)&&configCurrent(),"SOURCE_CHANGED")
        val header=db.rawQuery("SELECT json,state FROM memory_batches WHERE id=? AND friend_id=?",arrayOf(work.id,work.friendId)).use{rejectUnless(it.moveToFirst(),"STALE_TASK");it.getString(0) to it.getString(1)}
        rejectUnless(header.first==work.json().toString()&&header.second=="RUNNING","STALE_TASK")
    }
    private fun validateMemoryContext(db:SQLiteDatabase,work:MemoryWork,allowReviewed:Boolean=false){
        val own=db.rawQuery("SELECT result_id,MAX(result_version) FROM memory_operations WHERE batch_id=? GROUP BY result_id",arrayOf(work.id)).use{c->buildMap{while(c.moveToNext())if(c.getInt(1)>0)put(c.getString(0),c.getInt(1))}}
        val captured=work.resultVersions+work.existingVersions
        for(id in captured.keys+own.keys){
            val m=repo.memory(db,work.friendId,id)
            val current=m!=null&&m.valid&&m.evidence.all{repo.evidenceValid(db,it)}&&MemoryEvidenceRules.contextCurrent(captured[id],own[id],m.version)
            if(!current){
                if(allowReviewed&&id in own&&m!=null&&m.valid&&m.evidence.all{repo.evidenceValid(db,it)}&&acknowledged(db,work,m))continue
                throw ProcessingRejected(if(id in own)"BATCH_REVIEW_REQUIRED" else "SOURCE_CHANGED")
            }
        }
    }
    suspend fun currentContext(work:MemoryWork,configCurrent:()->Boolean={true}):List<StoredMemory> = repo.read{db->
        validate(db,work,configCurrent);validateMemoryContext(db,work)
        work.existingVersions.keys.map{repo.memory(db,work.friendId,it)?:throw ProcessingRejected("SOURCE_CHANGED")}
    }
    private fun localTarget(db:SQLiteDatabase,work:MemoryWork,local:String):String {
        val saved=db.rawQuery("SELECT json,result_id FROM memory_operations WHERE batch_id=? ORDER BY op_key",arrayOf(work.id)).use{c->
            var result:String?=null;while(result==null&&c.moveToNext()){val op=JSONObject(c.getString(0));if(op.optString("op")=="ADD"&&op.optString("target")==local)result=c.getString(1)};result
        }
        return saved?:"memory-"+stableDigest(work.friendId+work.id+local).take(40)
    }
    suspend fun applied(work:MemoryWork):List<String> = repo.read{db->db.rawQuery("SELECT op_key FROM memory_operations WHERE batch_id=? ORDER BY op_key",arrayOf(work.id)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}}
    suspend fun appliedOperations(work:MemoryWork):List<JSONObject> = repo.read{db->db.rawQuery("SELECT json FROM memory_operations WHERE batch_id=? ORDER BY op_key",arrayOf(work.id)).use{c->buildList{while(c.moveToNext())add(JSONObject(c.getString(0)))}}}
    suspend fun pending(friendId:String):MemoryWork?=repo.read{pendingWork(it,friendId)}
    suspend fun recoverAbandoned(session:String)=repo.tx{db->db.update("memory_batches",ContentValues().apply{put("state","FAILED");put("error","INTERRUPTED")},"state='RUNNING' AND (owner_session IS NULL OR owner_session<>?)",arrayOf(session))}
    private fun evidence(work:MemoryWork,op:JSONObject):List<MemoryEvidence> {
        val a=op.optJSONArray("evidence")?:throw ProcessingRejected("EVIDENCE")
        rejectUnless(a.length() in 1..20,"EVIDENCE")
        return (0 until a.length()).map{i->val j=a.getJSONObject(i)
            val turn=j.getLong("turn");val version=j.getLong("version");val side=j.getString("side");val quote=j.getString("quote")
            val source=work.targets.find{it.source.turnId==turn&&it.source.version==version}?:throw ProcessingRejected("EVIDENCE")
            rejectUnless(side in setOf("user","assistant","image")&&quote.isNotBlank(),"EVIDENCE")
            if(side=="image"){
                val observation=source.imageObservations.firstOrNull{it.evidence.observationId==j.optString("observation_id")&&quote in it.text}?:throw ProcessingRejected("EVIDENCE")
                return@map MemoryEvidence(source.source,"image",quote,observation.text,source.user.created,source.zone,observation.evidence)
            }
            val message=(if(side=="user")source.user else source.assistant)?:throw ProcessingRejected("EVIDENCE")
            rejectUnless(quote in message.text,"EVIDENCE")
            MemoryEvidence(source.source,side,quote,message.text,message.created,source.zone)
        }.let{MemoryEvidenceRules.merge(emptyList(),it)}
    }
    /** Repository-level single operation, kept for focused storage tests. The engine never commits per item: it validates a whole response with [check] and publishes it in one transaction. */
    suspend fun apply(work:MemoryWork,op:JSONObject,configCurrent:()->Boolean={true}):String=repo.tx{db->apply(db,work,op,configCurrent)}
    private fun skipped(work:MemoryWork,key:String,reason:String,log:Boolean){if(log)Diagnostics.event("memory.operation.skipped",mapOf("character" to work.friendId,"batch" to work.id,"operation" to key,"reason" to reason))}
    private fun apply(db:SQLiteDatabase,work:MemoryWork,op:JSONObject,configCurrent:()->Boolean,original:JSONObject=op,semanticVersions:Map<String,Int> = emptyMap(),log:Boolean=true):String {
        if(log)Diagnostics.event("memory.operation.received",mapOf("character" to work.friendId,"batch" to work.id,"operation" to Diagnostics.raw(op.toString())))
        validate(db,work,configCurrent)
        val key=op.getString("id");rejectUnless(key.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val digest=stableDigest(canonicalJson(original))
        val previous=db.rawQuery("SELECT digest,result_id FROM memory_operations WHERE batch_id=? AND op_key=?",arrayOf(work.id,key)).use{if(it.moveToFirst())it.getString(0) to it.getString(1) else null}
        if(previous!=null){rejectUnless(previous.first==digest,"OPERATION_CHANGED");skipped(work,key,"duplicate",log);return previous.second}
        validateMemoryContext(db,work)
        rejectUnless(!op.has("locked")&&!op.has("manual"),"PROTECTED")
        val kind=op.getString("op");rejectUnless(kind in setOf("ADD","UPDATE","CANCEL","COMPLETE","RETRACT"))
        val local=op.optString("target")
        val id=if(kind=="ADD"){
            rejectUnless(local.matches(Regex("new:[A-Za-z0-9_-]{1,64}")))
            localTarget(db,work,local)
        }else if(local.startsWith("new:"))localTarget(db,work,local) else local
        fun skipDeleted(deleted:StoredMemory):String{
            db.insertOrThrow("memory_operations",null,ContentValues().apply{put("batch_id",work.id);put("op_key",key);put("json",canonicalJson(original));put("digest",digest);put("result_id",deleted.id);put("result_version",0);put("saved",0)})
            return deleted.id
        }
        // 指向已删那条的改写/补回一律跳过；同文本的新增要等证据算出来再看是不是旧来源。
        repo.memory(db,work.friendId,id)?.takeIf{it.userDeleted}?.let{return skipDeleted(it)}
        var old=repo.memory(db,work.friendId,id)
        var deduplicated=false
        if(kind!="ADD")rejectUnless(old!=null&&(local.startsWith("new:")||id in work.existingVersions||id in semanticVersions),"UNKNOWN_TARGET")
        val category=op.optString("category").takeIf{it.isNotBlank()}?.let{MemoryCategory.valueOf(it)}?:old?.category?:throw ProcessingRejected("FORMAT")
        rejectUnless(category.modern&&(old==null||old.category.modern),"LEGACY_READ_ONLY")
        val about=if(op.has("about"))MemoryAbout.valueOf(op.getString("about")) else old?.about?:throw ProcessingRejected("FORMAT")
        val tags=if(op.has("tags")){val a=op.getJSONArray("tags");rejectUnless(a.length()<=12);MemoryFacets.tags((0 until a.length()).map{a.getString(it)})}else old?.tags?:emptyList()
        val text=op.optString("text").ifBlank{old?.text.orEmpty()};rejectUnless(text.isNotBlank()&&text.length<=4000)
        val proof=evidence(work,op)
        repo.deletedMatch(db,work.friendId,id,text,proof)?.let{return skipDeleted(it)}
        if(about==MemoryAbout.USER)rejectUnless(proof.any{it.side=="user"},"USER_EVIDENCE")
        if(about==MemoryAbout.BOTH&&proof.any{it.side=="image"})rejectUnless(proof.any{it.side=="user"},"USER_EVIDENCE")
        val dateType=if(op.has("date_type"))op.getString("date_type").also{rejectUnless(it in setOf("day","habit","birthday"))} else old?.dateType
        val expression=if(op.optString("date_type")=="habit")null else if(!op.has("date_expression"))old?.dateExpression else if(op.isNull("date_expression"))null else op.optString("date_expression").takeIf{it.isNotBlank()}
        if(expression!=null&&op.has("date_expression"))rejectUnless(proof.any{expression in it.quote&&(about!=MemoryAbout.USER||it.side=="user")},"DATE_EVIDENCE")
        val dates=proof.filter{expression!=null&&expression in it.quote}.map{MemoryDates.resolve(expression,it.sourceAt,it.zone)}.toSet()
        val birthday=if(dateType=="birthday")MemoryDates.birthday(expression) else null
        val annual=if(birthday!=null)birthday.second else if(!op.has("date_type")&&!op.has("date_expression"))old?.annualMonthDay else null
        val date=if(birthday!=null)birthday.first else if(op.optString("date_type")=="habit")null else if(!op.has("date_expression"))old?.eventDate else if(dates.size==1)dates.first() else null
        rejectUnless(!op.has("due_date"),"DATE_EVIDENCE")
        val dueExpression=if(!op.has("due_expression"))old?.dueExpression else if(op.isNull("due_expression"))null else op.getString("due_expression").takeIf{it.isNotBlank()}
        if(dueExpression!=null&&op.has("due_expression"))rejectUnless(proof.any{dueExpression in it.quote&&(about!=MemoryAbout.USER||it.side=="user")},"DATE_EVIDENCE")
        val dueDates=proof.filter{dueExpression!=null&&dueExpression in it.quote}.map{MemoryDates.resolve(dueExpression,it.sourceAt,it.zone)}.toSet()
        val dueDate=if(!op.has("due_expression"))old?.dueDate else if(dueDates.size==1)dueDates.first() else null
        val life=when(kind){"CANCEL"->MemoryLife.CANCELLED;"COMPLETE"->MemoryLife.COMPLETED;"RETRACT"->MemoryLife.RETRACTED;else->old?.life?:MemoryLife.ACTIVE}
        val fact=StoredMemory(id,work.friendId,category,text,life,dateExpression=expression,eventDate=date,about=about,dueExpression=dueExpression,dueDate=dueDate,expiresAt=old?.expiresAt,dateType=dateType,annualMonthDay=annual)
        if(kind=="ADD"){
            if(old!=null)rejectUnless(MemoryEvidenceRules.sameFact(old,fact)&&MemoryEvidenceRules.overlaps(old.evidence,proof),"OPERATION_CHANGED")
            // Dedup identity: same source message + source version + content hash.
            else old=repo.memories(db,work.friendId).firstOrNull{it.valid&&MemoryEvidenceRules.sameSourceContent(it,fact,proof)}?.also{deduplicated=true}
        }
        val actualId=old?.id?:id
        val proposed=MemoryForgetting.metadata(StoredMemory(actualId,work.friendId,category,text,life,if(op.has("core"))op.getBoolean("core") else old?.core?:false,false,false,(old?.version?:0)+1,true,expression,date,MemoryEvidenceRules.merge(old?.evidence.orEmpty(),proof),about,tags,dueExpression,dueDate,old?.expiresAt,dateType,annual),op,old)
        val lastOwnVersion=db.rawQuery("SELECT MAX(result_version) FROM memory_operations WHERE batch_id=? AND result_id=?",arrayOf(work.id,actualId)).use{if(it.moveToFirst()&&!it.isNull(0))it.getInt(0) else null}
        val expectedVersion=lastOwnVersion?:semanticVersions[actualId]?:work.existingVersions[actualId]
        val protected=old!=null&&(old.locked||old.manual||old.version!=expectedVersion)
        val unchanged=old!=null&&old.text==proposed.text&&old.category==proposed.category&&old.about==about&&old.tags==tags&&old.dueDate==dueDate&&old.life==proposed.life&&old.eventDate==proposed.eventDate&&old.dateType==proposed.dateType&&old.annualMonthDay==proposed.annualMonthDay&&old.core==proposed.core&&old.importance==proposed.importance&&old.gist==proposed.gist&&old.trace==proposed.trace
        var savedVersion=old?.version?:1
        var wrote=false
        val doubt=op.optString("doubt").takeIf{it.isNotBlank()&&it!="null"}
        if(doubt==null&&old!=null&&old.importance==proposed.importance&&old.gist==proposed.gist&&old.trace==proposed.trace&&MemoryEvidenceRules.repeated(old,proposed,proof)){
            savedVersion=old.version;skipped(work,key,if(deduplicated)"duplicate" else "unchanged",log)
        }else if(doubt!=null){
            val sameProposal=old!=null&&original.optString("op")=="ADD"&&kind=="UPDATE"&&old.id in semanticVersions&&repo.candidates(db,work.friendId).any{it.targetId==old.id&&it.targetVersion==old.version&&it.valid&&MemoryEvidenceRules.repeated(it.proposed,proposed,proposed.evidence)}
            if(!sameProposal)repo.candidate(db,work.friendId,old?.id,proposed,"拿不准：${doubt.take(240)}","candidate-"+stableDigest(work.id+key).take(40));savedVersion=old?.version?:0
        }else if(protected&&!unchanged){
            repo.candidate(db,work.friendId,actualId,proposed,"人工、锁定或并发修改的内容受保护", "candidate-"+stableDigest(work.id+key).take(40))
        }else if(!protected){repo.saveMemory(db,proposed);savedVersion=proposed.version;wrote=true}
        db.insertOrThrow("memory_operations",null,ContentValues().apply{put("batch_id",work.id);put("op_key",key);put("json",canonicalJson(original));put("digest",digest);put("result_id",actualId);put("result_version",savedVersion);put("saved",if(wrote)1 else 0)})
        return actualId
    }
    suspend fun complete(work:MemoryWork,operationKeys:Set<String>,declaredCount:Int,marker:String,configCurrent:()->Boolean={true},lease:ChatAttemptLease?=null)=repo.tx{db->complete(db,work,operationKeys,declaredCount,marker,configCurrent,lease)}
    /** A complete JSON document publishes all operations and retirement in one transaction. */
    internal suspend fun applyDocument(work:MemoryWork,operations:List<JSONObject>,count:Int,marker:String,configCurrent:()->Boolean,lease:ChatAttemptLease,publish:(SQLiteDatabase)->Boolean={true})=repo.tx{db->
        rejectUnless(lease.guard{
            rejectUnless(publish(db),"LATE_DISCARDED")
            validate(db,work,configCurrent)
            operations.forEach{apply(db,work,it,configCurrent)}
            complete(db,work,appliedKeys(db,work),count,marker,configCurrent,null)
        },"CANCELLED")
    }
    private fun complete(db:SQLiteDatabase,work:MemoryWork,operationKeys:Set<String>,declaredCount:Int,marker:String,configCurrent:()->Boolean,lease:ChatAttemptLease?):Boolean {
        val finish={
        validate(db,work,configCurrent)
        validateMemoryContext(db,work,allowReviewed=true)
        rejectUnless(marker==work.id&&declaredCount==operationKeys.size)
        val saved=db.rawQuery("SELECT op_key FROM memory_operations WHERE batch_id=?",arrayOf(work.id)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        rejectUnless(saved==operationKeys,"INCOMPLETE")
        db.rawQuery("SELECT result_id,MAX(result_version) FROM memory_operations WHERE batch_id=? GROUP BY result_id",arrayOf(work.id)).use{c->while(c.moveToNext()){
            val m=repo.memory(db,work.friendId,c.getString(0))
            if(m?.userDeleted==true&&c.getInt(1)==0)continue
            if(m==null){
                rejectUnless(c.getInt(1)==0&&repo.candidates(db,work.friendId).any{it.valid&&it.proposed.id==c.getString(0)},"SOURCE_CHANGED")
                continue
            }
            rejectUnless(m.valid&&m.evidence.all{repo.evidenceValid(db,it)},"SOURCE_CHANGED")
            rejectUnless(m.version==c.getInt(1)||acknowledged(db,work,m),"BATCH_REVIEW_REQUIRED")
        }}
        work.targets.forEach{s->db.insertWithOnConflict("memory_retired",null,ContentValues().apply{put("friend_id",work.friendId);put("source_user_id",s.source.turnId);put("source_version",s.source.version);put("batch_id",work.id)},SQLiteDatabase.CONFLICT_IGNORE)}
        val cursor=maxOf(repo.cursor(db,work.friendId),work.targets.last().source.turnId)
        db.insertWithOnConflict("memory_progress",null,ContentValues().apply{put("friend_id",work.friendId);put("cursor",cursor)},SQLiteDatabase.CONFLICT_REPLACE)
        db.update("memory_batches",ContentValues().apply{put("state","COMMITTED");putNull("error");put("next_retry",0)},"id=?",arrayOf(work.id))
        // Same transaction as the commit: a rolled-back or repaired batch can never leave a pill behind.
        ChatMemoryPills.record(db,work.friendId,work.id,ChatMemoryPills.savedCount(db,work.id))
        Diagnostics.event("memory.batch.receipt.staged",mapOf("character" to work.friendId,"batch" to work.id,"operation_count" to declaredCount,"cursor" to cursor))
        work.gistFill.take(MemoryForgetting.GIST_BATCH).forEach{fill->
            val id=fill.optString("target");val old=repo.memory(db,work.friendId,id)
            if(old!=null&&old.valid&&old.version==work.existingVersions[id]){
                val metadata=MemoryForgetting.metadata(old,fill,old)
                val updated=if(old.manual||old.locked)old.copy(gist=old.gist?.takeIf{it.isNotBlank()}?:metadata.gist,trace=old.trace?.takeIf{it.isNotBlank()}?:metadata.trace) else metadata
                if(updated!=old)repo.saveMemory(db,updated.copy(version=old.version+1))
            }
        }
        repo.maintainCore(db,work.friendId)
        // Optional graph errors cannot roll back a valid summary or retire an incomplete one.
        db.execSQL("SAVEPOINT optional_associations")
        try{MemoryAssociations(repo).commit(db,work);db.execSQL("RELEASE optional_associations")}
        catch(_:Exception){db.execSQL("ROLLBACK TO optional_associations");db.execSQL("RELEASE optional_associations")}
        true
        }
        return if(lease==null)finish() else lease.guard(finish)
    }
    internal data class SemanticSnapshot(val base:List<StoredMemory>,val fresh:List<SemanticFact>)
    /** Run the existing validator/exact dedup first; its trial writes never leave this savepoint. */
    internal suspend fun previewSemantic(work:MemoryWork,ops:List<JSONObject>,configCurrent:()->Boolean):SemanticSnapshot=repo.tx{db->
        validate(db,work,configCurrent);validateMemoryContext(db,work)
        val base=repo.usableMemories(db,work.friendId);val before=repo.memories(db,work.friendId).associateBy{it.id}
        val results=linkedMapOf<String,String>();db.execSQL("SAVEPOINT semantic_preview")
        try{
            ops.forEach{op->results[op.getString("id")]=apply(db,work,op,configCurrent,log=false)}
            val repeatedTargets=ops.groupingBy{it.getString("target")}.eachCount().filterValues{it>1}.keys
            val fresh=ops.mapNotNull{op->
                val kind=op.getString("op");val target=op.getString("target");val id=results.getValue(op.getString("id"))
                if(kind !in setOf("ADD","UPDATE")||op.optString("doubt").let{it.isNotBlank()&&it!="null"}||target in repeatedTargets)return@mapNotNull null
                val m=repo.memory(db,work.friendId,id)?.takeUnless{it.userDeleted}?:return@mapNotNull null
                if(kind=="ADD"&&(id in before||ops.any{it!==op&&it.getString("target")==target})||kind=="UPDATE"&&m.version==(before[id]?.version?:0))return@mapNotNull null
                SemanticFact("N${ops.indexOf(op)+1}",m.copy(evidence=m.evidence.filter{e->work.targets.any{it.source==e.source}}),op.getString("id"),kind)
            }
            SemanticSnapshot(base,fresh)
        }finally{db.execSQL("ROLLBACK TO semantic_preview");db.execSQL("RELEASE semantic_preview");Diagnostics.event("memory.semantic.preview.rolled_back",mapOf("character" to work.friendId,"operations" to ops.size))}
    }
    /** Decisions, all operations and the completion receipt publish together, or none publish. */
    internal suspend fun applySemantic(work:MemoryWork,originals:List<JSONObject>,resolved:List<JSONObject>,snapshot:SemanticSnapshot,count:Int,marker:String,configCurrent:()->Boolean,lease:ChatAttemptLease,publish:(SQLiteDatabase)->Boolean={true}):List<StoredMemory> =repo.tx{db->
        rejectUnless(originals.size==resolved.size)
        val finish={
            rejectUnless(publish(db),"LATE_DISCARDED")
            validate(db,work,configCurrent);validateMemoryContext(db,work)
            rejectUnless(repo.usableMemories(db,work.friendId)==snapshot.base,"SOURCE_CHANGED")
            val versions=snapshot.base.associate{it.id to it.version}
            originals.zip(resolved).forEach{(raw,op)->apply(db,work,op,configCurrent,raw,versions)}
            val keys=appliedKeys(db,work);rejectUnless(complete(db,work,keys,count,marker,configCurrent,null),"CANCELLED")
            true
        }
        rejectUnless(lease.guard(finish),"CANCELLED")
        repo.usableMemories(db,work.friendId)
    }
    /**
     * Dry run of a whole response inside a rolled-back savepoint. Every operation is tried, so the
     * repair request can list all concrete errors at once; nothing here is ever visible to readers.
     */
    internal suspend fun check(work:MemoryWork,ops:List<JSONObject>,raw:List<JSONObject>,count:Int,marker:String,configCurrent:()->Boolean):List<MemoryRepairFeedback.Issue> = repo.tx{db->
        validate(db,work,configCurrent);validateMemoryContext(db,work)
        val issues=mutableListOf<MemoryRepairFeedback.Issue>()
        db.execSQL("SAVEPOINT memory_check")
        try{
            ops.forEachIndexed{i,op->
                val source=raw.getOrNull(i)?:op;val name=source.optString("id").ifBlank{"#${i+1}"}
                db.execSQL("SAVEPOINT memory_check_op")
                try{apply(db,work,op,configCurrent,log=false);db.execSQL("RELEASE memory_check_op")}
                catch(e:Exception){
                    db.execSQL("ROLLBACK TO memory_check_op");db.execSQL("RELEASE memory_check_op")
                    val code=(e as? ProcessingRejected)?.code?:"FORMAT"
                    if(code in setOf("SOURCE_CHANGED","STALE_TASK","CANCELLED","BATCH_REVIEW_REQUIRED"))throw e
                    issues+=MemoryRepairFeedback.Issue(name,code,MemoryRepairFeedback.hint(code,source.optString("target"),source.opt("evidence")?.toString().orEmpty()))
                }
            }
            if(issues.isEmpty()){
                val keys=appliedKeys(db,work)
                if(marker!=work.id)issues+=MemoryRepairFeedback.Issue("receipt","FORMAT","end_marker 必须等于 batch_id ${work.id}")
                if(count!=keys.size)issues+=MemoryRepairFeedback.Issue("receipt","INCOMPLETE","operation_count=$count，但本批去重后实际为 ${keys.size} 项")
            }
        }finally{db.execSQL("ROLLBACK TO memory_check");db.execSQL("RELEASE memory_check")}
        issues
    }
    /** Why a failed attempt's batch is no longer the right batch: its cited rounds or captured memories changed. */
    suspend fun invalidation(work:MemoryWork):String?=repo.read{db->
        if(!repo.sourcesMatch(db,work.targets))"SOURCE_EDITED"
        else try{validateMemoryContext(db,work);null}catch(e:ProcessingRejected){if(e.code=="SOURCE_CHANGED")"MEMORY_CHANGED" else null}
    }
    /** The whole batch is void; nothing of it was published. The next summary is rebuilt from the latest content. */
    suspend fun invalidate(work:MemoryWork,reason:String)=repo.tx{db->db.update("memory_batches",ContentValues().apply{put("state","INVALIDATED");put("error",reason);put("attempt",0);put("next_retry",0)},"id=? AND json=? AND state NOT IN('COMMITTED','ARCHIVED')",arrayOf(work.id,work.json().toString()))}
    data class Failure(val batchId:String,val code:String,val detail:String,val attempts:Int,val firstRound:Int,val lastRound:Int,val at:Long)
    private fun failureKey(friendId:String)="memory-batch-failure:$friendId"
    /** Visible record of the batch that failed after all attempts; its rounds stay in the recent window. */
    suspend fun recordFailure(work:MemoryWork,code:String,detail:String,attempts:Int)=repo.tx{db->
        val all=repo.sources(db,work.friendId).map{it.source.turnId}
        val first=all.indexOf(work.targets.first().source.turnId)+1;val last=all.indexOf(work.targets.last().source.turnId)+1
        db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",failureKey(work.friendId));put("value",JSONObject().put("batch",work.id).put("code",code).put("detail",detail.take(2000)).put("attempts",attempts).put("first",first).put("last",last).put("at",System.currentTimeMillis()).toString())},SQLiteDatabase.CONFLICT_REPLACE)
    }
    suspend fun clearFailure(friendId:String)=repo.tx{db->db.delete("preferences","key=?",arrayOf(failureKey(friendId)))}
    suspend fun failure(friendId:String):Failure?=repo.read{db->db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(failureKey(friendId))).use{c->if(!c.moveToFirst())null else runCatching{val j=JSONObject(c.getString(0));Failure(j.getString("batch"),j.getString("code"),j.optString("detail"),j.optInt("attempts"),j.optInt("first"),j.optInt("last"),j.optLong("at"))}.getOrNull()}}
    private fun appliedKeys(db:SQLiteDatabase,work:MemoryWork)=db.rawQuery("SELECT op_key FROM memory_operations WHERE batch_id=?",arrayOf(work.id)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    suspend fun fail(work:MemoryWork,code:String,retryAt:Long=0)=repo.tx{db->db.update("memory_batches",ContentValues().apply{put("state",if(retryAt>0)"WAITING" else "FAILED");put("error",code);put("next_retry",retryAt)},"id=? AND json=? AND state NOT IN('COMMITTED','ARCHIVED')",arrayOf(work.id,work.json().toString()))}
}

