package app.lianxu.independent

import com.google.gson.JsonElement
import app.lianxu.independent.ToolArgumentDiagnosis.Problem
import app.lianxu.independent.ToolArgumentDiagnosis.actual

/**
 * Honest receipt for 关系输入. A record whose 关系输入 is missing or unreadable is not a plain
 * success: the rest is still staged, and the model is told once, in plain Chinese, which field to
 * fix and that one resend is allowed. A second miss is accepted without the relation part.
 */
internal object RelationReceipt {
    /** Shape errors in 关系输入 worth one resend. Wrong references (unknown old events, bases) are judged, not shape, and stay silent as before. */
    val formatCodes=setOf("OBJECT_REQUIRED","CURRENT_SOURCE_REQUIRED","OPTION_REQUIRED","INVALID_OPTION","BOOLEAN_REQUIRED","INCOMPLETE_GROUP")
    /** Whether this record needs the honest receipt: 关系输入 missing, unreadable or with a shape error. */
    fun needed(parsed:RelationInput?,issues:List<RelationIssue>)=parsed==null||issues.any{it.code in formatCodes}
    const val GIVEN_UP="关系部分仍未接收成功，这轮没有记下这部分；其余通过检查的内容只是暂存，要等本轮回复成功保存后才会正式写入。"
    /** Why 关系输入 could not be read; [issues] are the ones RelationInputContract.parse reported. */
    fun problems(value:JsonElement?,issues:List<RelationIssue>,current:Long):List<Problem> {
        if(value==null||value.isJsonNull)return listOf(Problem("关系输入","必填，放在参数顶层的对象",actual(value)))
        if(!value.isJsonObject)return listOf(Problem("关系输入","为对象，不要写成字符串或别的类型",actual(value)))
        val j=value.asJsonObject
        return issues.filter{it.code in formatCodes}.map{
            when(it.code){
                "CURRENT_SOURCE_REQUIRED"->Problem("关系输入.消息","原样填当前来源编号 \"$current\"",actual(j.get("消息")))
                "OPTION_REQUIRED"->Problem("关系输入.信息类别","为 ${RelationInputContract.categories.joinToString("/")} 之一",actual(j.get("信息类别")))
                "INVALID_OPTION"->Problem("关系输入.${it.field}","为 无/轻/中/重 之一",actual(j.get(it.field)))
                "BOOLEAN_REQUIRED"->Problem("关系输入.${it.field}","为 true 或 false",actual(j.get(it.field)))
                "INCOMPLETE_GROUP"->Problem("关系输入.${it.field}","三项齐全：{期待:低/中/高,重要性:低/中/高,落差:小/中/大}",actual(j.get(it.field)))
                else->Problem(if(it.field=="关系输入")"关系输入" else "关系输入.${it.field}","按工具说明填写",actual(if(it.field=="关系输入")value else j.get(it.field)))
            }
        }.ifEmpty{listOf(Problem("关系输入","按工具说明填写","读不出来"))}
    }
    fun resend(problems:List<Problem>,current:Long):Map<String,Any> {
        val missing=problems.size==1&&problems[0].field=="关系输入"&&problems[0].actual=="缺失"
        val head=if(missing)"关系输入没收到" else "关系输入格式不对"
        val fields=problems.joinToString("；"){"${it.field}：${it.actual}，应${it.expected}"}
        return mapOf("success" to false,"error" to if(missing)"RELATION_INPUT_MISSING" else "RELATION_INPUT_INVALID",
            "problems" to problems.map{mapOf("field" to it.field,"expected" to it.expected,"actual" to it.actual)},
            "detail" to "$head（$fields）。其余内容已经暂存，尚未正式保存；关系部分还没有接收成功。请把整份参数连同参数顶层的“关系输入”对象（例如 ${RelationInputContract.example(current)}）再调用一次 companion_record；只补这一次，补不上就照常回话。")
    }
}
