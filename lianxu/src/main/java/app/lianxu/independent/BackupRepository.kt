package app.lianxu.independent

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.google.gson.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** One database transaction publishes both data and the prepared encrypted vault generation. */
internal class BackupRepository(private val store:Store,private val vault:ModelVault,private val availableSpace:(java.io.File)->Long={it.usableSpace}){
    data class Export(val root:JsonObject,val files:Map<String,ByteArray>):java.io.Closeable{override fun close()=BackupAssetIO.close(files);override fun toString()="BackupExport(redacted)"}
    data class Preview(val pack:BackupArchive.Package,val mode:String,val target:String?,val fingerprint:String,val contentDigest:String,val impact:String,val relationReport:RelationRestoreReport=RelationRestoreReport(0),val assetDigests:Map<String,String> = emptyMap()){override fun toString()="BackupPreview(redacted)"}
    data class RestoreResult(val roles:List<String>,val relationReport:RelationRestoreReport)
    suspend fun commitWithReport(plan:Preview,confirmed:Boolean=false)=RestoreResult(commit(plan,confirmed),plan.relationReport)
    data class RoleDeletion(val friendId:String,val name:String,val characterStamp:String)
    private val base=RoleArchive.tables.keys.toList()
    private val extra=listOf("processing_policies","memory_batches","memory_operations","memory_batch_reviews","candidate_injections","message_reasoning","memory_recall_state","chat_memory_pills","chat_images","image_observations")
    private val companionTables=listOf("companion_state","companion_sources","companion_blocks","companion_uses","companion_opportunities","companion_receipts","companion_action_learning")
    private val order=base+extra+companionTables
    // One-off migration markers travel as global settings, so a full backup never stops on them and a restore never reruns the migration.
    private val globalKeys=setOf("nickname","compact","diagnostic-detailed",EXPORT_DETAIL_PREF,MemoryRules.KEY,UserPersona.KEY,LocalUpdateReceipt.KEY,MemoryForgetting.RECALL_DEFAULT_MIGRATED,UserAvatarData.KEY,ChatImages.SWITCH,ChatImages.SWITCH_REVISION,ToolboxPreferences.ORDER_KEY,ToolboxPreferences.VIDEO_KEY,ToolboxPreferences.VIDEO_REVISION)
    private val legacy=RoleArchiveRepository(store)
    private val repo=ProcessingRepository(store)
    private class Budget {
        private var json=0L
        fun json(value:JsonElement){json+=value.toString().toByteArray(Charsets.UTF_8).size;require(json<=BackupArchive.MAX_JSON){"备份文字资料超过8 MiB，请先减少资料后重试"}}
        fun asset(size:Int){require(size in 0..BackupArchive.MAX_FILE){"单个资源超过安全上限"}}
    }
    private fun rows(db:SQLiteDatabase,table:String,id:String,budget:Budget):JsonArray {
        val predicate=when(table){
            in companionTables->"role=?"
            "message_state","message_history","message_reasoning"->"message_id IN (SELECT id FROM messages WHERE friend_id=?)"
            "memory_history"->"memory_id IN (SELECT id FROM memory_current WHERE friend_id=?)"
            "memory_operations","memory_batch_reviews"->"batch_id IN (SELECT id FROM memory_batches WHERE friend_id=?)"
            else->"friend_id=?"
        }
        return db.rawQuery("SELECT * FROM $table WHERE $predicate ORDER BY rowid",arrayOf(id)).use{c->JsonArray().apply{while(c.moveToNext())add(row(c).also{budget.json(it)})}}
    }
    private fun row(c:Cursor)=JsonObject().apply{c.columnNames.forEachIndexed{i,k->when(c.getType(i)){Cursor.FIELD_TYPE_NULL->add(k,JsonNull.INSTANCE);Cursor.FIELD_TYPE_INTEGER->addProperty(k,c.getLong(i));Cursor.FIELD_TYPE_STRING->addProperty(k,c.getString(i));Cursor.FIELD_TYPE_FLOAT->addProperty(k,c.getDouble(i));else->error("资料含不支持的列类型")}}}
    private fun prefs(db:SQLiteDatabase)=db.rawQuery("SELECT key,value FROM preferences ORDER BY key",null).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}}
    private fun roleKey(key:String,id:String,turns:Set<String>,batches:Set<String>):Boolean =
        key in setOf(RolePromptStore.key(id),RelationStorage.key(id),ReplyAccountingQueue.batchKey(id),"role-examples:$id",MemoryAssociations.key(id))||key.startsWith("memory-reference-v1:$id:")||turns.any{key=="memory-recall-usage:$it"||key==ChatToolJournal.key(it)||key==OrderedChatReply.PREFIX+it||key==ReplyAccountingQueue.key(it)||key==ReplyAccountingQueue.readsKey(it)}||batches.any{key=="memory-dropped:$it"}
    /**
     * Rebuildable rows that a backup leaves out: relationship caches and the derived core-memory selection
     * are recomputed from the restored data. Per-turn tool receipts (the pills shown on old rounds) cannot be
     * recomputed, so they travel with their role; only receipts whose turn belongs to no role are skipped.
     */
    private val derivedPrefixes=listOf("relationship-cache:","memory-core-set:")
    private fun ignored(key:String)=derivedPrefixes.any{key.startsWith(it)}||key.startsWith(ChatToolJournal.PREFIX)||key.startsWith(OrderedChatReply.PREFIX)||key.startsWith("memory-tools:")||key.startsWith(ChatLedger.ERROR_PREFIX)||key.startsWith(ContactFailures.ERROR_PREFIX)||key.startsWith(ModelStatusStore.PREFIX)||key.startsWith("memory-batch-failure:")||key.startsWith("memory-vectors-v1:")||key.startsWith("model-vault-generation:")
    private fun snapshot(db:SQLiteDatabase,id:String,files:MutableMap<String,BackupAssetSource>,budget:Budget=Budget(),valid:()->Unit={}):JsonObject {
        val friend=db.rawQuery("SELECT * FROM friends WHERE id=?",arrayOf(id)).use{check(it.moveToFirst()){ "角色已不存在" };row(it).also(budget::json)}
        val tables=JsonObject();order.forEach{table->tables.add(table,rows(db,table,id,budget))}
        // A pill whose anchor message is not in this export could never be placed after restore.
        run{
            val messageIds=tables["messages"].asJsonArray.map{it.asJsonObject["id"].asLong}.toSet()
            val kept=JsonArray();tables["chat_memory_pills"].asJsonArray.forEach{r->if(r.asJsonObject["after_message_id"].asLong in messageIds)kept.add(r)}
            tables.add("chat_memory_pills",kept)
        }
        tables["chat_turns"].asJsonArray.forEach{v->v.asJsonObject.apply{addProperty("owner_session","");if(get("state").asString=="PENDING"){addProperty("state","INTERRUPTED");addProperty("error_code","INTERRUPTED")}}}
        for(table in listOf("memory_batches"))tables[table].asJsonArray.forEach{v->v.asJsonObject.apply{
            if(has("owner_session"))addProperty("owner_session","")
            if(has("next_retry"))addProperty("next_retry",0)
            if(get("state").asString !in setOf("COMMITTED","COMPLETED")){addProperty("state","ARCHIVED");addProperty("error","MANUAL_RETRY")}
        }}
        for(table in companionTables)tables[table]?.asJsonArray?.forEach{v->v.asJsonObject.apply{
            if(has("owner"))addProperty("owner","")
            if(has("claim_owner"))addProperty("claim_owner","")
            if(table=="companion_state"){addProperty("policy","awaiting_user_resume");addProperty("epoch",get("epoch").asLong+1);addProperty("policy_version",get("policy_version").asLong+1)}
            if(table=="companion_opportunities"&&get("state").asString in setOf("queued","running"))addProperty("state","cancelled")
            if(table=="companion_receipts"&&get("delivery").asString in setOf("ready","claimed"))addProperty("delivery","unknown")
        }}
        val turns=tables["chat_turns"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet();val batches=tables["memory_batches"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
        val memoryIds=tables["memory_current"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
        val preferences=JsonObject();prefs(db).filterKeys{roleKey(it,id,turns,batches)}.forEach{(k,raw)->
            val v=if(k.startsWith(ChatToolJournal.PREFIX))ChatToolJournal.forBackup(raw,memoryIds,tables["messages"].asJsonArray.map{it.asJsonObject["id"].asLong}.toSet()) else raw
            // Retired example records remain opaque history; there is no picker to resume.
            budget.json(JsonPrimitive(v));preferences.addProperty(k,v)
        }
        val assets=JsonArray();db.rawQuery("SELECT path,kind,mime,length(data) FROM role_assets WHERE friend_id=? ORDER BY path",arrayOf(id)).use{c->while(c.moveToNext()){
            valid();val path=c.getString(0);val size=c.getInt(3);budget.asset(size);require(files.size<BackupArchive.MAX_ENTRIES-2){"备份资源数量超过安全上限"};val name="assets/${files.size}.bin";files[name]=BackupAssetSource(size.toLong()){RoleAssets.stream(db,id,path,size,valid)}
            assets.add(JsonObject().apply{addProperty("path",c.getString(0));addProperty("kind",c.getString(1));addProperty("mime",c.getString(2));addProperty("entry",name)})
        }}
        return JsonObject().apply{add("character",friend);add("tables",tables);add("preferences",preferences);add("assets",assets)}
    }
    /** Keys are only ever exported sealed with the user's backup password. */
    suspend fun export(id:String?=null,includeKeys:Boolean=false,password:CharArray?=null):Export=withContext(Dispatchers.IO){val context=currentCoroutineContext();val valid={context.ensureActive()};read{db->
        require(id==null||!includeKeys)
        require(!includeKeys||password!=null&&password.size>=BackupKeySeal.MIN_PASSWORD){"包含密钥时需要设置至少 ${BackupKeySeal.MIN_PASSWORD} 位的备份密码"}
        val ids=if(id!=null)listOf(id)else db.rawQuery("SELECT id FROM friends ORDER BY rowid",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
        require(ids.size<=100){"一次最多备份100个角色"}
        val budget=Budget();val sources=linkedMapOf<String,BackupAssetSource>();val roles=JsonArray();ids.forEach{valid();roles.add(snapshot(db,it,sources,budget,valid))}
        val settings=JsonObject();if(id==null){
            val represented=roles.flatMap{it.asJsonObject["preferences"].asJsonObject.keySet()}.toSet()
            prefs(db).forEach{(k,v)->when{ k in globalKeys->settings.addProperty(k,v);k in represented||ignored(k)->Unit;else->error("存在尚未分类的持久设置，已停止以避免生成不完整备份") }}
        }
        val models=JsonArray();val selections=JsonObject();if(id==null){val saved=vault.backup(includeKeys);saved.first.forEach{models.add(modelJson(it,includeKeys))};saved.second.forEach{(p,v)->selections.addProperty(p.key,v)}}
        val root=JsonObject().apply{addProperty("scope",if(id==null)"all" else "role");add("roles",roles);add("preferences",settings);add("models",models);add("selections",selections);addProperty("includesKeys",includeKeys)}
        val safe=if(includeKeys)BackupKeySeal.seal(root,requireNotNull(password))else root
        val prepared=BackupArchive.snapshot(safe,SourceBackupAssets(sources),store.applicationContext.cacheDir,valid)
        try{validate(db,prepared.root,prepared.files,valid);context[Job]?.invokeOnCompletion{cause->if(cause!=null)prepared.close()};Export(prepared.root,prepared.files)}catch(e:Throwable){prepared.close();throw e}
    }}
    private fun modelJson(row:BackupModel,includeKeys:Boolean)=JsonObject().apply{add("config",Gson().toJsonTree(row.config));if(includeKeys)addProperty("key",row.secret.orEmpty())}
    private fun models(root:JsonObject)=root["models"].asJsonArray.map{v->val j=v.asJsonObject;require(j.keySet()==if(root["includesKeys"].asBoolean&&!BackupKeySeal.sealed(root))setOf("config","key")else setOf("config"));val fields=j["config"].asJsonObject.keySet();val required=setOf("id","name","providerId","baseUrl","modelId","contextTokens","outputTokens");val optional=setOf("memoryOutputTokens","thinkingMode","thinkingCustom","streamReplies");require(fields.containsAll(required)&&optional.containsAll(fields-required));val cfg=j["config"].asJsonObject.deepCopy();for((k,d) in listOf("thinkingMode" to "default","thinkingCustom" to "")){if(cfg.has(k))require(cfg.get(k).isJsonPrimitive&&cfg.get(k).asJsonPrimitive.isString){"模型配置无效"}else cfg.addProperty(k,d)}
        if (cfg.has("streamReplies")) require(cfg["streamReplies"].isJsonPrimitive && cfg["streamReplies"].asJsonPrimitive.isBoolean) { "模型回复方式无效" }
        else cfg.addProperty("streamReplies", true)
        val config=Gson().fromJson(cfg,ModelConfig::class.java)
        require(config.id.matches(Regex("[a-zA-Z0-9-]{1,80}"))&&config.name.length<=200&&config.modelId.length<=200&&config.baseUrl.length<=2000&&ConfigRules.validate(config,"validation-placeholder")==null&&ThinkingMode.entries.any{it.key==config.thinkingMode}&&config.thinkingCustom.length<=4000&&ThinkingFields.customError(config.thinkingCustom)==null){"模型配置无效"}
        val key=if(j.has("key"))j["key"].asString else null;require(key==null||key.length<=4096&&key.none{it.isWhitespace()||it.code<32}){"密钥字段格式无效"};BackupModel(config,key)
    }
    private fun validate(db:SQLiteDatabase,root:JsonObject,files:Map<String,ByteArray>,valid:()->Unit={}){
        require(root["preferences"].asJsonObject.keySet().all{it in globalKeys})
        root["preferences"].asJsonObject.entrySet().forEach{(k,v)->require(v.isJsonPrimitive&&v.asJsonPrimitive.isString);when(k){ToolboxPreferences.ORDER_KEY->ToolboxPreferences.order(v.asString);ToolboxPreferences.VIDEO_KEY->ToolboxPreferences.VideoMode.valueOf(v.asString);"compact"->require(v.asString in setOf("true","false"));ChatImages.SWITCH->require(v.asString in setOf("0","1"));ChatImages.SWITCH_REVISION,ToolboxPreferences.VIDEO_REVISION->require(v.asString.length<=80);"diagnostic-detailed",EXPORT_DETAIL_PREF->require(v.asString in setOf("0","1"));"nickname"->require(v.asString.length in 1..80);MemoryRules.KEY->require(MemoryRules.from(JSONObject(v.asString)).valid());UserPersona.KEY->UserPersona.decode(v.asString);LocalUpdateReceipt.KEY->LocalUpdateReceipt.decode(v.asString);MemoryForgetting.RECALL_DEFAULT_MIGRATED->require(v.asString=="true");UserAvatarData.KEY->require(v.asString.length<=UserAvatarData.MAX_ENCODED){"本人头像资料过大"}}}
        val configs=models(root);require(configs.map{it.config.id}.distinct().size==configs.size)
        root["selections"].asJsonObject.entrySet().forEach{(k,v)->require(ModelPurpose.entries.any{it.key==k}&&(v.asString.isEmpty()||configs.any{it.config.id==v.asString}))}
        val ids=hashSetOf<String>();val used=hashSetOf<String>();var total=0
        root["roles"].asJsonArray.forEach{v->val role=v.asJsonObject;require(role.keySet()==setOf("character","tables","preferences","assets"));val id=role["character"].asJsonObject["id"].asString;require(ids.add(id))
            val tables=role["tables"].asJsonObject
            // These tables may be absent from backups made before they existed.
            val optional=setOf("message_reasoning","memory_recall_state","chat_memory_pills","chat_images","image_observations")
            val requiredExtra=extra-optional;val keys=tables.keySet()
            require(keys.all{it in (base+requiredExtra+companionTables+optional).toSet()}&&(base+requiredExtra).all{it in keys})
            total+=base.sumOf{tables[it].asJsonArray.size()};require(total<=200000){"备份记录超过200000行"}
            val old=JsonObject().apply{addProperty("scope","records");add("character",role["character"]);add("prompts",JsonParser.parseString(role["preferences"].asJsonObject[RolePromptStore.key(id)]?.asString?:"{}"));add("records",JsonObject().apply{base.forEach{add(it,tables[it].deepCopy())}})}
            old["records"].asJsonObject["chat_turns"].asJsonArray.forEach{it.asJsonObject.addProperty("config_id","")}
            legacy.validateForBackup(db,RoleArchive.validate(old.toString().toByteArray()),tables["image_observations"]?.asJsonArray?:JsonArray())
            val turns=tables["chat_turns"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet();val batches=tables["memory_batches"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
            val messageIds=tables["messages"].asJsonArray.map{it.asJsonObject["id"].asLong}.toSet()
            val assistantIds=tables["messages"].asJsonArray.map{it.asJsonObject}.filter{it["mine"].asInt==0}.map{it["id"].asLong}.toSet()
            val deletedIds=tables["message_state"].asJsonArray.map{it.asJsonObject}.filter{it["deleted"].asInt==1}.map{it["message_id"].asLong}.toSet()
            val reasoningIds=hashSetOf<Long>()
            val memoryIds=tables["memory_current"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
            val candidateIds=tables["memory_candidates"].asJsonArray.map{it.asJsonObject["id"].asString}.toSet()
            val proposedIds=tables["memory_candidates"].asJsonArray.map{JsonParser.parseString(it.asJsonObject["json"].asString).asJsonObject["proposed"].asJsonObject["id"].asString}.toSet()
            role["preferences"].asJsonObject.entrySet().forEach{(k,value)->require(roleKey(k,id,turns,batches)&&value.isJsonPrimitive&&value.asJsonPrimitive.isString);RoleArchive.parse(("{\"value\":"+value.asString+"}").toByteArray())
                if(k.startsWith(ChatToolJournal.PREFIX))ChatToolJournal.validateBackup(value.asString,memoryIds,tables["messages"].asJsonArray.map{it.asJsonObject["id"].asLong}.toSet())}
            for(table in extra+companionTables){if(!tables.has(table))continue;val schema=db.rawQuery("PRAGMA table_info($table)",null).use{c->buildMap{while(c.moveToNext())put(c.getString(1),c.getString(2))}}
                val rows=tables[table].asJsonArray;total+=rows.size();require(total<=200000)
                rows.forEach{r->val j=r.asJsonObject;require(j.keySet().all{it in schema.keys});j.entrySet().forEach{(k,x)->if(!x.isJsonNull){if(schema[k]=="INTEGER")RoleArchive.integer(x)else if(schema[k]=="REAL")require(x.isJsonPrimitive&&x.asJsonPrimitive.isNumber&&x.asDouble.isFinite())else require(x.isJsonPrimitive&&x.asJsonPrimitive.isString)}};if(j.has("friend_id"))require(j["friend_id"].asString==id)
                    if(table in companionTables){
                        require(j["role"].asString==id)
                        for(field in listOf("source_id","turn_source","message_id"))if(j.has(field))require(j[field].asLong in messageIds)
                        if(table=="companion_state")require(j["policy"].asString in setOf("disabled","paused_by_user","awaiting_user_resume")&&j["owner"].asString.isEmpty())
                        if(table=="companion_opportunities")require(j["state"].asString !in setOf("queued","running")&&j["owner"].asString.isEmpty())
                        if(table=="companion_action_learning"){val use=ProactiveLearningUse.decode(j["considered"].asString,j["used"].asString);require(JsonParser.parseString(j["considered"].asString).asJsonArray.size()==use.first.size&&JsonParser.parseString(j["used"].asString).asJsonArray.size()==use.second.size){"主动交流学习引用无效"}}
                        if(table=="companion_sources"){val proposal=Gson().fromJson(j["proposal"].asString,ExperienceProposal::class.java);require(proposal.targets.all{it in messageIds});proposal.relationInput?.let{input->
                            require(RelationInputContract.validStored(input)&&input.source==SourceRef(id,j["source_id"].asLong,j["revision"].asLong)&&listOfNotNull(input.previous,input.fulfills,input.explains,input.repairs,input.repairBasis).all{it.turnId in messageIds}){"关系输入来源或选项无效"}
                        }}
                    }
                    if(table in setOf("memory_operations","memory_batch_reviews"))require(j["batch_id"].asString in batches){"批次引用不属于包内角色"}
                    if(table=="memory_operations"&&!j["result_id"].isJsonNull)require(j["result_id"].asString in memoryIds+proposedIds){"操作结果引用不属于包内角色"}
                    if(table=="memory_batch_reviews")require(j["memory_id"].asString in memoryIds){"记忆审阅引用不属于包内角色"}
                    if(table=="candidate_injections")require(j["candidate_id"].asString in candidateIds&&j["user_message_id"].asLong in messageIds){"候选引用不属于包内角色"}
                    if(table=="message_reasoning"){
                        val messageId=RoleArchive.integer(j["message_id"])
                        require(messageId in assistantIds&&messageId !in deletedIds&&reasoningIds.add(messageId)){"模型思考不属于包内有效助手消息"}
                        RoleArchive.text(j,"display_reasoning",256*1024,blank=true)
                    }
                    if(table=="memory_batches")require(j["state"].asString in setOf("COMMITTED","ARCHIVED")&&j["next_retry"].asLong==0L)
                }
            }
            valid();validateImages(role,files,valid)
            val paths=hashSetOf<String>();role["assets"].asJsonArray.forEach{a->val j=a.asJsonObject;require(j.keySet()==setOf("path","kind","mime","entry"));val path=RoleArchive.entryName(j["path"].asString);require(path.length<=240&&paths.add(path)&&j["kind"].asString in setOf("avatar","attachment")&&j["mime"].asString.length in 1..100);val entry=j["entry"].asString;require(used.add(entry)&&files.containsKey(entry)&&BackupAssetIO.length(files,entry)<=BackupArchive.MAX_FILE)}
        }
        require(used==files.keys){"资源清单不完整"}
    }
    private fun validateImages(role:JsonObject,files:Map<String,ByteArray>,valid:()->Unit){
        val tables=role["tables"].asJsonObject;val friend=role["character"].asJsonObject["id"].asString
        val messages=tables["messages"].asJsonArray.map{it.asJsonObject}.associateBy{it["id"].asLong}
        val images=(tables["chat_images"]?.asJsonArray?:JsonArray()).map{it.asJsonObject}
        require(images.map{it["message_id"].asLong}.distinct().size==images.size)
        val assets=role["assets"].asJsonArray.map{it.asJsonObject}.associateBy{it["path"].asString}
        for(image in images){
            val fields=setOf("friend_id","message_id","path","mime","hash","width","height","revision")
            require(image.keySet()==fields||image.keySet()==fields+"storage_kind")
            val storage=image["storage_kind"]?.asString?:"legacy-normalized"
            require(storage in setOf("original","legacy-normalized","chunked-original"))
            require(image["friend_id"].asString==friend&&messages[image["message_id"].asLong]?.get("mine")?.asInt==1)
            val asset=assets[image["path"].asString]?:error("图片缺少对应资源")
            val entry=asset["entry"].asString;require(entry in files){"图片文件不在备份中"}
            val video=VideoPolicy.isVideo(image["mime"].asString)
            require(asset["kind"].asString=="attachment"&&(video||image["mime"].asString in ImagePolicy.mimes))
            require(!video||storage=="chunked-original")
            require(image["hash"].asString.matches(Regex("[a-f0-9]{64}"))&&image["revision"].asLong>0)
            valid();val temporary=if(files is ZipBackupAssets)files.temporary()else java.io.File.createTempFile("backup-image-",".image",ImageDisk.root())
            try{
                val source=if(storage=="chunked-original"){
                    require(asset["mime"].asString==OriginalChunks.MIME)
                    val bytes=BackupAssetIO.open(files,entry).use{BackupArchive.bounded(it,1024*1024)}
                    val manifest=OriginalChunks.parse(bytes,image["path"].asString);require(manifest.hash==image["hash"].asString)
                    OriginalChunks.extract(manifest,temporary){part->valid();val chunk=assets[part.path]?:error("备份缺少原件资源块");require(chunk["kind"].asString=="attachment"&&chunk["mime"].asString=="application/octet-stream");val key=chunk["entry"].asString;require(key in files&&BackupAssetIO.length(files,key)==part.size.toLong());BackupAssetIO.open(files,key).use{BackupArchive.bounded(it,OriginalChunks.CHUNK)}};ImageSource(temporary)
                }else{
                    require(BackupAssetIO.length(files,entry) in 1..BackupArchive.MAX_FILE.toLong()&&asset["mime"].asString==image["mime"].asString)
                    BackupAssetIO.open(files,entry).use{input->temporary.outputStream().use{output->
                        val checked=object:java.io.FilterOutputStream(output){override fun write(b:ByteArray,off:Int,len:Int){ImageDisk.space(temporary.parentFile!!,len.toLong());out.write(b,off,len)}}
                        BackupAssetIO.copy(input,checked,BackupAssetIO.length(files,entry),valid)
                    }};ImageSource(temporary)
                }
                require(source.hash()==image["hash"].asString){"原件哈希不符"}
                if(video){
                    val info=ChatVideos.inspect(requireNotNull(source.file))
                    require(info.displayWidth==image["width"].asInt&&info.displayHeight==image["height"].asInt&&info.mime==image["mime"].asString){"视频尺寸与记录不一致"}
                    ChatVideos.thumbnail(source).recycle()
                }else{
                    val info=ChatImages.inspect(source);require(info.width==image["width"].asInt&&info.height==image["height"].asInt&&info.mime==image["mime"].asString){"图片尺寸与记录不一致"}
                    ImagePixels.preview(source,512).recycle()
                    if(storage=="legacy-normalized"){require(source.size<=512*1024);val bytes=source.bytes();require(info.mime=="image/jpeg"&&info.width<=1600&&info.height<=1600&&UserAvatarData.withoutMetadata(bytes).contentEquals(bytes)){"旧版图片副本记录无效"}}
                }
            }finally{temporary?.delete()}

        }
        val turns=tables["chat_turns"].asJsonArray.map{it.asJsonObject}.associateBy{it["user_message_id"].asLong}
        val imageMap=images.associateBy{it["message_id"].asLong}
        val observationIds=hashSetOf<String>()
        for(value in tables["image_observations"]?.asJsonArray?:JsonArray()){
            val row=value.asJsonObject
            require(row.keySet()==setOf("id","friend_id","message_id","question_message_id","question_revision","source_revision","image_hash","model_id","body","created"))
            require(row["id"].asString.matches(Regex("[a-zA-Z0-9-]{1,80}"))&&observationIds.add(row["id"].asString)&&row["friend_id"].asString==friend)
            val image=imageMap[row["message_id"].asLong]?:error("观察缺少图片来源")
            require(row["image_hash"].asString==image["hash"].asString&&row["source_revision"].asLong>0&&messages[row["question_message_id"].asLong]?.get("mine")?.asInt==1)
            RoleArchive.text(row,"body",ChatImages.MAX_OBSERVATION_CHARS);RoleArchive.text(row,"model_id",80);require(row["created"].asLong>0)
            require(row["source_revision"].asLong in 1..(turns[row["message_id"].asLong]?.get("source_revision")?.asLong?:0)&&row["question_revision"].asLong in 1..(turns[row["question_message_id"].asLong]?.get("source_revision")?.asLong?:0)){"图片观察引用了不存在或未来版本的轮次"}
        }
    }
    private fun fingerprint(db:SQLiteDatabase,valid:()->Unit={}):String {
        val hash=java.security.MessageDigest.getInstance("SHA-256")
        fun add(value:String){val bytes=value.toByteArray(Charsets.UTF_8);hash.update((bytes.size.toString()+":").toByteArray());hash.update(bytes)}
        for(table in listOf("friends")+order+listOf("preferences")){
            val filter=if(table=="preferences")" WHERE key NOT LIKE 'relationship-cache:%'"else ""
            add(table);db.rawQuery("SELECT * FROM $table$filter ORDER BY rowid",null).use{c->while(c.moveToNext())add(row(c).toString())}
        }
        db.rawQuery("SELECT friend_id,path,kind,mime,length(data) FROM role_assets ORDER BY friend_id,path",null).use{c->while(c.moveToNext()){
            add(c.getString(0));add(c.getString(1));add(c.getString(2));add(c.getString(3));add(RoleAssets.stream(db,c.getString(0),c.getString(1),c.getInt(4),valid).use{BackupAssetIO.digest(it,valid)})
        }}
        add(vault.stamp());return hash.digest().joinToString(""){"%02x".format(it)}
    }
    suspend fun preview(pack:BackupArchive.Package,mode:String,target:String?=null):Preview=withContext(Dispatchers.IO){val context=currentCoroutineContext();val valid={context.ensureActive()};read{db->
        require(mode in setOf("ADD","REPLACE"));require(mode!="REPLACE"||pack.all||target!=null){"请选择要覆盖的角色"}
        require(pack.legacy==null){"请通过旧格式兼容入口导入"}
        val safe=pack.copy(root=pack.root.deepCopy());val relationReport=RelationshipBackup.sanitize(safe.root)
        LocalStep.PREVIEW_VALIDATE.observe { validate(db,safe.root,safe.files,valid) }
        if(mode=="REPLACE"&&target!=null)require(db.rawQuery("SELECT 1 FROM friends WHERE id=?",arrayOf(target)).use{it.moveToFirst()})
        val impact=if(mode=="ADD")"新增 ${pack.roles.size()} 个独立角色及全部包内角色资料；保留当前角色、设置、模型服务和密钥，不启用包内凭据。"else if(pack.all)"替换软件全部角色、聊天、记忆、关系、资源、设置和模型配置。${if(pack.includesKeys&&BackupKeySeal.sealed(pack.root))"备份里的 API 密钥用备份密码加了密，覆盖时需要输入导出时设的密码。"else if(pack.includesKeys)"备份含可直接使用的 API 密钥，请勿分享。"else "备份没有密钥：仅按原服务身份和原端点匹配保留，不能匹配的原安全配置会另行保留，不自动选择。"}不会恢复在途任务或自动请求模型。"else "替换明确选中的角色全部资料和资源，其他角色及全局模型配置保留。"
        val hashes=assetHashes(safe.files,valid);Preview(safe,mode,target,fingerprint(db,valid),contentDigest(safe,hashes),impact,relationReport,hashes)
    }}
    private fun assetHashes(files:Map<String,ByteArray>,valid:()->Unit)=files.keys.associateWith{BackupAssetIO.hash(files,it,valid)}
    private fun contentDigest(pack:BackupArchive.Package,hashes:Map<String,String>):String {
        val hash=java.security.MessageDigest.getInstance("SHA-256");hash.update(pack.root.toString().toByteArray(Charsets.UTF_8))
        hashes.toSortedMap().forEach{(name,value)->hash.update(name.toByteArray(Charsets.UTF_8));hash.update(0.toByte());hash.update(value.toByteArray(Charsets.US_ASCII))}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    private fun cv(row:JsonObject)=ContentValues().apply{row.entrySet().forEach{(k,v)->when{v.isJsonNull->putNull(k);v.asJsonPrimitive.isNumber->if(k in setOf("half_life_days","anchor_clarity"))put(k,v.asDouble)else put(k,RoleArchive.integer(v));else->put(k,v.asString)}}}
    suspend fun commit(plan:Preview,confirmed:Boolean=false,lease:ChatAttemptLease=ChatAttemptLease(),checkpoint:()->Unit={},password:CharArray?=null):List<String> =withContext(Dispatchers.IO){
        val pack=plan.pack.copy(root=plan.pack.root.deepCopy())
        val valid={check(lease.isActive()){ "导入已取消" };checkpoint()}
        // Only a full replace puts the package's keys to use, so only then is the backup password needed.
        val keysRoot=if(plan.mode=="REPLACE"&&pack.all&&BackupKeySeal.sealed(pack.root)) BackupKeySeal.open(pack.root,password) else pack.root
        val action={
        require(plan.mode!="REPLACE"||confirmed){"覆盖需要明确确认"};if(plan.mode=="REPLACE"){if(pack.all)store.memorySummary.requireAllEditable()else store.memorySummary.requireEditable(requireNotNull(plan.target))};val db=store.writableDatabase;var staged:ModelVault.Prepared?=null
        db.beginTransaction();var committed=false
        try {
            check(lease.isActive()){ "导入已取消" };LocalStep.RECHECK.observe { require(contentDigest(pack,assetHashes(pack.files,valid))==plan.contentDigest){"备份内容已变化，请重新预览"};require(fingerprint(db,valid)==plan.fingerprint){"资料或配置已变化，请重新预览"};validate(db,pack.root,pack.files,valid) }
            val pages=db.rawQuery("PRAGMA page_count",null).use{it.moveToFirst();it.getLong(0)}
            val pageSize=db.rawQuery("PRAGMA page_size",null).use{it.moveToFirst();it.getLong(0)}
            val resources=BackupAssetIO.total(pack.files);val metadata=pack.root.toString().toByteArray(Charsets.UTF_8).size.toLong()
            val needed=Math.addExact(Math.multiplyExact(resources,2),Math.addExact(metadata*4,if(plan.mode=="REPLACE")Math.multiplyExact(pages,pageSize)else 0))
            LocalStep.CHECK_SPACE.observe { ImageDisk.space(store.applicationContext.filesDir,needed,availableSpace(store.applicationContext.filesDir)) };valid()
            val restoredAvatar=if(plan.mode=="REPLACE"&&pack.all)pack.root["preferences"].asJsonObject[UserAvatarData.KEY]?.asString?.let(UserAvatarData::forRestore)else null
            if(plan.mode=="REPLACE"&&pack.all){staged=LocalStep.STAGE_MODELS.observe { vault.prepareRestore(models(keysRoot),pack.root["selections"].asJsonObject.entrySet().associate{(k,v)->ModelPurpose.entries.first{it.key==k} to v.asString},pack.includesKeys) };clearAll(db);checkpoint()}
            val imported=LocalStep.RESTORE_ROWS.observe { pack.roles.map{v->val old=v.asJsonObject;val target=if(plan.mode=="REPLACE"&&!pack.all)plan.target!! else UUID.randomUUID().toString();if(plan.mode=="REPLACE"&&!pack.all)clearRole(db,target);restore(db,old,target,pack.files,valid,plan.assetDigests);target} }
            if(plan.mode=="REPLACE"&&pack.all){pack.root["preferences"].asJsonObject.entrySet().forEach{(k,v)->if(k==UserAvatarData.KEY){restoredAvatar?.let{pref(db,k,it)}}else pref(db,k,v.asString)};pref(db,vault.generationKey,staged!!.generation)
                // This database already has the current schema; an older backup without the marker must not reopen proactive recall later.
                pref(db,MemoryForgetting.RECALL_DEFAULT_MIGRATED,"true")}
            checkpoint();LocalStep.COMMIT.observe { check(lease.commit{db.setTransactionSuccessful();true}){ "导入已取消" };db.endTransaction() };committed=true
            staged?.let{ LocalStep.FINISH_MODELS.observe { vault.finishRestore(it) } }
            imported
        }finally{
            try{if(db.inTransaction())db.endTransaction()}finally{
                // A failed/uncertain SQLite commit must never delete the generation it may have published.
                if(!committed)staged?.let{if(vault.isActive(it)==false)vault.abort(it)}
            }
        }
    };if(plan.mode=="REPLACE"&&pack.all)AppMutationGate.write(action)else AppMutationGate.read(action)}
    suspend fun previewRoleDeletion(id:String):RoleDeletion=read{db->
        val name=db.rawQuery("SELECT name FROM friends WHERE id=?",arrayOf(id)).use{require(it.moveToFirst()){"角色已不存在，请返回列表。"};it.getString(0)}
        RoleDeletion(id,name,repo.characterStamp(db,id))
    }
    suspend fun deleteRole(plan:RoleDeletion,confirmed:Boolean,lease:ChatAttemptLease=ChatAttemptLease(),checkpoint:()->Unit={})=withContext(Dispatchers.IO){
        require(confirmed){"删除角色需要再次确认"}
        // Keep other characters' in-flight config epochs valid; serialize only the local transaction.
        AppMutationGate.read{
            val db=store.writableDatabase;db.beginTransaction()
            try {
                store.memorySummary.requireEditable(plan.friendId)
                check(lease.isActive()){"删除已取消"}
                require(repo.characterStamp(db,plan.friendId)==plan.characterStamp){"角色资料已变化，请重新确认。"}
                clearRole(db,plan.friendId);checkpoint()
                check(lease.commit{db.setTransactionSuccessful();true}){"删除已取消"}
            }finally{db.endTransaction()}
        }
    }
    private fun clearRole(db:SQLiteDatabase,id:String){
        ImageDisk.clearReadCache()
        fun ids(table:String)=db.rawQuery("SELECT id FROM $table WHERE friend_id=?",arrayOf(id)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
        val turnIds=ids("chat_turns");val batches=ids("memory_batches")
        prefs(db).keys.filter{key->roleKey(key,id,turnIds,batches)||key=="relationship-cache:$id"||key==RelationFeeling.key(id)||key=="memory-vectors-v1:$id"||key=="memory-batch-failure:$id"||key=="memory-core-set:$id"||turnIds.any{key.startsWith("memory-tools:$it:")||key==ChatLedger.errorKey(it)||key==ChatLedger.errorMetaKey(it)||key=="chat_tool_display:$it"}}.forEach{db.delete("preferences","key=?",arrayOf(it))}
        companionTables.reversed().forEach{db.delete(it,"role=?",arrayOf(id))}
        for(table in (extra+base).reversed().distinct()){
            val predicate=when(table){
            "message_state","message_history","message_reasoning"->"message_id IN (SELECT id FROM messages WHERE friend_id=?)";"memory_history"->"memory_id IN (SELECT id FROM memory_current WHERE friend_id=?)";"memory_operations","memory_batch_reviews"->"batch_id IN (SELECT id FROM memory_batches WHERE friend_id=?)";else->"friend_id=?"}
            db.delete(table,predicate,arrayOf(id))
        }
        db.delete("role_assets","friend_id=?",arrayOf(id))
        db.delete("friends","id=?",arrayOf(id))
    }
    private fun clearAll(db:SQLiteDatabase){
        ImageDisk.clearReadCache()
        // Foreign key order also removes in-flight rows; none are imported or restarted.
        (companionTables.reversed()+listOf("image_observations","chat_images","memory_recall_state","memory_operations","memory_batch_reviews","candidate_injections","message_reasoning","message_state","message_history","memory_history","memory_current","memory_candidates","memory_retired","memory_progress","memory_batches","processing_policies","chat_memory_pills","role_assets","chat_turns","messages","friends","preferences")).forEach{db.delete(it,null,null)}
    }
    private fun restore(db:SQLiteDatabase,role:JsonObject,id:String,files:Map<String,ByteArray>,checkpoint:()->Unit,hashes:Map<String,String>){
        val oldId=role["character"].asJsonObject["id"].asString;val tables=role["tables"].asJsonObject
        val strings=linkedMapOf<String,String>();listOf("memory_current","memory_candidates","chat_turns","memory_batches").forEach{table->tables[table].asJsonArray.forEach{strings.putIfAbsent(it.asJsonObject["id"].asString,UUID.randomUUID().toString())}}
        tables["memory_candidates"].asJsonArray.forEach{val proposed=JsonParser.parseString(it.asJsonObject["json"].asString).asJsonObject["proposed"].asJsonObject["id"].asString;strings.putIfAbsent(proposed,UUID.randomUUID().toString())}
        tables["memory_retired"].asJsonArray.forEach{strings.putIfAbsent(it.asJsonObject["batch_id"].asString,UUID.randomUUID().toString())}
        var next=db.rawQuery("SELECT COALESCE(MAX(seq),0) FROM sqlite_sequence WHERE name='messages'",null).use{it.moveToFirst();it.getLong(0)}
        val messages=tables["messages"].asJsonArray.associate{it.asJsonObject["id"].asLong to ++next}
        tables["companion_opportunities"]?.asJsonArray?.forEach{strings[it.asJsonObject["id"].asString]=UUID.randomUUID().toString()}
        tables["image_observations"]?.asJsonArray?.forEach{strings[it.asJsonObject["id"].asString]=UUID.randomUUID().toString()}
        val map=BackupRemapper(oldId,id,strings,messages)
        db.insertOrThrow("friends",null,cv(map.json(role["character"]).asJsonObject.apply{addProperty("id",id)}))
        role["assets"].asJsonArray.forEach{v->checkpoint();val a=v.asJsonObject;val key=a["entry"].asString;BackupAssetIO.open(files,key).use{RoleAssets.restoreStream(db,id,a["path"].asString,a["kind"].asString,a["mime"].asString,it,BackupAssetIO.length(files,key),hashes.getValue(key),checkpoint)}}
        for(table in order){for(value in (tables[table]?.asJsonArray?:JsonArray())){checkpoint();val row=map.json(value).asJsonObject
            if(table=="processing_policies"){val p=ProcessingPolicy.from(JSONObject(row["json"].asString));row.addProperty("json",p.json().toString())}
            db.insertOrThrow(table,null,cv(row))
        }}
        role["preferences"].asJsonObject.entrySet().forEach{(key,value)->pref(db,map.preferenceKey(key),if(key.startsWith(ChatToolJournal.PREFIX))map.toolDisplayValue(value.asString) else map.preferenceValue(value.asString))}
        CompanionRepository(store).relationship(db,id)
    }
    private fun pref(db:SQLiteDatabase,key:String,value:String){db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    private suspend fun <T> read(block:(SQLiteDatabase)->T):T=withContext(Dispatchers.IO){AppMutationGate.read{val db=store.readableDatabase;db.beginTransaction();try{block(db)}finally{db.endTransaction()}}}
}

internal class BackupRemapper(private val old:String,private val id:String,private val strings:Map<String,String>,private val messages:Map<Long,Long>){
    private val numeric=setOf("message_id","question_message_id","source_user_id","user_message_id","assistant_message_id","after_message_id","user_id","assistant_id","turn","turnId","cursor","boundary","source_id","turn_source","targets","messageId","actionMessage","replyId","afterMessage")
    private val ids=setOf("id","request","task","task_id","batch_id","batch","candidate_id","target_id","memory_id","result_id","source","target","sourceId","targetId","observation_id","memoryIds","retainedIds","opportunity_id")
    fun json(v:JsonElement,key:String=""):JsonElement=when{
        v.isJsonObject->JsonObject().apply{v.asJsonObject.entrySet().forEach{(k,x)->add(if(key in setOf("associationVersions","existingVersions","resultVersions"))strings[k]?:error("记忆版本引用缺失") else k,json(x,k))}}
        v.isJsonArray->JsonArray().apply{v.asJsonArray.forEach{add(json(it,key))}}
        v.isJsonNull->JsonNull.INSTANCE
        v.asJsonPrimitive.isNumber&&(key in numeric||key=="id")->JsonPrimitive(if(v.asLong==0L)0L else messages[v.asLong]?:error("原话编号无法映射"))
        v.asJsonPrimitive.isString->{val s=v.asString;when{key in setOf("json","proposal")->JsonPrimitive(json(JsonParser.parseString(s)).toString());key in setOf("friend_id","friendId","character","character_id","characterId","conversation","role")&&s==old->JsonPrimitive(id);key in ids->JsonPrimitive(if(key=="id"&&s==old)id else strings[s]?:s);key=="owner_session"->JsonPrimitive("");else->v.deepCopy()}}
        else->v.deepCopy()
    }
    fun preferenceKey(key:String):String {
        if(key.startsWith(ReplyAccountingQueue.READ_PREFIX))return ReplyAccountingQueue.readsKey(strings[key.removePrefix(ReplyAccountingQueue.READ_PREFIX)]?:error("轮次引用缺失"))
        if(key==ReplyAccountingQueue.batchKey(old))return ReplyAccountingQueue.batchKey(id)
        if(key.startsWith(ReplyAccountingQueue.PREFIX))return ReplyAccountingQueue.key(strings[key.removePrefix(ReplyAccountingQueue.PREFIX)]?:error("轮次引用缺失"))
        listOf("role-prompts:","role-examples:","memory-associations-v1:","relationship-history:").forEach{if(key==it+old)return it+id}
        if(key.startsWith("memory-reference-v1:$old:")){val n=key.substringAfterLast(':').toLong();return "memory-reference-v1:$id:${messages[n]?:error("原话引用缺失")}"}
        if(key.startsWith("memory-recall-usage:"))return "memory-recall-usage:"+(strings[key.removePrefix("memory-recall-usage:")]?:error("轮次引用缺失"))
        if(key.startsWith("memory-dropped:"))return "memory-dropped:"+(strings[key.removePrefix("memory-dropped:")]?:error("批次引用缺失"))
        if(key.startsWith(OrderedChatReply.PREFIX))return OrderedChatReply.PREFIX+(strings[key.removePrefix(OrderedChatReply.PREFIX)]?:error("轮次引用缺失"))
        if(key.startsWith(ChatToolJournal.PREFIX))return ChatToolJournal.key(strings[key.removePrefix(ChatToolJournal.PREFIX)]?:error("轮次引用缺失"))
        error("不支持的角色设置")
    }
    fun toolDisplayValue(raw:String)=runCatching{ChatToolJournal.remapBackup(raw,strings,messages)}.getOrElse{error("角色设置格式无效")}
    fun preferenceValue(raw:String)=runCatching{json(JsonParser.parseString(raw)).toString()}.getOrElse{error("角色设置格式无效")}
}
