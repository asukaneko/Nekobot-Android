package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Adapts our optional recall tools and FusedMemoryWriter to Lianxu's source/version transactions. */
internal class MemoryTools(private val repo:ProcessingRepository,private val turn:ChatTurn,private val input:String,
    private val initial:ProcessingRepository.References,private val companionIndex:CompanionVectorIndex?=null,private val allowWrite:Boolean=true,private val memoryVectors:MemoryVectorIndex?=null,private val queryCache:QueryEmbeddingCache?=null,private val relationEnabled:Boolean=false,private val controlTool:(suspend(JSONObject)->Map<String,Any>)?=null,private val recordTool:(suspend(String)->Map<String,Any>)?=null,private val freshControlContext:(suspend()->Pair<TextChatRequest,ProcessingRepository.References>)?=null,private val recordNeedsExperience:Boolean=false,private val contactTool:(suspend(String)->Map<String,Any>)?=null,private val contactTime:Long=0,private val contactZone:java.time.ZoneId=java.time.ZoneId.systemDefault(),private val contactScheduling:Boolean=true,private val voiceEligibility:()->VoiceEligibility={VoiceEligibility(reason=VoiceReason.SERVICE_NOT_READY)},private val active:()->Boolean) {
    private var contactAttempts=0
    private var controlAttempts=0
    private var recordAttempts=0
    /** Staged memory capsules whose candidates a later control in the same turn discards. */
    private val stagedMemory=mutableMapOf<String,ChatToolActivity>()
    val relationSeen=mutableSetOf<Long>()
    private val relationQueries=mutableSetOf<String>()
    private var relationCalls=0
    private val recallAt=initial.at
    private val recalled=linkedMapOf<String,RecallSource>()
    private val deliveredMemories=mutableSetOf<String>()
    private val clarities=mutableMapOf<String,Double>()
    private val recallContent=RecallContentCapture(repo,turn.friendId)
    private fun remembered(db:SQLiteDatabase,m:StoredMemory):String {
        val clarity=clarities.getOrPut(m.id){MemoryForgetting.state(db,m,recallAt).clarity(recallAt)}
        return MemoryForgetting.text(m,clarity){repo.describe(m)}
    }
    private suspend fun searchRelation(args:JSONObject):Map<String,Any> {
        if(!relationQueries.add(canonicalJson(args)))return mapOf("status" to "duplicate_query")
        if(relationCalls>=RelationshipRecall.LIMIT){Diagnostics.event("relation.recall.quota",mapOf("count" to relationCalls));return mapOf("status" to "quota_exhausted")}
        relationCalls++
        return try{repo.read{db->
            val repository=CompanionRepository(repo.store);val view=repository.relationship(db,turn.friendId)
            val now=AppServices.companionClock();val terms=recallSearchTerms(args.optString("query"));val kind=args.optString("kind","全部")
            val min=args.optLong("days_ago_min",0).coerceAtLeast(0);val max=args.optLong("days_ago_max",RelationshipRecall.SEARCH_DAYS).coerceAtLeast(min)

            val content=RelationshipRecall.resolver(repo,db,turn.friendId,now)
            val events=mutableListOf<Map<String,Any>>()
            for(row in view.inputs.sortedByDescending{it.at}){

                if(!RelationshipRecall.matches(row,view.state,kind))continue
                if((now-row.at)/RelationshipProjector.DAY !in min..max||!repository.refCurrent(db,row.source)||!CompanionRepository.aiAllowed(db,turn.friendId,row.source.turnId))continue
                val text=db.rawQuery("SELECT body FROM messages WHERE friend_id=? AND id=?",arrayOf(turn.friendId,row.source.turnId.toString())).use{if(it.moveToFirst())it.getString(0)else ""}
                if(terms.isNotEmpty()&&terms.none{it in text.lowercase()})continue
                val rememberedContent=content(row.source);if(rememberedContent.isBlank())continue
                val event=RelationshipRecall.event(row,view.state,rememberedContent)
                if(gson.toJson(events+listOf(event)).length>RelationshipRecall.SEARCH_CHARS)break
                events.add(event)
                if(events.size>=args.optInt("limit",RelationshipRecall.SEARCH_LIMIT).coerceIn(1,RelationshipRecall.SEARCH_LIMIT))break
            }
            mapOf("status" to "ok","events" to events,"note" to "只列仍记着的事；没列出的不要假装记得")
        }}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Diagnostics.event("relation.recall.failed",mapOf("code" to e.javaClass.simpleName));mapOf("status" to "error")}
    }
    internal var completedReads:Int=0;private set
    private data class Target(val id:String,val version:Int,val candidate:String?=null)
    private val targets=linkedMapOf<String,Target>()
    private val gson=Gson()
    private var writeEvidence=emptyMap<String,MemoryEvidence>()
    private var writeSourceAllowed=true
    private fun validWriteEvidence(db:SQLiteDatabase):List<MemoryEvidence> {
        rejectUnless(CompanionRepository.aiAllowed(db,turn.friendId,turn.userMessageId),"SOURCE_CHANGED")
        val current=db.rawQuery("SELECT t.source_revision,t.source_zone,m.created,m.body FROM chat_turns t JOIN messages m ON m.id=t.user_message_id LEFT JOIN message_state s ON s.message_id=m.id WHERE t.id=? AND t.friend_id=? AND t.user_message_id=? AND m.mine=1 AND COALESCE(s.deleted,0)=0",arrayOf(turn.id,turn.friendId,turn.userMessageId.toString())).use{c->
            rejectUnless(c.moveToFirst()&&c.getString(3)==input,"SOURCE_CHANGED")
            MemoryEvidence(SourceRef(turn.friendId,turn.userMessageId,c.getLong(0)),"user",input,input,c.getLong(2),c.getString(1))
        }
        return (listOf(current)+repo.recentSources(db,turn.friendId).map{s->MemoryEvidence(s.source,"user",s.user.text,s.user.text,s.user.created,s.zone)}).distinctBy{it.source}
    }
    private val stageKey="memory-tools:${turn.id}:${turn.attempt}"
    private val usageKey="memory-recall-usage:${turn.id}"
    private fun pref(key:String)=repo.store.readableDatabase.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun savePref(key:String,value:String){repo.store.writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    private val rulesState=repo.memoryRules(repo.store.readableDatabase)
    private val rules=rulesState.first
    private fun authorized(write:Boolean=false)=active()&&repo.memoryRules(repo.store.readableDatabase)==rulesState&&(if(write)rules.immediateWrite&&allowWrite else rules.proactiveRecall)
    private val budget=CompanionRecallBudget({CompanionRecallSettings(rules.recallRounds,rules.recallTokens,rules.recallPerCall)}, {pref(usageKey)}, {savePref(usageKey,it)},adaptive=true)
    private val reader=object:AgentRecallReader {
        override suspend fun isAvailable(sessionId:String)=sessionId==turn.friendId&&authorized()
        override suspend fun searchRecall(sessionId:String,terms:List<String>,limit:Int):List<AgentRecallCandidate>{
            val scores=memoryVectors?.scores(sessionId,terms.firstOrNull().orEmpty(),queryCache).orEmpty()
            return (repo.read{db->
            repo.usableMemories(db,sessionId).filter{m->terms.any{it in (m.text+" "+m.tags.joinToString(" ")+" "+m.eventDate.orEmpty()).lowercase()}||(scores["${m.id}@${m.version}"]?:0.0)>=AdaptiveContext.MIN_RELEVANCE}
                .map{m->val body=remembered(db,m);AgentRecallCandidate(m.id,"companion_memory",if(clarities.getValue(m.id)>60)m.category.label else "",body,if(clarities.getValue(m.id)>60)m.tags else emptyList(),sessionId,startMessageId=if(clarities.getValue(m.id)>60)m.evidence.firstOrNull()?.source?.turnId?.toString()else null,startedAt=if(clarities.getValue(m.id)>60)m.eventDate else null,current=true,vectorScore=(scores["${m.id}@${m.version}"]?:0.0).toFloat(),displayVersion=m.version,displayMode=if(clarities.getValue(m.id)>60)"full"else if(clarities.getValue(m.id)>=20)"gist"else"trace")}
                .filter{it.content.isNotBlank()}
                .sortedWith(compareByDescending<AgentRecallCandidate>{recallScore(it,terms)}.thenBy{it.id}).take(limit)
        }+(companionIndex?.search(sessionId,terms.firstOrNull().orEmpty(),limit,queryCache)?:CompanionRepository(repo.store).recall(sessionId,terms,limit))).sortedWith(compareByDescending<AgentRecallCandidate>{recallScore(it,terms)}.thenBy{it.id}).take(limit)
        }
        override suspend fun searchHistory(sessionId:String,terms:List<String>,limit:Int)=repo.read{db->recallReadSnapshot(db){
            val versions=db.rawQuery("SELECT m.id,COALESCE(s.revision,1) FROM messages m LEFT JOIN message_state s ON s.message_id=m.id WHERE m.friend_id=?",arrayOf(sessionId)).use{c->buildMap{while(c.moveToNext())put(c.getLong(0),c.getInt(1))}}
            repo.sources(db,sessionId).flatMap{it.messages}
                .filter{m->terms.any{it in m.text.lowercase()}}.sortedWith(compareByDescending<Message>{m->terms.sumOf{if(it in m.text.lowercase())it.length.coerceAtMost(8) else 0}}.thenByDescending{it.created})
                .take(limit).map{m->history(m).copy(displayVersion=versions[m.id])}
        }}
        override suspend fun readHistory(sessionId:String,anchorMessageId:String,before:Int,after:Int):List<AgentHistoryMessage>{
            val rows=repo.sources(sessionId).flatMap{it.messages};val index=rows.indexOfFirst{it.id.toString()==anchorMessageId}
            if(index<0)return emptyList();return rows.subList((index-before).coerceAtLeast(0),(index+after+1).coerceAtMost(rows.size)).map(::history)
        }
    }
    private fun history(m:Message)=AgentHistoryMessage(m.id.toString(),if(m.mine)"user" else "assistant",m.text,Instant.ofEpochMilli(m.created).toString())
    private val recall=AgentRecallToolHandler(turn.friendId,reader,true)
    private fun register(id:String,version:Int,candidate:String?=null):String {
        targets.entries.firstOrNull{it.value==Target(id,version,candidate)}?.let{return it.key}
        return "M${targets.size+1}".also{targets[it]=Target(id,version,candidate)}
    }
    private fun writeDefinition():Map<String,Any> {
        fun field(type:String,description:String)=mapOf("type" to type,"description" to description)
        return mapOf("type" to "function","function" to mapOf("name" to "agent_memory_update","description" to """根据本次上下文提供的有效用户原话S编号存记忆或澄清待确认记忆；S1为当前句，历史事实用相应S编号。记录日期事实时，即使content概括后省去了时间词，也必须提供含对应完整时间词的quote和time_text；相对日期按所引原话当时的日期、时区计算。例如S2原话为“明天，我去看展”，可调用{"op":"add","content":"我计划去看展","quote":"明天，我去看展","evidence":["S2"],"time_text":"明天","date_type":"day"}。非日期事实只引用对应证据，不绑定同句其他事实的日期。写入随最终回复同一事务完成，保留人工/锁定保护。""",
            "parameters" to mapOf("type" to "object","properties" to mapOf(
                "op" to mapOf("type" to "string","enum" to listOf("add","update","cancel","complete","retract")),
                "target" to field("string","已有记忆或待确认的M编号；add不填"),"content" to field("string","忠于所选用户证据提取或概括记忆，不必复制整句话；不要混入助手或人设的事实"),
                "quote" to field("string","所选S用户原话中与本条事实对应的逐字片段；正文可以概括，证据不能改写。日期事实须包含对应完整时间词；非日期事实不要引同句另一事实的日期。省略时保留整条用户原话作证据"),
                "category" to mapOf("type" to "string","enum" to MemoryCategory.entries.filter{it.modern}.map{it.name}),
                "about" to mapOf("type" to "string","enum" to MemoryAbout.entries.map{it.name}),
                "importance" to mapOf("type" to "string","enum" to MemoryImportance.entries.map{it.name}),"gist" to field("string","40字内的大意，不带数字、地名和原句细节"),"trace" to field("string","8字内印象"),
                "evidence" to mapOf("type" to "array","items" to mapOf("type" to "string","enum" to writeEvidence.keys.toList().ifEmpty{listOf("S1")} ),"minItems" to 1,"maxItems" to 1),
                "time_text" to field("string","逐字引用所选S原话的时间词；日期类事实必填，非日期事实可空"),"date_type" to mapOf("type" to "string","enum" to listOf("day","habit","birthday"),"description" to "生日使用birthday，无出生年份只存年度月日，不猜出生年；habit仅用于不含具体日期的习惯"),
                "tags" to mapOf("type" to "array","items" to mapOf("type" to "string")),"doubt" to field("string","仍拿不准时填写理由，不能伪装成已确定事实")),"required" to listOf("op","evidence"))))
    }
    suspend fun run(config:ModelConfig,key:String,request:TextChatRequest,client:TextChatClient,onResponseStart:(suspend()->Unit)?=null,onEvent:(suspend(TextStreamEvent)->Unit)?=null,onToolActivity:(suspend(ChatToolActivity)->Unit)?=null):TextChatResult = runLoop(config,key,request,client,onResponseStart,onEvent,onToolActivity)
    /** A rejected structured candidate cannot leave writes for the next candidate to commit. */
    suspend fun discardCandidate()=repo.tx{db->db.delete("preferences","key=?",arrayOf(stageKey));Unit}
    var orderedParts: List<ReplyPart>? = null
        private set
    var spokenReply: RequestedVoiceReply? = null
        private set

    private fun definitions(target:VoiceTarget?=voiceEligibility().target)=(if(target!=null)listOf(ChatVoiceDelivery.definition)else emptyList())+(if(contactTool!=null)listOf(ImmediateContact.definition(turn.userMessageId,contactTime,contactZone,contactScheduling))else emptyList())+(if(controlTool!=null)listOf(JointControl.definition)else emptyList())+(if(recordTool!=null)listOf(CompanionRecord.definition(turn.userMessageId,recordNeedsExperience))else emptyList())+(if(rules.proactiveRecall)buildAgentRecallToolDefinitions(true) else emptyList())+(if(rules.immediateWrite&&allowWrite&&writeSourceAllowed)listOf(writeDefinition()) else emptyList())+(if(rules.proactiveRecall)listOf(mapOf("type" to "function","function" to mapOf("name" to "agent_memory_read","description" to "按问题读取当前有效角色记忆","parameters" to mapOf("type" to "object","properties" to mapOf("query" to mapOf("type" to "string")),"required" to listOf("query"))))) else emptyList())
    internal fun reservedTokens():Int {val offered=definitions()+(if(relationEnabled)listOf(RelationshipRecall.definition)else emptyList());return if(offered.isEmpty())0 else ConservativeTokens.tokens(gson.toJson(offered))+(if(rules.proactiveRecall)ConservativeTokens.tokens(CompanionRecallBudget.QUERY_RULES) else 0)+768}
    /** Read-only planning, before any model/tool execution. Same messages and cost as the first send. */
    internal suspend fun initialCapacityFailure(config:ModelConfig,request:TextChatRequest):ChatFailure? {
        if(noTools())return null
        val initialMessages=initialMessages(request)
        if(noTools())return null
        val offered=definitions()+(if(relationEnabled)listOf(RelationshipRecall.definition)else emptyList())
        val outgoing=CompanionPrompt.forSend(request.copy(messages=initialMessages),offered)
        return if(requestCost(outgoing,offered)>=config.contextTokens)ChatFailure.CONTEXT_CAPACITY else null
    }
    private fun noTools(eligibility:VoiceEligibility=voiceEligibility())=eligibility.target==null&&controlTool==null&&contactTool==null&&recordTool==null&&!relationEnabled&&!rules.proactiveRecall&&!(rules.immediateWrite&&allowWrite&&writeSourceAllowed)
    private fun messageMaps(messages:List<WireMessage>)=messages.map{m->buildMap<String,Any>{
        put("role",m.role);put("content",m.content)
        if(m.name.isNotEmpty())put("name",m.name)
        if(m.toolCallId.isNotEmpty())put("tool_call_id",m.toolCallId)
        if(m.toolCalls.isNotEmpty())put("tool_calls",m.toolCalls.map{mapOf("id" to it.id,"type" to "function","function" to mapOf("name" to it.name,"arguments" to it.arguments))})
        if(m.sourceMessageId.isNotEmpty())put(CompanionRecallBudget.SOURCE_META,mapOf("id" to m.sourceMessageId,"revision" to stableDigest(m.content.substring(m.sourcePrefix.coerceIn(0,m.content.length))),"prefix" to m.sourcePrefix))
        if(m.memoryIds.isNotEmpty())put("_companion_memory_ids",m.memoryIds)
    }}
    private fun requestCost(request:TextChatRequest,offered:List<Map<String,Any>>)=
        estimateLocalMessagesTokens(messageMaps(request.messages).map(::cleanRecallMessage))+request.messages.sumOf{it.continuation?.tokenCost?:0}+ConservativeTokens.tokens(gson.toJson(offered))+request.outputLimit
    private suspend fun initialMessages(request:TextChatRequest,references:ProcessingRepository.References=initial):List<WireMessage> {
        val guides=buildList {
            if(rules.proactiveRecall) add(guardedGuide(CompanionRecallBudget.QUERY_RULES,CompanionRecallBudget.readNames))
            if(rules.immediateWrite && allowWrite) writeGuide(request,references)?.let { add(it) }
        }
        if(guides.isEmpty()) return request.messages
        val current=request.messages.indexOfLast{it.role=="user" && it.sourceMessageId==turn.userMessageId.toString()}
        val insertAt=if(current>=0)current else request.messages.indexOfLast{it.role=="user"}.takeIf{it>=0}?:request.messages.size
        return request.messages.toMutableList().apply{addAll(insertAt,guides)}
    }
    private fun guardedGuide(text:String,tools:Set<String>)=
        WireMessage("system",text,toolInstructions=listOf(ToolInstruction(0,text,tools)))

    private suspend fun writeGuide(request:TextChatRequest,references:ProcessingRepository.References):WireMessage? {
        repo.read{db->
            writeSourceAllowed=CompanionRepository.aiAllowed(db,turn.friendId,turn.userMessageId)
            if (!writeSourceAllowed) return@read
            val evidence=validWriteEvidence(db)
            writeEvidence=ImmediateMemoryEvidence.aliases(evidence.first(),evidence.drop(1),request.messages)
            val visible=if(rules.adaptiveContext) request.messages.flatMap{it.memoryIds}.toSet() else references.valid.map{it.memoryId}.toSet()
            references.valid.map{it.memoryId}.filter{it.isNotBlank()&&it in visible}.distinct().forEach{id->repo.memory(db,turn.friendId,id)?.let{register(it.id,it.version)}}
            repo.candidates(db,turn.friendId).filter{it.valid&&references.pending.any{p->p.startsWith("候选 ${it.id}（")}}.forEach{c->register(c.targetId?:c.proposed.id,c.targetVersion?:0,c.id)}
        }
        if (!writeSourceAllowed) return null
        val mapping=targets.entries.joinToString("\n"){(ref,t)->"$ref -> ${t.candidate?:t.id}@${t.version}"}
        val users=request.messages.filter{it.role=="user"}
        val sources=writeEvidence.entries.joinToString("\n"){(alias,e)->"$alias -> ${if(alias=="S1")"当前用户原话"else"上方第${users.indexOfFirst{it.sourceMessageId==e.source.turnId.toString()}+1}条用户原话"}；原话日期${java.time.Instant.ofEpochMilli(e.sourceAt).atZone(java.time.ZoneId.of(e.zone)).toLocalDate()}（${e.zone}）"}
        val guide="[记忆工具编号]\n$sources\n所引时间词必须在对应S的用户原话中；助手话语和未列来源不能作用户证据。\n$mapping".trimEnd()
        return guardedGuide(guide,setOf("agent_memory_update"))
    }
    private suspend fun runLoop(config:ModelConfig,key:String,request:TextChatRequest,client:TextChatClient,onResponseStart:(suspend()->Unit)?,onEvent:(suspend(TextStreamEvent)->Unit)?,onToolActivity:(suspend(ChatToolActivity)->Unit)?):TextChatResult {
        deliveredMemories.clear();deliveredMemories.addAll(request.messages.flatMap{it.memoryIds})
        fun trace(target:VoiceTarget?)=VoiceTrace(turn.id,turn.attempt,target?.provider ?: VoiceVendor.UNKNOWN)
        var requestNumber = 0L
        suspend fun send(value:TextChatRequest, eligibility: VoiceEligibility, fallback: Boolean = false): TextChatResult =
            withContext(Diagnostics.linkedContext(mapOf("turn" to turn.id, "attempt" to turn.attempt, "request_role" to "ordinary_chat", "request_number" to ++requestNumber))) {
            val target = eligibility.target
            val voice = trace(target)
            val provided = value.tools.any { toolNameOf(it) == "send_short_voice" }
            onResponseStart?.invoke()
            voice.offer(provided, eligibility.reason, fallback)
            val result=if(onEvent==null)client.complete(config,key,value)else client.streamEvents(config,key,value,onEvent)
            voiceRejection(result,value)?.let { reason ->
                trace(target).rejected(target,reason)
            }
            voice.response(provided, result)
            result
        }
        suspend fun withoutTools(eligibility: VoiceEligibility):TextChatResult {
            if(!active())return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
            val result=send(CompanionPrompt.forSend(request,emptyList()), eligibility)
            return if(result is TextChatResult.Success&&result.toolCalls.isNotEmpty())TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE) else result
        }
        voiceEligibility().let { if(noTools(it))return withoutTools(it) }
        val messages=initialMessages(request).toMutableList()
        voiceEligibility().let { if(noTools(it))return withoutTools(it) }
        val progress=RecallProgress()
        val failedRecords=mutableSetOf<Int>()
        // The old 8-step loop allowed 7 tool batches before a no-tool answer. Keep that
        // write boundary independently; reads have no program-defined round/deadline/quota.
        var writeBatches=0;var step=0L;var displayReasoning=""
        while(true){
            kotlinx.coroutines.yield()
            currentCoroutineContext().ensureActive();if(!active()||repo.memoryRules(repo.store.readableDatabase)!=rulesState)return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
            val actor="${turn.id}:${turn.attempt}:$step"
            val eligibility=voiceEligibility()
            val offeredVoice=eligibility.target
            val definitions=definitions(offeredVoice)+(if(relationEnabled)listOf(RelationshipRecall.definition)else emptyList())
            val stepDefinitions=definitions.filterNot{(contactAttempts>=3&&toolNameOf(it)==ImmediateContact.NAME)||(controlAttempts>=3&&toolNameOf(it)==JointControl.NAME)||(recordAttempts>=3&&toolNameOf(it)==CompanionRecord.NAME)||(relationCalls>RelationshipRecall.LIMIT&&toolNameOf(it)==RelationshipRecall.NAME)||(writeBatches>=7&&toolNameOf(it)=="agent_memory_update")||(progress.stopped!=null&&toolNameOf(it) in CompanionRecallBudget.readNames)}
            val preparedMessages=CompanionPrompt.forSend(request.copy(messages=messages.toList()),stepDefinitions).messages
            val maps=messageMaps(preparedMessages)
            val continuationCost=preparedMessages.sumOf{it.continuation?.tokenCost?:0}
            if(continuationCost>=config.contextTokens-request.outputLimit)return TextChatResult.Failure(ChatFailure.CONTEXT_CAPACITY)
            val prepared=try{budget.prepare(actor,maps,stepDefinitions,config.contextTokens-continuationCost,request.outputLimit)}catch(_:RecallContextCapacity){return TextChatResult.Failure(ChatFailure.CONTEXT_CAPACITY)}
            val offered=prepared.tools
            val outgoing=CompanionPrompt.forSend(request.copy(messages=messages.toList()),offered)
            val availableWriteEvidence=writeEvidence.filter{(alias,proof)->ImmediateMemoryEvidence.visible(proof,outgoing.messages,alias=="S1")}
            if(requestCost(outgoing,offered)>config.contextTokens)return TextChatResult.Failure(ChatFailure.CONTEXT_CAPACITY)
            // One batch span per model request so its reasoning, tool calls and reply can be tied
            // back to the same turn even when the tool loop runs several requests.
            val result=Diagnostics.span("model.batch",mapOf("turn" to turn.id,"attempt" to turn.attempt,"step" to step,"offered_tools" to offered.map(::toolNameOf))){send(outgoing,eligibility)}
            if(result is TextChatResult.Success&&result.displayReasoning.isNotEmpty())displayReasoning+=result.displayReasoning
            currentCoroutineContext().ensureActive();if(!active()||repo.memoryRules(repo.store.readableDatabase)!=rulesState)return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
            if(step==0L&&offered.isNotEmpty()&&result is TextChatResult.Failure&&result.reason==ChatFailure.UNSUPPORTED_TOOLS){
                if(!active()||repo.memoryRules(repo.store.readableDatabase)!=rulesState)return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
                val fallback=send(CompanionPrompt.forSend(request,emptyList()),eligibility,fallback=true)
                if(fallback is TextChatResult.Success&&fallback.toolCalls.isNotEmpty())return TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE)
                return if(fallback is TextChatResult.Success)fallback.copy(displayReasoning=displayReasoning+fallback.displayReasoning)else fallback
            }
            if(result !is TextChatResult.Success)return result
            // Thinking is kept across tool rounds: the saved display must match what the user watched.
            if(result.toolCalls.isEmpty())return result.copy(displayReasoning=displayReasoning)
            if(result.toolCalls.size>8||result.toolCalls.any{c->c.name !in offered.map(::toolNameOf)}) {
                if(result.toolCalls.any{it.name=="send_short_voice"} && offered.any{toolNameOf(it)=="send_short_voice"}) trace(offeredVoice).rejected(offeredVoice,VoiceReason.INVALID_CALL)
                return TextChatResult.Failure(ChatFailure.TOOLS_UNAVAILABLE)
            }
            if(result.toolCalls.any{it.name=="send_short_voice"})
                return finishVoiceTool(result,displayReasoning,offeredVoice,{voiceEligibility().target},trace(offeredVoice), onParts={ orderedParts=it }){spokenReply=it}
            // Display metadata only: even two responses at the same thinking offset stay separate.
            val recallDisplayGroup=java.util.UUID.randomUUID().toString()
            if(result.toolCalls.any{it.name=="agent_memory_update"})writeBatches++
            if(result.toolCalls.any{it.name==JointControl.NAME}&&result.toolCalls.size!=1){
                messages+=WireMessage("assistant","",result.toolCalls)
                result.toolCalls.forEachIndexed{index,call->
                    val item=ChatToolJournal.start(call,turn.attempt,if(index==0)ChatDisplayDecoder().append(result.text)else "",recallDisplayGroup)
                    val output=ToolArgumentDiagnosis.retryExhausted(call.name,if(call.name==JointControl.NAME)controlAttempts+1 else 0,mapOf("success" to false,"error" to "control_requires_separate_batch"))
                    onToolActivity?.invoke(ChatToolJournal.result(item,output))
                    if(call.name==CompanionRecord.NAME)failedRecords.add(messages.size)
                    messages+=WireMessage("tool",gson.toJson(output),toolCallId=call.id,name=call.name)
                }
                controlAttempts++;step++;continue
            }
            // A successful record is accounting, not dialogue. Keeping its payload in the next request
            // can push an otherwise-fitting reply past the local budget and fail the whole turn, so the
            // exchange is folded into a short note. A rejected record keeps the normal tool result so
            // the model can read the error code and try once more.
            val recordOnly=result.toolCalls.size==1&&result.toolCalls[0].name==CompanionRecord.NAME
            messages+=WireMessage("assistant",result.text,result.toolCalls,continuation=result.continuation)
            for((callIndex,call) in result.toolCalls.withIndex()){
                // Memory writes show the same human receipt as every other tool; nothing is hidden.
                val activity=ChatToolJournal.start(call,turn.attempt,if(callIndex==0)ChatDisplayDecoder().append(result.text)else "",recallDisplayGroup)
                onToolActivity?.invoke(activity)
                Diagnostics.event("tool.call",mapOf("character" to turn.friendId,"turn" to turn.id,"step" to step,"call_id" to call.id,"name" to call.name,"arguments" to Diagnostics.raw(call.arguments)))
                if(!active()||repo.memoryRules(repo.store.readableDatabase)!=rulesState)return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
                var rawRecallRows:List<Map<String,Any>>?=null
                var rawRecallOrigins:List<RecallContentOrigin> = emptyList()
                val rawOutput=try {
                    val args=JSONObject(call.arguments)
                    if(call.name==ImmediateContact.NAME){contactAttempts++;contactTool!!.invoke(call.arguments)} else if(call.name==JointControl.NAME){controlAttempts++;controlTool!!.invoke(args)} else if(call.name==CompanionRecord.NAME){recordAttempts++;recordTool!!.invoke(call.arguments)} else if(call.name==RelationshipRecall.NAME){budget.read(actor){searchRelation(args).also{if(it["status"]=="quota_exhausted")relationCalls++}}}
                    else if(call.name=="agent_memory_update")stage(call,args,availableWriteEvidence)
                    else {rejectUnless(authorized(),"TOOLS_DISABLED");
                        if(!progress.begin(call.name+":"+canonicalJson(args)))progress.blockedResult()
                        else budget.read(actor){
                        val converted=gson.fromJson(call.arguments,Map::class.java).entries.filter{it.value!=null}.associate{it.key.toString() to it.value!!}
                        val name=if(call.name=="agent_memory_read")"agent_recall_search" else call.name
                        val raw=recall.execute(name,converted);completedReads++
                        @Suppress("UNCHECKED_CAST")
                        if(call.name in ChatToolActivity.RECALL_TOOLS){rawRecallRows=raw["results"] as? List<Map<String,Any>>;rawRecallOrigins=recall.displayOrigins}
                        @Suppress("UNCHECKED_CAST")
                        val rows=(raw["results"] as? List<Map<String,Any>>)?.map{row->
                            val id=row["id"] as? String
                            val m=id?.takeIf { offered.any { tool -> toolNameOf(tool)=="agent_memory_update" } }?.let{repo.read{db->repo.memory(db,turn.friendId,it)}}
                            if(m!=null)row+("memory_ref" to register(m.id,m.version)) else row
                        }
                        if(rows==null)raw else raw+("results" to rows)
                    }}
                }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:RecallContextCapacity){if(call.name==RelationshipRecall.NAME)mapOf("status" to "context_full")else return TextChatResult.Failure(ChatFailure.CONTEXT_CAPACITY)}catch(e:Exception){ToolArgumentDiagnosis.writeFailure(call.name,call.arguments,input,targets.keys,mapOf("success" to false,"error" to ((e as? ProcessingRejected)?.code?:"invalid_arguments")),e,availableWriteEvidence,turn.userMessageId)}
                currentCoroutineContext().ensureActive()
                val output=ToolArgumentDiagnosis.retryExhausted(call.name,if(call.name==JointControl.NAME)controlAttempts else recordAttempts,rawOutput)
                val resultActivity=ChatToolJournal.result(activity,output)
                val shown=if(call.name in ChatToolActivity.RECALL_TOOLS&&resultActivity.state=="success"){
                    val refs=try{recallContent.capture(rawRecallRows,output,rawRecallOrigins)}
                    catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null}
                    resultActivity.copy(recallRefs=refs)
                }else resultActivity
                onToolActivity?.invoke(shown)
                if(call.name=="agent_memory_update"&&shown.state=="staged")stagedMemory[shown.id]=shown
                if(call.name in CompanionRecallBudget.readNames){progress.accept(output);progress.stopped?.let{Diagnostics.event("recall.stopped",mapOf("reason" to it,"turn" to turn.id,"step" to step))}}
                if(call.name==JointControl.NAME&&output["success"]==true){
                    // The control withdraws the sources these candidates cited; their receipts must
                    // say so instead of turning green with the turn's commit.
                    stagedMemory.values.forEach{a->onToolActivity?.invoke(a.copy(state="failed",detail="根据后续操作，这条没有保存"))}
                    stagedMemory.clear()
                    discardCandidate();relationSeen.clear();relationQueries.clear();recalled.clear();deliveredMemories.clear()
                    // Do not continue with prompts, tool results or native continuation containing withdrawn sources.
                    failedRecords.clear()
                    targets.clear();writeEvidence=emptyMap()
                    val (fresh,references)=requireNotNull(freshControlContext).invoke()
                    messages.clear();messages.addAll(initialMessages(fresh,references))
                    messages+=WireMessage("assistant","",listOf(call))
                }
                currentCoroutineContext().ensureActive();if(!active()||repo.memoryRules(repo.store.readableDatabase)!=rulesState)return TextChatResult.Failure(ChatFailure.SOURCE_CHANGED)
                if(call.name==CompanionRecord.NAME&&output["success"]==true){
                    val resolved=RecordRetryContext.resolve(messages,failedRecords,true)
                    messages.clear();messages.addAll(resolved);failedRecords.clear()
                }
                if(call.name==CompanionRecord.NAME&&output["success"]==false)failedRecords.add(messages.size)
                if(recordOnly&&output["success"]==true){
                    messages.removeAt(messages.lastIndex)          // the assistant tool_calls payload
                    if(result.text.isNotBlank())messages+=WireMessage("assistant",result.text,continuation=result.continuation)
                    messages+=WireMessage("system",CompanionRecord.receiptNote(output))
                }else messages+=WireMessage("tool",gson.toJson(output),toolCallId=call.id,name=call.name)
                @Suppress("UNCHECKED_CAST")
                val returned=((output["results"]?:output["messages"]) as? List<Map<String,Any>>).orEmpty()
                if(call.name==RelationshipRecall.NAME){
                    @Suppress("UNCHECKED_CAST")
                    val events=(output["events"] as? List<Map<String,Any>>).orEmpty()
                    val ids=events.mapNotNull{(it["消息"] as? Number)?.toLong()}.toSet()
                    relationSeen.addAll(ids)
                    if(ids.isNotEmpty())repo.tx{db->
                        if(active())ReplyAccountingQueue.rememberReads(db,turn.id,CompanionRepository(repo.store).relationshipInputs(db,turn.friendId).filter{it.source.turnId in ids}.map{it.source})
                    }
                }
                if(call.name in setOf("agent_recall_search","agent_memory_read"))returned.mapNotNull{it["id"]?.toString()}.forEach{recalled[it]=RecallSource.TOOL_HIT}
                if(call.name in setOf("agent_history_search","agent_history_read")){
                    val ids=returned.mapNotNull{(it["message_id"]?:it["id"])?.toString()?.toLongOrNull()}.toSet()
                    repo.read{db->val sources=repo.sources(db,turn.friendId).filter{it.user.id in ids||it.assistant?.id in ids}.map{it.source}.toSet()
                        repo.usableMemories(db,turn.friendId).filter{m->m.evidence.any{it.source in sources}}.forEach{recalled[it.id]=RecallSource.HISTORY_HIT}}
                }
                Diagnostics.event("tool.result",mapOf("character" to turn.friendId,"turn" to turn.id,"call_id" to call.id,"name" to call.name,"output" to Diagnostics.rawJson(output)))
            }
            step++
        }
    }
    private suspend fun stage(call:ChatToolCall,args:JSONObject,available:Map<String,MemoryEvidence>):Map<String,Any> = repo.tx{db->
        // A forget request remains visible for this reply, but is not a new memory source.
        rejectUnless(authorized(true)&&pending(db)&&CompanionRepository.aiAllowed(db,turn.friendId,turn.userMessageId),"SOURCE_CHANGED")
        val proof=ImmediateMemoryEvidence.select(args,available)
        rejectUnless(proof in validWriteEvidence(db),"SOURCE_CHANGED")
        val op=args.getString("op");rejectUnless(op in setOf("add","update","cancel","complete","retract"))
        val target=if(op=="add"){rejectUnless(args.optString("target").isEmpty());null}else targets[args.getString("target")]?:throw ProcessingRejected("UNKNOWN_TARGET")
        val old=target?.let{repo.memory(db,turn.friendId,it.id)}
        if(target!=null&&target.candidate==null)rejectUnless(old!=null&&old.version==target.version&&old.valid,"SOURCE_CHANGED")
        val proposed=target?.candidate?.let{id->repo.candidates(db,turn.friendId).firstOrNull{it.id==id&&it.valid}?:throw ProcessingRejected("SOURCE_CHANGED")}
        val base=ImmediateMemoryEvidence.base(old,proposed?.proposed)
        val content=args.optString("content").ifBlank{base?.text.orEmpty()};rejectUnless(content.isNotBlank()&&content.length<=4000)
        val category=args.optString("category").ifBlank{base?.category?.name.orEmpty()};rejectUnless(MemoryCategory.valueOf(category).modern)
        val about=args.optString("about").ifBlank{base?.about?.name.orEmpty()};MemoryAbout.valueOf(about)
        val evidence=ImmediateMemoryEvidence.quote(proof,args)
        val dateArgs=ImmediateMemoryEvidence.withDateType(args,base?.dateType)
        ImmediateMemoryEvidence.dates(dateArgs,evidence,content)
        rejectUnless(args.optString("date_type","day") in setOf("day","habit","birthday"))
        val data=ImmediateMemoryEvidence.stamp(evidence,dateArgs).put("content_supplied",args.optString("content").isNotBlank()).put("category_supplied",args.optString("category").isNotBlank()).put("about_supplied",args.optString("about").isNotBlank()).put("content",content).put("category",category).put("about",about).put("target_id",target?.id?:JSONObject.NULL).put("target_version",target?.version?:0).put("candidate",target?.candidate?:JSONObject.NULL)
        val digest=stableDigest(canonicalJson(data));data.put("digest",digest)
        val stages=JSONArray(pref(stageKey)?:"[]");rejectUnless(stages.length()<16,"TOOL_LIMIT")
        if((0 until stages.length()).none{stages.getJSONObject(it).getString("digest")==digest}){stages.put(data);savePref(stageKey,stages.toString())}
        mapOf("success" to true,"status" to "staged_until_reply_commit","operation" to digest,"note" to "最终回复成功保存时一并写入；失败或取消不会改动原记忆")
    }
    private fun pending(db:SQLiteDatabase)=db.rawQuery("SELECT 1 FROM chat_turns t JOIN messages m ON m.id=t.user_message_id WHERE t.id=? AND t.friend_id=? AND t.attempt=? AND t.state='PENDING' AND m.body=?",arrayOf(turn.id,turn.friendId,turn.attempt.toString(),input)).use{it.moveToFirst()}
    /** Invoked inside ChatLedger's final reply transaction, after its ownership/context/config guards. */
    fun receivedEffects():ReceivedMemoryEffects {
        val hits=recalled.toMutableMap()
        initial.valid.filter{it.memoryId in deliveredMemories&&!it.mandatory&&(it.label.startsWith("关键词记忆")||it.label.startsWith("关联记忆"))}.forEach{hits.putIfAbsent(it.memoryId,RecallSource.CUE_HIT)}
        return ReceivedMemoryEffects(hits,recallAt,stableDigest(gson.toJson(rulesState)))
    }
    fun commit(db:SQLiteDatabase)=commit(db,receivedEffects())
    fun commit(db:SQLiteDatabase,effects:ReceivedMemoryEffects){
        effects.recalls.forEach{(id,source)->MemoryForgetting.recalled(db,turn.friendId,id,effects.at,source)}
        Diagnostics.event("tool.commit.staged",mapOf("character" to turn.friendId,"turn" to turn.id,"attempt" to turn.attempt))
        val raw=pref(stageKey)?:return
        rejectUnless(stableDigest(gson.toJson(repo.memoryRules(db)))==effects.rulesStamp&&rules.immediateWrite&&allowWrite,"SOURCE_CHANGED")
        val stages=JSONArray(raw)
        val source=repo.sources(db,turn.friendId).firstOrNull{it.requestId==turn.id}?:throw ProcessingRejected("SOURCE_CHANGED")
        rejectUnless(source.user.text==input,"SOURCE_CHANGED")
        val written=mutableMapOf<String,Int>()
        /** stage digest -> the stored row it really produced, so its receipt can read that row later. */
        val linked=mutableMapOf<String,String>()
        val resolved=mutableMapOf<String,ProcessingRepository.Candidate>()
        val validSources=validWriteEvidence(db)
        for(i in 0 until stages.length()){
            val op=stages.getJSONObject(i);val evidence=ImmediateMemoryEvidence.revalidate(op,validSources);val target=if(op.isNull("target_id"))null else op.getString("target_id");var old=target?.let{repo.memory(db,turn.friendId,it)}
            val candidate=if(op.isNull("candidate"))null else (resolved[op.getString("candidate")]?:repo.candidates(db,turn.friendId).firstOrNull{it.id==op.getString("candidate")&&it.valid})?:throw ProcessingRejected("SOURCE_CHANGED")
            if(target!=null&&old!=null)rejectUnless(old.version==(written[target]?:op.getInt("target_version")),"SOURCE_CHANGED")
            if(target!=null&&old==null)rejectUnless(candidate!=null&&candidate.targetId==null,"SOURCE_CHANGED")
            val base=ImmediateMemoryEvidence.base(old,candidate?.proposed,candidate?.id in resolved)
            val kind=op.getString("op");val expression=if(!op.has("time_text")&&!op.has("date_type"))base?.dateExpression else op.optString("time_text").takeIf{it.isNotBlank()&&op.optString("date_type")!="habit"}
            val dateType=if(op.has("date_type"))op.getString("date_type") else base?.dateType
            val content=if(!op.optBoolean("content_supplied")&&base!=null)base.text else op.getString("content")
            val dates=if(!op.has("time_text")&&!op.has("date_type"))base?.eventDate to base?.annualMonthDay else ImmediateMemoryEvidence.dates(op,evidence,content,op.optBoolean("content_supplied"))
            val eventDate=dates.first
            val category=if(!op.optBoolean("category_supplied")&&base!=null)base.category else MemoryCategory.valueOf(op.getString("category"))
            val about=if(!op.optBoolean("about_supplied")&&base!=null)base.about else MemoryAbout.valueOf(op.getString("about"))
            val id=old?.id?:candidate?.proposed?.id?:"tool-"+stableDigest(turn.id+op.getString("digest")).take(40)
            val tags=op.optJSONArray("tags")?.let{a->MemoryFacets.tags((0 until a.length()).map{a.getString(it)})}?:old?.tags?:emptyList()
            var value=StoredMemory(id,turn.friendId,category,content,when(kind){"cancel"->MemoryLife.CANCELLED;"complete"->MemoryLife.COMPLETED;"retract"->MemoryLife.RETRACTED;else->old?.life?:MemoryLife.ACTIVE},old?.core?:false,old?.locked?:false,false,(old?.version?:0)+1,true,expression,eventDate,MemoryEvidenceRules.merge(base?.evidence.orEmpty(),listOf(evidence)),about,tags,old?.dueExpression,old?.dueDate,old?.expiresAt,dateType,dates.second)
            value=MemoryForgetting.metadata(value,op,old)
            if(kind=="add"&&old==null&&candidate==null){
                old=repo.memories(db,turn.friendId).firstOrNull{it.valid&&MemoryEvidenceRules.sameFact(it,value)&&MemoryEvidenceRules.overlaps(it.evidence,listOf(evidence))}
                old?.let{value=value.copy(id=it.id,version=it.version+1,evidence=MemoryEvidenceRules.merge(it.evidence,listOf(evidence)))}
            }
            val digest=op.getString("digest")
            if(candidate==null&&op.optString("doubt").isBlank()&&old!=null&&MemoryEvidenceRules.repeated(old,value,listOf(evidence))){written[old.id]=old.version;linked[digest]=old.id;continue}
            if(old?.locked==true||old?.manual==true||op.optString("doubt").isNotBlank()){
                repo.candidate(db,turn.friendId,target,value,"本轮澄清建议，人工或锁定内容保持保护；${op.optString("doubt")}","tool-candidate-"+stableDigest(turn.id+digest).take(40))
            }else{
                repo.saveMemory(db,value);written[value.id]=value.version;linked[digest]=value.id
                if(candidate!=null){db.update("memory_candidates",ContentValues().apply{put("resolved",1)},"id=? AND friend_id=?",arrayOf(candidate.id,turn.friendId));resolved[candidate.id]=candidate}
            }
        }
        db.delete("preferences","key=?",arrayOf(stageKey))
        // The receipt may only offer the row this transaction really wrote.
        ChatToolJournal.linkMemory(db,turn.id,turn.attempt,linked)
    }
}

