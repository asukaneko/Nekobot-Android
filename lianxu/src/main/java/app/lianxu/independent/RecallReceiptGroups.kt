package app.lianxu.independent

/** Display projection only. The original tool states, results and journal rows are never rewritten. */
internal enum class RecallDisplayOutcome(val label:String) {
    FOUND("想起来了"),NOT_FOUND("没找到"),ERROR("回想出错"),RUNNING("让我想一想"),UNKNOWN("结果未确认")
}

internal fun ChatToolActivity.recallOutcome():RecallDisplayOutcome=when(state){
    "success"->RecallDisplayOutcome.FOUND
    // Current misses have empty detail. Older ungrouped chat searches kept this fixed miss text;
    // preserve that recorded meaning without guessing whether an old lost payload had duplicates.
    "failed"->if(detail.isBlank()||(tool=="agent_history_search"&&recallGroup==null&&detail=="没找到符合的内容"))RecallDisplayOutcome.NOT_FOUND else RecallDisplayOutcome.ERROR
    "running"->RecallDisplayOutcome.RUNNING
    else->RecallDisplayOutcome.UNKNOWN
}

internal data class RecallReceiptGroup(val items:List<ChatToolActivity>) {
    val id get()=items.first().id
    val hasFailure get()=items.any{it.state=="failed"}
    val succeeded get()=items.all{it.state=="success"}
    fun label():String {
        val outcomes=items.map{it.recallOutcome()}.toSet()
        if(RecallDisplayOutcome.RUNNING in outcomes)return "让我想一想"+if(hasFailure)" · 已有失败" else ""
        if(RecallDisplayOutcome.UNKNOWN in outcomes)return (if(RecallDisplayOutcome.FOUND in outcomes)"想起来了 · 部分结果未确认" else "结果未确认")+if(hasFailure)" · 存在失败" else ""
        if(hasFailure&&RecallDisplayOutcome.FOUND in outcomes)return "想起来了 · 部分失败"
        return outcomes.joinToString(" · "){it.label}
    }
}

/** Every member points at its group; only the first member renders the shared capsule. */
internal fun recallReceiptGroups(items:List<ChatToolActivity>):Map<String,RecallReceiptGroup> {
    val groups=items.filter{it.tool in ChatToolActivity.RECALL_TOOLS&&it.state!=ChatToolActivity.SUPERSEDED}
        .groupBy{Triple(it.attempt,it.recallGroup!=null,it.recallGroup?:it.id)}
    return buildMap{groups.values.forEach{members->
        val group=RecallReceiptGroup(members.toList());members.forEach{put(it.id,group)}
    }}
}
