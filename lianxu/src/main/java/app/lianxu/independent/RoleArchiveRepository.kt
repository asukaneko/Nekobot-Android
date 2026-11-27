package app.lianxu.independent

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Selected-role snapshots only. Preview is optimistic; commit rechecks inside one SQLite transaction. */
internal class RoleArchiveRepository(private val store:Store) {
    internal fun validateForBackup(db:SQLiteDatabase,p:RoleArchive.Package,imageRows:JsonArray=JsonArray())=validate(db,p,imageRows)
    data class Preview(val archive:RoleArchive.Package,val target:String?,val fingerprint:String?,val impact:String)
    private val order=RoleArchive.tables.keys.toList()
    private fun rows(db:SQLiteDatabase,table:String,id:String):JsonArray {
        val predicate=when(table){
            "message_state","message_history"->"message_id IN (SELECT id FROM messages WHERE friend_id=?)"
            "memory_history"->"memory_id IN (SELECT id FROM memory_current WHERE friend_id=?)"
            else->"friend_id=?"
        }
        return db.rawQuery("SELECT * FROM $table WHERE $predicate ORDER BY rowid",arrayOf(id)).use{c->JsonArray().apply{while(c.moveToNext())add(JsonObject().apply{c.columnNames.forEachIndexed{i,key->
            when(c.getType(i)){Cursor.FIELD_TYPE_NULL->add(key,JsonNull.INSTANCE);Cursor.FIELD_TYPE_INTEGER->addProperty(key,c.getLong(i));Cursor.FIELD_TYPE_STRING->addProperty(key,c.getString(i));else->error("不支持的本地数据类型")}
        }})}}
    }
    private fun exists(db:SQLiteDatabase,id:String)=db.rawQuery("SELECT 1 FROM friends WHERE id=?",arrayOf(id)).use{it.moveToFirst()}
    private fun idle(db:SQLiteDatabase,id:String){
        listOf("chat_turns" to "PENDING","memory_batches" to "RUNNING").forEach{(table,state)->
            require(!db.rawQuery("SELECT 1 FROM $table WHERE friend_id=? AND state=? LIMIT 1",arrayOf(id,state)).use{it.moveToFirst()}){"角色仍有进行中的任务，请先停止或等待完成，再重新预览"}
        }
    }
    private fun snapshot(db:SQLiteDatabase,id:String,records:Boolean):JsonObject {
        require(exists(db,id)){"角色已不存在，请重新选择"}
        val friend=db.rawQuery("SELECT * FROM friends WHERE id=?",arrayOf(id)).use{c->check(c.moveToFirst());JsonObject().apply{listOf("id","name","bio","prompt").forEach{addProperty(it,c.getString(c.getColumnIndexOrThrow(it)))};addProperty("tone",c.getInt(c.getColumnIndexOrThrow("tone")))}}
        val prompts=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(id))).use{if(it.moveToFirst())JsonParser.parseString(it.getString(0)).asJsonObject else JsonObject()}
        if(!records){prompts.remove("examples");prompts.addProperty("examplesEnabled",false);prompts.addProperty("examplesClosed",true)}
        val data=JsonObject();if(records)order.forEach{table->data.add(table,rows(db,table,id))}
        data["chat_turns"]?.asJsonArray?.forEach{v->v.asJsonObject.apply{addProperty("config_id","");addProperty("owner_session","")}}
        return JsonObject().apply{addProperty("scope",if(records)"records" else "role");add("character",friend);add("prompts",prompts);add("records",data)}
    }
    suspend fun export(id:String,records:Boolean):JsonObject=atomic{db->if(records)require(!db.rawQuery("SELECT 1 FROM chat_images WHERE friend_id=? LIMIT 1",arrayOf(id)).use{it.moveToFirst()}){"聊天含图片或视频，请使用设置中的完整数据备份"};idle(db,id);snapshot(db,id,records).also{validate(db,RoleArchive.validate(it.toString().toByteArray()))}}
    suspend fun preview(archive:RoleArchive.Package,target:String?):Preview=atomic{db->
        validate(db,archive)
        if(target==null)Preview(archive,null,null,"新增独立角色，不修改任何现有角色。\n${archive.summary}")
        else {idle(db,target);val old=snapshot(db,target,true);val f=old["character"].asJsonObject
            val counts=old["records"].asJsonObject
            Preview(archive,target,RoleArchive.hash(old.toString().toByteArray()),if(archive.scope=="role")
                "覆盖「${f["name"].asString}」的名字、介绍、角色提示和表达设置。保留聊天和记忆记录；关系性格和起点保持不变；取消该角色旧处理任务。"
                else "覆盖「${f["name"].asString}」全部角色资料及记录：本地聊天 ${counts["messages"].asJsonArray.size()} 条、记忆 ${counts["memory_current"].asJsonArray.size()} 条将由包内资料替换。旧任务与派生缓存会清理，无法在此撤销，请先导出备份。\n${archive.summary}")
        }
    }
    suspend fun commit(preview:Preview,confirmOverwrite:Boolean=false,lease:ChatAttemptLease=ChatAttemptLease(),checkpoint:()->Unit={}):String=atomic(mutation=true){db->
        check(lease.isActive()){"导入已取消"}
        val archive=RoleArchive.validate(preview.archive.raw.toByteArray());require(archive.digest==preview.archive.digest)
        validate(db,archive)
        val target=preview.target
        if(target!=null){store.memorySummary.requireEditable(target);require(confirmOverwrite){"覆盖需要再次确认"};idle(db,target)
            require(RoleArchive.hash(snapshot(db,target,true).toString().toByteArray())==preview.fingerprint){"本地资料已变化，请重新预览"}
        }
        val root=archive.root()
        val id=target?:UUID.randomUUID().toString()
        if(target!=null&&archive.scope=="records")clearRecords(db,id)
        // Old job snapshots must never commit into newly imported character settings.
        if(target!=null)clearJobs(db,id)
        val friend=root["character"].asJsonObject.deepCopy().apply{addProperty("id",id)}
        if(target==null)db.insertOrThrow("friends",null,values(friend)) else db.update("friends",values(friend),"id=?",arrayOf(id))
        checkpoint()
        val prompts=root["prompts"].asJsonObject.deepCopy().apply {
            addProperty("examplesEnabled",false);addProperty("examplesClosed",true)
            if(target!=null){
                val old=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(id))).use{if(it.moveToFirst())RolePromptStore.decode(it.getString(0))else RolePrompts()}
                addProperty("relationTemplate",old.relationTemplate);addProperty("relationStart",old.relationStart)
            }
        }
        putPreference(db,RolePromptStore.key(id),prompts.toString())
        // Retain imported example text, but never resume selection or activate historical dialogue.
        db.delete("preferences","key=?",arrayOf("role-examples:$id"))
        if(archive.scope=="records")restore(db,root["records"].asJsonObject,id,checkpoint)
        // No import can activate paid evaluation or restore model selection.
        db.delete("processing_policies","friend_id=?",arrayOf(id))
        db.insertOrThrow("processing_policies",null,ContentValues().apply{put("friend_id",id);put("json",ProcessingPolicy().json().toString());put("version",1)})
        if(target==null&&archive.scope=="role")store.seedOpening(db,id,RolePromptStore.decode(prompts.toString()).opening)
        checkpoint();check(lease.commit{true}){"导入已取消"};id
    }
    private fun clearJobs(db:SQLiteDatabase,id:String){
        listOf("memory_operations","memory_batch_reviews").forEach{db.delete(it,"batch_id IN (SELECT id FROM memory_batches WHERE friend_id=?)",arrayOf(id))}
        db.delete("memory_batches","friend_id=?",arrayOf(id))
    }
    private fun clearRecords(db:SQLiteDatabase,id:String){
        val view=CompanionRepository(store).relationship(db,id)
        val turns=db.rawQuery("SELECT id FROM chat_turns WHERE friend_id=?",arrayOf(id)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        val transientKeys=db.rawQuery("SELECT key FROM preferences",null).use{c->buildList{while(c.moveToNext()){val key=c.getString(0)
            if(key==ReplyAccountingQueue.batchKey(id)||turns.any{key=="memory-recall-usage:$it"||key.startsWith("memory-tools:$it:")||key==ChatReplyQuote.PREFIX+it||key==OrderedChatReply.PREFIX+it||key==ChatToolJournal.key(it)||key==ChatLedger.errorKey(it)||key==ChatLedger.errorMetaKey(it)||key==ReplyAccountingQueue.key(it)||key==ReplyAccountingQueue.readsKey(it)})add(key)}}}
        transientKeys.forEach{db.delete("preferences","key=?",arrayOf(it))}
        listOf("companion_action_learning","companion_receipts","companion_opportunities","companion_uses","companion_blocks","companion_sources","companion_vectors","companion_state").forEach{db.delete(it,"role=?",arrayOf(id))}
        db.delete("relationship_snapshots","role=?",arrayOf(id))
        db.delete("memory_recall_state","friend_id=?",arrayOf(id))
        listOf(RelationStorage.key(id),"relationship-cache:$id",RelationFeeling.key(id),"memory-core-set:$id","memory-batch-failure:$id").forEach{db.delete("preferences","key=?",arrayOf(it))}
        // No old message IDs: a baseline at epoch zero preserves the effective personality for all imported records.
        RelationStorage.write(db,id,RelationHistory(changes=listOf(RelationChange(0,view.parameters,view.parameters,view.label))))
        clearJobs(db,id)
        db.delete("candidate_injections","friend_id=?",arrayOf(id))
        val picturePaths=db.rawQuery("SELECT path FROM role_assets WHERE friend_id=? AND kind='attachment' AND path LIKE 'chat-images/%'",arrayOf(id)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        ImageDisk.clearReadCache()
        db.delete("image_observations","friend_id=?",arrayOf(id))
        db.delete("chat_images","friend_id=?",arrayOf(id))
        picturePaths.forEach{db.delete("role_assets","friend_id=? AND path=?",arrayOf(id,it))}
        for(table in order.reversed()){
            val predicate=when(table){"message_state","message_history"->"message_id IN (SELECT id FROM messages WHERE friend_id=?)";"memory_history"->"memory_id IN (SELECT id FROM memory_current WHERE friend_id=?)";else->"friend_id=?"}
            db.delete(table,predicate,arrayOf(id))
        }
        listOf("memory-associations-v1:$id","memory-vectors-v1:$id").forEach{db.delete("preferences","key=?",arrayOf(it))}
        // Avoid LIKE patterns from external identifiers; only exact selected-role prefix matches.
        val keys=db.rawQuery("SELECT key FROM preferences",null).use{c->buildList{while(c.moveToNext()){val k=c.getString(0);if(k.startsWith("memory-reference-v1:$id:"))add(k)}}}
        keys.forEach{db.delete("preferences","key=?",arrayOf(it))}
    }
    private fun restore(db:SQLiteDatabase,data:JsonObject,id:String,checkpoint:()->Unit){
        val messageIds=linkedMapOf<Long,Long>();val memoryIds=linkedMapOf<String,String>()
        fun newMemory(key:String)=memoryIds.getOrPut(key){UUID.randomUUID().toString()}
        data["memory_current"].asJsonArray.forEach{newMemory(it.asJsonObject["id"].asString)}
        data["memory_candidates"].asJsonArray.forEach{newMemory(JSONObject(it.asJsonObject["json"].asString).getJSONObject("proposed").getString("id"))}
        fun msg(n:Long):Long=if(n==0L)0 else messageIds[n]?:error("原话引用不存在")
        fun memory(raw:String):String {
            val m=StoredMemory.from(JSONObject(raw))
            return m.copy(id=newMemory(m.id),friendId=id,evidence=m.evidence.map{e->e.copy(source=e.source.copy(characterId=id,turnId=msg(e.source.turnId)))}).json().toString()
        }
        for(table in order){for(raw in data[table].asJsonArray){checkpoint();val row=raw.asJsonObject.deepCopy()
            if(row.has("friend_id"))row.addProperty("friend_id",id)
            when(table){
                "messages"->{val old=row.remove("id").asLong;messageIds[old]=db.insertOrThrow(table,null,values(row));continue}
                "chat_turns"->{row.addProperty("id",UUID.randomUUID().toString());row.addProperty("user_message_id",msg(row["user_message_id"].asLong));if(!row["assistant_message_id"].isJsonNull)row.addProperty("assistant_message_id",msg(row["assistant_message_id"].asLong));row.addProperty("config_id","");row.addProperty("owner_session","")}
                "message_state","message_history"->row.addProperty("message_id",msg(row["message_id"].asLong))
                "memory_current"->{row.addProperty("id",newMemory(row["id"].asString));row.addProperty("json",memory(row["json"].asString))}
                "memory_history"->{row.addProperty("memory_id",newMemory(row["memory_id"].asString));row.addProperty("json",memory(row["json"].asString))}
                "memory_candidates"->{row.addProperty("id",UUID.randomUUID().toString());if(!row["target_id"].isJsonNull)row.addProperty("target_id",newMemory(row["target_id"].asString));val j=JSONObject(row["json"].asString);j.put("proposed",JSONObject(memory(j.getJSONObject("proposed").toString())));row.addProperty("json",j.toString())}
                "memory_retired"->{row.addProperty("source_user_id",msg(row["source_user_id"].asLong));row.addProperty("batch_id","archive-"+UUID.randomUUID())}
                "memory_progress"->row.addProperty("cursor",msg(row["cursor"].asLong))
            }
            db.insertOrThrow(table,null,values(row))
        }}
    }
    private fun validate(db:SQLiteDatabase,p:RoleArchive.Package,imageRows:JsonArray=JsonArray()){
        val root=p.root();RolePromptStore.decode(root["prompts"].toString())
        if(p.scope=="role")return
        val id=root["character"].asJsonObject["id"].asString;val data=root["records"].asJsonObject
        val records=order.associateWith{table->data[table].asJsonArray.map{it.asJsonObject}}
        for((table,rows) in records){
            val schema=db.rawQuery("PRAGMA table_info($table)",null).use{c->buildMap{while(c.moveToNext())put(c.getString(1),Triple(c.getString(2),c.getInt(3)==1,c.getInt(5)))}}
            val primary=schema.filterValues{it.third>0}.keys;val seen=hashSetOf<String>()
            rows.forEach{row->require(seen.add(primary.joinToString("|"){row[it].toString()})){"记录主键重复"};row.entrySet().forEach{(key,v)->val col=schema.getValue(key)
                if(v.isJsonNull)require(!col.second&&col.third==0){"记录缺少必填值"}
                else if(col.first=="INTEGER")RoleArchive.integer(v) else require(v.isJsonPrimitive&&v.asJsonPrimitive.isString){"记录文字类型无效"}
            }}
        }
        val messages=records.getValue("messages").associateBy{it["id"].asLong}
        require(messages.keys.all{it>0}){"原话编号无效"}
        messages.values.forEach{require(it["mine"].asLong in 0..1&&it["created"].asLong>=0);RoleArchive.text(it,"body",64000,true)}
        val states=records.getValue("message_state").associateBy{it["message_id"].asLong}
        states.forEach{(m,s)->require(m in messages&&s["revision"].asLong>0&&s["deleted"].asLong in 0..1)}
        val history=records.getValue("message_history")
        history.forEach{require(it["message_id"].asLong in messages&&it["revision"].asLong>0&&it["deleted"].asLong in 0..1)}
        val turns=records.getValue("chat_turns").associateBy{it["user_message_id"].asLong};require(turns.size==records.getValue("chat_turns").size)
        val assistants=hashSetOf<Long>()
        turns.forEach{(u,t)->require(messages[u]?.get("mine")?.asLong==1L&&t["source_revision"].asLong>0)
            java.time.ZoneId.of(t["source_zone"].asString)
            require(t["attempt"].asLong in 1..100000 && (t["error_code"].isJsonNull || ChatFailure.entries.any{it.name==t["error_code"].asString})){"聊天状态无效"}
            require(t["state"].asString in setOf("COMPLETED","FAILED","CANCELLED","INTERRUPTED","SUPERSEDED")){"不导入进行中的聊天任务"}
            require(t["config_id"].asString.isEmpty()&&t["owner_session"].asString.isEmpty()){"包内不能恢复模型配置或任务"}
            if(t["state"].asString=="COMPLETED"||!t["assistant_message_id"].isJsonNull){val a=t["assistant_message_id"].asLong;require(messages[a]?.get("mine")?.asLong==0L&&a>u&&assistants.add(a))}
            else require(t["assistant_message_id"].isJsonNull)
        }
        fun source(ref:SourceRef,current:Boolean=false){val t=turns[ref.turnId]?:error("来源原话不在包内");require(ref.characterId==id&&ref.version>0&&ref.version<=t["source_revision"].asLong){"来源归属或版本无效"};if(current)require(t["state"].asString in setOf("COMPLETED","SUPERSEDED")&&ref.version==t["source_revision"].asLong&&listOfNotNull(ref.turnId,t["assistant_message_id"].takeUnless{it.isJsonNull}?.asLong).none{states[it]?.get("deleted")?.asLong==1L}){"有效来源已经变化"}}
        fun memory(raw:String,checkCurrent:Boolean=false):StoredMemory {RoleArchive.parse(raw.toByteArray());val m=StoredMemory.from(JSONObject(raw));require(m.friendId==id&&m.version>0&&m.text.length in 1..8000)
            m.evidence.forEach{e->source(e.source,checkCurrent&&m.valid);require(e.side in setOf("user","assistant","image")&&e.quote.isNotBlank()&&e.quote in e.snapshot);java.time.ZoneId.of(e.zone)
                val t=turns.getValue(e.source.turnId)
                if(e.side=="image"){
                    val proof=e.image?:error("图片证据缺少出处")
                    source(proof.imageSource,checkCurrent&&m.valid)
                    val observation=imageRows.map{it.asJsonObject}.singleOrNull{it["id"].asString==proof.observationId}?:error("图片观察出处不在备份中，请使用完整备份")
                    require(observation["friend_id"].asString==id&&observation["message_id"].asLong==proof.imageSource.turnId&&observation["source_revision"].asLong==proof.imageSource.version&&observation["image_hash"].asString==proof.hash&&observation["question_message_id"].asLong==e.source.turnId&&observation["question_revision"].asLong==e.source.version&&observation["body"].asString==e.snapshot){"图片证据与观察不一致"}
                    require(e.sourceAt==messages.getValue(e.source.turnId)["created"].asLong&&e.zone==t["source_zone"].asString)
                    return@forEach
                }
                require(e.image==null)
                val mid=if(e.side=="user")e.source.turnId else t["assistant_message_id"].asLong
                require(e.sourceAt==messages.getValue(mid)["created"].asLong && e.zone==t["source_zone"].asString){"来源时间或时区不一致"}
                if(e.source.version==t["source_revision"].asLong)require(messages.getValue(mid)["body"].asString==e.snapshot){"当前来源快照不一致"}
                require(messages[mid]?.get("body")?.asString==e.snapshot||history.any{it["message_id"].asLong==mid&&it["body"].asString==e.snapshot}){"来源快照与包内原话不一致"}
            };return m
        }
        val memories=records.getValue("memory_current").associateBy{it["id"].asString}
        memories.forEach{(key,r)->val m=memory(r["json"].asString,true);require(m.id==key&&m.version==r["version"].asInt&&m.valid==(r["valid"].asInt==1)&&r["valid"].asInt in 0..1)}
        records.getValue("memory_history").forEach{r->val m=memory(r["json"].asString);require(m.id==r["memory_id"].asString&&m.id in memories&&m.version==r["version"].asInt&&m.version<=memories.getValue(m.id)["version"].asInt)}
        records.getValue("memory_candidates").forEach{r->val j=JSONObject(r["json"].asString);memory(j.getJSONObject("proposed").toString());if(!r["target_id"].isJsonNull)require(r["target_id"].asString in memories);require(r["resolved"].asInt in 0..1);j.getString("reason")}
        fun cursor(n:Long){require(n==0L||n in turns){"记录游标不在包内"}}
        records.getValue("memory_progress").forEach{cursor(it["cursor"].asLong)}
        records.getValue("memory_retired").forEach{r->source(SourceRef(id,r["source_user_id"].asLong,r["source_version"].asLong))}
        require(records.getValue("memory_progress").size<=1)
    }
    private fun values(row:JsonObject)=ContentValues().apply{row.entrySet().forEach{(key,v)->when{v.isJsonNull->putNull(key);v.asJsonPrimitive.isNumber->put(key,RoleArchive.integer(v));else->put(key,v.asString)}}}
    private fun putPreference(db:SQLiteDatabase,key:String,value:String){db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    private suspend fun <T> atomic(mutation:Boolean=false,block:(SQLiteDatabase)->T):T=withContext(Dispatchers.IO){
        val action={val db=store.writableDatabase;db.beginTransaction();try{val result=block(db);db.setTransactionSuccessful();result}finally{db.endTransaction()}}
        if(mutation)AppMutationGate.write(action) else AppMutationGate.read(action)
    }
}
