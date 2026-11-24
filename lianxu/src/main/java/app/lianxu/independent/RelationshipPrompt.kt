package app.lianxu.independent

/** Model-readable projection only. UI summary and the authoritative calculation stay unchanged. */
internal object RelationshipPrompt {
    const val UNVERIFIED_STATUS="旧分值无法核对\n回顾暂停 原话保留"
    const val UNVERIFIED="部分旧感受已经合并，无法完整核对来源变更的影响。这组旧分值只作历史记录，不作为当前判断依据；请根据仍有效的原话交流，不猜测旧分值已经纠正。"
    const val LABEL="最近完成的情绪与关系状态"
    private val names=linkedMapOf("happy" to "开心","hurt" to "委屈","unhappy" to "生气","missing" to "想念","low" to "低落",
        "trust" to "信任","closeness" to "亲近","tension" to "心结","disappointment" to "失望")
    private fun number(value:Double?)=value?.takeIf{it.isFinite()&&it in 0.0..1.0}?.toString()?:"未知"
    fun render(state:RelationState?,now:Long,quote:(SourceRef)->String?):String=buildString{
        append("以下是最近完成记录所形成的状态，可能还没计入最新几轮聊天，不保证实时。各项独立取0–1，越高表示该项越强，不是概率，总和无需为1；未知不等于0。\n")
        append("情绪：开心=${number(state?.happy)}；委屈=${number(state?.hurt)}；生气=${number(state?.unhappy)}；想念=${number(state?.missing)}；低落=${number(state?.low)}。\n")
        append("关系：信任=${number(state?.trust)}；亲近=${number(state?.closeness)}；心结=${number(state?.tension)}；失望=${number(state?.disappointment)}。失望是既有关系印象，与当下情绪分别参考。\n")
        append("把这些当成你心里隐约留着的印象，结合当前聊天、相关经历和下面的依据自主理解，自然融进你原本的性格里；数值不是规定口气或行为的指令，不必逐项表达，更别拿分数当剧本演或向对方报分。过去的感受不等于此刻感受，未列出原因时不要编造原因。\n")
        val refs=mutableListOf<String>()
        state?.causes.orEmpty().filterKeys{it in names}.entries.groupBy{it.value.source}.entries.forEachIndexed{i,(source,entries)->
            val original=quote(source)?:return@forEachIndexed
            val label="状态依据${i+1}"
            append("$label：").append(entries.joinToString("；"){(key,cause)->"${names.getValue(key)}，${ExperiencePresentation.at(cause.at,now)}记录的变化：${cause.direction}"})
            append("；原话：「${original.take(70)}${if(original.length>70)"…"else""}」。这是已记录的变化线索，不是全部原因。\n")
            refs+="$label：${ExperiencePresentation.reference(source)}"
        }
        state?.excuses.orEmpty().filterValues{it>=2}.forEach{(reason,count)->append("已有解释背景：〈$reason〉累计记录$count 次；如何理解仍结合具体原话。\n")}
        if(refs.isNotEmpty())append("${ExperiencePresentation.REFS}\n").append(refs.joinToString("\n"))
    }.trimEnd()
}
