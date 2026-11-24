package app.lianxu.independent

/**
 * Bounded repair loop for one memory batch: the initial request plus at most two repairs that carry
 * the concrete validation errors back to the model, three model calls in total. Nothing publishes
 * until a whole response validates; a failed batch keeps its source rounds in the recent window.
 */
internal object MemoryRepairFeedback {
    const val MAX_ATTEMPTS=3
    data class Issue(val operation:String,val code:String,val detail:String) {
        fun line()="${clean(operation,64)} · $code · ${clean(detail,240)}"
        companion object {fun parse(line:String):Issue?=line.split(" · ",limit=3).takeIf{it.size==3}?.let{Issue(it[0],it[1],it[2])}}
    }
    private fun clean(value:String,limit:Int)=value.replace(Regex("[\\r\\n\\t]+")," ").trim().take(limit)
    private val repairable=setOf("FORMAT","EVIDENCE","UNKNOWN_TARGET","USER_EVIDENCE","DATE_EVIDENCE","PROTECTED","LEGACY_READ_ONLY","OPERATION_CHANGED","INCOMPLETE","AFTER_COMPLETE")
    private val final=setOf("BATCH_REVIEW_REQUIRED","SOURCE_CHANGED","SOURCE_EDITED","MEMORY_CHANGED","STALE_TASK","CANCELLED","CAPACITY","MODEL_UNAUTHORIZED","MODEL_FORBIDDEN","MODEL_PAYMENT","MODEL_TLS","MODEL_INVALID_CONFIG","LATE_DISCARDED","MODEL_STREAM_LIMIT","MODEL_STREAM_PROTOCOL")
    /** Structural or source-reference errors that the model can fix when told exactly what was wrong. */
    fun repairable(code:String)=code in repairable
    fun retry(attempt:Int,code:String)=attempt<MAX_ATTEMPTS&&code !in final
    // At most 64 operation rows plus one grouped header and one grouped document receipt.
    fun encode(issues:List<Issue>)=issues.take(66).joinToString("\n"){it.line()}
    fun decode(detail:String)=detail.lines().mapNotNull{Issue.parse(it)}
    /** Specific, model-facing description. Raw model values are quoted, never executed. */
    fun hint(code:String,target:String,evidence:String):String=when(code){
        "UNKNOWN_TARGET"->"target「${clean(target,40)}」不是提供的已有记忆编号；更新只能用 existing_memories 的 M 编号，新增用 N 编号"
        "EVIDENCE"->"evidence「${clean(evidence,80)}」无效；只能引用 target_sources 中存在的 S 编号，R 辅助原话不能作证据"
        "USER_EVIDENCE"->"about=USER 但没有引用用户原话（side=user 的 S 编号）"
        "DATE_EVIDENCE"->"date_expression/due_expression 必须逐字出现在所引原话中，且不能提供 due_date"
        "PROTECTED"->"不能包含 locked 或 manual 字段"
        "LEGACY_READ_ONLY"->"不能更新未归入当前四分类的历史记录"
        "OPERATION_CHANGED"->"同一 id 的内容与之前不一致，或 ADD 与既有事实冲突"
        "INCOMPLETE"->"缺少完整结束回执，或 operation_count 与实际操作数不符"
        "AFTER_COMPLETE"->"结束回执之后不能再输出内容"
        else->"字段缺失、类型或取值无效（id/op/category/about/text/evidence）"
    }
    /** Short user-facing reason for the failure notice and the settings overview. */
    fun reason(code:String):String=when(code){
        "UNKNOWN_TARGET"->"模型引用了不存在的记忆编号"
        "EVIDENCE"->"模型引用了不存在的原话编号或原文不符"
        "USER_EVIDENCE"->"用户画像没有引用用户原话"
        "DATE_EVIDENCE"->"日期表达与原话不符"
        "FORMAT"->"输出格式不合格"
        "INCOMPLETE"->"输出不完整（缺少结束回执或数量不符）"
        "OPERATION_CHANGED"->"重试时同一操作被改写"
        "PROTECTED"->"试图写入受保护字段"
        "LEGACY_READ_ONLY"->"试图更新历史分类记录"
        "AFTER_COMPLETE"->"结束回执之后还有内容"
        "SUMMARY_TIMEOUT","MODEL_TIMEOUT"->"超时"
        "SOURCE_CHANGED"->"来源或设置在总结期间变化"
        "SOURCE_EDITED"->"引用的原话在总结期间被编辑或撤回"
        "MEMORY_CHANGED"->"关联的已有记忆在总结期间被改动"
        "LATE_DISCARDED"->"超时后晚到的结果已丢弃"
        "MODEL_UNAUTHORIZED"->"认证失败";"MODEL_FORBIDDEN"->"无权访问";"MODEL_PAYMENT"->"服务账户余额不足（HTTP 402），请充值或更换记忆模型";"MODEL_RATE_LIMIT"->"服务限流";"MODEL_NETWORK"->"网络错误";"MODEL_TLS"->"HTTPS 证书校验失败"
        "MODEL_STREAM_LIMIT"->"模型一次回的内容太长，超过读取上限（本次没有采用）";"MODEL_STREAM_PROTOCOL"->"模型返回的不是正常的事件流（本次没有采用）"
        "CAPACITY","TURN_TOO_LARGE","INPUT_TOO_LARGE"->"模型容量不足"
        "CANCELLED"->"已停止"
        else->if(code.startsWith("MODEL_"))"模型服务错误（${code.removePrefix("MODEL_")}）" else code
    }
}
