package app.lianxu.independent

internal data class LearningRecord(
    val state:RoleLearningState,
    val updatedAt:Long?,
    val evidence:List<LearningEvidence>,
    val changes:List<LearningChange>,
)

internal data class LearningEvidence(
    val messageId:Long,
    val text:String,
    val mine:Boolean,
    val created:Long,
    val kind:String,
)

internal data class LearningChange(
    val sourceId:Long,
    val created:Long?,
    val phase:String,
    val content:String,
    val reason:String,
    val evidence:List<LearningEvidence>,
)

/** A read-only presentation of the same valid projection used by conversations. */
internal suspend fun CompanionRepository.learningRecords(role:String):List<LearningRecord> = ProcessingRepository(store).read { db ->
    val context=roleContext(db,role)
    if(context.learning.isEmpty())return@read emptyList()
    val current=context.learning.associateBy{it.id}
    val entries=context.entries.associateBy{it.source.turnId}
    val actions=context.actions.associateBy{it.replyId}
    val messages=mutableMapOf<Long,Message?>()
    fun message(id:Long):Message? = messages.getOrPut(id){
        db.rawQuery("SELECT m.body,m.mine,m.created FROM messages m LEFT JOIN message_state s ON s.message_id=m.id WHERE m.friend_id=? AND m.id=? AND COALESCE(s.deleted,0)=0",arrayOf(role,id.toString())).use { c ->
            if(c.moveToFirst())Message(id,role,c.getString(0),c.getInt(1)==1,c.getLong(2))else null
        }
    }
    fun readEvidence(ref:RoleEvidence):LearningEvidence? {
        if(ref.source.characterId!=role||context.knownSources[ref.source.turnId]!=ref.source||!refCurrent(db,ref.source)||!CompanionRepository.aiAllowed(db,role,ref.source.turnId))return null
        return when(ref.layer){
            "fact" -> message(ref.source.turnId)?.takeIf{it.mine}?.let{LearningEvidence(it.id,it.text,true,it.created,"fact")}
            "action" -> {
                val action=actions[ref.messageId]?.takeIf{it.source==ref.source}?:return null
                if(!CompanionRepository.aiAllowed(db,role,action.replyId))return null
                message(action.replyId)?.takeUnless{it.mine}?.let{LearningEvidence(it.id,it.text,false,it.created,"action")}
            }
            "interpretation" -> {
                val entry=entries[ref.source.turnId]?.takeIf{it.source==ref.source&&it.flow.revision==ref.interpretationRevision}?:return null
                val source=message(ref.source.turnId)?:return null
                // This is a saved concise interpretation, never user speech or native reasoning.
                LearningEvidence(source.id,"${entry.flow.appraisal?.meaning}\n${entry.flow.judgment?.position}",false,source.created,"interpretation")
            }
            else -> null
        }
    }
    fun evidence(refs:List<RoleEvidence>)=refs.mapNotNull(::readEvidence).distinctBy{it.messageId to it.kind}
    val contents=mutableMapOf<String,String>()
    val changes=mutableMapOf<String,MutableList<LearningChange>>()
    for(entry in context.entries.sortedBy{it.source.turnId}){
        val sourceAt=message(entry.source.turnId)?.created
        entry.flow.learning?.takeIf{it.id in current}?.let { change ->
            val previous=contents[change.id]
            val accepted=when(change.phase){
                "propose" -> previous==null
                "revise","pause" -> previous!=null
                else -> false
            }
            if(accepted){
                val content=if(change.phase=="pause")requireNotNull(previous)else change.content
                contents[change.id]=content
                val reason=when(change.phase){
                    "propose" -> "根据这次交流提出，尚待后续经历验证。"
                    "revise" -> "根据新的交流依据修订内容。"
                    else -> "根据这次交流暂停使用。"
                }
                changes.getOrPut(change.id){mutableListOf()}.add(LearningChange(entry.source.turnId,sourceAt,change.phase,content,reason,evidence(change.evidence)))
            }
        }
        for(feedback in entry.flow.feedback.filter{it.verdict=="unhelpful"}){
            val action=actions[feedback.actionMessage]?:continue
            for(id in action.usedLearningIds){
                if(current[id]?.kind!="habit")continue
                val content=contents[id]?:continue
                val refs=listOf(RoleEvidence(entry.source),RoleEvidence(action.source,"action",action.replyId))
                changes.getOrPut(id){mutableListOf()}.add(LearningChange(entry.source.turnId,sourceAt,"pause",content,
                    "反馈：“${feedback.quote}”；这次反馈使相处方法暂停使用。",evidence(refs)))
            }
        }
    }
    context.learning.map { state ->
        val history=changes[state.id].orEmpty().toList()
        LearningRecord(state,message(state.source.turnId)?.created,
            history.flatMap{it.evidence}.distinctBy{it.messageId to it.kind},history)
    }.sortedWith(compareByDescending<LearningRecord>{it.updatedAt?:Long.MIN_VALUE}.thenBy{it.state.id})
}
