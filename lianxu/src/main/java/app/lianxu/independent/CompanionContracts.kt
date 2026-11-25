package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.max
import kotlin.math.min

/** Candidates only. The repository owns all authoritative state changes. */
/** expiresAt is retained only for old stored records; it no longer expires a topic. */
internal data class Reconsideration(val reason:String,val notBefore:Long,val expiresAt:Long=Long.MAX_VALUE)
internal data class ExperienceProposal(
    val quote:String="",val memory:String="",val emotion:String="unknown",val valence:Int=0,
    val relationship:String="none",val reconsider:Reconsideration?=null,
    val control:String="none",val targets:List<Long> = emptyList(),val controlQuote:String="",val controlChecked:Boolean=false,
    val interpretationUnknown:Boolean=false,
    val claimKind:String="reported_event",val controlIntent:String="none",val requestQuote:String="",val controlAuthorized:Boolean=false,
    val understanding:RoleUnderstanding?=null,val understandingHistory:List<RoleUnderstanding> = emptyList(),
    // Local prompt clock, never taken from a model field. Preserve it across C1/C2 retries.
    val timingReferenceAt:Long?=null,
    // Local fingerprint of the actual profile used for appraisal; never accepted from model output.
    val appraisalProfileStamp:String?=null,
    val relationInput:RelationInput?=null,val relationAttempted:Boolean=false,
    val relationIssues:List<RelationIssue> = emptyList(),val recordIssues:List<RelationIssue> = emptyList(),val parameterProposal:RelationParameterProposal?=null
)
/** These acknowledgements are published only by the successful C2 transaction. */
internal fun ExperienceProposal.controlReply():String? = when(control){
    "forget"->"已停止使用这段经历和相关判断，关联回访也已取消。原聊天保留供你查看。"
    "stop_reminding"->"已取消相关提醒，经历记录仍保留。"
    "correct"->"已停用你指定的旧来源，并保存这次更正。原聊天保留供你查看。"
    else->if(controlChecked)"这次还没有明确定位到需要更正或停止使用的旧消息，尚未执行该操作。请指出具体消息。"else null
}
/** reconsider.v1 output. Learning IDs are role-local change IDs from the current snapshot only:
 * considered = actually weighed, used = actually shaped this decision/text (subset of considered).
 * judgmentKind is required when any learning is used; candidates can never be evidence_based. */
internal data class OpportunityDecision(val action:String,val reason:String,val text:String="",val nextAt:Long?=null,
    val consideredLearningIds:List<String> = emptyList(),val usedLearningIds:List<String> = emptyList(),val judgmentKind:String="")
