package app.lianxu.independent

/**
 * Optional accounting tool (protocol C). The reply itself is plain text; when something really
 * happened that is worth recording, the character calls this the same way it calls recall.
 * The payload keeps the field names the parsers already validate, so accounting rules stay one place.
 */
internal object CompanionRecord {
    const val NAME="companion_record"
    /** Follow-up round note: the accounting landed, so its payload is replaced by this line. */
    const val stagedNote="这次通过检查的内容已暂存，尚未正式保存；本轮回复成功保存时才会一并写入，回复失败或取消就不会保存这次记录。"
    const val partialStagedNote="$stagedNote 关系部分只接收了通过检查的内容，没通过的部分没有记下。"
    const val receiptNote="[companion_record] $stagedNote"
    /** Preserve a partial result when the successful tool exchange is folded into a short note. */
    fun receiptNote(result:Map<String,Any>)="[companion_record] "+(result["note"] as? String?:stagedNote)+(result["relation"]?.let{"\n$it"}?:"")
    /**
     * The field guide travels with the tool instead of the reply prompt: the visible reply stays
     * instructions about talking, and the accounting rules sit next to the fields they describe.
     * [source] and [needsExperience] change every turn, so this is built per request.
     */
    internal fun definition(source:Long,needsExperience:Boolean,afterReply:Boolean=false):Map<String,Any> {
        val sample="""{"experience":{"quote":"","memory":"","emotion":"unknown","valence":0,"relationship":"none","control":"none","targets":[],"reconsider":null,"claimKind":"none","controlIntent":"none","requestQuote":""},"appraisal":{"event":"本轮交流","meaning":"依据原话理解当前处境","certainty":"reported","relevance":"回应当前交流","attribution":"shared_interaction","situation":"daily_chat","eventRelation":"new_event","uncertainty":[],"evidence":[{"source":$source,"layer":"fact"}]},"feelings":[],"goal":{"description":"回应当前交流","origin":"current_request","state":"active","reason":"当前输入需要回应","evidence":[{"source":$source,"layer":"fact"}]},"choices":[{"id":"respond","action":"acknowledge","reason":"依据当前完整原话回应","evidence":[{"source":$source,"layer":"fact"}]}],"selected":"respond","judgment":{"position":"不超出已有依据","kind":"inference","evidence":[{"source":$source,"layer":"fact"}]},"feedback":[],"learning":null,"usedLearningIds":[],"consideredLearningIds":[],"future":null,"关系输入":${RelationInputContract.example(source)}}"""
        val guide="""
            [companion.record.v1] 以下字段只在调用本工具时使用；不调用就完全不用管，也不要把它们写进回复。样例只示结构，不是默认结论：
            $sample
            ${if(needsExperience)JointExperience.guide(afterReply) else "experience 本轮已理解过，可省略；只记独立有效的感受、关系与学习。"}
            emotion 是用户观察：unknown/calm/happy/sad/anxious/angry；valence=-1/0/1，unknown 配 0。relationship=none/appreciation/boundary（无/明确感谢/明确边界），非关系分数。
            claimKind=reported_event/plan/hypothesis/fiction/quoted_report/interaction/none；假设、虚构不作真实事件计账。controlIntent=none/forget/stop_reminding/correct/uncertain；requestQuote 逐字引当前直接要求，引用/否定/往事不授权。
            appraisal 的 event/meaning/certainty/relevance/attribution/situation/eventRelation 必填非空字符串；meaning=事件对角色关注事项的意义，不能用 event 替代。uncertainty 是字符串数组，无疑点才 []。certainty=reported/inference/unknown；attribution=user_report/shared_interaction/role_inference/unknown；eventRelation=new_event/progress/repetition/correction/uncertain。
            evidence={source:有效用户消息整数编号,layer:fact/interpretation/action}；action 还须 message=已提交回复编号。当前来源 ${source} 仅 fact，不自证，不引未知角色/未来编号。action.source 取回话笔记「你之前的回应」里对方那条消息的编号，message 取对应的回复编号。
            feelings 元素为 {kind:joy/curiosity/care/relief/disappointment/concern/calm,degree:mild/moderate/strong,object,reason,evidence}；未知不可形成强烈负向判断。goal={description,origin:current_request/shared_goal/role_interest/clarification,state:active/waiting/satisfied/paused/cancelled,reason,evidence}。judgment.kind=evidence_based/inference/uncertain。
            choices 每项 {id,action,reason,evidence}，action 仅 acknowledge/celebrate/listen/clarify/explore/share/assist/pause；selected 必须等于其中一项 id。有不确定且获准回查须实际查；未知时只选 clarify/listen/acknowledge/pause。
            feedback 仅据当前原话对先前真实行动的结果/评价：{actionMessage,verdict:helpful/unhelpful/rejected/unknown,quote}。未回复=未知，自评非反馈。
            learning 可为 {id:已有候选ID或新建时空串,kind:viewpoint/interest/habit,scope,content,phase:propose/revise/pause,evidence}；变化无须一律获批，新候选 propose、有新经历 revise、反证 pause，revise 保留 id/kind/scope。用户报告照着已保存回复/主动消息行动后的结果时，revise.evidence 须同时引用该 action 与当前 fact；无真实行动不得编造。兴趣不授权外部行动。
            usedLearningIds 须为 consideredLearningIds 的子集；仅用当前状态，paused 禁用。future={kind:interest_share/shared_continuation/follow_up/actual_result,reason,notBefore:Unix毫秒,evidence}，下次时间由你决定，可从现在起安排，无固定等待下限或独立有效期；到时结合最新聊天再决定联系、延后或放下，系统可能迟到；无适合理由省略。
            ${RelationInputContract.instruction(source)}
            ${RelationParameterContract.instruction}
        """.trimIndent()
        return mapOf("type" to "function","function" to mapOf(
            "name" to NAME,
            "description" to "记下这轮真正值得留下的事：新约定或改期、重要的新情况、约好兑现或没做到、伤到你或越过边界、失望或落差、对方的解释道歉和弥补、明确的感谢这类。平时专心聊天，没有新内容的问候、玩笑、颜文字一般不用记；「下班」「吃饭」这类日常不是禁区，带出新事实或有特殊意义时照样可以记。不调用也能正常回话。程序会校验字段和来源，失败会返回错误代码，可按提示改一次再试。\n"+guide,
            "parameters" to mapOf("type" to "object","properties" to mapOf(
                "experience" to mapOf("type" to "object","description" to "本轮用户原话的独立理解（quote/memory/emotion/valence/relationship/control/targets/reconsider/claimKind/controlIntent/requestQuote）；未理解过本轮时必须给"),
                "appraisal" to mapOf("type" to "object","description" to "本轮事件对角色关注事项的意义、确定性与依据；字段规则见本工具的说明"),
                "feelings" to mapOf("type" to "array","items" to mapOf("type" to "object"),"description" to "角色自己的感受；没有就空数组"),
                "goal" to mapOf("type" to "object","description" to "角色本轮的目标"),
                "choices" to mapOf("type" to "array","items" to mapOf("type" to "object"),"description" to "可选的回应方式"),
                "selected" to mapOf("type" to "string","description" to "选中的 choices.id"),
                "judgment" to mapOf("type" to "object","description" to "角色对当前交流的判断"),
                "feedback" to mapOf("type" to "array","items" to mapOf("type" to "object"),"description" to "对先前真实行动的结果反馈；没有就空数组"),
                "learning" to mapOf("type" to "object","description" to "有依据的看法/兴趣/习惯候选；没有就省略"),
                "usedLearningIds" to mapOf("type" to "array","items" to mapOf("type" to "string")),
                "consideredLearningIds" to mapOf("type" to "array","items" to mapOf("type" to "string")),
                "future" to mapOf("type" to "object","description" to "有依据的未来回访；没有就省略"),
                "关系输入" to mapOf("type" to "object","description" to "本轮关系记账；字段规则见本工具的说明"),
                "改主动参数" to mapOf("type" to "object","description" to "按用户明确要求调整主动联系参数；没有就省略")),
                "required" to listOf<Any>())))
    }
}
