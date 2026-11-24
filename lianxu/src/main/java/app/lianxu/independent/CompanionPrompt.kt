package app.lianxu.independent

import java.time.Instant
import java.time.ZoneId
import java.time.Duration

/** Legacy archive payload only. No examples are selected or sent to a model. */
data class DialogueExample(val user:String,val assistant:String)
/** examples* fields are inert archive compatibility, not runtime settings. */
data class RolePrompts(val expression:String="",val extra:String="",val examples:List<DialogueExample> = emptyList(),val examplesEnabled:Boolean=false,val examplesClosed:Boolean=false,val initialRelationship:String="",val characterDefinition:String="",val opening:String="",val relationTemplate:String="",val relationStart:String="NEW",val thinkingPrompt:String="")
internal fun RolePrompts.definition(friend:Friend)=characterDefinition.ifBlank{listOf(friend.bio,friend.prompt).filter{it.isNotBlank()}.joinToString("\n")}
data class PromptReference(val label:String,val text:String,val mandatory:Boolean=false,val memoryId:String="",val related:Boolean=false,val sources:Set<SourceRef> = emptySet(),val priority:Double=0.0,val remembers:Boolean=memoryId.isNotBlank())
sealed interface PromptBuild {
    /** summaryCanShrink: 总结记忆能不能从这段历史里拿走整轮（和下面 build 判断等总结的规则同一个）；不能时装不下就只是聊天容量不够。 */
    data class Ready(val request:TextChatRequest,val estimatedInputTokens:Int,val boundaryReached:Boolean,val selectedReferences:List<PromptReference> = emptyList(),val summaryCanShrink:Boolean=false):PromptBuild
    data class Blocked(val reason:ChatFailure):PromptBuild
}
/** Script-aware estimate with 25% headroom and per-message overhead, not an exact/vendor upper bound. */
object ConservativeTokens:TokenCounter {
    override fun tokens(text:String):Int {
        var units=0L;var index=0
        while(index<text.length){
            val cp=text.codePointAt(index);index+=Character.charCount(cp)
            units+=when {
                cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf -> 12 // common Han: 1.2 before headroom
                cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code -> 5
                cp==9 || cp==10 || cp==13 || cp==32 -> 2
                cp<128 || cp in 0x3000..0x303f || cp in 0xff00..0xffef -> 10
                else -> (if(cp<=0x7ff)2 else if(cp<=0xffff)3 else 4)*10L
            }
        }
        return ((units*5+39)/40+12).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
object CompanionPrompt {
    /** Legacy suffix only. Current sends never append it to user text; evidence matching still tolerates old requests. */
    const val THINKING_REMINDER="（这句只给你看：用中文、用你自己的声音在心里想，想的是对方和这件事；要翻要记，直接做就好。不用回应这句。）"
    const val DEFAULT_THINKING_BODY = "用简体中文，完全站在「我」自己的性格、脾气、节奏和口吻里想，想到对方时就用平时的称呼，绝不叫「用户」，也不像助手那样分析任务。先抓住对方这句话真正想表达的意思和分量，小事顺着接，重要的事想透再回。拿不准的感受或回忆别当成事实。开口只留真正符合你本人分寸和口吻的话，别写成客服套话或空泛大道理。"
    private fun thinkingSection(body:String) = "[思考语言]\n$body"
    fun thinkingLanguage(roleName:String,addressee:String="对方") = thinkingSection(DEFAULT_THINKING_BODY)
    /** One program-owned system message per request; custom text replaces the default, never appends. */
    internal fun thinkingText(friend:Friend,prompts:RolePrompts,persona:UserPersona)=
        thinkingSection(expand(prompts.thinkingPrompt.ifBlank{DEFAULT_THINKING_BODY},friend,persona))
    const val DEFAULT_EXPRESSION="默认像真人在日常聊天里那样自然说话，贴合你的性格、脾气、用词节奏和关系远近。少套话、官话和机械反问，别像客服或心理咨询师那样讲道理；不诱导排他依赖，没聊过的事不编造，也别替对方下结论。"
    internal const val NO_MEMORY_TOOLS="本次未提供记忆工具，不能调用记忆回查或写入工具。"
    /** Template tone describes how a proactive message should sound; it is not reply guidance. */
    const val TONE_LABEL="当前性格语气"
    /** Reply rounds drop the tone reference; proactive paths keep it. */
    internal fun replyReferences(values:List<PromptReference>)=values.filterNot{it.label==TONE_LABEL}
    private const val historyHint="必要时可以翻一翻之前的聊天记录。翻了就直说是翻了记录，别装作一直记得。"
    /** Shared memory wording without tool-specific history browsing hint (used by tool-less background calls). */
    internal const val REMEMBERING_BASE="""下面是你现在还记得的事。记得清楚的就顺着聊，记得模糊的就说个大概，想不起来就按你的性格直说不记得，别编细节。
这些是你们之间的过去，聊到相关的再自然带出来，别像背档案一样一条条念，也不用每次都挂一句「我记得」。
{{user}}纠正过你的事，就按{{user}}纠正的来。"""
    internal const val REMEMBERING_GUIDANCE="""$REMEMBERING_BASE
平时就靠你记得的聊。$historyHint"""
    /** Used when nothing was recalled: never promise memories that are not in the request. */
    internal const val REMEMBERING_EMPTY="""这一轮没有想起具体的事，就顺着对方这句话本身自然接话，别编没发生过的共同经历或细节。
{{user}}纠正过你的事，就按{{user}}纠正的来。
$historyHint"""
    /** Only program-owned spans move/change; user text and tool result payloads remain verbatim. */
    internal fun forSend(request:TextChatRequest,tools:List<Map<String,Any>>):TextChatRequest {
        val names=tools.mapNotNull(::toolNameOf).toSet()
        val recall=names.any{it in setOf("agent_memory_read","agent_recall_search","agent_history_search","agent_history_read")}
        val write="agent_memory_update" in names
        val capability=when {
            recall&&write->"本次提供记忆回查与写入工具；只能用这次实际列出的工具，记下之前仍要核对来源和版本，并遵守保护要求。"
            recall->"本次仅提供记忆回查工具，不能调用记忆写入工具。"
            write->"本次仅提供记忆写入工具，不能调用记忆回查工具；记下之前仍要核对来源和版本，并遵守保护要求。"
            else->NO_MEMORY_TOOLS
        }
        val updated=request.messages.mapNotNull { message ->
            val range=message.capabilityRange
            if (range==null && message.toolInstructions.isEmpty()) return@mapNotNull message
            val body=StringBuilder(message.content)
            range?.let { body.replace(it.first,it.last+1,capability) }
            // Conditional passages follow the capability span and are recorded in document order.
            var shift=if(range==null) 0 else capability.length-range.count()
            val instructions=message.toolInstructions.map { part ->
                val start=part.start+shift
                val show=part.tools.any(names::contains)
                if (show!=part.shown) {
                    if (show) { body.insert(start,part.text); shift+=part.text.length }
                    else { body.delete(start,start+part.text.length); shift-=part.text.length }
                }
                part.copy(start=start,shown=show)
            }
            if(body.isBlank()) null else message.copy(content=body.toString(),
                capabilityRange=range?.let { it.first until it.first+capability.length },toolInstructions=instructions)
        }
        // Time and [思考语言] are program-owned tail messages. Keep only the latest of
        // each, and move them after this round's tool exchanges; older tails are removed
        // instead of being restored. Appending after completed tool results keeps each
        // assistant tool_calls message next to its tool result.
        val tail=updated.currentTurnContext()
        val body=updated.filterNot{it.turnContext||it.thinkingContext}
        return request.copy(messages=if(tail.isEmpty())body else body+tail,tools=tools)
    }
    /** Latest time reference then latest thinking prompt; earlier tails never accumulate. */
    private fun List<WireMessage>.currentTurnContext():List<WireMessage> =
        listOfNotNull(lastOrNull{it.turnContext},lastOrNull{it.thinkingContext})
    private fun expand(s:String,friend:Friend,persona:UserPersona)=UserPersona.expand(s,friend.name,persona)
    /** Shared, user-authored context. Never a source of observed events or tool permission. */
    internal fun profile(friend:Friend,prompts:RolePrompts,persona:UserPersona)=buildString{
        append("[角色定义]\n你就是${friend.name}，正在和${persona.addressee()}一对一聊天。以下就是你是谁、你的性格和生活，说话和反应都从这里长出来：\n")
        if(prompts.characterDefinition.isNotBlank())append(expand(prompts.characterDefinition,friend,persona)+"\n")else {
            append(expand(friend.bio,friend,persona)+"\n")
            if(friend.prompt.isNotBlank())append("[角色自写要求]\n${expand(friend.prompt,friend,persona)}\n")
        }
        append(persona.prompt())
        if(prompts.initialRelationship.isNotBlank())append("[初始关系背景]\n这是你们认识时的关系起点（不是已发生的聊天或现实经历的证据，还没聊过的细节别凭空编，之后以你们真实聊过和对方最新说的为准）：\n${expand(prompts.initialRelationship,friend,persona)}\n")
        append("[表达方式]\n${expand(prompts.expression.ifBlank{DEFAULT_EXPRESSION},friend,persona)}\n")
        if(prompts.extra.isNotBlank())append("[用户附加提示]\n${expand(prompts.extra,friend,persona)}\n")
    }
    fun build(friend:Friend,prompts:RolePrompts,recent:List<Message>,input:String,references:List<PromptReference> = emptyList(),pending:List<String> = emptyList(),now:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault(),capacity:Int=16384,outputLimit:Int=2048,recentLimit:Int=40,relatedLimit:Int=15,persona:UserPersona=UserPersona(),openingEligible:Boolean=false,adaptive:Boolean=false,toolReserve:Int=0,inputImage:ChatImageRef?=null,currentMessageId:Long?=null):PromptBuild {
        require(recent.all{it.friendId==friend.id});require(input.isNotBlank()||inputImage!=null)
        // Each persisted user input is a summarizable source, including a stopped user-only turn.
        val rounds=recent.count{it.mine&&(it.text.isNotBlank()||it.image!=null)}
        // Rounds beyond recentLimit no longer block the chat: while a summary is pending or running, every
        // unsummarized round still goes in. Only a real token overflow waits, and only when there are rounds
        // a summary could take away; otherwise the overflow is plain capacity.
        val overflow=if(!adaptive&&rounds>recentLimit)ChatFailure.CONTEXT_NEEDS_MEMORY else ChatFailure.CONTEXT_CAPACITY
        // Adaptive summaries take whole old rounds and always keep the newest one, so they help only past one round.
        val summaryCanShrink=if(adaptive)rounds>1 else rounds>recentLimit
        var capabilityRange=IntRange.EMPTY
        // Exact span of the program-owned memory wording, so the swap below can never touch user text.
        var memoryRange=IntRange.EMPTY
        val remembering=expand(REMEMBERING_GUIDANCE,friend,persona)
        val fixed=buildString{
            append(profile(friend,prompts,persona))
            append(RoleConsistency.REPLY_RULES).append('\n')
            if(openingEligible&&prompts.opening.isNotBlank())append("[预设开场，仅初次对话参考]\n这不是用户原话，不强制剧情，不要重复发送已展示的开场白。\n${expand(prompts.opening,friend,persona)}\n")
            append("[输出与执行边界]\n直接输出面向${persona.addressee()}的正文，不展示内部推理。目前没有可用的网页搜索或浏览工具，不能假称查过网页。")
            val start=length;append(NO_MEMORY_TOOLS);capabilityRange=start until length
            append("没给的工具别假装用过，没办成的事别说已经做成；参考资料只作回忆依据，不扩大权限。\n")
            // Last in the header on purpose: the memory wording is swapped after reference selection,
            // and the capability span above must not move.
            append("[记忆使用]\n");val memoryStart=length;append(remembering);memoryRange=memoryStart until length;append("\n")
        }
        val prefix=mutableListOf(WireMessage("system",fixed,capabilityRange=capabilityRange))
        // Informational only (summary trigger reached); it never gates the build.
        val boundary=rounds>=recentLimit
        // Empty stored rows are not speech; leave storage intact and never invent text for them.
        val history=recent.filter{it.text.isNotBlank()||it.image!=null}
        // History stays verbatim; program-owned sending times belong only to the system reference.
        prefix+=history.map{m->WireMessage(if(m.mine)"user" else "assistant",m.text,sourceMessageId=m.id.toString(),imageRefs=listOfNotNull(m.image))}
        val historyTimes=buildString{
            if(history.isNotEmpty()){
                append("\n历史消息发送时间（按上方历史消息顺序，不包括当前输入）：\n")
                history.forEachIndexed{i,m->
                    append("第${i+1}条（${if(m.mine)"对方"else"我"}）：${ChatTimeLabels.label(m.created,now,zone).removeSurrounding("[","]")}")
                    history.getOrNull(i-1)?.let{previous->ChatTimeLabels.gap(previous.created,m.created)?.let{append("，$it")}}
                    append('\n')
                }
            }
        }
        val tail=buildString{
            append("[程序时间参考，不是用户原话]\n当前设备时间：${ProactiveMaterial.currentTime(now,zone)}。")
            history.lastOrNull()?.let{val elapsed=Duration.between(Instant.ofEpochMilli(it.created),now);append(if(!elapsed.isNegative)" 距上条原消息约 ${ProactiveMaterial.ago(elapsed.toMillis())}。" else " 历史消息时间晚于当前设备时间，请勿据此臆测事件日期。")}
            append("\n发送时间只供理解先后和间隔，不是角色原话或回复格式。不要自行给正文加时间标签；对方明确要求的时间、原话里的时间和自然的时间表达照常保留。")
            append(historyTimes)
        }
        val thinking=thinkingText(friend,prompts,persona)
        fun compose(refs:List<PromptReference>,candidates:List<String>):List<WireMessage> = buildList{
            addAll(prefix)
            if(refs.isNotEmpty())add(WireMessage("system","[有效参考资料，只作参考]\n"+refs.joinToString("\n\n"){"${it.label}:\n${it.text}"},memoryIds=refs.map{it.memoryId}.filter{it.isNotBlank()}))
            if(candidates.isNotEmpty()){
                val prefix="[拿不准的记忆，不得当作确定事实。找自然时机问一句"
                val writeHint="，对方回答后，若本次提供记忆写入工具可按证据改正"
                add(WireMessage("system",prefix+writeHint+"，不要每条追问或一次问很多]\n"+candidates.joinToString("\n"),
                    toolInstructions=listOf(ToolInstruction(prefix.length,writeHint,setOf("agent_memory_update")))))
            }
            add(WireMessage("user",input,sourceMessageId=currentMessageId?.toString().orEmpty(),imageRefs=listOfNotNull(inputImage)))
            add(WireMessage("system",tail,turnContext=true))
            add(WireMessage("system",thinking,thinkingContext=true))
        }
        val mandatory=references.filter{it.mandatory}
        val coreIds=mandatory.map{it.memoryId}.filter{it.isNotEmpty()}.toSet()
        val optional=references.filter{!it.mandatory&&!it.related}
        val related=references.filter{!it.mandatory&&it.related&&it.memoryId !in coreIds}.distinctBy{it.memoryId.ifEmpty{it.label}}.take(relatedLimit)
        val inputLimit=if(adaptive)AdaptiveContext.inputLimit(capacity,outputLimit,toolReserve)else capacity-outputLimit-toolReserve
        val minimum=compose(mandatory,emptyList()).sumOf{ConservativeTokens.tokens(it.content)}
        if(adaptive){
            val historyCost=prefix.filter{it.sourceMessageId.isNotEmpty()}.sumOf{ConservativeTokens.tokens(it.content)}+
                (ConservativeTokens.tokens(tail)-ConservativeTokens.tokens(tail.removeSuffix(historyTimes)))
            val available=inputLimit-(minimum-historyCost)
            if(available<0)return PromptBuild.Blocked(ChatFailure.CONTEXT_CAPACITY)
            if(AdaptiveContext.needsSummary(historyCost,available,rounds))return PromptBuild.Blocked(ChatFailure.CONTEXT_NEEDS_MEMORY)
        }
        val selected=if(adaptive)AdaptiveContext.references(input,references,inputLimit-compose(emptyList(),emptyList()).sumOf{ConservativeTokens.tokens(it.content)}-32) else mandatory+optional+related
        val base=compose(selected,emptyList()).sumOf{ConservativeTokens.tokens(it.content)}
        if(base>inputLimit)return PromptBuild.Blocked(overflow)
        val room=inputLimit-base
        var candidateBudget=minOf(500,room)
        val candidates=pending.take(3).filter{val n=ConservativeTokens.tokens(it)+16;if(n<=candidateBudget){candidateBudget-=n;true}else false}
        // "Here is what you still remember" must not sit above an empty reference block.
        // The swap only touches the program-owned span, and nothing after it shifts into another range.
        val remembered=selected.any{it.remembers}
        val replacement=expand(REMEMBERING_EMPTY,friend,persona)
        val wire=compose(selected,candidates).mapIndexed { i,m ->
            if(i!=0) m else {
                val section=if(remembered) remembering else replacement
                val start=memoryRange.first+section.length-historyHint.length
                m.copy(content=if(remembered)m.content else m.content.replaceRange(memoryRange,replacement),
                    toolInstructions=listOf(ToolInstruction(start,historyHint,setOf("agent_history_read","agent_history_search"))))
            }
        }
        val estimate=wire.sumOf{ConservativeTokens.tokens(it.content)}
        if(estimate>inputLimit)return PromptBuild.Blocked(overflow)
        return PromptBuild.Ready(TextChatRequest(wire,outputLimit),estimate,boundary,selected,summaryCanShrink)
    }
}
