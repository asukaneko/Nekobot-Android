package app.lianxu.independent

import com.google.gson.Gson

/** Local planning only. The ratios and relevance floor are conservative starting values, not learned optima. */
internal object AdaptiveContext {
    const val TRIGGER = 0.80
    const val TARGET = 0.60
    const val MIN_RELEVANCE = 0.12
    private val gson = Gson()

    fun inputLimit(capacity:Int,output:Int,tools:Int=0):Int =
        (capacity.toLong()-output-tools-maxOf(256,capacity/20)).coerceIn(0,Int.MAX_VALUE.toLong()).toInt()

    fun needsSummary(historyTokens:Int,available:Int,rounds:Int):Boolean =
        rounds>1 && historyTokens>=(available.coerceAtLeast(0)*TRIGGER).toInt()

    /** A contiguous prefix of whole rounds; the newest complete exchange stays available. */
    fun summaryCount(costs:List<Int>,available:Int):Int {
        if(costs.size<2)return 0
        var remaining=costs.sumOf{it.toLong()}
        val target=(available.coerceAtLeast(0)*TARGET).toLong()
        var count=0
        while(count<costs.lastIndex&&remaining>target){remaining-=costs[count];count++}
        return count
    }

    fun cost(ref:PromptReference)=ConservativeTokens.tokens(ref.label+":\n"+ref.text)+8

    /** Exact duplicate content+sources only: two similar events must never collapse by wording alone. */
    fun references(query:String,values:List<PromptReference>,budget:Int):List<PromptReference> {
        val unique=values.sortedByDescending{it.mandatory}.distinctBy {
            if(it.memoryId.isNotBlank())"id:${it.memoryId}" else "${it.sources.sortedBy{r->r.turnId}}:${it.label}:${it.text}"
        }
        val mandatory=unique.filter{it.mandatory}
        val seen=mutableSetOf<Pair<Set<SourceRef>,String>>()
        mandatory.filter{it.sources.isNotEmpty()}.forEach{seen+=it.sources to it.text.trim()}
        var room=budget-mandatory.sumOf(::cost)
        val ranked=unique.filterNot{it.mandatory}.map{it to maxOf(it.priority,MemoryRecall.relevance(query,it.text))}
            .filter{it.second>=MIN_RELEVANCE}.sortedWith(compareByDescending<Pair<PromptReference,Double>>{it.second}.thenBy{it.first.memoryId.ifBlank{it.first.label}})
        return mandatory+ranked.mapNotNull{(ref,_)->
            val key=ref.sources to ref.text.trim()
            val size=cost(ref)
            if(size>room||ref.sources.isNotEmpty()&&key in seen)null else {room-=size;if(ref.sources.isNotEmpty())seen+=key;ref}
        }
    }

    /** Derived view only. Full context continues to validate sources, learning and actual outcomes. */
    fun roleView(full:RoleContext,query:String,budget:Int,semantic:Map<SourceRef,Double> = emptyMap()):RoleContext {
        var view=full.copy(knownSources=mapOf(full.current.turnId to full.current),entries=emptyList(),actions=emptyList(),learning=emptyList(),facts=emptyList())
        data class Unit(val score:Double,val key:String,val add:(RoleContext)->RoleContext)
        val units=mutableListOf<Unit>()
        fun score(source:SourceRef,text:String)=maxOf(semantic[source]?:0.0,MemoryRecall.relevance(query,text))
        full.learning.forEach{item->units+=Unit(maxOf(if(item.state in setOf("active","paused"))0.6 else 0.2,score(item.source,item.scope+" "+item.content)),"learning:${item.id}"){it.copy(learning=it.learning+item)}}
        full.entries.forEach{entry->
            val latest=entry==full.entries.lastOrNull()
            units+=Unit(maxOf(if(latest)1.1 else 0.0,score(entry.source,entry.flow.appraisal?.event.orEmpty()+" "+entry.flow.appraisal?.meaning)),"entry:${entry.source.turnId}"){it.copy(entries=it.entries+entry)}
        }
        full.actions.forEach{action->units+=Unit(maxOf(if(action==full.actions.lastOrNull())1.2 else 0.0,score(action.source,action.reply)),"action:${action.replyId}"){it.copy(actions=it.actions+action)}}
        full.facts.forEach{fact->units+=Unit(score(fact.source,fact.text),"fact:${fact.source.turnId}"){it.copy(facts=it.facts+fact)}}
        for(unit in units.filter{it.score>=MIN_RELEVANCE}.sortedWith(compareByDescending<Unit>{it.score}.thenBy{it.key})){
            var next=unit.add(view)
            val refs=(next.entries.map{it.source}+next.actions.map{it.source}+next.facts.map{it.source}+next.learning.map{it.source}+full.current).toSet()
            next=next.copy(knownSources=full.knownSources.filterValues{it in refs})
            if(ConservativeTokens.tokens(gson.toJson(next))<=budget)view=next
        }
        return view.copy(entries=view.entries.sortedBy{it.source.turnId},actions=view.actions.sortedBy{it.replyId})
    }
}
