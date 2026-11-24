package app.lianxu.independent

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException

internal data class RelationParameterRange(val min:Double,val max:Double,val explanation:String,val whole:Boolean=false)
internal object RelationParameterRules {
    val ranges=linkedMapOf(
        "trustGain" to RelationParameterRange(.05,.40,"好的相处让她增加多少信任"),"trustLoss" to RelationParameterRange(.10,.60,"失约或越界时信任会减少多少"),
        "closenessGain" to RelationParameterRange(.05,.40,"相处后亲近增加多快"),"cooling" to RelationParameterRange(0.0,.20,"已不再使用：亲近改按半衰期慢慢变淡，这个值留着只为兼容旧数据"),
        "sensitivity" to RelationParameterRange(0.0,1.5,"落空时有多容易失望"),"disappointmentHours" to RelationParameterRange(1.0,336.0,"失望多久消掉一半（小时）"),
        "unhappyHours" to RelationParameterRange(1.0,336.0,"生气多久消掉一半（小时）"),"happyHours" to RelationParameterRange(1.0,336.0,"开心多久消掉一半（小时）"),
        "apology" to RelationParameterRange(0.0,1.0,"道歉能缓解多少心结"),
        "missingMax" to RelationParameterRange(0.0,1.0,"想念最多有多强"),"missingDays" to RelationParameterRange(1.0,30.0,"想念多久涨到大半（天）"),
        "silenceDays" to RelationParameterRange(.5,14.0,"已停用：旧主动联系阈值，仅保留历史数据"),"resendDays" to RelationParameterRange(.25,7.0,"已停用：旧未回复间隔，仅保留历史数据"),
        "resendCount" to RelationParameterRange(0.0,3.0,"已停用：旧未回复次数上限，仅保留历史数据",true),"dailyMax" to RelationParameterRange(0.0,5.0,"已停用：旧每日上限，仅保留历史数据",true),
        "ignoredDisappointment" to RelationParameterRange(0.0,.2,"主动消息被晾时增加多少失望"))
    // Legacy keys remain readable/reversible in history, but are not active settings or proposals.
    val storedProposalKeys=setOf("silenceDays","resendDays","resendCount","dailyMax","tone")
    val modelAllowed=setOf("tone")
    fun activeProposal(values:Map<String,String>)=values.isNotEmpty()&&values.keys.all{it in modelAllowed}
    val descriptions=ranges.mapValues{it.value.explanation}+mapOf("tone" to "主动消息用什么语气","proactive" to "有心结时会不会主动提一次")
    fun clamp(p:RelationParameters):RelationParameters {
        val j=Gson().toJsonTree(p).asJsonObject
        ranges.forEach{(key,range)->val raw=j[key].asDouble;val n=(if(raw.isFinite())raw else range.min).coerceIn(range.min,range.max);j.addProperty(key,if(range.whole)n.toInt()else n)}
        j.addProperty("tone",p.tone.take(120));return Gson().fromJson(j,RelationParameters::class.java)
    }
    fun apply(p:RelationParameters,values:Map<String,String>):RelationParameters {
        require(values.keys.all{it in storedProposalKeys})
        val j=Gson().toJsonTree(p).asJsonObject
        values.forEach{(key,value)->if(key=="tone"){require(value.isNotBlank());j.addProperty(key,value.take(120))}else{
            val n=value.toDouble();require(n.isFinite());j.addProperty(key,n)
        }}
        return clamp(Gson().fromJson(j,RelationParameters::class.java))
    }
    fun custom(base:RelationTemplate,p:RelationParameters)=RelationCustom("自定义（基于${base.label}）",base,clamp(p))
}
internal data class RelationCustom(val label:String,val base:RelationTemplate,val parameters:RelationParameters)
internal data class RelationParameterProposal(val source:SourceRef?,val values:Map<String,String>,val sentence:String,val at:Long)
internal data class RelationParameterAnswer(val proposal:RelationParameterProposal?,val message:String=if(proposal==null)"她没说清楚，再问一次"else "")
internal object RelationParameterContract {
    val parameterInstruction="""
        只有本轮用户明确提出更改主动联系的语气时，顶层加“改主动参数”:{"消息":"本轮来源编号","参数":{"tone":"温柔些"},"她的话":"那我说得温柔些。"}。
        参数只准tone（语气）。用户没有提出就省略。不能改情绪、人设、记忆、模型或密钥，不要把普通聊天当设置。只是待用户确认的建议，不能声称已经改好。
    """.trimIndent()
    val instruction="""
        只有本轮用户明确提出更改主动联系的语气时，顶层加“改主动参数”:{"消息":"本轮来源编号","参数":{"tone":"温柔些"},"她的话":"那我说得温柔些。"}。
        参数只准tone（语气）。用户没有提出就省略。联系时间由你结合对话安排，不提供频率、间隔或次数设置建议。不能改情绪、人设、记忆、模型或密钥，不要把普通聊天当设置。只是待用户确认的建议，不能声称已经改好。
    """.trimIndent()
    fun parse(value:JsonElement?,source:SourceRef?,input:String?,at:Long):RelationParameterProposal?=runCatching{
        val j=value?.takeIf{it.isJsonObject}?.asJsonObject?:return null
        if(source!=null){
            require(j["消息"].asString.toLong()==source.turnId)
        }
        val values=j["参数"].asJsonObject.entrySet().associate{(key,v)->require(v.isJsonPrimitive);key to v.asString}
        require(RelationParameterRules.activeProposal(values))
        val sentence=j["她的话"].asString;require(sentence.isNotBlank()&&sentence.length<=500)
        val clamped=RelationParameterRules.apply(RelationTemplate.STEADY.parameters,values)
        val encoded=Gson().toJsonTree(clamped).asJsonObject
        RelationParameterProposal(source,values.keys.associateWith{encoded[it].asString},sentence,at)
    }.getOrNull()
}
internal class RelationshipAsk(private val client:TextChatClient){
    suspend fun ask(definition:String,config:ModelConfig,key:String,parameter:String?=null,at:Long):RelationParameterAnswer {
        require(parameter==null||parameter in RelationParameterRules.modelAllowed)
        val names=parameter?.let{setOf(it)}?:RelationParameterRules.modelAllowed
        val instructions=names.associateWith{RelationParameterRules.descriptions[it]}
        return try{
            val request=TextChatRequest(listOf(WireMessage("system","按人设提出主动联系参数建议。只返回JSON {\"参数\":{参数名:值},\"她的话\":\"一句自然的话\"}。允许参数及说明：${Gson().toJson(instructions)}。不得改其他设置。"),WireMessage("user",definition)),jsonObject=true,outputLimit=minOf(1024,config.outputTokens))
            val response=client.complete(config,key,request) as? TextChatResult.Success
            val proposal=response?.takeIf{it.toolCalls.isEmpty()}?.let{RelationParameterContract.parse(JsonParser.parseString(it.text),null,null,at)}?.takeIf{it.values.keys==names}
            RelationParameterAnswer(proposal)
        }catch(e:CancellationException){throw e}catch(_:Exception){RelationParameterAnswer(null)}
    }
}
