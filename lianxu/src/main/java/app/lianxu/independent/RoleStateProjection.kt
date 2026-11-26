package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson

/** Rebuildable views over source revisions, committed replies and delivery receipts. */
internal suspend fun CompanionRepository.roleContext(role:String,current:SourceRef):RoleContext=ProcessingRepository(store).read{db->roleContext(db,role,current)}
internal fun CompanionRepository.roleContext(db:SQLiteDatabase,role:String,current:SourceRef=SourceRef(role,0,0)):RoleContext {
    data class Row(val source:SourceRef,val proposal:ExperienceProposal,val committed:Boolean,val replyId:Long?,val reply:String?,val readAt:Long?,val requestedAt:Long?,val input:String,val at:Long)
    val gson=Gson()
    val rows=db.rawQuery("SELECT s.source_id,s.revision,s.proposal,s.committed,t.assistant_message_id,m.body,r.read_at,r.requested_at,u.body,u.created FROM companion_sources s JOIN chat_turns t ON t.friend_id=s.role AND t.user_message_id=s.source_id AND t.source_revision=s.revision JOIN messages u ON u.id=t.user_message_id AND u.friend_id=s.role LEFT JOIN messages m ON m.id=t.assistant_message_id LEFT JOIN companion_receipts r ON r.message_id=t.assistant_message_id WHERE s.role=? ORDER BY s.source_id",arrayOf(role)).use{c->buildList{
        while(c.moveToNext()){
            val ref=SourceRef(role,c.getLong(0),c.getLong(1));if(!refCurrent(db,ref) || !CompanionRepository.aiAllowed(db,role,ref.turnId))continue
            val p=runCatching{gson.fromJson(c.getString(2),ExperienceProposal::class.java)}.getOrNull()?:continue
            add(Row(ref,p,c.getInt(3)==1,if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))null else c.getString(5),if(c.isNull(6))null else c.getLong(6),if(c.isNull(7))null else c.getLong(7),c.getString(8),c.getLong(9)))
        }
    }}
    val known=rows.associate{it.source.turnId to it.source}.toMutableMap()
    db.rawQuery("SELECT user_message_id,source_revision FROM chat_turns WHERE friend_id=? AND state='COMPLETED'",arrayOf(role)).use{c->while(c.moveToNext()){val ref=SourceRef(role,c.getLong(0),c.getLong(1));if(refCurrent(db,ref)&&CompanionRepository.aiAllowed(db,role,ref.turnId))known[ref.turnId]=ref}}
    if(current.turnId>0&&refCurrent(db,current)&&CompanionRepository.aiAllowed(db,role,current.turnId))known[current.turnId]=current
    val rawActions=rows.mapNotNull{row->
        if(!row.committed||row.replyId==null||row.reply==null||!CompanionRepository.aiAllowed(db,role,row.source.turnId)||!CompanionRepository.aiAllowed(db,role,row.replyId))null else {
            val flow=row.proposal.understanding
            RoleActualAction(row.source,runCatching{flow?.selectedChoice()?.action}.getOrNull()?:"legacy_reply",flow?.appraisal?.situation?:"unknown",row.replyId,row.reply,
                flow?.usedLearningIds.orEmpty(),flow?.consideredLearningIds.orEmpty(),flow?.goal?.origin?:"unknown",row.readAt,row.requestedAt)
        }
    }.toMutableList()
    db.rawQuery("SELECT o.source_id,o.revision,m.id,m.body,r.read_at,r.requested_at,s.proposal,a.considered,a.used FROM companion_receipts r JOIN companion_opportunities o ON o.id=r.opportunity_id AND o.role=r.role JOIN messages m ON m.id=r.message_id AND m.friend_id=r.role LEFT JOIN companion_sources s ON s.role=o.role AND s.source_id=o.source_id AND s.revision=o.revision LEFT JOIN companion_action_learning a ON a.message_id=r.message_id AND a.role=r.role WHERE r.role=?",arrayOf(role)).use{c->while(c.moveToNext()){
        val ref=SourceRef(role,c.getLong(0),c.getLong(1));val id=c.getLong(2)
        val kind=if(c.isNull(6))"legacy_contact"else runCatching{gson.fromJson(c.getString(6),ExperienceProposal::class.java).understanding?.future?.kind}.getOrNull()?:"legacy_contact"
        val share=kind in setOf("interest_share","shared_continuation")
        val (considered,used)=ProactiveLearningUse.decode(if(c.isNull(7))null else c.getString(7),if(c.isNull(8))null else c.getString(8))
        val origin=when(kind){"interest_share"->"role_interest";"shared_continuation"->"shared_goal";"follow_up","actual_result"->"current_request";else->"unknown"}
        if(refCurrent(db,ref)&&CompanionRepository.aiAllowed(db,role,ref.turnId)&&CompanionRepository.aiAllowed(db,role,id))rawActions+=RoleActualAction(ref,if(share)"share"else"contact","proactive_$kind",id,c.getString(3),used,considered,origin,if(c.isNull(4))null else c.getLong(4),if(c.isNull(5))null else c.getLong(5))
    }}
    val entries=mutableListOf<RoleEntry>()
    for(row in rows){
        val flow=row.proposal.understanding
        val valid=flow!=null&&runCatching{
            val previousActions=rawActions.filter{it.replyId<row.source.turnId&&(rows.firstOrNull{r->r.source==it.source}?.proposal?.understanding==null||entries.any{e->e.source==it.source})}
            RoleUnderstandingContract.validateAccepted(flow,RoleContext(role,row.source,known,entries,previousActions,RoleLearningProjector.project(entries,previousActions),emptyList()),row.input,stored=true)
            flow.dependencies.all{e->
            e.source.characterId==role&&CompanionRepository.aiAllowed(db,role,e.source.turnId)&&refCurrent(db,e.source)&&(e.source.turnId<row.source.turnId||e.source==row.source&&e.layer=="fact")&&when(e.layer){
                "fact"->true
                "interpretation"->entries.any{it.source==e.source&&it.flow.revision==e.interpretationRevision}
                "action"->e.messageId!=null&&e.messageId<row.source.turnId&&rawActions.any{it.source==e.source&&it.replyId==e.messageId}&&(rows.firstOrNull{it.source==e.source}?.proposal?.understanding==null||entries.any{it.source==e.source})
                else->false
            }
        }}.getOrDefault(false)
        val actual=if(flow==null||valid)rawActions.firstOrNull{it.replyId==row.replyId}else null
        if(valid)entries+=RoleEntry(row.source,requireNotNull(flow),actual,row.proposal.claimKind,row.at)
    }
    val facts=rows.filter{it.proposal.memory.isNotBlank()}.map{CompanionExperience(it.source,it.proposal.memory,it.proposal.quote)}
    val actions=rawActions.filter{a->(current.turnId==0L||a.replyId<current.turnId)&&(rows.firstOrNull{it.source==a.source}?.proposal?.understanding==null||entries.any{it.source==a.source})}
    val relation=relationship(db,role).state
    val relationRows=relationshipInputs(db,role).filter{(current.turnId==0L||it.source.turnId<current.turnId)&&CompanionRepository.aiAllowed(db,role,it.source.turnId)}
    val controls=LearningControls.read(db,role);val at=clock()
    return RoleContext(role,current,known,entries,actions,LearningControls.apply(RoleLearningProjector.project(entries,actions,controls),controls,at),facts,relationRows,relation.promises,RelationshipRecall.recent(relationRows,relation,clock(),RelationshipRecall.resolver(ProcessingRepository(store),db,role,clock())),relation,controls,at)
}

