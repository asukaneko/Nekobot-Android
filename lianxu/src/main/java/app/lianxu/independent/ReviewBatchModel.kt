package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One ordinary model request covers the frozen batch, never one request per turn. */
internal class ReviewBatchModel(private val client:TextChatClient) {
    suspend fun review(config:ModelConfig,key:String,items:List<ReplyAccountingQueue.Item>,context:RoleContext,profile:String,now:Long,active:()->Boolean):Map<Long,JsonObject?> {
        val guide=(CompanionRecord.definition(items.first().source.turnId,true,true)["function"] as Map<*,*>)["description"].toString()
        val instructions="""
            $profile
            [多轮后台回顾]
            下面几轮已经结束。一次回顾这一整批，按先后顺序理解经历、你的感受和关系影响。程序只做校验与算分。
            只返回JSON {"records":[{"source":用户消息编号,"record":该轮记录或null}]}。每个给出的source恰好出现一次，顺序一致；没有值得记录的内容填null，不漏行、不合并来源。
            record沿用下面的字段规则与英文键。各轮experience只引用那一轮用户原话；助手正文只含确实显示并保存的内容，不编造未显示的尾段、已读或线下行为。
            只能用该轮及此前依据，不拿后面的事倒推当时的感受。批内前面已发生的真实回复可以引用；不要把尚未发生的回复当行动依据。
            这是回顾，不继续聊天，不输出给用户看的新正文或思考。记忆总结保持独立，不在此合并历史。
            即时记忆操作、遗忘、更正和联系安排由聊天工具执行；这里不得执行或重新安排。future固定null，不输出“改主动参数”；保留的即时结果由程序合并，不能伪造成功。
            没有回查工具时，如所给资料不足就保留未知。当前时间=$now；时间协议和关系计算规则不变。
            以下只是每个record对象的字段指南，不是让你调用工具。例子里的来源编号换成对应那一轮的source：
            $guide
        """.trimIndent()
        val data=Gson().toJson(items.map{mapOf("source" to it.source.turnId,"version" to it.source.version,"发生时间" to ExperiencePresentation.at(it.at,now),"用户原话" to it.input,"已发回复编号" to it.turn.assistantMessageId,"已实际说过的正文" to it.spoken)})
        val fixed=listOf(WireMessage("system",instructions),WireMessage("user",data))
        val remaining=AdaptiveContext.inputLimit(config.contextTokens,config.outputTokens)-fixed.sumOf{ConservativeTokens.tokens(it.content)+8}
        if(remaining<0)throw CompanionModelFailure(ChatFailure.CONTEXT_CAPACITY)
        val state=AdaptiveContext.roleView(context,items.joinToString("\n"){it.input},remaining)
        // Batch review has no chat snapshot facts; retain its existing action states once,
        // separately from the shared action descriptions (which no longer repeat them).
        val readStates=state.actions.distinctBy{it.replyId}.joinToString("\n"){ExperiencePresentation.receipt(it.replyId,it.readAt,now)}
        val notes=state.prompt(now)+"\n"+RolePlanner(client).replyNotes(ExperienceProposal(interpretationUnknown=true),state,UserPersona(),now)+
            if(readStates.isBlank())""else "\n${ExperiencePresentation.MESSAGE_STATE_LABEL}：\n${ExperiencePresentation.READ_STATE_RULE}\n$readStates"
        val messages=if(ConservativeTokens.tokens(notes)+8<=remaining)listOf(fixed.first(),WireMessage("system",notes),fixed.last())else fixed
        return StructuredModelCall(client).generate("reply_batch_review",config,key,messages,active=active){parse(it,items,now)}
    }
    companion object {
        fun parse(raw:String,items:List<ReplyAccountingQueue.Item>,now:Long):Map<Long,JsonObject?> {
            val rows=JsonParser.parseString(raw).asJsonObject.get("records")?.takeIf{it.isJsonArray}?.asJsonArray
            require(rows!=null){"BATCH_RECORDS_REQUIRED"}
            require(rows.size()==items.size){"BATCH_COVERAGE"}
            return items.zip(rows).associate{(item,value)->
                val row=value.asJsonObject;require(row.get("source").asBigDecimal.longValueExact()==item.source.turnId){"BATCH_SOURCE_ORDER"}
                require(row.has("record")){"BATCH_RECORD_REQUIRED"}
                val record=row.get("record").takeUnless{it.isJsonNull}?.asJsonObject
                if(record!=null){
                    JointExperience.parse(record,item.input,now)
                    require(record.has("关系输入")){"RELATION_INPUT_MISSING"}
                    require((!record.has("future")||record.get("future").isJsonNull)&&!record.has("改主动参数")){"IMMEDIATE_ACTION_NOT_ALLOWED_IN_REVIEW"}
                }
                item.source.turnId to record
            }
        }
    }
}
