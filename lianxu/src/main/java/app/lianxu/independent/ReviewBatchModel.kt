package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One ordinary model request covers the frozen batch, never one request per turn. */
internal class ReviewBatchModel(private val client:TextChatClient) {
    suspend fun review(config:ModelConfig,key:String,items:List<ReplyAccountingQueue.Item>,context:RoleContext,profile:String,now:Long,allowInnerNote:Boolean=true,active:()->Boolean):Map<Long,JsonObject?> {
        val instructions="""
            $profile
            [多轮后台轻回顾] 记忆总结保持独立。按顺序看每轮实际说过的话，只用当时已经发生的事。
            只返回 {"records":[{"source":本轮用户消息编号,"record":null}]}。每轮恰好一行，普通闲聊填 null。
            有波澜时才写 record，例如 {"关系输入":{"P":"轻"}}。只填发生的事：暖心 P、兑现 C、失约 V、越界 B，程度轻/中/重。
            定下新约加“约定”:true；兑现必须填“兑现约定”:旧约编号。提前改期只填“没达到预期”:小/中/大。
            道歉和好填“指向之前”:{消息:旧编号,方式:道歉和好}。解释填“解释”:{事件:旧编号,判断:真误会/有原因,类别:工作忙/身体不舒服/忘了/家里有事/其他}。
            已经弥补才填“修复心结”:旧编号、“弥补已完成”:true。旧编号只能选材料中有效且适用的原话，没有就省略。假设、转述、回忆不算新的互动。
            ${if(allowInnerNote) "整批最多在一行 record 内附 innerNote，一句自己心里的话，30字以内。该行用户原话就是依据，没有就省略。" else "本批还有后续内容，本段省略 innerNote。"}
            确有新看法或习惯时可附 learning:{id,kind:viewpoint/interest/habit,scope,content,phase:propose/revise/pause,evidence:[{source:编号,layer:"fact"}]}。新提议 id 填空，修订/暂停沿用已有 id 和范围。本轮让你修正或印证已有学习时，用 revise，依据填本轮用户消息编号和 fact 即可。
            不继续聊天，不重复写经历。即时控制与联系安排不得执行或重新安排。
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
        return StructuredModelCall(client).generate("reply_batch_review",config,key,messages,active=active){parse(it,items,now,allowInnerNote)}
    }
    companion object {
        fun relationForCommit(record:JsonObject?,parsed:RelationInput?,cached:RelationInput?):RelationInput? {
            val light=record!=null && !record.has("experience")
            return if(light && record?.get("关系输入")?.takeUnless{it.isJsonNull}==null) cached else parsed
        }
        private val lightFields=setOf("关系输入","innerNote","learning")

        private fun ignoreNote(reason:String) {
            Diagnostics.event("review.inner_note.ignored",mapOf("reason" to reason))
        }

        private fun cleanNote(raw:String):String? {
            val text=raw.filterNot{it.isISOControl() || it=='\u2028' || it=='\u2029'}.trim()
            if(text.isEmpty())return null
            return when(text.codePointCount(0,text.length)){
                in 1..30 -> text
                in 31..80 -> text.substring(0,text.offsetByCodePoints(0,29))+"…"
                else -> {ignoreNote("too_long");null}
            }
        }

        fun parse(raw:String,items:List<ReplyAccountingQueue.Item>,now:Long,allowInnerNote:Boolean=true):Map<Long,JsonObject?> {
            val rows=JsonParser.parseString(raw).asJsonObject.get("records")?.takeIf{it.isJsonArray}?.asJsonArray
            require(rows!=null){"BATCH_RECORDS_REQUIRED"}
            require(rows.size()==items.size){"BATCH_COVERAGE"}
            var lastNote:JsonObject?=null
            return items.zip(rows).associate{(item,value)->
                val row=value.asJsonObject;require(row.get("source").asBigDecimal.longValueExact()==item.source.turnId){"BATCH_SOURCE_ORDER"}
                require(row.has("record")){"BATCH_RECORD_REQUIRED"}
                val record=row.get("record").takeUnless{it.isJsonNull}?.asJsonObject
                if(record!=null){
                    require((!record.has("future") || record["future"].isJsonNull) && !record.has("改主动参数")){"IMMEDIATE_ACTION_NOT_ALLOWED_IN_REVIEW"}
                    if(record["experience"]?.isJsonObject==true) {
                        JointExperience.parse(record,item.input,now)
                        require(record.has("关系输入")){"RELATION_INPUT_MISSING"}
                    }
                    else {
                        require(record.keySet().any{it in lightFields}){"INVALID_REVIEW_FIELDS"}
                        record.keySet().retainAll(lightFields)
                        record.get("关系输入")?.takeUnless{it.isJsonNull}?.let{event->
                            require(event.isJsonObject)
                            val normalized=RelationInputContract.reviewEvent(event.asJsonObject,item.source)
                            record.add("关系输入",normalized)
                        }
                    }
                    record.get("innerNote")?.takeUnless{it.isJsonNull}?.let{note->
                        val clean=if(allowInnerNote){
                            require(note.isJsonPrimitive && note.asJsonPrimitive.isString){"INVALID_INNER_NOTE"}
                            cleanNote(note.asString)
                        }else{ignoreNote("non_final_segment");null}
                        record.addProperty("innerNote",clean)
                        if(clean!=null){
                            lastNote?.let{it.add("innerNote",JsonNull.INSTANCE);ignoreNote("superseded")}
                            lastNote=record
                        }
                    }
                }
                item.source.turnId to record
            }.mapValues{(_,record)->record?.takeIf{it.entrySet().any{field->!field.value.isJsonNull}}}
        }
    }
}