internal fun voiceRejection(result: TextChatResult, request: TextChatRequest): VoiceReason? {
    val sawVoice = when (result) {
        is TextChatResult.Failure -> result.rejectedVoiceCall
        is TextChatResult.Success -> result.toolCalls.any { it.name == "send_short_voice" }
    }
    if (!sawVoice) return null
    if (request.tools.none { toolNameOf(it) == "send_short_voice" }) return VoiceReason.NOT_OFFERED
    if (result !is TextChatResult.Failure) return null
    return when (result.reason) {
        ChatFailure.TOOLS_UNAVAILABLE, ChatFailure.FORMAT, ChatFailure.REPLY_TEXT_INVALID -> VoiceReason.INVALID_CALL
        else -> VoiceReason.INCOMPLETE_RESPONSE
    }
}

internal fun finishVoiceTool(result:TextChatResult.Success,displayReasoning:String,offeredVoice:VoiceTarget?,
    voiceTarget:(()->VoiceTarget?)?,trace:VoiceTrace,onParts:(List<ReplyPart>)->Unit={},accept:(RequestedVoiceReply)->Unit):TextChatResult {
    val speech=result.toolCalls.singleOrNull()?.takeIf{it.name=="send_short_voice"}
    val parts=speech?.let { OrderedChatReply.parse(it.arguments) }
    if(parts==null) {
        trace.rejected(offeredVoice,VoiceReason.INVALID_CALL)
        return TextChatResult.Failure(ChatFailure.REPLY_TEXT_INVALID)
    }
    val sentence=OrderedChatReply.body(parts)
    onParts(parts)
    if(offeredVoice!=null && voiceTarget?.invoke()==offeredVoice) {
        accept(RequestedVoiceReply(sentence,offeredVoice))
        trace.record(VoiceStage.TOOL,VoiceStatus.ACCEPTED)
    } else {
        trace.rejected(offeredVoice,VoiceReason.TARGET_CHANGED)
        ChatVoiceDiagnostics.outcome(trace,VoiceStatus.TEXT_FALLBACK,VoiceReason.TARGET_CHANGED)
    }
    return result.copy(text=sentence,toolCalls=emptyList(),continuation=null,displayReasoning=displayReasoning)
}
