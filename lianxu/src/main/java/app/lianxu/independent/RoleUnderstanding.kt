package app.lianxu.independent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Public, concise reasons and evidence, never a provider's hidden reasoning chain. */
internal data class RoleEvidence(val source:SourceRef,val layer:String="fact",val messageId:Long?=null,val interpretationRevision:Int?=null)
internal data class RoleAppraisal(val event:String,val meaning:String,val certainty:String,val relevance:String,val attribution:String,val situation:String,val eventRelation:String,val uncertainty:List<String>,val evidence:List<RoleEvidence>)
internal data class RoleFeeling(val kind:String,val degree:String,val objectText:String,val reason:String,val evidence:List<RoleEvidence>)
internal data class RoleGoal(val description:String,val origin:String,val state:String,val reason:String,val evidence:List<RoleEvidence>)
internal data class RoleChoice(val id:String,val action:String,val reason:String,val evidence:List<RoleEvidence>)
internal data class RoleJudgment(val position:String,val kind:String,val evidence:List<RoleEvidence>)
internal data class RoleFeedback(val actionMessage:Long,val verdict:String,val quote:String)
internal data class RoleLearning(val id:String,val kind:String,val scope:String,val content:String,val phase:String,val evidence:List<RoleEvidence>)
internal data class RoleFuture(val kind:String,val timing:Reconsideration,val evidence:List<RoleEvidence>)
internal data class RoleUnderstanding(val schemaVersion:Int=1,val appraisal:RoleAppraisal?,val feelings:List<RoleFeeling>,val goal:RoleGoal?,val choices:List<RoleChoice>,val selected:String,val judgment:RoleJudgment?,val feedback:List<RoleFeedback>,val learning:RoleLearning?,val usedLearningIds:List<String>,val consideredLearningIds:List<String>,val future:RoleFuture?,val dependencies:List<RoleEvidence>,val revision:Int=1) {
    fun hasRecords()=appraisal!=null||feelings.isNotEmpty()||goal!=null||choices.isNotEmpty()||judgment!=null||feedback.isNotEmpty()||learning!=null||future!=null
    fun selectedChoice()=choices.singleOrNull{it.id==selected}
}
internal data class RoleActualAction(val source:SourceRef,val action:String,val situation:String,val replyId:Long,val reply:String,val usedLearningIds:List<String>,val consideredLearningIds:List<String>,val goalOrigin:String,val readAt:Long?,val requestedAt:Long?)
internal data class RoleEntry(val source:SourceRef,val flow:RoleUnderstanding,val action:RoleActualAction?,val claimKind:String="reported_event")
internal data class RoleLearningState(val id:String,val kind:String,val scope:String,val content:String,val state:String,val source:SourceRef,val version:Int=1,val supportingResults:Set<Long> = emptySet(),val reason:String="")
internal data class RoleContext(val role:String,val current:SourceRef,val knownSources:Map<Long,SourceRef>,val entries:List<RoleEntry>,val actions:List<RoleActualAction>,val learning:List<RoleLearningState>,val facts:List<CompanionExperience>,@Transient val relationInputs:List<RelationInput> = emptyList(),@Transient val relationPromises:List<RelationPromise> = emptyList(),val relationRecent:List<Map<String,Any>> = emptyList(),@Transient val relationState:RelationState?=null)

/** C1 may already contain this turn's failed candidate; it is not evidence for its own retry. */
internal fun RoleContext.beforeCurrent():RoleContext {
    if(current.turnId<=0)return this
    if(entries.none{it.source.turnId>=current.turnId}&&actions.none{it.replyId>=current.turnId})return this
    val previous=entries.filter{it.source.turnId<current.turnId}.map{entry->
        if(entry.action?.let{it.replyId>=current.turnId}==true)entry.copy(action=null)else entry
    }
    val actual=actions.filter{it.source.turnId<current.turnId&&it.replyId<current.turnId}
    return copy(knownSources=knownSources.filterKeys{it<=current.turnId},entries=previous,actions=actual,
        learning=RoleLearningProjector.project(previous,actual),facts=facts.filter{it.source.turnId<=current.turnId})
}

