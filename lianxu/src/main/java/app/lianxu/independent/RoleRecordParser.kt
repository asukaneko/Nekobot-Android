package app.lianxu.independent

import com.google.gson.JsonParser

/** Shared field/source rules for old tool callers and the multi-turn background review. */
internal object RoleRecordParser {
    data class Parsed(val reply:RolePlanner.Reply,val relationProblems:List<ToolArgumentDiagnosis.Problem>,val relationMissing:Boolean)
    fun parse(args:String,input:String,facts:ExperienceProposal,view:RoleContext,now:Long,eventAt:Long,
        readPerformed:Boolean,readAllowed:Boolean,relationSeen:Set<Long>,executed:ExperienceProposal?):Parsed {
        val raw=RecordArguments.normalize(args)
        val json=JsonParser.parseString(raw).asJsonObject
        val currentFacts=if(facts.interpretationUnknown)JointExperience.parse(json,input,now)else facts
        val acceptedFacts=if(currentFacts.controlIntent!="none"||executed!=null)
            executed?.let{if(it.control=="forget")it else it.copy(quote=currentFacts.quote,memory=currentFacts.memory,emotion=currentFacts.emotion,valence=currentFacts.valence,relationship=currentFacts.relationship,claimKind=currentFacts.claimKind)}?:currentFacts.copy(controlChecked=true)
            else currentFacts
        val recordIssues=mutableListOf<RelationIssue>();val relationIssues=mutableListOf<RelationIssue>()
        val flow=RoleUnderstandingContract.parse(raw,input,currentFacts,view,now,readPerformed,readAllowed,lenient=true){field,code->recordIssues+=RelationIssue(field,code);Diagnostics.event("role.input.ignored",mapOf("field" to field,"code" to code))}
        val parsedRelation=RelationInputContract.parse(json.get("关系输入"),view.current,eventAt,
            java.time.Instant.ofEpochMilli(eventAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay(),view.relationInputs){field,code->relationIssues+=RelationIssue(field,code);Diagnostics.event("relation.input.ignored",mapOf("field" to field,"code" to code))}
        var relation=parsedRelation;val parseIssues=relationIssues.toList()
        view.relationState?.let{state->relation=relation?.let{row->RelationshipRecall.validate(row,state,relationSeen+view.relationRecent.mapNotNull{(it["消息"] as? Number)?.toLong()},view.relationInputs.map{it.source}.toSet()){field,code->relationIssues+=RelationIssue(field,code)}}}
        if(flow.appraisal?.eventRelation=="repetition"||currentFacts.claimKind in setOf("fiction","hypothesis","quoted_report")){
            relation=relation?.copy(p=null,c=null,v=null,b=null,disappointment=null,promise=false,fulfills=null,repairs=null,
                explains=if(currentFacts.claimKind in setOf("fiction","hypothesis","quoted_report"))null else relation.explains)
            relationIssues+=RelationIssue("newExperience","NOT_NEW_EXPERIENCE")
            Diagnostics.event("relation.input.ignored",mapOf("field" to "newExperience","code" to "NOT_NEW_EXPERIENCE"))
        }
        val allowed=view.knownSources[view.current.turnId]==view.current
        if(!allowed&&relation!=null){relation=null;relationIssues+=RelationIssue("source","UNKNOWN_SOURCE")}
        val reply=RolePlanner.Reply(flow,"",relationInput=relation,relationIssues=relationIssues,recordIssues=recordIssues,
            parameterProposal=if(allowed)RelationParameterContract.parse(json.get("改主动参数"),view.current,input,now)else null,experience=acceptedFacts,recordAccepted=true)
        val problems=if(allowed&&RelationReceipt.needed(parsedRelation,parseIssues))RelationReceipt.problems(json.get("关系输入"),parseIssues,view.current.turnId)else emptyList()
        return Parsed(reply,problems,parsedRelation==null)
    }
}
