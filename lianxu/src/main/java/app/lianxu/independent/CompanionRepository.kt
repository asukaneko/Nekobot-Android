package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import java.util.UUID

internal data class CompanionSnapshot(val role:String,val epoch:Long,val policyVersion:Long,val characterStamp:String,
    val sources:List<SourceRef>,val prompt:String,val policy:String,val overview:String="",val learning:List<RoleLearningState> = emptyList(),val references:List<PromptReference> = emptyList(),
    val persona:UserPersona=UserPersona(),val roleProfile:String="",val replyPrompt:String="",val hasExperiences:Boolean=false,
    /** Proactive contact: shared numeric state and evidence, no canned summary or template tone. */
    val proactivePrompt:String="",val accountingRevision:String="")
internal data class CompanionOpportunity(val id:String,val role:String,val source:Long,val revision:Long,val epoch:Long,
    val policyVersion:Long,val notBefore:Long,val expiresAt:Long,val reason:String,val state:String,val attempt:Int)
internal data class CompanionReceipt(val messageId:Long,val readAt:Long?,val requestedAt:Long?,val observedAt:Long?,val clickedAt:Long?,val delivery:String)
private class ReviewAttributionUnavailable:IllegalStateException()

/** One phone-local authority. Network work must happen outside these transactions. */
internal class CompanionRepository(internal val store:Store,private val now:()->Long=System::currentTimeMillis) {
    internal fun clock()=now()
    private val repo=ProcessingRepository(store)
    private val gson=Gson()
    companion object {
        fun create(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS companion_vectors(role TEXT NOT NULL,source_id INTEGER NOT NULL,revision INTEGER NOT NULL,config_stamp TEXT NOT NULL,fingerprint TEXT NOT NULL,dimension INTEGER NOT NULL,vector TEXT NOT NULL,PRIMARY KEY(role,source_id),FOREIGN KEY(role,source_id) REFERENCES messages(friend_id,id) ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS messages_role_id ON messages(friend_id,id)")
            db.execSQL("CREATE TABLE IF NOT EXISTS companion_state(role TEXT PRIMARY KEY REFERENCES friends(id) ON DELETE CASCADE,epoch INTEGER NOT NULL DEFAULT 0,policy_version INTEGER NOT NULL DEFAULT 0,policy TEXT NOT NULL DEFAULT 'disabled',owner TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE IF NOT EXISTS companion_sources(role TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,source_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,revision INTEGER NOT NULL,created INTEGER NOT NULL,proposal TEXT NOT NULL,committed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(role,source_id,revision),FOREIGN KEY(role,source_id) REFERENCES messages(friend_id,id) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS companion_blocks(role TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,source_id INTEGER NOT NULL,PRIMARY KEY(role,source_id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS companion_uses(role TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,turn_source INTEGER NOT NULL,source_id INTEGER NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(role,turn_source,source_id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS companion_opportunities(id TEXT PRIMARY KEY,role TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,source_id INTEGER NOT NULL,revision INTEGER NOT NULL,epoch INTEGER NOT NULL,policy_version INTEGER NOT NULL,not_before INTEGER NOT NULL,expires_at INTEGER NOT NULL,reason TEXT NOT NULL,state TEXT NOT NULL,attempt INTEGER NOT NULL DEFAULT 0,owner TEXT NOT NULL DEFAULT '',FOREIGN KEY(role,source_id) REFERENCES messages(friend_id,id) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE IF NOT EXISTS companion_receipts(message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,role TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,opportunity_id TEXT UNIQUE,delivery TEXT NOT NULL,claim_owner TEXT NOT NULL DEFAULT '',claimed_at INTEGER,requested_at INTEGER,observed_at INTEGER,clicked_at INTEGER,read_at INTEGER,FOREIGN KEY(role,message_id) REFERENCES messages(friend_id,id) ON DELETE CASCADE)")
            // v8: learning IDs that a formally saved proactive message considered/used (JSON string arrays).
            db.execSQL("CREATE TABLE IF NOT EXISTS companion_action_learning(message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,role TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,considered TEXT NOT NULL,used TEXT NOT NULL,judgment TEXT NOT NULL DEFAULT '',FOREIGN KEY(role,message_id) REFERENCES messages(friend_id,id) ON DELETE CASCADE)")
        }
        /** Deny old raw dialogue and derivatives immediately, including legacy extraction. */
        fun aiAllowed(db:SQLiteDatabase,role:String,id:Long):Boolean {
            if(db.rawQuery("SELECT 1 FROM companion_blocks WHERE role=? AND source_id=?",arrayOf(role,id.toString())).use{it.moveToFirst()})return false
            return !db.rawQuery("SELECT 1 FROM companion_uses u LEFT JOIN chat_turns t ON t.friend_id=u.role AND t.user_message_id=u.source_id LEFT JOIN companion_blocks b ON b.role=u.role AND b.source_id=u.source_id LEFT JOIN message_state ms ON ms.message_id=u.source_id WHERE u.role=? AND u.turn_source=? AND (b.source_id IS NOT NULL OR t.id IS NULL OR t.source_revision<>u.revision OR COALESCE(ms.deleted,0)=1) LIMIT 1",arrayOf(role,id.toString())).use{it.moveToFirst()}
        }
        fun bump(db:SQLiteDatabase,role:String){
            db.execSQL("INSERT OR IGNORE INTO companion_state(role,owner) VALUES(?,?)",arrayOf<Any>(role,CompanionRuntime.owner))
            db.execSQL("UPDATE companion_state SET epoch=epoch+1 WHERE role=?",arrayOf<Any>(role))
            db.execSQL("UPDATE companion_opportunities SET state='cancelled' WHERE role=? AND state IN('queued','running')",arrayOf<Any>(role))
        }
    }
    private fun state(db:SQLiteDatabase,role:String):Triple<Long,Long,String> {
        db.execSQL("INSERT OR IGNORE INTO companion_state(role,owner) VALUES(?,?)",arrayOf<Any>(role,CompanionRuntime.owner))
        return db.rawQuery("SELECT epoch,policy_version,policy FROM companion_state WHERE role=?",arrayOf(role)).use{it.moveToFirst();Triple(it.getLong(0),it.getLong(1),it.getString(2))}
    }
    internal fun refCurrent(db:SQLiteDatabase,r:SourceRef)=!db.rawQuery("SELECT 1 FROM companion_blocks WHERE role=? AND source_id=?",arrayOf(r.characterId,r.turnId.toString())).use{it.moveToFirst()}&&db.rawQuery("SELECT 1 FROM chat_turns t LEFT JOIN message_state ms ON ms.message_id=t.user_message_id WHERE t.friend_id=? AND t.user_message_id=? AND t.source_revision=? AND COALESCE(ms.deleted,0)=0",arrayOf(r.characterId,r.turnId.toString(),r.version.toString())).use{it.moveToFirst()}
    private fun sourceRefs(db:SQLiteDatabase,role:String)=db.rawQuery("SELECT t.user_message_id,t.source_revision FROM chat_turns t WHERE t.friend_id=? AND (t.state='COMPLETED' OR EXISTS(SELECT 1 FROM companion_sources s WHERE s.role=t.friend_id AND s.source_id=t.user_message_id AND s.revision=t.source_revision)) ORDER BY t.user_message_id",arrayOf(role)).use{c->buildList{while(c.moveToNext()){val r=SourceRef(role,c.getLong(0),c.getLong(1));if(refCurrent(db,r))add(r)}}}
    suspend fun sourceFor(turn:ChatTurn):SourceRef=repo.read{db->db.rawQuery("SELECT source_revision FROM chat_turns WHERE id=? AND friend_id=? AND user_message_id=? AND state='PENDING' AND attempt=?",arrayOf(turn.id,turn.friendId,turn.userMessageId.toString(),turn.attempt.toString())).use{check(it.moveToFirst());SourceRef(turn.friendId,turn.userMessageId,it.getLong(0))}}
    suspend fun beginInput(role:String)=repo.tx{db->
        state(db,role)
        db.execSQL("UPDATE companion_state SET epoch=epoch+1 WHERE role=?",arrayOf<Any>(role))
        // Input invalidates an execution snapshot, not the user's saved future reasons.
        opportunities(db,"role=? AND state='running'",arrayOf(role)).forEach{requeue(db,it)}
    }
    suspend fun policy(role:String)=repo.tx{state(it,role).third}
    suspend fun setPolicy(role:String,enabled:Boolean,owner:String)=repo.tx{db->
        check(db.rawQuery("SELECT 1 FROM friends WHERE id=?",arrayOf(role)).use{it.moveToFirst()}){"角色已不存在"}
        state(db,role);bump(db,role)
        db.execSQL("UPDATE companion_state SET policy=?,policy_version=policy_version+1,owner=? WHERE role=?",arrayOf<Any>(if(enabled)"enabled"else"paused_by_user",owner,role))
        // Explicit permission changes never revive a cancelled reason.
    }
    /** The toolbox list must not create states merely by displaying roles. */
    internal suspend fun contactPolicies()=repo.read{db->db.rawQuery("SELECT f.id,COALESCE(c.policy,'disabled') FROM friends f LEFT JOIN companion_state c ON c.role=f.id",null).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}}}
    suspend fun checkContinuity(owner:String)=repo.tx{db->
        // Owner is process execution identity; permission/version survive normal recreation.
        // A local result survives process identity rollover only if it matched the role before rollover.
        db.execSQL("""UPDATE received_chat_replies SET companion_epoch=companion_epoch+1 WHERE EXISTS(
            SELECT 1 FROM chat_turns t JOIN companion_state s ON s.role=t.friend_id
            WHERE t.id=received_chat_replies.turn_id AND t.attempt=received_chat_replies.attempt
            AND t.state='PENDING' AND s.owner<>? AND s.epoch=received_chat_replies.companion_epoch)""",arrayOf<Any>(owner))
        db.execSQL("UPDATE companion_state SET epoch=epoch+1,owner=? WHERE owner<>?",arrayOf<Any>(owner,owner))
        opportunities(db,"state='running' AND owner<>?",arrayOf(owner)).forEach{requeue(db,it)}
        opportunities(db,"state='queued'",emptyArray()).forEach{settlePermission(db,it)}
        db.execSQL("UPDATE companion_receipts SET delivery='unknown' WHERE delivery='claimed' AND claim_owner<>?",arrayOf<Any>(owner))
    }
    suspend fun cached(turn:ChatTurn):ExperienceProposal?=repo.read{db->db.rawQuery("SELECT s.proposal FROM companion_sources s JOIN chat_turns t ON t.friend_id=s.role AND t.user_message_id=s.source_id AND t.source_revision=s.revision WHERE t.id=?",arrayOf(turn.id)).use{if(it.moveToFirst())gson.fromJson(it.getString(0),ExperienceProposal::class.java)else null}}
    suspend fun experiences(role:String)=repo.read{experiences(it,role)}
    internal fun experiences(db:SQLiteDatabase,role:String):List<CompanionExperience> = db.rawQuery("SELECT source_id,revision,proposal FROM companion_sources WHERE role=?",arrayOf(role)).use{c->buildList{
        while(c.moveToNext()){val source=SourceRef(role,c.getLong(0),c.getLong(1));val p=gson.fromJson(c.getString(2),ExperienceProposal::class.java)
            if(refCurrent(db,source)&&p.memory.isNotBlank())add(CompanionExperience(source,p.memory,p.quote))}
    }}
    suspend fun snapshot(role:String,query:String="",semantic:Map<SourceRef,Double> = emptyMap(),tokenBudget:Int=Int.MAX_VALUE):CompanionSnapshot=repo.tx{db->snapshot(db,role,query,semantic,tokenBudget)}
    private fun snapshot(db:SQLiteDatabase,role:String,query:String="",semantic:Map<SourceRef,Double> = emptyMap(),tokenBudget:Int=Int.MAX_VALUE):CompanionSnapshot {
        val s=state(db,role);val refs=sourceRefs(db,role)
        val rows=db.rawQuery("SELECT source_id,revision,created,proposal,committed FROM companion_sources WHERE role=? ORDER BY created",arrayOf(role)).use{c->buildList{while(c.moveToNext()){
            val ref=SourceRef(role,c.getLong(0),c.getLong(1))
            if(refCurrent(db,ref))add(Triple(ref,c.getLong(2),gson.fromJson(c.getString(3),ExperienceProposal::class.java) to (c.getInt(4)==1)))
        }}}
        val latest=rows.lastOrNull{it.third.first.emotion!="unknown"}
        val support=rows.filter{it.third.second&&it.third.first.relationship=="appreciation"}
        val boundary=rows.count{it.third.second&&it.third.first.relationship=="boundary"}
        val facts=rows.filter{it.third.first.memory.isNotBlank()}
        val ranked=if(query.isBlank())facts.asReversed() else facts.map{it to maxOf(semantic[it.first]?:0.0,MemoryRecall.relevance(query,it.third.first.memory))}
            .filter{it.second>0}.sortedByDescending{it.second}.map{it.first}
        val materialNow=now()
        var remaining=tokenBudget.coerceAtLeast(0)
        val selected=ranked.take(repo.memoryRules(db).first.relatedCount).filter{r->val cost=ConservativeTokens.tokens(ExperiencePresentation.experience(r.first,r.second,r.third.first.memory,materialNow)+"\n");if(cost<=remaining){remaining-=cost;true}else false}
        val roleState=roleContext(db,role)
        val relation=relationship(db,role)
        // The reply round uses this body without the tone line; the control lookup keeps the full body.
        // Reply and proactive contact share the same state/evidence, without a prescribed tone.
        val contactFacts=contactFacts(db,role,materialNow)
        val contactBoundary=if(s.third=="enabled")ExperiencePresentation.permission(s.third)+"；记下的时间只是再次考虑的机会，不保证提醒或送达。" else ""
        fun legacy(includeTone:Boolean,includeMessageState:Boolean=true)=buildString{
            append("[${RelationshipPrompt.LABEL}]\n").append(relation.modelMaterial).append("\n")
            if(includeTone)append(relation.template.prompt(relation.parameters)).append("\n")
            if(selected.isNotEmpty())append("[以前记下的经历，只在有关时参考；对方更正或要求忘掉时，以有效记录为准]\n")
            selected.forEach{(ref,created,p)->append(ExperiencePresentation.experience(ref,created,p.first.memory,materialNow)).append('\n')}
            if(latest!=null)append("${ExperiencePresentation.at(latest.second,materialNow)}，当时观察到对方看起来${ExperiencePresentation.emotion(latest.third.first.emotion)}；这是过去的观察，不能断言现在仍然如此。\n${ExperiencePresentation.REFS}\n这次心情观察：${ExperiencePresentation.reference(latest.first)}\n")
            append("用户表达记录：${support.size}条明确感谢；${boundary}条明确边界。这不是角色感受、亲密分或信任分；不能靠计数替代角色评价。未回复不扣信任，不能威胁或惩罚。\n")
            if(!repo.memoryRules(db).first.adaptiveContext)append(roleState.prompt(materialNow)).append('\n')
            append("$contactBoundary${ExperiencePresentation.READ_STATE_RULE}")
            if(includeMessageState)append('\n').append(contactFacts)
        }
        val legacyPrompt=legacy(true)
        val replyPrompt=legacy(false)
        val overview=buildString{
            append("已保存的新经历：${facts.size}条。\n")
            facts.lastOrNull()?.let{r->append("最近一条（来源消息${r.first.turnId}）：\n${r.third.first.memory}\n依据原句：${r.third.first.quote}\n")}
            latest?.let{r->val label=when(r.third.first.emotion){"calm"->"平静";"happy"->"开心";"sad"->"难过";"anxious"->"焦虑";"angry"->"生气";else->"未知"};append("最近情绪观察：$label，来源消息${r.first.turnId}。这是历史记录，不是此刻结论。\n")}
            append("关系线索：${support.size}条明确感谢，${boundary}条明确边界。未回复不扣信任。")
        }
        val adaptive=repo.memoryRules(db).first.adaptiveContext
        val viewBudget=minOf(tokenBudget.coerceAtLeast(0),4096)
        val stateView=if(adaptive)AdaptiveContext.roleView(roleState,query,viewBudget/2,semantic)else roleState
        val contextRefs=buildList{
            add(PromptReference(RelationshipPrompt.LABEL,relation.modelMaterial,true));add(PromptReference(CompanionPrompt.TONE_LABEL,relation.template.prompt(relation.parameters),true))
            add(PromptReference("当前权限与交流边界","$contactBoundary${ExperiencePresentation.READ_STATE_RULE}未回复不扣信任，不能威胁或惩罚。",true))
            if(contactFacts.isNotBlank())add(PromptReference(ExperiencePresentation.MESSAGE_STATE_LABEL,contactFacts,true))
            add(PromptReference("本轮可用的角色状态","下面是过去的理解，以及仍有效的看法和习惯；没有提到的事不代表从未发生。\n"+stateView.prompt(materialNow),priority=1.1))
            facts.forEach{(ref,created,p)->add(PromptReference("${ExperiencePresentation.at(created,materialNow)}记下的经历",p.first.memory+"\n${ExperiencePresentation.REFS}\n这段经历：${ExperiencePresentation.reference(ref)}",mandatory=p.first.relationship=="boundary",memoryId="experience:${ref.turnId}@${ref.version}",related=true,sources=setOf(ref),priority=maxOf(semantic[ref]?:0.0,MemoryRecall.relevance(query,p.first.memory))))}
        }
        val prompt=if(adaptive)AdaptiveContext.references(query,contextRefs,viewBudget).joinToString("\n\n"){it.label+":\n"+it.text}else legacyPrompt
        // Same completed state as ordinary chat; the model interprets it alongside recent dialogue.
        val proactivePrompt=(if(adaptive)AdaptiveContext.references(query,proactiveReferences(contextRefs),viewBudget).joinToString("\n\n"){it.label+":\n"+it.text}
            else legacy(false,includeMessageState=false))
        val persona=store.persona(db)
        val prompts=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(role))).use{if(it.moveToFirst())RolePromptStore.decode(it.getString(0))else RolePrompts()}
        val profile=CompanionPrompt.profile(repo.friend(db,role),prompts,persona)
        return CompanionSnapshot(role,s.first,s.second,repo.characterStamp(db,role),refs,prompt,s.third,overview,roleState.learning,contextRefs,persona,profile,replyPrompt,selected.isNotEmpty(),proactivePrompt,stableDigest(gson.toJson(RelationStorage.read(db,role))))
    }
    internal fun current(db:SQLiteDatabase,s:CompanionSnapshot):Boolean {
        val st=state(db,s.role)
        return st.first==s.epoch&&st.second==s.policyVersion&&repo.characterStamp(db,s.role)==s.characterStamp&&store.persona(db)==s.persona&&s.sources.all{refCurrent(db,it)}
    }
    suspend fun current(s:CompanionSnapshot)=repo.tx{current(it,s)}
    /** C1 is keyed by the real source revision, independent of chat retries. */
    suspend fun accept(turn:ChatTurn,proposal:ExperienceProposal,expected:CompanionSnapshot,active:()->Boolean):Boolean=repo.tx{db->
        if(!active()||!current(db,expected))return@tx false
        val revision=db.rawQuery("SELECT source_revision FROM chat_turns WHERE id=? AND state='PENDING' AND attempt=?",arrayOf(turn.id,turn.attempt.toString())).use{if(it.moveToFirst())it.getLong(0)else return@tx false}
        saveProposal(db,turn,revision,proposal,expected,false)
    }
    /** Runs now; a successful action survives reply failure and the later batch review. */
    suspend fun contact(turn:ChatTurn,request:ImmediateContact.Request,expected:CompanionSnapshot,at:Long,active:()->Boolean):Map<String,Any> = repo.tx{db->
        check(active()&&current(db,expected)){"source_changed"}
        val revision=db.rawQuery("SELECT source_revision FROM chat_turns WHERE id=? AND state='PENDING' AND attempt=?",arrayOf(turn.id,turn.attempt.toString())).use{check(it.moveToFirst());it.getLong(0)}
        val source=SourceRef(turn.friendId,turn.userMessageId,revision)
        check(refCurrent(db,source)&&aiAllowed(db,turn.friendId,source.turnId))
        val prior=db.rawQuery("SELECT proposal FROM companion_sources WHERE role=? AND source_id=? AND revision=?",arrayOf(source.characterId,source.turnId.toString(),source.version.toString())).use{if(it.moveToFirst())gson.fromJson(it.getString(0),ExperienceProposal::class.java)else null}
        val future=request.flow?.future?.let{candidate->prior?.understanding?.future?.takeIf{ImmediateContact.same(it,candidate)}?:candidate}
        if(future!=null){
            check(expected.policy=="enabled"){"contact_permission_disabled"}
            require(prior?.understanding?.future==null||prior.understanding.future==future){"contact_already_applied"}
        }
        require(prior?.parameterProposal==null||request.parameters==null||prior.parameterProposal.values==request.parameters.values){"parameter_proposal_already_applied"}
        val flow=prior?.understanding?.let{old->if(future==null)old else old.copy(future=future,dependencies=(old.dependencies+future.evidence).distinct())}?:request.flow
        val p=(prior?:ExperienceProposal(claimKind="none")).copy(understanding=flow,reconsider=flow?.future?.timing,
            timingReferenceAt=if(prior?.understanding?.future!=null)prior.timingReferenceAt?:at else at,parameterProposal=prior?.parameterProposal?:request.parameters)
        check(saveProposal(db,turn,revision,p,expected,true))
        if(future!=null){
            val stored=db.rawQuery("SELECT proposal FROM companion_sources WHERE role=? AND source_id=? AND revision=?",arrayOf(source.characterId,source.turnId.toString(),source.version.toString())).use{check(it.moveToFirst());gson.fromJson(it.getString(0),ExperienceProposal::class.java)}
            check(stored.understanding?.future==future){"contact_evidence_changed"}
        }
        // Parameter suggestions stay pending user approval. No appraisal/score is generated here.
        db.execSQL("UPDATE companion_sources SET committed=1 WHERE role=? AND source_id=? AND revision=?",arrayOf<Any>(source.characterId,source.turnId,source.version))
        if(future!=null){
            val t=future.timing
            db.insertWithOnConflict("companion_opportunities",null,ContentValues().apply{
                put("id","${source.characterId}:${source.turnId}:${source.version}");put("role",source.characterId);put("source_id",source.turnId);put("revision",source.version);put("epoch",expected.epoch);put("policy_version",expected.policyVersion);put("not_before",t.notBefore);put("expires_at",t.expiresAt);put("reason",t.reason);put("state","queued")
            },SQLiteDatabase.CONFLICT_IGNORE)
        }
        val state=if(future==null)null else db.rawQuery("SELECT state FROM companion_opportunities WHERE id=?",arrayOf("${source.characterId}:${source.turnId}:${source.version}")).use{check(it.moveToFirst());it.getString(0)}
        mapOf("success" to true,"status" to "applied","note" to when{
            future==null->"已提出参数建议，等待用户确认；设置尚未改变。"
            state !in setOf("queued","running")->"此前的联系机会已经处理或结束，这次没有重新安排；参数建议仍需用户确认。"
            else->"已保存再次考虑联系的机会，不保证发送或送达；参数建议仍需用户确认。"
        })
    }
    /** Both paths use the same validation; post-reply accounting can never execute a control. */
    private fun saveProposal(db:SQLiteDatabase,turn:ChatTurn,revision:Long,proposal:ExperienceProposal,expected:CompanionSnapshot,accounting:Boolean):Boolean {
        val old=db.rawQuery("SELECT 1 FROM companion_sources WHERE role=? AND source_id=? AND revision=?",arrayOf(turn.friendId,turn.userMessageId.toString(),revision.toString())).use{it.moveToFirst()}
        if(!aiAllowed(db,turn.friendId,turn.userMessageId)&&proposal.control!="forget")return false
        val input=db.rawQuery("SELECT body FROM messages WHERE id=? AND friend_id=?",arrayOf(turn.userMessageId.toString(),turn.friendId)).use{check(it.moveToFirst());it.getString(0)}
        val recordIssues=proposal.recordIssues.orEmpty().toMutableList()
        val relationIssues=proposal.relationIssues.orEmpty().toMutableList()
        val source=SourceRef(turn.friendId,turn.userMessageId,revision)
        val flow=proposal.understanding?.let{RoleRecordSanitizer.sanitize(it,roleContext(db,turn.friendId,source),input){field,code->
            recordIssues+=RelationIssue(field,code);Diagnostics.event("role.input.ignored",mapOf("field" to field,"code" to code))
        }}?.takeIf{it.hasRecords()}
        val relation=proposal.relationInput?.takeIf{row->
            (row.source==source&&RelationInputContract.validStored(row)).also{valid->if(!valid){
                relationIssues+=RelationIssue("关系输入","INVALID_STORED_INPUT")
                Diagnostics.event("relation.input.ignored",mapOf("field" to "关系输入","code" to "INVALID_STORED_INPUT"))
            }}
        }
        val proposal=proposal.copy(understanding=flow,reconsider=if(proposal.understanding!=null)flow?.future?.timing else proposal.reconsider,
            relationInput=relation?.copy(sourceHash=RelationshipBackup.hash(input)),parameterProposal=proposal.parameterProposal?.takeIf{it.source==source&&RelationParameterRules.activeProposal(it.values)},relationAttempted=proposal.relationAttempted||proposal.relationInput!=null,recordIssues=recordIssues.distinct(),relationIssues=relationIssues.distinct())
        val previous=if(old)db.rawQuery("SELECT proposal,committed FROM companion_sources WHERE role=? AND source_id=? AND revision=?",arrayOf(turn.friendId,turn.userMessageId.toString(),revision.toString())).use{it.moveToFirst();gson.fromJson(it.getString(0),ExperienceProposal::class.java) to it.getInt(1)}else null
        val controlFacts=previous?.first?.let{saved->saved.controlChecked&&saved.control in setOf("correct","stop_reminding")&&proposal.controlChecked&&proposal.control==saved.control&&proposal.targets==saved.targets&&proposal.controlQuote==saved.controlQuote&&proposal.requestQuote==saved.requestQuote}==true
        try {
            // A minimum delay is relative to the clock supplied to the model. Network latency
            // must not move that reference or silently extend either saved timestamp.
            CompanionContracts.experience(gson.toJson(proposal),input,proposal.timingReferenceAt?:now(),expected.sources.map{it.turnId}.toSet()+if(controlFacts)previous!!.first.targets else emptyList())
        }catch(e:IllegalArgumentException){
            Diagnostics.failure("companion.accept.contract_failed",e,mapOf("character" to turn.friendId,"turn" to turn.id))
            throw CompanionModelFailure(ChatFailure.STRUCTURED_FORMAT)
        }
        val newControl=!accounting&&proposal.control!="none"&&previous?.first?.control.let{it==null||it=="none"}
        if(newControl)when(proposal.control){
            "forget","correct"->{proposal.targets.forEach{withdraw(db,turn.friendId,it)};if(proposal.control=="forget")db.execSQL("INSERT OR IGNORE INTO companion_blocks(role,source_id) VALUES(?,?)",arrayOf<Any>(turn.friendId,turn.userMessageId))}
            "stop_reminding"->proposal.targets.forEach{db.execSQL("UPDATE companion_opportunities SET state='cancelled' WHERE role=? AND source_id=? AND state IN('queued','running')",arrayOf<Any>(turn.friendId,it))}
        }
        if(previous!=null){
            if(accounting||newControl||previous.second==0&&(controlFacts||previous.first.understanding!=proposal.understanding||previous.first.appraisalProfileStamp!=proposal.appraisalProfileStamp||previous.first.relationInput!=proposal.relationInput||previous.first.relationAttempted!=proposal.relationAttempted||previous.first.relationIssues!=proposal.relationIssues||previous.first.recordIssues!=proposal.recordIssues||previous.first.parameterProposal!=proposal.parameterProposal)){
                check(accounting||newControl||controlFacts||previous.first.memory==proposal.memory&&previous.first.quote==proposal.quote)
                val history=(previous.first.understandingHistory.orEmpty()+listOfNotNull(previous.first.understanding)).distinctBy{it.revision}
                db.execSQL("UPDATE companion_sources SET proposal=? WHERE role=? AND source_id=? AND revision=?",arrayOf<Any>(gson.toJson(proposal.copy(understandingHistory=history)),turn.friendId,turn.userMessageId,revision))
            }
            return true
        }
        db.insertOrThrow("companion_sources",null,ContentValues().apply{put("role",turn.friendId);put("source_id",turn.userMessageId);put("revision",revision);put("created",now());put("proposal",gson.toJson(proposal))})
        return true
    }
    /** New inputs do not invalidate background work. Withdrawals, profile and permissions still do. */
    internal fun accountingCurrent(db:SQLiteDatabase,s:CompanionSnapshot):Boolean {
        val current=state(db,s.role)
        return current.second==s.policyVersion&&repo.characterStamp(db,s.role)==s.characterStamp&&store.persona(db)==s.persona&&s.sources.all{refCurrent(db,it)}&&s.accountingRevision==stableDigest(gson.toJson(RelationStorage.read(db,s.role)))
    }
    internal suspend fun finishReviewBatch(batch:ReplyAccountingQueue.Batch,items:List<ReplyAccountingQueue.Item>,records:Map<Long,com.google.gson.JsonObject?>,s:CompanionSnapshot,reviewAt:Long,active:()->Boolean):Boolean=try{repo.tx{db->
        if(ReplyAccountingQueue.batch(db,s.role)!=batch||!active()||!accountingCurrent(db,s))return@tx false
        if(relationship(db,s.role).attributionIncomplete)return@tx false
        val current=ReplyAccountingQueue.frozen(db,s.role,batch)
        if(current!=items)return@tx false
        check(records.keys==items.filter{ReplyAccountingQueue.valid(db,it)}.map{it.source.turnId}.toSet())
        for(item in items){
            if(ReplyAccountingQueue.valid(db,item)){
                val cached=db.rawQuery("SELECT proposal FROM companion_sources WHERE role=? AND source_id=? AND revision=?",arrayOf(s.role,item.source.turnId.toString(),item.source.version.toString())).use{if(it.moveToFirst())gson.fromJson(it.getString(0),ExperienceProposal::class.java)else null}
                val record=records[item.source.turnId]
                val seen=item.readSources.filter{refCurrent(db,it)&&aiAllowed(db,s.role,it.turnId)}.map{it.turnId}.toSet()
                val parsed=record?.let{RoleRecordParser.parse(it.toString(),item.input,ExperienceProposal(interpretationUnknown=true),roleContext(db,s.role,item.source).beforeCurrent(),reviewAt,item.at,false,false,seen,cached?.takeIf{p->p.controlChecked}).reply}
                val immediateFuture=cached?.understanding?.future
                val flow=parsed?.understanding?.takeIf{it.hasRecords()}?.copy(revision=item.turn.attempt)?.let{f->if(immediateFuture==null)f else f.copy(future=immediateFuture,dependencies=(f.dependencies+immediateFuture.evidence).distinct())}?:cached?.understanding?.takeIf{immediateFuture!=null}
                val p=(parsed?.experience?:cached?:ExperienceProposal(claimKind="none")).copy(understanding=flow,
                    reconsider=cached?.reconsider,timingReferenceAt=cached?.timingReferenceAt?:reviewAt,appraisalProfileStamp=RoleConsistency.appraisalStamp(s.roleProfile),
                    relationInput=parsed?.relationInput,relationAttempted=true,relationIssues=parsed?.relationIssues.orEmpty(),recordIssues=parsed?.recordIssues.orEmpty(),parameterProposal=cached?.parameterProposal)
                check(p.control=="none"||cached?.let{it.controlChecked&&it.control==p.control&&it.targets==p.targets}==true)
                check(saveProposal(db,item.turn,item.source.version,p,s,true))
                // Immediate actions have already been applied. This transaction only publishes review state.
                commitAccounting(db,item.turn,item.source.version,s.copy(sources=s.sources.filter{it.turnId<item.source.turnId}),schedule=false)
                item.turn.assistantMessageId?.let{id->db.insertWithOnConflict("companion_receipts",null,ContentValues().apply{put("message_id",id);put("role",s.role);put("delivery","chat")},SQLiteDatabase.CONFLICT_IGNORE)}
            }
            ReplyAccountingQueue.done(db,item)
        }
        if(relationship(db,s.role).attributionIncomplete)throw ReviewAttributionUnavailable()
        ReplyAccountingQueue.finish(db,s.role,batch)
        true
    }}catch(_:ReviewAttributionUnavailable){
        // Roll back every proposal and done marker first; preserve only the explicit pause flag.
        repo.tx{db->RelationFeeling.read(db,s.role)?.let{RelationFeeling.write(db,s.role,it.copy(attributionIncomplete=true))}}
        Diagnostics.event("reply.accounting.blocked",mapOf("batch" to batch.id,"reason" to "RELATIONSHIP_ATTRIBUTION_UNVERIFIED"))
        CompanionRuntime.changed()
        false
    }
    private fun withdraw(db:SQLiteDatabase,role:String,id:Long){
        require(db.rawQuery("SELECT 1 FROM messages WHERE id=? AND friend_id=?",arrayOf(id.toString(),role)).use{it.moveToFirst()})
        db.execSQL("INSERT OR IGNORE INTO companion_blocks(role,source_id) VALUES(?,?)",arrayOf<Any>(role,id))
        db.execSQL("DELETE FROM companion_vectors WHERE role=? AND source_id=?",arrayOf<Any>(role,id))
        // Locks protect ordinary editing, not a user's withdrawal of AI-use permission.
        val affected=mutableSetOf<String>()
        repo.memories(db,role).forEach{m->
            val historical=db.rawQuery("SELECT json FROM memory_history WHERE memory_id=?",arrayOf(m.id)).use{c->buildList{while(c.moveToNext())add(StoredMemory.from(org.json.JSONObject(c.getString(0))))}}
            if((historical+m).any{it.evidence.any{e->(e.source.characterId==role&&e.source.turnId==id)||(e.image?.imageSource?.characterId==role&&e.image?.imageSource?.turnId==id)}}){
                affected.add(m.id)
                if(m.valid)repo.saveMemory(db,m.copy(version=m.version+1,valid=false))
            }
        }
        repo.candidates(db,role).filter{it.targetId in affected||it.proposed.evidence.any{e->(e.source.characterId==role&&e.source.turnId==id)||(e.image?.imageSource?.characterId==role&&e.image?.imageSource?.turnId==id)}}.forEach{repo.invalidateCandidate(db,role,it.id)}
        bump(db,role)
        relationship(db,role)
    }
    suspend fun forget(role:String,id:Long)=repo.tx{withdraw(it,role,id)}
    /** Raw chat stays visible to its owner; proactive model input follows the same AI-use revocations. */
    suspend fun contactHistory(role:String):List<Message> = repo.read{db->
        db.rawQuery("SELECT m.id,m.friend_id,m.body,m.mine,m.created,COALESCE(t.user_message_id,m.id),r.opportunity_id,o.source_id,o.revision FROM messages m LEFT JOIN chat_turns t ON t.friend_id=m.friend_id AND (t.user_message_id=m.id OR t.assistant_message_id=m.id) LEFT JOIN companion_receipts r ON r.message_id=m.id AND r.role=m.friend_id LEFT JOIN companion_opportunities o ON o.id=r.opportunity_id AND o.role=r.role LEFT JOIN message_state ms ON ms.message_id=m.id WHERE m.friend_id=? AND COALESCE(ms.deleted,0)=0 AND NOT EXISTS(SELECT 1 FROM message_state s WHERE s.deleted=1 AND s.message_id IN(t.user_message_id,t.assistant_message_id)) ORDER BY m.id",arrayOf(role)).use{c->buildList{
            while(c.moveToNext()){
                val sourceAllowed=aiAllowed(db,role,c.getLong(5))&&aiAllowed(db,role,c.getLong(0))
                val contactAllowed=c.isNull(6)||!c.isNull(7)&&repo.sourceValid(db,SourceRef(role,c.getLong(7),c.getLong(8)))
                if(sourceAllowed&&contactAllowed)add(Message(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getLong(4)))
            }
        }}
    }
    suspend fun stopReminder(role:String,id:Long)=repo.tx{db->
        db.execSQL("UPDATE companion_opportunities SET state='cancelled' WHERE role=? AND source_id=? AND state IN('queued','running','send')",arrayOf<Any>(role,id))
    }
    suspend fun sourceOfMessage(role:String,id:Long):Long?=repo.read{db->
        db.rawQuery("SELECT user_message_id FROM chat_turns WHERE friend_id=? AND (user_message_id=? OR assistant_message_id=?) UNION ALL SELECT o.source_id FROM companion_receipts r JOIN companion_opportunities o ON o.id=r.opportunity_id WHERE r.role=? AND r.message_id=? LIMIT 1",arrayOf(role,id.toString(),id.toString(),role,id.toString())).use{if(it.moveToFirst())it.getLong(0)else null}
    }
    suspend fun recall(role:String,terms:List<String>,limit:Int)=repo.read{db->
        db.rawQuery("SELECT source_id,revision,proposal FROM companion_sources WHERE role=? ORDER BY source_id DESC",arrayOf(role)).use{c->buildList{
            while(c.moveToNext()){
                val ref=SourceRef(role,c.getLong(0),c.getLong(1));val p=gson.fromJson(c.getString(2),ExperienceProposal::class.java)
                if(refCurrent(db,ref)&&p.memory.isNotBlank()&&terms.any{it in p.memory.lowercase()})add(AgentRecallCandidate("experience-${ref.turnId}-${ref.version}","episode","有来源的经历",p.memory,sourceSessionId=role,startMessageId=ref.turnId.toString(),current=true))
            }
        }.take(limit)}
    }
    /** Called in ChatLedger's reply transaction (C2). */
    fun finishReply(db:SQLiteDatabase,turn:ChatTurn,s:CompanionSnapshot){
        check(current(db,s))
        val id=db.rawQuery("SELECT assistant_message_id FROM chat_turns WHERE id=? AND state='COMPLETED'",arrayOf(turn.id)).use{check(it.moveToFirst());it.getLong(0)}
        s.sources.filter{it.turnId!=turn.userMessageId}.forEach{r->db.execSQL("INSERT OR IGNORE INTO companion_uses(role,turn_source,source_id,revision) VALUES(?,?,?,?)",arrayOf<Any>(turn.friendId,turn.userMessageId,r.turnId,r.version))}
        db.insertWithOnConflict("companion_receipts",null,ContentValues().apply{put("message_id",id);put("role",turn.friendId);put("delivery","chat")},SQLiteDatabase.CONFLICT_IGNORE)
    }
    /** Kept for existing import/repository callers; the live reply uses finishReply. */
    fun finish(db:SQLiteDatabase,turn:ChatTurn,s:CompanionSnapshot,p:ExperienceProposal){
        check(current(db,s))
        val ids=db.rawQuery("SELECT assistant_message_id,source_revision FROM chat_turns WHERE id=? AND state='COMPLETED'",arrayOf(turn.id)).use{check(it.moveToFirst());it.getLong(0) to it.getLong(1)}
        commitAccounting(db,turn,ids.second,s,p)
        ReplyAccountingQueue.pending(db,turn.friendId).firstOrNull{it.turn.id==turn.id&&it.source.version==ids.second}?.let{ReplyAccountingQueue.done(db,it)}
        db.insertOrThrow("companion_receipts",null,ContentValues().apply{put("message_id",ids.first);put("role",turn.friendId);put("delivery","chat")})
    }
    private fun commitAccounting(db:SQLiteDatabase,turn:ChatTurn,revision:Long,s:CompanionSnapshot,fallback:ExperienceProposal=ExperienceProposal(),schedule:Boolean=true){
        val p=db.rawQuery("SELECT proposal FROM companion_sources WHERE role=? AND source_id=? AND revision=?",arrayOf(turn.friendId,turn.userMessageId.toString(),revision.toString())).use{if(it.moveToFirst())gson.fromJson(it.getString(0),ExperienceProposal::class.java)else fallback}
        db.execSQL("UPDATE companion_sources SET committed=1 WHERE role=? AND source_id=? AND revision=?",arrayOf<Any>(turn.friendId,turn.userMessageId,revision))
        s.sources.filter{it.turnId!=turn.userMessageId}.forEach{r->db.execSQL("INSERT OR IGNORE INTO companion_uses(role,turn_source,source_id,revision) VALUES(?,?,?,?)",arrayOf<Any>(turn.friendId,turn.userMessageId,r.turnId,r.version))}
        relationship(db,turn.friendId)
        val reason=p.understanding?.future?.timing?:p.reconsider
        if(schedule&&reason!=null&&p.control=="none"&&!p.controlChecked&&s.policy=="enabled"){
            db.insertWithOnConflict("companion_opportunities",null,ContentValues().apply{
                put("id","${turn.friendId}:${turn.userMessageId}:$revision");put("role",turn.friendId);put("source_id",turn.userMessageId);put("revision",revision);put("epoch",s.epoch);put("policy_version",s.policyVersion);put("not_before",reason.notBefore);put("expires_at",reason.expiresAt);put("reason",reason.reason);put("state","queued")
            },SQLiteDatabase.CONFLICT_IGNORE)
        }
    }
    private fun opportunities(db:SQLiteDatabase,where:String,args:Array<String>)=db.rawQuery("SELECT id,role,source_id,revision,epoch,policy_version,not_before,expires_at,reason,state,attempt FROM companion_opportunities WHERE $where",args).use{c->buildList{while(c.moveToNext())add(CompanionOpportunity(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getLong(5),c.getLong(6),c.getLong(7),c.getString(8),c.getString(9),c.getInt(10)))}}
    suspend fun queued()=repo.read{opportunities(it,"state='queued'",emptyArray())}
    /** Source validity is checked before model IO and again before sending; no contact quota. */
    private fun contactAllowed(db:SQLiteDatabase,o:CompanionOpportunity):ContactPermission {
        if(o.id.startsWith("relationship:"))return ContactPermission.Denied
        val latest=db.rawQuery("SELECT MAX(user_message_id) FROM chat_turns WHERE friend_id=?",arrayOf(o.role)).use{it.moveToFirst();it.getLong(0)}
        if(latest!=o.source)return ContactPermission.Denied
        return ContactPermission.Allowed
    }
    private fun permission(db:SQLiteDatabase,o:CompanionOpportunity):ContactPermission {
        val s=state(db,o.role)
        if(s.third!="enabled"||s.second!=o.policyVersion||!refCurrent(db,SourceRef(o.role,o.source,o.revision)))return ContactPermission.Denied
        return contactAllowed(db,o)
    }
    private fun permitted(db:SQLiteDatabase,o:CompanionOpportunity)=permission(db,o)==ContactPermission.Allowed
    /** Fence any old worker attempt when permission or source validity has changed. */
    private fun settlePermission(db:SQLiteDatabase,o:CompanionOpportunity):Boolean {
        val result=if(hasReceipt(db,o.id))ContactPermission.Denied else permission(db,o)
        if(result==ContactPermission.Allowed)return true
        db.execSQL("UPDATE companion_opportunities SET state='cancelled',attempt=attempt+1,owner='' WHERE id=?",arrayOf<Any>(o.id))
        return false
    }
    private fun pending(db:SQLiteDatabase,role:String)=db.rawQuery("SELECT 1 FROM chat_turns WHERE friend_id=? AND state='PENDING'",arrayOf(role)).use{it.moveToFirst()}
    suspend fun inputPending(role:String)=repo.read{pending(it,role)}
    private fun eligible(db:SQLiteDatabase,o:CompanionOpportunity)=permitted(db,o)&&!pending(db,o.role)
    private fun hasReceipt(db:SQLiteDatabase,id:String)=db.rawQuery("SELECT 1 FROM companion_receipts WHERE opportunity_id=?",arrayOf(id)).use{it.moveToFirst()}
    private fun requeue(db:SQLiteDatabase,o:CompanionOpportunity){
        if(!hasReceipt(db,o.id)&&!settlePermission(db,o))return
        val next=if(hasReceipt(db,o.id))"send" else "queued"
        // Fence the old attempt before another Worker can claim; saved timestamps stay intact.
        db.execSQL("UPDATE companion_opportunities SET state=?,attempt=attempt+1,owner='' WHERE id=? AND state='running'",arrayOf<Any>(next,o.id))
    }
    suspend fun claim(id:String,owner:String):CompanionOpportunity?=repo.tx{db->
        val o=opportunities(db,"id=?",arrayOf(id)).firstOrNull()?:return@tx null
        if(o.state!="queued"||o.notBefore>now())return@tx null
        if(!settlePermission(db,o))return@tx null
        if(pending(db,o.role))return@tx null // Busy is temporary, not withdrawal of the reason.
        val epoch=state(db,o.role).first
        db.execSQL("UPDATE companion_opportunities SET state='running',epoch=?,attempt=attempt+1,owner=? WHERE id=? AND state='queued'",arrayOf<Any>(epoch,owner,id))
        o.copy(state="running",epoch=epoch,attempt=o.attempt+1)
    }
    suspend fun current(o:CompanionOpportunity,owner:String)=repo.tx{db->owned(db,o,owner)&&state(db,o.role).first==o.epoch&&eligible(db,o)}
    private fun owned(db:SQLiteDatabase,o:CompanionOpportunity,owner:String)=db.rawQuery("SELECT 1 FROM companion_opportunities WHERE id=? AND state='running' AND attempt=? AND owner=? AND epoch=?",arrayOf(o.id,o.attempt.toString(),owner,o.epoch.toString())).use{it.moveToFirst()}
    suspend fun conclude(o:CompanionOpportunity,owner:String,d:OpportunityDecision,s:CompanionSnapshot,executionCurrent:()->Boolean={true},decisionAt:Long=now(),modelCurrent:()->Boolean):Long?=repo.tx{db->
        if(!owned(db,o,owner))return@tx null
        if(!settlePermission(db,o))return@tx null
        if(!modelCurrent()){db.execSQL("UPDATE companion_opportunities SET state='failed' WHERE id=?",arrayOf<Any>(o.id));return@tx null}
        if(!executionCurrent()||pending(db,o.role)||state(db,o.role).first!=o.epoch||s.role!=o.role||s.epoch!=o.epoch||!current(db,s)){
            requeue(db,o);return@tx null
        }
        // Re-check learning IDs against the authoritative projection at commit time (current(db,s) already holds).
        val d=CompanionContracts.decisionForReply(gson.toJson(d),decisionAt,o.expiresAt,roleContext(db,o.role).learning)
        if(d.action=="defer"){
            require(d.nextAt!=null&&d.nextAt>=decisionAt)
            db.execSQL("UPDATE companion_opportunities SET state='queued',not_before=?,reason=? WHERE id=?",arrayOf<Any>(d.nextAt,d.reason,o.id));return@tx null
        }
        db.execSQL("UPDATE companion_opportunities SET state=?,reason=? WHERE id=?",arrayOf<Any>(d.action,d.reason,o.id))
        if(d.action!="send")return@tx null
        val message=db.insertOrThrow("messages",null,ContentValues().apply{put("friend_id",o.role);put("body",d.text);put("mine",0);put("created",now())})
        s.sources.forEach{r->db.execSQL("INSERT OR IGNORE INTO companion_uses(role,turn_source,source_id,revision) VALUES(?,?,?,?)",arrayOf<Any>(o.role,message,r.turnId,r.version))}
        db.insertOrThrow("companion_receipts",null,ContentValues().apply{put("message_id",message);put("role",o.role);put("opportunity_id",o.id);put("delivery","ready")})
        db.insertOrThrow("companion_action_learning",null,ContentValues().apply{put("message_id",message);put("role",o.role);put("considered",ProactiveLearningUse.encode(d.consideredLearningIds));put("used",ProactiveLearningUse.encode(d.usedLearningIds));put("judgment",d.judgmentKind)})
        // A new model-chosen opportunity, not a retry of this already-sent message.
        // Receipt and successor commit together, so stale/repeated workers cannot create either twice.
        d.nextAt?.let{next->
            db.insertOrThrow("companion_opportunities",null,ContentValues().apply{
                put("id","contact:$message");put("role",o.role);put("source_id",o.source);put("revision",o.revision)
                put("epoch",o.epoch);put("policy_version",o.policyVersion);put("not_before",next);put("expires_at",0L)
                put("reason",d.reason);put("state","queued")
            })
        }
        relationship(db,o.role)
        message
    }
    suspend fun release(o:CompanionOpportunity,owner:String,recover:Boolean,failure:ContactFailureKind?=null,detail:String="")=repo.tx{db->
        if(owned(db,o,owner)){
            if(recover)requeue(db,o)
            else if(settlePermission(db,o)){
                db.execSQL("UPDATE companion_opportunities SET state='failed',fail_kind=?,fail_at=? WHERE id=?",arrayOf<Any>((failure?:ContactFailureKind.OTHER).name,now(),o.id))
                db.delete("preferences","key=?",arrayOf(ContactFailures.errorKey(o.id)))
                if(detail.isNotBlank())db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",ContactFailures.errorKey(o.id));put("value",detail)},SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }
    suspend fun latestContactFailure(role:String):ContactFailure?=repo.read{db->
        db.rawQuery("SELECT o.fail_kind,o.fail_at,(SELECT value FROM preferences WHERE key='${ContactFailures.ERROR_PREFIX}'||o.id) FROM companion_opportunities o JOIN companion_state s ON s.role=o.role WHERE o.role=? AND o.policy_version=s.policy_version AND o.fail_kind IS NOT NULL AND NOT EXISTS(SELECT 1 FROM companion_receipts r JOIN messages m ON m.id=r.message_id WHERE r.role=o.role AND r.opportunity_id IS NOT NULL AND m.created>=o.fail_at) ORDER BY o.fail_at DESC LIMIT 1",arrayOf(role)).use{c->
            if(c.moveToFirst())ContactFailure(ContactFailureKind.valueOf(c.getString(0)),c.getLong(1),if(c.isNull(2))"" else c.getString(2))else null
        }
    }
    suspend fun retryable(role:String)=repo.tx{db->opportunities(db,"role=? AND state='failed'",arrayOf(role)).count{eligible(db,it)}}
    suspend fun retryFailed(role:String)=repo.tx{db->
        opportunities(db,"role=? AND state='failed'",arrayOf(role)).forEach{o->
            val next=now()+ContactRetry.FAILED_RETRY_DELAY_MS
            if(eligible(db,o))db.execSQL("UPDATE companion_opportunities SET state='queued',not_before=? WHERE id=? AND state='failed'",arrayOf<Any>(next,o.id))
        }
    }
    /** Reuse the existing visibility receipt and message clock; do not invent a separate read/reply state. */
    internal fun contactFacts(db:SQLiteDatabase,role:String,at:Long)=db.rawQuery("SELECT r.message_id,r.read_at,m.created FROM companion_receipts r JOIN messages m ON m.id=r.message_id AND m.friend_id=r.role LEFT JOIN message_state s ON s.message_id=m.id WHERE r.role=? AND COALESCE(s.deleted,0)=0 ORDER BY r.message_id DESC LIMIT 20",arrayOf(role)).use{c->buildList{
        while(c.moveToNext())if(aiAllowed(db,role,c.getLong(0)))add(ExperiencePresentation.receipt(c.getLong(0),if(c.isNull(1))null else c.getLong(1),at,createdAt=c.getLong(2)))
    }.joinToString("\n")}
    suspend fun contactFacts(role:String,at:Long=clock())=repo.read{contactFacts(it,role,at)}
    suspend fun receipts(role:String?=null)=repo.read{db->db.rawQuery("SELECT message_id,read_at,requested_at,observed_at,clicked_at,delivery FROM companion_receipts"+(if(role!=null)" WHERE role=?"else""),role?.let{arrayOf(it)}).use{c->buildList{while(c.moveToNext())add(CompanionReceipt(c.getLong(0),if(c.isNull(1))null else c.getLong(1),if(c.isNull(2))null else c.getLong(2),if(c.isNull(3))null else c.getLong(3),if(c.isNull(4))null else c.getLong(4),c.getString(5)))}}}
    suspend fun notificationCurrent(id:Long)=repo.tx{db->notificationCurrent(db,id)}
    suspend fun publishIfCurrent(id:Long,publish:()->Boolean):Boolean=repo.tx{db->notificationCurrent(db,id)&&publish()}
    private fun notificationCurrent(db:SQLiteDatabase,id:Long):Boolean {
        val key=db.rawQuery("SELECT opportunity_id,read_at FROM companion_receipts WHERE message_id=?",arrayOf(id.toString())).use{if(!it.moveToFirst()||!it.isNull(1)||it.isNull(0))return false;it.getString(0)}
        val o=opportunities(db,"id=?",arrayOf(key)).firstOrNull()?:return false
        return o.state=="send"&&eligible(db,o)&&aiAllowed(db,o.role,id)
    }
    suspend fun markRead(role:String,id:Long,visible:()->Boolean={true})=repo.tx{db->if(visible())db.execSQL("UPDATE companion_receipts SET read_at=COALESCE(read_at,?) WHERE message_id=? AND role=?",arrayOf<Any>(now(),id,role))}
    suspend fun clicked(role:String,id:Long)=repo.tx{db->db.execSQL("UPDATE companion_receipts SET clicked_at=COALESCE(clicked_at,?) WHERE message_id=? AND role=?",arrayOf<Any>(now(),id,role))}
    /** C3 durable claim precedes the external API. A crash/exception is unknown, never auto-post again. */
    suspend fun claimNotification(id:Long):Boolean=repo.tx{db->notificationCurrent(db,id)&&db.update("companion_receipts",ContentValues().apply{put("delivery","claimed");put("claim_owner",CompanionRuntime.owner);put("claimed_at",now())},"message_id=? AND delivery='ready' AND read_at IS NULL",arrayOf(id.toString()))==1}
    suspend fun delivery(id:Long,value:String,observed:Boolean=false)=repo.tx{db->db.update("companion_receipts",ContentValues().apply{put("delivery",value);if(value=="requested")put("requested_at",now());if(observed)put("observed_at",now())},"message_id=? AND delivery='claimed'",arrayOf(id.toString()))}
}
internal const val RELATION_SUMMARY_LABEL="当前关系小结"
internal fun proactiveReferences(values:List<PromptReference>)=values.filterNot{it.label==RELATION_SUMMARY_LABEL||it.label==CompanionPrompt.TONE_LABEL||it.label==ExperiencePresentation.MESSAGE_STATE_LABEL}
