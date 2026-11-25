package app.lianxu.independent

import java.util.Locale
import kotlin.math.sqrt

/** Independent local implementation. Weights are engineering defaults, not trained optima. */
internal object MemoryRecall {
    const val SUMMARY_CONTEXT_LIMIT = 32
    const val RELATION_CANDIDATE_LIMIT = 12
    const val ENTRY_LIMIT = 8
    const val LINKS_PER_OPERATION = 3
    const val LINKS_PER_BATCH = 12
    const val STRONG_DIRECT = 0.55
    const val MIN_RELIABILITY = 0.8
    const val MAX_ASSOCIATION_BONUS = 0.20
    const val MAX_HOPS = 2
    const val SECOND_HOP_DECAY = 0.5
    const val MIN_SECOND_HOP_RELEVANCE = 0.10
    private const val ENTRY_WEIGHT = 0.10
    private const val RELIABILITY_WEIGHT = 0.10
    private const val KEYWORD_MAX = 1.0
    private const val DATE_MATCH_BONUS = 1.0
    private val reliabilities=mapOf("explicit_cause" to 0.9,"explicit_reference" to 0.85,"same_event" to 0.8)
    private val maxReliability=reliabilities.values.max()
    val MAX_FIRST_HOP_BONUS=minOf(MAX_ASSOCIATION_BONUS,ENTRY_WEIGHT+RELIABILITY_WEIGHT*maxReliability)
    val MAX_SECOND_HOP_BONUS=SECOND_HOP_DECAY*minOf(MAX_ASSOCIATION_BONUS,ENTRY_WEIGHT+RELIABILITY_WEIGHT*maxReliability*maxReliability)
    val MAX_RAW_SCORE=KEYWORD_MAX+DATE_MATCH_BONUS+maxOf(MAX_FIRST_HOP_BONUS,MAX_SECOND_HOP_BONUS)

    /** Fixed linear scale, not a probability or per-query min/max. Never used for sorting. */
    fun points(raw:Double):Double {
        require(raw.isFinite()&&raw>=0.0&&raw<=MAX_RAW_SCORE)
        return raw/MAX_RAW_SCORE*100.0
    }
    val strongDirectPoints get()=points(STRONG_DIRECT)
    val secondHopRelevancePoints get()=points(MIN_SECOND_HOP_RELEVANCE)
    data class ScorePoints(val total:Double,val direct:Double,val association:Double)

    data class Edge(val friendId:String,val sourceId:String,val sourceVersion:Int,val targetId:String,val targetVersion:Int,
        val kind:String,val reason:String,val quote:String,val targetQuote:String,val batchId:String) {
        val reliability:Double get()=reliabilities[kind]?:0.0
    }
    data class Hit(val memory:StoredMemory,val score:Double,val direct:Double,val via:String?=null,val reason:String="",val path:List<String> = emptyList()) {
        val points get()=ScorePoints(MemoryRecall.points(score),MemoryRecall.points(direct),MemoryRecall.points(score-direct))
    }
    /** Public-facing diagnostic values use the same scale for total, direct and contribution. */
    fun diagnostics(hits:List<Hit>)=hits.map{h->val p=h.points;mapOf("memoryId" to h.memory.id,"totalPoints" to p.total,"directPoints" to p.direct,"associationPoints" to p.association,"path" to h.path)}
    private val date=Regex("(?<!\\d)(\\d{4})[-年/](\\d{1,2})[-月/](\\d{1,2})日?")
    private fun dates(text:String)=date.findAll(text).map{m->m.groupValues.drop(1).map(String::toInt).joinToString("-")}.toSet()
    private fun terms(text:String):Map<String,Int> = Regex("[a-z0-9_]+|[\\p{IsHan}]+").findAll(text.lowercase(Locale.ROOT)).flatMap { m ->
        if(m.value.first().code>127) m.value.windowed(2).asSequence() else sequenceOf(m.value)
    }.groupingBy{it}.eachCount()
    fun relevance(query:String,text:String):Double {
        val q=terms(query);val t=terms(text)
        if(q.isEmpty()||t.isEmpty())return 0.0
        val dot=q.entries.sumOf{(term,n)->n.toDouble()*(t[term]?:0)}
        val cosine=if(dot==0.0)0.0 else dot/sqrt(q.values.sumOf{it.toDouble()*it}*t.values.sumOf{it.toDouble()*it})
        return cosine+(if(dates(query).intersect(dates(text)).isEmpty())0.0 else DATE_MATCH_BONUS)
    }
    fun direct(query:String,m:StoredMemory)=relevance(query,m.text+" "+m.tags.joinToString(" ")+" "+m.eventDate.orEmpty())
    fun relationCandidates(query:String,existing:List<StoredMemory>)=existing.map{it to direct(query,it)}
        .filter{it.second>0}.sortedWith(compareByDescending<Pair<StoredMemory,Double>>{it.second}.thenBy{it.first.id})
        .take(RELATION_CANDIDATE_LIMIT).map{it.first}
    fun summaryContext(query:String,existing:List<StoredMemory>)=(relationCandidates(query,existing)+existing.sortedWith(
        compareByDescending<StoredMemory>{it.manual||it.locked}.thenByDescending{it.core}
            .thenByDescending{direct(query,it)}.thenBy{it.id})).distinctBy{it.id}.take(SUMMARY_CONTEXT_LIMIT)