/** Internal evolution uses traced experiences/results, not approval or a satisfaction reward. */
internal object RoleLearningProjector {
    fun project(entries:List<RoleEntry>,actualActions:List<RoleActualAction> = entries.mapNotNull{it.action}):List<RoleLearningState> {
        val states=linkedMapOf<String,RoleLearningState>()
        val actions=actualActions.associateBy{it.replyId}
        for(entry in entries.sortedBy{it.source.turnId}){
            val flow=entry.flow
            flow.learning?.let{change->
                val previous=states[change.id]
                when(change.phase){
                    "propose"->if(previous==null)states[change.id]=RoleLearningState(change.id,change.kind,change.scope,change.content,"candidate",entry.source,reason="候选尚无独立结果")
                    "revise"->if(previous!=null){
                        val priorResults=change.evidence.filter{it.layer=="action"&&it.source.turnId!=entry.source.turnId&&actions.containsKey(it.messageId)}.mapNotNull{it.messageId}.toSet()
                        val freshEvidence=change.evidence.any{it.layer=="fact"&&it.source==entry.source}
                        val supported=priorResults.isNotEmpty()&&freshEvidence&&entry.source!=previous.source&&entry.claimKind in setOf("reported_event","interaction")&&flow.appraisal?.certainty in setOf("reported","inference")&&flow.appraisal?.eventRelation !in setOf("repetition","uncertain")
                        states[change.id]=previous.copy(content=change.content,state=if(supported)"active"else"developing",source=entry.source,version=previous.version+1,supportingResults=previous.supportingResults+priorResults,
                            reason=if(supported)"先前候选、真实行动及新的有来源经历/结果共同支持本次有范围修订"else"依据不足以确定，保留可修正的变化候选")
                    }
                    "pause"->if(previous!=null)states[change.id]=previous.copy(state="paused",source=entry.source,reason="有来源的反例或明确停止")
                }
            }
            for(feedback in flow.feedback){
                val action=actions[feedback.actionMessage]?:continue
                for((id,value) in states.toMap()){
                    if(value.kind=="habit"&&id in action.usedLearningIds&&feedback.verdict=="unhelpful")states[id]=value.copy(state="paused",source=entry.source,version=value.version+1,reason="实际结果显示此相处方法在该范围失效；暂停方法，不改变身份或惩罚用户")
                }
            }
        }
        return states.values.toList()
    }
}