/** Model-facing history omits inactive learning while the local evidence ledger stays complete. */
internal fun RoleContext.visibleLearning():RoleContext {
    val hidden=learning.filter{it.state=="faded" || learningControls[it.id]?.paused==true}.mapTo(mutableSetOf()){it.id}
    learningControls.filterValues{it.deleted}.keys.let(hidden::addAll)
    if(hidden.isEmpty())return this
    fun RoleActualAction.visible()=copy(usedLearningIds=usedLearningIds-hidden,consideredLearningIds=consideredLearningIds-hidden)
    return copy(learning=learning.filterNot{it.id in hidden},actions=actions.map{it.visible()},entries=entries.map{entry->
        val flow=entry.flow
        entry.copy(action=entry.action?.visible(),flow=flow.copy(learning=flow.learning?.takeUnless{it.id in hidden},
            usedLearningIds=flow.usedLearningIds-hidden,consideredLearningIds=flow.consideredLearningIds-hidden))
    })
}

internal fun RoleContext.prompt(now:Long=System.currentTimeMillis(),zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):String=buildString{
    val refs=mutableListOf<String>()
    append("[相处中留下的看法、习惯与主动交流]\n")
    learning.filter{it.state!="paused" && it.state!="faded"}.forEachIndexed{i,item->val label="看法和习惯${i+1}"
        append("$label（${when(item.kind){"interest"->"兴趣";"habit"->"习惯";"viewpoint"->"看法";else->"其他学习"}}）：${item.content}；${item.familiarity}，适用于${item.scope}；${when(item.state){"candidate"->"只是刚形成的想法";"developing"->"还在尝试，可以修正";"active"->"目前已经确定";else->"还不确定能否沿用"}}；${item.reason}。当前明确请求优先，不需要对方批准才能形成看法。\n")
        refs+="$label：变化 ${item.id}，版本 ${item.version}；${ExperiencePresentation.reference(item.source)}"
    }
    val ordinary=entries.mapNotNull{it.action?.replyId}.toSet()
    actions.filter{it.replyId !in ordinary&&it.situation.startsWith("proactive_")}.forEachIndexed{i,a->val label="主动交流${i+1}"
        append("$label（${when(a.situation){"proactive_interest_share"->"分享兴趣";"proactive_shared_continuation"->"接着共同的话题";"proactive_follow_up"->"回访";"proactive_actual_result"->"了解实际结果";else->"主动联系"}}）：你确实主动说过「${a.reply}」。\n")
        refs+="$label：回复 ${a.replyId}；${ExperiencePresentation.reference(a.source)}"+if(a.usedLearningIds.isEmpty())""else"；当时使用的变化 ${a.usedLearningIds.joinToString()}"
    }
    append("这些记录不直接改写人设原文或权限；稳定身份与核心原则不变，普通偏好与相处习惯可随有依据的互动自然调整。自己的判断无需讨好对方，但须忠于依据并说明不确定，不能把未执行的事说成已完成。")
    if(refs.isNotEmpty())append("\n${ExperiencePresentation.REFS}\n").append(refs.joinToString("\n"))
}
