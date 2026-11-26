package app.lianxu.independent

import com.google.gson.JsonParser

/** Existing contact fields separated from private mood/relationship accounting. */
internal object ImmediateContact {
    const val NAME="companion_contact"
    fun same(a:RoleFuture,b:RoleFuture)=a.copy(timing=a.timing.copy(expiresAt=0))==b.copy(timing=b.timing.copy(expiresAt=0))
    fun definition(source:Long,now:Long,zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):Map<String,Any> {
        val sourceField=mapOf("type" to "integer","description" to "本轮已提供的有效用户消息编号；当前编号为$source")
        fun evidence(layer:String,extra:Map<String,Any> = emptyMap())=mapOf("type" to "object","properties" to mapOf("source" to sourceField,"layer" to mapOf("type" to "string","enum" to listOf(layer)))+extra,
            "required" to listOf("source","layer")+extra.keys,"additionalProperties" to false)
        val proof=mapOf("type" to "array","minItems" to 1,"items" to mapOf("anyOf" to listOf(
            evidence("fact"),evidence("interpretation",mapOf("interpretationRevision" to mapOf("type" to "integer","description" to "已提供旧解释的版本编号"))),
            evidence("action",mapOf("message" to mapOf("type" to "integer","description" to "已经提交的角色回复整数编号，绝不是回复文字或未来打算"))))))
        val example=com.google.gson.Gson().toJson(mapOf("future" to mapOf("kind" to "follow_up","reason" to "对方明确请求稍后回访当前话题","when" to "30秒后","evidence" to listOf(mapOf("source" to source,"layer" to "fact")))))
        return mapOf("type" to "function","function" to mapOf("name" to NAME,
        "description" to "聊到稍后提醒、共同打算或想再问的事，就在这里约个合适的时间。当前来源=$source，当前时间=${ContactTime.clock(now,zone)}。future={kind:interest_share/shared_continuation/follow_up/actual_result,reason,when:自然时间,evidence:[{source:有效用户消息整数编号,layer:fact/interpretation/action}]}。${ContactTime.GUIDE}下次时间由你按实际话题选择，可从现在起安排，不设固定等待下限，也不填写独立有效期；到时再结合最新聊天判断联系、延后或放下，系统可能迟到；不因未回复自动认定对方故意冷落。当前来源仅fact；引用旧解释加interpretationRevision，行动加message。没有合适理由不调用。保持当前权限，不能自行打开开关。成功才可说已安排再考虑，同一轮已成功的安排不能重复或替换。",
        "parameters" to mapOf("type" to "object","additionalProperties" to false,"properties" to mapOf(
            "future" to mapOf("type" to "object","additionalProperties" to false,"description" to "当前用户来源${source}只能为fact；action仅表示已经做过的行动，message必须是已保存回复的整数编号，不能写待发送文字。预约示例：$example",
                "properties" to mapOf("kind" to mapOf("type" to "string","enum" to listOf("interest_share","shared_continuation","follow_up","actual_result")),
                    "reason" to mapOf("type" to "string","minLength" to 1,"maxLength" to 1200),"when" to mapOf("type" to "string","description" to ContactTime.GUIDE),"evidence" to proof),
                "required" to listOf("kind","reason","when","evidence"))),"required" to listOf("future"))))
    }
    data class Request(val flow:RoleUnderstanding)
    fun parse(raw:String,input:String,context:RoleContext,now:Long,zone:java.time.ZoneId=java.time.ZoneId.systemDefault(),scheduling:Boolean=true):Request {
        val root=JsonParser.parseString(raw).asJsonObject
        if(!scheduling)throw RoleContractViolation("SCHEDULING_DISABLED")
        require(root.keySet().all{it=="future"}){"INVALID_CONTACT_FIELDS"}
        val future=requireNotNull(root.get("future")?.takeUnless{it.isJsonNull}){"EMPTY_CONTACT"}.asJsonObject
        val at=ContactTime.field(future,"when","notBefore",now,zone)
        future.remove("when");future.addProperty("notBefore",at)
        var futureIssue:String?=null
        val flow=RoleUnderstandingContract.parse(root.toString(),input,ExperienceProposal(),context,now,false,false,true){field,code->if(field=="future")futureIssue=code}.also{
            if(it.future==null)throw RoleContractViolation(futureIssue?:"INVALID_CONTACT")
            RoleUnderstandingContract.validateAccepted(it,context,input)
            future.getAsJsonArray("evidence").forEach{entry->
                val e=entry.asJsonObject
                if(e.get("layer").asString=="interpretation"){
                    val revision=runCatching{e.get("interpretationRevision")?.takeIf{v->v.isJsonPrimitive&&v.asJsonPrimitive.isNumber}?.asBigDecimal?.intValueExact()}.getOrNull()
                    val source=e.get("source").asLong
                    if(revision==null||it.future.evidence.none{proof->proof.layer=="interpretation"&&proof.source.turnId==source&&proof.interpretationRevision==revision})throw RoleContractViolation("INTERPRETATION_REVISION")
                }
            }
        }
        return Request(flow)
    }
}