internal object RoleUnderstandingContract {
    val actions=setOf("acknowledge","celebrate","listen","clarify","explore","share","assist","pause")
    private fun fail(code:String):Nothing=throw RoleContractViolation(code)
    private fun JsonObject.text(key:String,empty:Boolean=false,limit:Int=1200):String {
        val v=get(key)?:fail("MISSING_$key")
        if(!v.isJsonPrimitive||!v.asJsonPrimitive.isString)fail("TYPE_$key")
        return v.asString.also{if((!empty&&it.isBlank())||it.length>limit)fail("TEXT_$key")}
    }
    private fun JsonObject.array(key:String):JsonArray=get(key)?.takeIf{it.isJsonArray}?.asJsonArray?:fail("ARRAY_$key")
    private fun JsonObject.obj(key:String):JsonObject=get(key)?.takeIf{it.isJsonObject}?.asJsonObject?:fail("OBJECT_$key")
    private fun JsonObject.number(key:String):Long=try{get(key)?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asBigDecimal?.longValueExact()?:fail("NUMBER_$key")}catch(_:ArithmeticException){fail("INTEGER_$key")}
    private fun JsonObject.strings(key:String)=array(key).map{if(!it.isJsonPrimitive||!it.asJsonPrimitive.isString)fail("STRING_ARRAY_$key");it.asString}.distinct()
    fun parse(raw:String,input:String,p:ExperienceProposal,context:RoleContext,now:Long,readPerformed:Boolean,readAllowed:Boolean=true,lenient:Boolean=false,ignored:(String,String)->Unit={field,code->Diagnostics.event("role.input.ignored",mapOf("field" to field,"code" to code))}):RoleUnderstanding {
        val context=context.beforeCurrent()
        val root=JsonParser.parseString(raw).asJsonObject
        // Known wire-only deviation: appraise is a stage name, appraisal is the field.
        // Never fill semantic content or choose between conflicting objects.
        if(root.has("appraise")){
            if(root.has("appraisal")){if(!lenient)fail("AMBIGUOUS_APPRAISAL_FIELDS");ignored("appraisal","AMBIGUOUS_APPRAISAL_FIELDS");root.remove("appraisal");root.remove("appraise")}
            if(root.has("appraise"))root.add("appraisal",root.remove("appraise"))
        }
        // This endpoint has one supported contract; an explicit different version still fails.
        if(!root.has("schemaVersion"))root.addProperty("schemaVersion",1)
        if(!lenient&&!root.keySet().containsAll(setOf("schemaVersion","appraisal","feelings","goal","choices","selected","judgment","feedback","learning","usedLearningIds","consideredLearningIds","future")))fail("INCOMPLETE_ROLE_UNDERSTANDING")
        if(!lenient&&root.number("schemaVersion")!=1L)fail("SCHEMA_VERSION")
        if(lenient&&runCatching{root.number("schemaVersion")!=1L}.getOrDefault(true)){
            ignored("schemaVersion","SCHEMA_VERSION");root.keySet().toList().forEach{root.remove(it)}
        }
        val dependencies=linkedSetOf<RoleEvidence>()
        fun <T> part(field:String,block:()->T):T? {
            val before=dependencies.toSet()
            try { return block() } catch(e:RuntimeException) {
                if(!lenient)throw e
                dependencies.retainAll(before)
                ignored(field,(e as? RoleContractViolation)?.code?:"INVALID_TYPE")
                return null
            }
        }
        fun evidence(j:JsonObject):List<RoleEvidence> =j.array("evidence").map{element->
            val e=element.asJsonObject;val id=e.number("source");val layer=e.text("layer")
            val ref=context.knownSources[id]?:fail("UNKNOWN_SOURCE")
            if(layer !in setOf("fact","interpretation","action"))fail("EVIDENCE_LAYER")
            if(id==context.current.turnId&&layer!="fact")fail("SELF_SUPPORT")
            if(layer=="interpretation"&&context.entries.none{it.source==ref})fail("NO_INTERPRETATION")
            val message=if(layer=="action")e.number("message")else null
            if(layer=="action"&&context.actions.none{it.source==ref&&it.replyId==message})fail("NO_REAL_ACTION")
            val interpretation=if(layer=="interpretation")context.entries.single{it.source==ref}.flow.revision else null
            RoleEvidence(ref,layer,message,interpretation).also{dependencies+=it}
        }.distinct().also{if(it.isEmpty())fail("MISSING_EVIDENCE")}
        val appraisal=part("appraisal"){
        val a=root.obj("appraisal")
        val appraisal=RoleAppraisal(a.text("event"),a.text("meaning"),a.text("certainty"),a.text("relevance"),a.text("attribution"),a.text("situation"),a.text("eventRelation"),a.strings("uncertainty"),evidence(a))
        if(appraisal.certainty !in setOf("reported","inference","unknown"))fail("CERTAINTY")
        if(appraisal.attribution !in setOf("user_report","shared_interaction","role_inference","unknown"))fail("ATTRIBUTION")
        if(appraisal.eventRelation !in setOf("new_event","progress","repetition","correction","uncertain"))fail("EVENT_RELATION")
        if(appraisal.certainty=="unknown"&&appraisal.uncertainty.isEmpty())fail("UNCERTAINTY_MISSING")
            appraisal
        }
        val feelings=part("feelings"){root.array("feelings").mapNotNull{item->part("feelings.item"){val f=item.asJsonObject
            RoleFeeling(f.text("kind"),f.text("degree"),f.text("object"),f.text("reason"),evidence(f)).also{v->
                if(v.kind !in setOf("joy","curiosity","care","relief","disappointment","concern","calm"))fail("FEELING_KIND")
                if(v.degree !in setOf("mild","moderate","strong"))fail("FEELING_DEGREE")
                if((appraisal==null||appraisal.certainty=="unknown")&&v.degree=="strong"&&v.kind in setOf("disappointment","concern"))fail("UNCERTAIN_STRONG_NEGATIVE")
            }}}}.orEmpty()
        val goal=part("goal"){
        val g=root.obj("goal");val goal=RoleGoal(g.text("description"),g.text("origin"),g.text("state"),g.text("reason"),evidence(g))
        if(goal.origin !in setOf("current_request","shared_goal","role_interest","clarification"))fail("GOAL_ORIGIN")
        if(goal.state !in setOf("active","waiting","satisfied","paused","cancelled"))fail("GOAL_STATE")
            goal
        }
        val selection=part("choices"){
        val choices=root.array("choices").map{val c=it.asJsonObject;RoleChoice(c.text("id",limit=64),c.text("action"),c.text("reason"),evidence(c))}
        if(choices.isEmpty()||choices.map{it.id}.distinct().size!=choices.size||choices.any{it.action !in actions})fail("CHOICES")
        val selected=root.text("selected");val choice=choices.singleOrNull{it.id==selected}?:fail("SELECTED_CHOICE")
        if(appraisal?.uncertainty.orEmpty().isNotEmpty()&&readAllowed&&!readPerformed)fail("UNCERTAINTY_REQUIRES_RECALL")
        if((appraisal?.certainty=="unknown"||appraisal?.uncertainty.orEmpty().isNotEmpty()&&!readAllowed)&&choice.action !in setOf("clarify","listen","acknowledge","pause"))fail("UNKNOWN_REQUIRES_HONEST_RESPONSE")
            choices to selected
        }
        val choices=selection?.first.orEmpty();val selected=selection?.second.orEmpty()
        val judgment=part("judgment"){
        val j=root.obj("judgment");val judgment=RoleJudgment(j.text("position"),j.text("kind"),evidence(j))
        if(judgment.kind !in setOf("evidence_based","inference","uncertain"))fail("JUDGMENT_KIND")
            judgment
        }
        val feedback=part("feedback"){root.array("feedback").mapNotNull{item->part("feedback.item"){val f=item.asJsonObject
            val message=f.number("actionMessage");val action=context.actions.singleOrNull{it.replyId==message}?:fail("FEEDBACK_REQUIRES_COMMITTED_ACTION")
            val quote=f.text("quote");if(quote !in input)fail("FEEDBACK_QUOTE")
            val verdict=f.text("verdict");if(verdict !in setOf("helpful","unhelpful","rejected","unknown"))fail("FEEDBACK_VERDICT")
            dependencies+=RoleEvidence(action.source,"action",message);dependencies+=RoleEvidence(context.current)
            RoleFeedback(message,verdict,quote)
        }}}.orEmpty()
        val learning=part("learning"){if(!root.has("learning"))fail("MISSING_learning");root.get("learning")?.takeUnless{it.isJsonNull}?.asJsonObject?.let{l->
            val kind=l.text("kind");if(kind !in setOf("viewpoint","interest","habit"))fail("LEARNING_KIND")
            val phase=l.text("phase");if(phase !in setOf("propose","revise","pause"))fail("LEARNING_PHASE")
            val scope=l.text("scope");val content=l.text("content");val requestedId=l.text("id",empty=true,limit=160)
            val id=if(phase=="propose")"learning-"+stableDigest("${context.role}:${context.current.turnId}:${context.current.version}:$kind:$scope").take(32)else requestedId
            val previous=if(phase=="propose")null else (context.learning.singleOrNull{it.id==id}?:fail("LEARNING_TARGET"))
            if(previous!=null){if(kind!=previous.kind||scope!=previous.scope)fail("LEARNING_SCOPE_CHANGED");dependencies+=RoleEvidence(previous.source,"interpretation",interpretationRevision=context.entries.single{it.source==previous.source}.flow.revision)}
            RoleLearning(id,kind,scope,content,phase,evidence(l))
        }}
        val use=part("learningUse"){
        val used=root.strings("usedLearningIds");val considered=root.strings("consideredLearningIds")
        RoleConsistency.learningUseError(considered,used,judgment?.kind.orEmpty(),context.learning)?.let{fail(it)}
        for(id in considered){val state=context.learning.singleOrNull{it.id==id}?:fail("UNKNOWN_LEARNING");dependencies+=RoleEvidence(state.source,"interpretation",interpretationRevision=context.entries.single{it.source==state.source}.flow.revision)}
            used to considered
        }
        val used=use?.first.orEmpty();val considered=use?.second.orEmpty()
        val future=part("future"){if(!root.has("future"))fail("MISSING_future");root.get("future")?.takeUnless{it.isJsonNull}?.asJsonObject?.let{f->
            val kind=f.text("kind");if(kind !in setOf("interest_share","shared_continuation","follow_up","actual_result"))fail("FUTURE_KIND")
            val time=Reconsideration(f.text("reason"),ContactTime.field(f,"when","notBefore",now))
            if(time.notBefore<now)fail("FUTURE_TIME")
            RoleFuture(kind,time,evidence(f))
        }}
        if(p.control!="none"||p.controlChecked)fail("CONTROL_DOES_NOT_NEED_ROLE_PLAN")
        return RoleUnderstanding(appraisal=appraisal,feelings=feelings,goal=goal,choices=choices,selected=selected,judgment=judgment,feedback=feedback,learning=learning,usedLearningIds=used,consideredLearningIds=considered,future=future,dependencies=dependencies.toList())
    }
    fun validateAccepted(flow:RoleUnderstanding,context:RoleContext,input:String){
        val context=context.beforeCurrent()
        if(flow.schemaVersion!=1||flow.revision<1||(flow.choices.isNotEmpty()&&flow.choices.count{it.id==flow.selected}!=1)||flow.choices.map{it.id}.distinct().size!=flow.choices.size||flow.choices.any{it.action !in actions||it.reason.isBlank()})fail("INVALID_ROLE_PLAN")
        if(flow.appraisal!=null&&(flow.appraisal.certainty !in setOf("reported","inference","unknown")||flow.appraisal?.eventRelation !in setOf("new_event","progress","repetition","correction","uncertain")||flow.appraisal.meaning.isBlank()))fail("INVALID_APPRAISAL")
        if(flow.judgment!=null&&(flow.judgment.kind !in setOf("evidence_based","inference","uncertain")||flow.judgment.position.isBlank()))fail("INVALID_JUDGMENT")
        if(flow.feelings.any{it.kind !in setOf("joy","curiosity","care","relief","disappointment","concern","calm")||it.degree !in setOf("mild","moderate","strong")||it.reason.isBlank()})fail("INVALID_FEELING")
        if((listOfNotNull(flow.appraisal?.evidence,flow.goal?.evidence,flow.judgment?.evidence)+flow.feelings.map{it.evidence}+flow.choices.map{it.evidence}+listOfNotNull(flow.learning?.evidence,flow.future?.evidence)).any{it.isEmpty()})fail("MISSING_EVIDENCE")
        val required=linkedSetOf<RoleEvidence>()
        required+=flow.appraisal?.evidence.orEmpty();required+=flow.goal?.evidence.orEmpty();required+=flow.judgment?.evidence.orEmpty()
        flow.feelings.forEach{required+=it.evidence};flow.choices.forEach{required+=it.evidence}
        flow.learning?.let{l->required+=l.evidence
            if(l.kind !in setOf("viewpoint","interest","habit")||l.phase !in setOf("propose","revise","pause")||l.scope.isBlank()||l.content.isBlank())fail("INVALID_LEARNING")
            if(l.phase!="propose"){val previous=context.learning.singleOrNull{it.id==l.id}?:fail("LEARNING_TARGET");if(l.kind!=previous.kind||l.scope!=previous.scope)fail("LEARNING_SCOPE_CHANGED");required+=RoleEvidence(previous.source,"interpretation",interpretationRevision=context.entries.single{it.source==previous.source}.flow.revision)}
        }
        flow.future?.let{required+=it.evidence}
        flow.feedback.forEach{f->val action=context.actions.singleOrNull{it.replyId==f.actionMessage}?:fail("FEEDBACK_REQUIRES_COMMITTED_ACTION");if(f.quote.isBlank()||f.quote !in input)fail("FEEDBACK_QUOTE");required+=RoleEvidence(action.source,"action",f.actionMessage);required+=RoleEvidence(context.current)}
        flow.consideredLearningIds.forEach{id->val item=context.learning.singleOrNull{it.id==id}?:fail("UNKNOWN_LEARNING");required+=RoleEvidence(item.source,"interpretation",interpretationRevision=context.entries.single{it.source==item.source}.flow.revision)}
        RoleConsistency.learningUseError(flow.consideredLearningIds,flow.usedLearningIds,flow.judgment?.kind.orEmpty(),context.learning)?.let{fail(it)}
        if(required!=flow.dependencies.toSet())fail("DEPENDENCY_SET")
        for(e in required){
            if(e.source.characterId!=context.role)fail("CROSS_ROLE_EVIDENCE")
            if(context.knownSources[e.source.turnId]!=e.source)fail("SOURCE_CHANGED")
            when(e.layer){
                "fact"->Unit
                "interpretation"->if(context.entries.none{it.source==e.source&&it.flow.revision==e.interpretationRevision})fail("INVALID_INTERPRETATION_DEPENDENCY")
                "action"->if(context.actions.none{it.source==e.source&&it.replyId==e.messageId})fail("INVALID_ACTION_DEPENDENCY")
                else->fail("EVIDENCE_LAYER")
            }
            if(e.source.turnId>context.current.turnId||e.source==context.current&&e.layer!="fact")fail("SELF_SUPPORT")
        }
    }
}
