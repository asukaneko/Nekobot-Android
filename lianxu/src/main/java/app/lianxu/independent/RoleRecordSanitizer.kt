package app.lianxu.independent

/** Commit boundary: independently valid records survive; no missing evidence is invented. */
internal object RoleRecordSanitizer {
    fun sanitize(flow:RoleUnderstanding,context:RoleContext,input:String,
        ignored:(String,String)->Unit):RoleUnderstanding {
        val context=context.beforeCurrent()
        val empty=flow.copy(schemaVersion=1,revision=flow.revision.coerceAtLeast(1),appraisal=null,feelings=emptyList(),goal=null,
            choices=emptyList(),selected="",judgment=null,feedback=emptyList(),learning=null,usedLearningIds=emptyList(),
            consideredLearningIds=emptyList(),future=null,dependencies=emptyList())
        if(flow.schemaVersion!=1||flow.revision<1){ignored("schemaVersion","INVALID_ROLE_PLAN");return empty}
        val retained=linkedSetOf<RoleEvidence>()
        fun keep(field:String,value:RoleUnderstanding):RoleUnderstanding? = try {
            val required=linkedSetOf<RoleEvidence>()
            required+=value.appraisal?.evidence.orEmpty();required+=value.goal?.evidence.orEmpty();required+=value.judgment?.evidence.orEmpty()
            value.feelings.forEach{required+=it.evidence};value.choices.forEach{required+=it.evidence}
            value.learning?.let{l->required+=l.evidence
                if(l.phase!="propose"){
                    val previous=context.learning.singleOrNull{it.id==l.id}?:throw RoleContractViolation("LEARNING_TARGET")
                    val entry=context.entries.singleOrNull{it.source==previous.source}?:throw RoleContractViolation("NO_INTERPRETATION")
                    required+=RoleEvidence(previous.source,"interpretation",interpretationRevision=entry.flow.revision)
                }
            }
            required+=value.future?.evidence.orEmpty()
            value.feedback.forEach{f->
                val action=context.actions.singleOrNull{it.replyId==f.actionMessage}?:throw RoleContractViolation("FEEDBACK_REQUIRES_COMMITTED_ACTION")
                required+=RoleEvidence(action.source,"action",f.actionMessage);required+=RoleEvidence(context.current)
            }
            value.consideredLearningIds.forEach{id->
                val previous=context.learning.singleOrNull{it.id==id}?:throw RoleContractViolation("UNKNOWN_LEARNING")
                val entry=context.entries.singleOrNull{it.source==previous.source}?:throw RoleContractViolation("NO_INTERPRETATION")
                required+=RoleEvidence(previous.source,"interpretation",interpretationRevision=entry.flow.revision)
            }
            if(!flow.dependencies.containsAll(required))throw RoleContractViolation("DEPENDENCY_SET")
            value.copy(dependencies=required.toList()).also{RoleUnderstandingContract.validateAccepted(it,context,input);retained+=required}
        }catch(e:RuntimeException){ignored(field,(e as? RoleContractViolation)?.code?:"INVALID_RECORD");null}
        val appraisal=flow.appraisal?.let{keep("appraisal",empty.copy(appraisal=it))?.appraisal}
        val feelings=flow.feelings.mapNotNull{keep("feelings.item",empty.copy(feelings=listOf(it)))?.feelings?.single()}
        val goal=flow.goal?.let{keep("goal",empty.copy(goal=it))?.goal}
        val selection=if(flow.choices.isEmpty()&&flow.selected.isEmpty())null else keep("choices",empty.copy(choices=flow.choices,selected=flow.selected))
        val judgment=flow.judgment?.let{keep("judgment",empty.copy(judgment=it))?.judgment}
        val feedback=flow.feedback.mapNotNull{keep("feedback.item",empty.copy(feedback=listOf(it)))?.feedback?.single()}
        val learning=flow.learning?.let{keep("learning",empty.copy(learning=it))?.learning}
        val future=flow.future?.let{keep("future",empty.copy(future=it))?.future}
        val use=keep("learningUse",empty.copy(judgment=judgment,usedLearningIds=flow.usedLearningIds,consideredLearningIds=flow.consideredLearningIds))
        if(flow.dependencies.toSet()!=retained)ignored("dependencies","UNUSED_OR_DISCARDED_DEPENDENCY")
        return empty.copy(appraisal=appraisal,feelings=feelings,goal=goal,choices=selection?.choices.orEmpty(),selected=selection?.selected.orEmpty(),
            judgment=judgment,feedback=feedback,learning=learning,future=future,usedLearningIds=use?.usedLearningIds.orEmpty(),
            consideredLearningIds=use?.consideredLearningIds.orEmpty(),dependencies=retained.toList())
    }
}