internal object CompanionContracts {
    private fun JsonObject.text(key:String):String {
        val value=get(key)?:return ""
        require(value.isJsonPrimitive&&value.asJsonPrimitive.isString){"INVALID_$key"}
        return value.asString
    }
    private fun JsonObject.long(key:String):Long {
        val value=get(key);require(value!=null&&value.isJsonPrimitive&&value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.longValueExact()
    }
    fun experience(raw:String,input:String,now:Long,allowedTargets:Set<Long>):ExperienceProposal {
        val j=JsonParser.parseString(raw).asJsonObject
        val quote=j.text("quote");val memory=j.text("memory")
        val emotion=j.text("emotion").ifEmpty{"unknown"};val relation=j.text("relationship").ifEmpty{"none"}
        val numericValence=if(j.has("valence"))j.long("valence") else 0L
        require(numericValence in -1L..1L)
        val valence=numericValence.toInt()
        val control=j.text("control").ifEmpty{"none"}
        require(emotion in setOf("unknown","calm","happy","sad","anxious","angry"))
        require(relation in setOf("none","appreciation","boundary")&&valence in -1..1)
        require(control in setOf("none","forget","stop_reminding","correct"))
        require(memory.length<=1200)
        if(memory.isNotEmpty()||emotion!="unknown"||relation!="none")require(quote.isNotBlank()&&quote in input)
        if(emotion=="unknown")require(valence==0)
        val targets=j.getAsJsonArray("targets")?.map{it.asBigDecimal.longValueExact()}?.distinct().orEmpty()
        require(targets.all{it in allowedTargets})
        val controlQuote=if(control!="none")j.text("controlQuote").ifBlank{quote}else""
        if(control!="none"){
            require(controlQuote.isNotBlank()&&controlQuote in input)
            require(targets.isNotEmpty())
            require(j.get("controlAuthorized")?.let{it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean&&it.asBoolean}==true){"CONTROL_NOT_AUTHORIZED"}
            require(j.text("controlIntent")==control){"CONTROL_INTENT_MISMATCH"}
            val requestQuote=j.text("requestQuote");require(requestQuote.isNotBlank()&&requestQuote in input&&(requestQuote in controlQuote||controlQuote in requestQuote)){"CONTROL_REQUEST_EVIDENCE"}
        }else require(targets.isEmpty())
        val reconsider=j.get("reconsider")?.takeUnless{it.isJsonNull}?.asJsonObject?.let{
            val reason=it.text("reason");val next=ContactTime.field(it,"when","notBefore",now)
            require(reason.isNotBlank()&&reason.length<=600&&next>=now)
            Reconsideration(reason,next)
        }
        val claim=j.text("claimKind").ifBlank{"reported_event"}
        require(claim in setOf("reported_event","plan","hypothesis","fiction","quoted_report","interaction","none"))
        return ExperienceProposal(quote,memory,emotion,valence,relation,reconsider,control,targets,controlQuote,
            claimKind=claim,controlIntent=j.text("controlIntent").ifBlank{"none"},requestQuote=j.text("requestQuote"),controlAuthorized=j.get("controlAuthorized")?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean?:false,
            timingReferenceAt=now.takeIf{reconsider!=null})
    }
    /** Send text is the gate; invalid bookkeeping cannot turn a valid message into a retry. */
    fun decisionForReply(raw:String,now:Long,expiry:Long,learning:List<RoleLearningState>?=null,zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):OpportunityDecision {
        val j=JsonParser.parseString(raw).asJsonObject
        if(runCatching{j.text("action")}.getOrNull()!="send")return runCatching{decision(raw,now,expiry,learning,zone)}.getOrElse{
            Diagnostics.event("role.input.ignored",mapOf("field" to "decision","code" to "INVALID_BACKGROUND_RECORD"))
            OpportunityDecision("silent","")
        }
        val text=j.text("text");require(text.isNotBlank()&&text.length<=8000){"INVALID_REPLY"}
        fun ignored(field:String)=Diagnostics.event("role.input.ignored",mapOf("field" to field,"code" to "INVALID_BACKGROUND_RECORD"))
        val reason=runCatching{j.text("reason").also{require(it.isNotBlank()&&it.length<=600)}}.getOrElse{ignored("reason");""}
        val use=runCatching{
            val considered=j.ids("consideredLearningIds");val used=j.ids("usedLearningIds");val judgment=j.text("judgmentKind")
            require(RoleConsistency.learningUseError(considered,used,judgment,learning)==null)
            Triple(considered,used,judgment)
        }.getOrElse{ignored("learningUse");Triple(emptyList(),emptyList(),"")}
        val next=runCatching{j.nextContact(now,zone)}.getOrElse{ignored("nextWhen");null}
        return OpportunityDecision("send",reason,text,next,consideredLearningIds=use.first,usedLearningIds=use.second,judgmentKind=use.third)
    }
    private fun JsonObject.nextContact(now:Long,zone:java.time.ZoneId):Long? {
        if(listOf("nextWhen","nextAt").none{get(it)?.isJsonNull==false})return null
        return ContactTime.field(this,"nextWhen","nextAt",now,zone).also{require(it>=now)}
    }
    private fun JsonObject.ids(key:String):List<String> {
        val value=get(key)?.takeUnless{it.isJsonNull}?:return emptyList()
        require(value.isJsonArray&&value.asJsonArray.size()<=20){"INVALID_$key"}
        val ids=value.asJsonArray.map{require(it.isJsonPrimitive&&it.asJsonPrimitive.isString&&it.asString.isNotBlank()&&it.asString.length<=160){"INVALID_$key"};it.asString}
        require(ids.distinct().size==ids.size){"DUPLICATE_$key"}
        return ids
    }
    /** [learning] is the role learning projection of the snapshot being decided on; IDs are checked locally. */
    @Suppress("UNUSED_PARAMETER") // Old callers may pass the legacy expiry; it has no decision authority.
    fun decision(raw:String,now:Long,expiry:Long,learning:List<RoleLearningState>?=null,zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):OpportunityDecision {
        val j=JsonParser.parseString(raw).asJsonObject
        val action=j.text("action");val reason=j.text("reason");val text=j.text("text")
        require(action in setOf("send","defer","cancel","silent")&&reason.isNotBlank()&&reason.length<=600)
        val next=when(action){"defer"->ContactTime.field(j,"nextWhen","nextAt",now,zone);"send"->j.nextContact(now,zone);else->null}
        if(next!=null)require(next>=now)
        if(action=="send")require(text.isNotBlank()&&text.length<=8000)else require(text.isEmpty())
        val considered=j.ids("consideredLearningIds");val used=j.ids("usedLearningIds");val judgment=j.text("judgmentKind")
        val invalid=RoleConsistency.learningUseError(considered,used,judgment,learning)
        require(invalid==null){invalid.orEmpty()}
        return OpportunityDecision(action,reason,text,next,considered,used,judgment)
    }
}

/** Persisted learning use of one formally saved proactive message. Damaged rows claim no influence. */
internal object ProactiveLearningUse {
    fun encode(ids:List<String>):String=Gson().toJson(ids)
    fun decode(considered:String?,used:String?):Pair<List<String>,List<String>> = runCatching{
        val c=parse(considered);val u=parse(used);if(c.containsAll(u))c to u else null
    }.getOrNull()?:(emptyList<String>() to emptyList())
    private fun parse(raw:String?):List<String> = if(raw.isNullOrBlank())emptyList() else JsonParser.parseString(raw).asJsonArray.map{require(it.isJsonPrimitive&&it.asJsonPrimitive.isString&&it.asString.isNotBlank());it.asString}.distinct()
}

/** Read means an opportunity to see content, never understanding or consent.
 * Short bubbles need 90% of their height; long bubbles need the first viewport-sized
 * segment. Horizontal clipping/focus and a 600ms continuous dwell are checked by UI. */
internal object MessageVisibility {
    fun eligible(top:Float,bottom:Float,left:Float,right:Float,viewportTop:Float,viewportBottom:Float,
        viewportLeft:Float,viewportRight:Float,foreground:Boolean,focused:Boolean):Boolean {
        if(!foreground||!focused||bottom<=top||right<=left||viewportBottom<=viewportTop)return false
        val required=min(bottom-top,(viewportBottom-viewportTop)*.8f)
        val visible=max(0f,min(bottom,viewportBottom)-max(top,viewportTop))
        return top>=viewportTop-1f&&visible>=required*.9f&&left>=viewportLeft-1f&&right<=viewportRight+1f
    }
}
