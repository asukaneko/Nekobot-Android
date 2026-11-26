package app.lianxu.independent

/** Model-readable projection only. UI summary and the authoritative calculation stay unchanged. */
internal object RelationshipPrompt {
    const val UNVERIFIED_STATUS="旧分值无法核对\n回顾暂停 原话保留"
    const val UNVERIFIED="部分旧感受已经合并，无法完整核对来源变更的影响。这组旧分值只作历史记录，不作为当前判断依据；请根据仍有效的原话交流，不猜测旧分值已经纠正。"
    const val LABEL="最近完成的情绪与关系状态"
    private val names=linkedMapOf("happy" to "开心","hurt" to "委屈","unhappy" to "生气","missing" to "想念","low" to "低落",
        "trust" to "信任","closeness" to "亲近","tension" to "心结","disappointment" to "失望")
    fun render(state:RelationState?,now:Long,attributionIncomplete:Boolean=false,innerNote:()->String?={null},quote:(SourceRef)->String?):String {
        if(attributionIncomplete)return UNVERIFIED
        return verified(state,now,innerNote(),quote)
    }
    private fun verified(state:RelationState?,now:Long,innerNote:String?,quote:(SourceRef)->String?):String=buildString{
        append("这些印象可能还没计入最新几轮聊天，不保证实时。结合原话自主理解，不必逐项表达；过去的感受不等于此刻感受。\n")
        val values=state?.run{listOf(happy,hurt,unhappy,missing,low,trust,closeness,tension,disappointment)}
        if(state==null || values.orEmpty().any{!it.isFinite()||it !in 0.0..1.0}){
            append("此刻的心情和关系底色还说不准。\n")
        }else{
            append("这会儿${RelationshipSummary.mood(state).sentence}。心里${RelationshipSummary.trust(state.trust)}对方，相处起来${RelationshipSummary.closeness(state.closeness)}。")
            if(state.tension>=.1)append(if(state.tension>.3)"有些事心里还有疙瘩。" else "有件事还稍微在意。")
            if(state.disappointment>=.1)append(if(state.disappointment>.45)"先前的落空，心里还没完全放下。" else "对先前的落空还有点在意。")
            append('\n')
        }
        innerNote?.let{append("最近心里闪过一句：「$it」。这是会慢慢淡下去的心事，不必照念。\n")}
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
