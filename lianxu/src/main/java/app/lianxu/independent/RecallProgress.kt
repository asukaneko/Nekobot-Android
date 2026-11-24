package app.lianxu.independent

/** Per-answer exact-call guard. Ordinary misses/errors are feedback, not a global stop. */
internal class RecallProgress {
    private val seen=hashSetOf<String>()
    var stopped:String?=null;private set
    fun begin(signature:String):Boolean = stopped==null&&seen.add(signature)
    fun blockedResult():Map<String,Any> = stopped?.let {
        mapOf("success" to true,"recall_stopped" to true,"reason" to it,"note" to "已到回查安全边界，不能通过换词或换工具继续读取；请依据已有资料回答，不足就说明。")
    } ?: mapOf("success" to true,"status" to "already_checked","query_duplicate" to true,
        "note" to "本次已尝试相同调用，未再次执行。可使用已有反馈，或换关键词、换工具/读取原话；没有结果不代表用户没说过。")
    fun accept(result:Map<String,Any>){
        if(stopped!=null)return
        if(result["recall_stopped"]==true)stopped=(result["reason"]?:result["status"]?:"safety_limit").toString()
        else if(result["error"] in setOf("TOOLS_DISABLED","SOURCE_CHANGED","PERMISSION_DENIED","CONTEXT_CAPACITY","CANCELLED"))stopped=result["error"].toString()
    }
}