    /** Callers supply only verified current rows. Score every direct candidate before selecting. */
    fun select(friendId:String,query:String,memories:List<StoredMemory>,edges:List<Edge>,limit:Int,vectors:Map<String,Double> = emptyMap()):List<Hit> {
        require(limit>0)
        val valid=memories.filter{it.friendId==friendId&&it.valid&&it.life==MemoryLife.ACTIVE&&(it.expiresAt==null||it.expiresAt>System.currentTimeMillis())}.distinctBy{it.id}.associateBy{it.id}
        fun hybrid(m:StoredMemory):Double {
            val lexical=direct(query,m)
            val dateBonus=if(dates(query).intersect(dates(m.text+" "+m.tags.joinToString(" ")+" "+m.eventDate.orEmpty())).isEmpty())0.0 else DATE_MATCH_BONUS
            val v=vectors["${m.id}@${m.version}"]?.takeIf{it.isFinite()&&it in 0.0..1.0}?:0.0
            return maxOf((lexical-dateBonus).coerceAtLeast(0.0),v)+dateBonus
        }
        val ranked=valid.values.map{m->val s=hybrid(m);Hit(m,s,s)}
            .filter{it.direct>0}.sortedWith(compareByDescending<Hit>{it.direct}.thenBy{it.memory.id})
        if(ranked.isEmpty())return emptyList()
        val strong=ranked.filter{!it.memory.core&&it.direct>=STRONG_DIRECT}.take(limit)
        val hits=ranked.filter{!it.memory.core}.associateBy{it.memory.id}.toMutableMap()
        // Validate every edge endpoint before building adjacency; the repository also checks source evidence.
        val scores=valid.values.associate{it.id to hybrid(it)}
        val adjacent=mutableMapOf<String,MutableList<Pair<StoredMemory,Edge>>>()
        for(edge in edges){
            if(edge.friendId!=friendId||edge.sourceId==edge.targetId||edge.reliability<MIN_RELIABILITY)continue
            val source=valid[edge.sourceId]?:continue;val target=valid[edge.targetId]?:continue
            if(source.version!=edge.sourceVersion||target.version!=edge.targetVersion)continue
            adjacent.getOrPut(source.id){mutableListOf()}.add(target to edge)
            adjacent.getOrPut(target.id){mutableListOf()}.add(source to edge)
        }
        data class Walk(val node:StoredMemory,val ids:List<String>,val reasons:List<String>,val reliability:Double)
        // Roots stay frozen. Enumerate legal paths separately: global visited would discard better routes.
        for(entry in ranked.take(ENTRY_LIMIT)){
            var frontier=listOf(Walk(entry.memory,listOf(entry.memory.id),emptyList(),1.0))
            for(depth in 1..MAX_HOPS){
                val next=mutableListOf<Walk>()
                for(walk in frontier)for((neighbour,edge) in adjacent[walk.node.id].orEmpty()){
                    if(neighbour.id in walk.ids)continue
                    val own=scores.getValue(neighbour.id)
                    val reasonMatch=relevance(query,edge.reason).coerceAtMost(1.0)
                    if(own<=0&&reasonMatch<=0)continue
                    if(depth==2&&maxOf(own,reasonMatch)<MIN_SECOND_HOP_RELEVANCE)continue
                    val reliability=walk.reliability*edge.reliability
                    val decay=if(depth==1)1.0 else SECOND_HOP_DECAY
                    val bonus=decay*(ENTRY_WEIGHT*entry.direct.coerceAtMost(1.0)+RELIABILITY_WEIGHT*reliability*reasonMatch).coerceAtMost(MAX_ASSOCIATION_BONUS)
                    val path=walk.ids+neighbour.id;val reasons=walk.reasons+edge.reason
                    val candidate=Hit(neighbour,own+bonus,own,entry.memory.id,reasons.joinToString("；"),path)
                    val previous=hits[neighbour.id]
                    // Never accumulate over paths; prefer a shorter path when scores are equal.
                    if(!neighbour.core&&(previous==null||candidate.score>previous.score||
                            candidate.score==previous.score&&candidate.path.size<previous.path.size))hits[neighbour.id]=candidate
                    if(depth<MAX_HOPS)next+=Walk(neighbour,path,reasons,reliability)
                }
                frontier=next
                if(frontier.isEmpty())break
            }
        }
        val protected=strong.map{it.memory.id}.toSet()
        val rest=hits.values.filter{it.memory.id !in protected}.sortedWith(compareByDescending<Hit>{it.score}.thenByDescending{it.direct}.thenBy{it.memory.id})
        return (strong+rest).distinctBy{it.memory.id}.take(limit).also{selected->Diagnostics.event("memory.recall.ranked",mapOf("character" to friendId,"query" to Diagnostics.raw(query),"candidate_count" to valid.size,"edge_count" to edges.size,"limit" to limit,"selected" to diagnostics(selected)))}
    }
}

