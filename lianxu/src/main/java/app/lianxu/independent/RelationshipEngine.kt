package app.lianxu.independent

import kotlin.math.exp
import kotlin.math.pow

internal data class RelationChange(val at:Long,val old:RelationParameters,val new:RelationParameters,val label:String,val afterMessage:Long=0)
internal data class RelationCorrection(val source:SourceRef,val at:Long,val voided:Boolean)
internal data class RelationExplanation(val source:SourceRef,val target:SourceRef,val category:String,val ratio:Double,val at:Long) {
    val message:String get()="你解释过了，她好受多了"
}
internal data class RelationImpact(val loss:Double=0.0,val tension:Double=0.0,val disappointment:Double=0.0,
    val happy:Double=0.0,val unhappy:Double=0.0,val hurt:Double=0.0,val low:Double=0.0,
    val at:Long=0,val kind:String?=null,val big:Boolean=false,val grudgeSlow:Double=1.0,val grudgeFloor:Double=0.0,
    // 当前感觉：这一笔当时真正加进去的信任/亲近（已饱和），随时间按小事/大事各自的半衰期变淡。
    val trust:Double=0.0,val closeness:Double=0.0,val lasting:Boolean=false)
internal data class RelationPending(val loss:Double,val big:Boolean)
/** 一笔经历对另一笔旧事做的修改（解释、道歉、弥补）。撤回这笔时按剩余量加回去。 */
internal data class RelationAdjust(val target:SourceRef,val at:Long,val trust:Double=0.0,val disappointment:Double=0.0,
    val tension:Double=0.0,val unhappy:Double=0.0,val hurt:Double=0.0,val low:Double=0.0,val grudgeFloor:Double=0.0,
    val pending:RelationPending?=null,val excuse:String?=null,val explained:Boolean=false,val apology:Boolean=false,val pair:RelationRepairPair?=null)
/** 撤回一笔需要的附带记录；加减量本身就在 impacts 里。 */
internal data class RelationUndo(val at:Long,val day:Long,val positive:Boolean=false,val care:Long?=null,val bigKind:String?=null,
    val fulfilled:SourceRef?=null,val adjusts:List<RelationAdjust> = emptyList())
/** 点滴：哪一笔让她的感觉变了。只存反应类别，句子由页面用固定句式拼。 */
internal data class RelationMoment(val source:SourceRef,val at:Long,val reaction:String,val positive:Boolean)

/** 存下来的「当前感觉」：每笔只在上面小幅调整；删除或改判时只撤回那一笔。 */
internal class RelationCursor(val start:RelationStart,var parameters:RelationParameters,private val compact:Boolean=true) {
    // Current-feeling storage keeps attribution beside the bounded projection snapshot.
    // These maps do not add to a bucket's totals; they only describe its existing shares.
    internal var trackMerged=false
    internal var unattributedMerged=false
    internal val mergedImpacts=linkedMapOf<SourceRef,RelationImpact>()
    internal val fulfillmentSources=linkedMapOf<SourceRef,SourceRef>()
    internal val archivedUndo=linkedMapOf<SourceRef,RelationUndo>()
    companion object { const val EPSILON=1e-4;const val MERGE_EPSILON=5e-3;/** 每项最多记住的造成变化的消息条数。 */ const val CAUSE_HISTORY=32 }
    val repairPairs=linkedSetOf<RelationRepairPair>()
    val pending=linkedMapOf<SourceRef,RelationPending>()
    val bigRecent=linkedMapOf<String,MutableList<Long>>()
    val fulfilledAt=linkedMapOf<SourceRef,Long>()
    val undo=linkedMapOf<SourceRef,RelationUndo>()
    val moments=mutableListOf<RelationMoment>()
    private fun faded(v:RelationImpact,eps:Double=EPSILON)=listOf(kotlin.math.abs(v.trust),kotlin.math.abs(v.closeness),v.tension,v.disappointment,v.happy,v.unhappy,v.hurt,v.low).all{it<eps}
    private fun held(v:RelationImpact)=(at?:v.at)<v.at+RelationDecay.HOLD_H*RelationshipProjector.HOUR
    private fun forgotten(v:RelationImpact)=!v.big&&!held(v)&&faded(v)
    fun compact(){
        val now=at?:return
        impacts.forEach{(ref,v)->if(forgotten(v)){pending.remove(ref);apologized.remove(ref)}}
        // 小事等解释最多挂 30 天（或差不多淡没了）；大事有心结，一直挂到解释或弥补。
        impacts.forEach{(ref,v)->if(!v.big&&ref in pending&&!held(v)&&(faded(v,MERGE_EPSILON)||now-v.at>RelationDecay.MERGE_DAYS*RelationshipProjector.DAY))pending.remove(ref)}
        if(compact){
            val individual=impacts.keys.filter{it.turnId>0}.sortedWith(compareBy<SourceRef>{impacts.getValue(it).at}.thenBy{it.turnId})
            val overflow=individual.take((individual.size-RelationDecay.MAX_ENTRIES).coerceAtLeast(0)).toSet()
            for((ref,v) in impacts.toMap()){
                // 老的小事并成一笔，只剩很少时大事也并；心结和还在等解释的不并，才能按笔撤回或弥补。
                // 例外：一个月前只剩一点点的心结、以及超过上限的最老几笔也并进去（总量不变，只是不能再单独弥补）。
                if(ref.turnId<0||held(v))continue
                val age=now-v.at
                val forced=ref in overflow||age>RelationDecay.MERGE_DAYS*RelationshipProjector.DAY&&v.tension<RelationDecay.KNOT_MERGE
                if(!forced&&(v.tension>=MERGE_EPSILON||ref in pending))continue
                if(!forced&&!(faded(v,MERGE_EPSILON)||!v.lasting&&age>RelationDecay.MERGE_DAYS*RelationshipProjector.DAY))continue
                val key=SourceRef(ref.characterId,if(v.lasting)-2L else -1L,1)
                if(trackMerged)mergedImpacts[ref]=v else unattributedMerged=true
                val bucket=impacts[key]?:RelationImpact(lasting=v.lasting)
                impacts[key]=bucket.copy(tension=bucket.tension+v.tension,disappointment=bucket.disappointment+v.disappointment,happy=bucket.happy+v.happy,unhappy=bucket.unhappy+v.unhappy,
                    hurt=bucket.hurt+v.hurt,low=bucket.low+v.low,trust=bucket.trust+v.trust,closeness=bucket.closeness+v.closeness)
                impacts.remove(ref);apologized.remove(ref);pending.remove(ref)
            }
        }
        // 只改了自己这一笔的记录两天后就不用留（加减量在 impacts 里）；改过旧事或兑现约定的留 90 天。
        undo.entries.removeAll{(ref,u)->
            val expired=now-u.at>(if(u.adjusts.isEmpty()&&u.fulfilled==null)2.0 else RelationDecay.UNDO_DAYS)*RelationshipProjector.DAY
            if(expired&&trackMerged&&u.adjusts.isNotEmpty())archivedUndo[ref]=u
            expired
        }
        bigRecent.values.forEach{times->times.removeAll{now-it>RelationDecay.REPEAT_WINDOW_DAYS*RelationshipProjector.DAY};while(times.size>2)times.removeAt(0)}
        explanations.entries.removeAll{now-it.value.at>30*RelationshipProjector.DAY}
        fulfilledAt.filterValues{now-it>30*RelationshipProjector.DAY}.keys.toList().forEach{promises.remove(it);fulfilledAt.remove(it)}
        cares.removeAll{now-it>=24*RelationshipProjector.HOUR}
        positives.keys.maxOrNull()?.let{last->positives.keys.removeAll{it<last-2}}
    }
    var at:Long?=null;var contact:Long?=null;var nextCooling:Long?=null
    val impacts=linkedMapOf<SourceRef,RelationImpact>()
    // 信任和亲近 = 起始值 + 各笔剩余的加减量。
    var baseTrust=start.trust;var baseCloseness=start.closeness;var peak=start.closeness;var missing=0.0
    var trust:Double
        get()=(baseTrust+impacts.values.sumOf{it.trust}).coerceIn(0.0,1.0)
        set(value){baseTrust=value-impacts.values.sumOf{it.trust}}
    var closeness:Double
        get()=(baseCloseness+impacts.values.sumOf{it.closeness}).coerceIn(0.0,1.0)
        set(value){baseCloseness=value-impacts.values.sumOf{it.closeness}}
    val causes=linkedMapOf<String,RelationCause>()
    /**
     * 每一项按先后留下造成变化的几条消息（最近的在最后），[causes] 总是其中最后一条。
     * 撤回某条消息时只删掉它自己，这一项的「因为哪句话」退回上一条仍然有效的；一条都不剩才留空。
     */
    val causeHistory=linkedMapOf<String,MutableList<RelationCause>>()
    val promises=linkedMapOf<SourceRef,RelationPromise>()
    val explanations=linkedMapOf<SourceRef,RelationExplanation>()
    val excuses=linkedMapOf<String,Int>()
    val positives=mutableMapOf<Long,Int>();val apologized=mutableSetOf<SourceRef>();val negatives=mutableSetOf<SourceRef>();var fulfillments=0
    var applied=0
    val cares=mutableListOf<Long>()
    fun copy():RelationCursor=RelationCursor(start,parameters,compact).also{
        it.trackMerged=trackMerged;it.unattributedMerged=unattributedMerged
        it.mergedImpacts.putAll(mergedImpacts);it.fulfillmentSources.putAll(fulfillmentSources)
        it.archivedUndo.putAll(archivedUndo)
        it.repairPairs.addAll(repairPairs);it.pending.putAll(pending);bigRecent.forEach{(k,v)->it.bigRecent[k]=v.toMutableList()};it.fulfilledAt.putAll(fulfilledAt)
        it.at=at;it.contact=contact;it.nextCooling=nextCooling;it.baseTrust=baseTrust;it.baseCloseness=baseCloseness;it.peak=peak;it.missing=missing
        it.cares.addAll(cares);it.undo.putAll(undo);it.moments.addAll(moments)
        it.impacts.putAll(impacts);it.causes.putAll(causes);causeHistory.forEach{(k,v)->it.causeHistory[k]=v.toMutableList()};it.promises.putAll(promises);it.explanations.putAll(explanations)
        it.excuses.putAll(excuses);it.positives.putAll(positives);it.apologized.addAll(apologized);it.negatives.addAll(negatives);it.fulfillments=fulfillments;it.applied=applied
    }
    private fun impressionFactor(lasting:Boolean,hours:Double)=2.0.pow(-hours/(24*(if(lasting)RelationDecay.LASTING_DAYS else RelationDecay.SMALL_DAYS)))
    /** 从 from 淡到 from+hours：信任/亲近/失望按天，心情按小时，心结不自己变淡。 */
    private fun fade(v:RelationImpact,from:Long,hours:Double,multiplier:Double=1.0):RelationImpact {
        // 心情记下后先停 2 小时再开始平复；advance 会在这个时刻分段，晚到的一笔一次跨过去。
        val holdEnd=v.at+RelationDecay.HOLD_H*RelationshipProjector.HOUR
        val moodHours=if(from<holdEnd)((from+hours*RelationshipProjector.HOUR-holdEnd)/RelationshipProjector.HOUR).coerceAtLeast(0.0)else hours
        fun mood(n:Double,half:Double,negative:Boolean=true)=n*2.0.pow(-moodHours/half*(if(negative)multiplier else 1.0))
        val f=impressionFactor(v.lasting,hours)
        return v.copy(trust=v.trust*f,closeness=v.closeness*f,disappointment=v.disappointment*f,
            happy=mood(v.happy,RelationDecay.HAPPY_H,false),unhappy=mood(v.unhappy,RelationDecay.ANGRY_H),hurt=mood(v.hurt,RelationDecay.HURT_H),low=mood(v.low,RelationDecay.LOW_H))
    }
    fun advance(time:Long){
        val before=at
        if(before!=null&&time<before)return
        if(before!=null&&time>before){
            val boundaries=(impacts.values.map{it.at+(RelationDecay.HOLD_H*RelationshipProjector.HOUR).toLong()}+
                cares.flatMap{listOf(it,it+(RelationDecay.CARE_WINDOW_H*RelationshipProjector.HOUR).toLong())}).filter{it>before&&it<time}
            val end=boundaries.minOrNull()?:time
            val hours=(end-before).toDouble()/RelationshipProjector.HOUR
            val multiplier=RelationDecay.careMultiplier(cares.count{it<=before&&before-it<RelationDecay.CARE_WINDOW_H*RelationshipProjector.HOUR})
            val closenessBefore=closeness
            impacts.replaceAll{_,v->fade(v,before,hours,multiplier)}
            mergedImpacts.replaceAll{_,v->fade(v,before,hours,multiplier)}
            if(contact!=null)missing=1-(1-missing)*exp(-hours*RelationDecay.missingRate(closenessBefore))
            if(end<time){at=end;advance(end);advance(time);return}
        }
        at=time
    }
    fun contact(time:Long){advance(time);contact=time;missing*=RelationDecay.MISS_CHAT}
    fun reachOut(time:Long){advance(time);missing*=RelationDecay.MISS_REACH}
    private fun total(value:(RelationImpact)->Double)=impacts.values.sumOf(value).coerceIn(0.0,1.0)
    private fun cause(key:String,row:RelationInput,direction:String="↑"){
        val c=RelationCause(row.source,row.at,direction);causes[key]=c
        val history=causeHistory.getOrPut(key){mutableListOf()}
        history.removeAll{it.source==row.source};history.add(c)
        while(history.size>CAUSE_HISTORY)history.removeAt(0)
    }
    private fun remember(row:RelationInput,undoRecord:RelationUndo,reaction:String?,positive:Boolean){
        undo[row.source]=undoRecord
        moments.removeAll{it.source==row.source}
        if(reaction!=null){
            moments.add(RelationMoment(row.source,row.at,reaction,positive))
            moments.sortWith(compareByDescending<RelationMoment>{it.at}.thenByDescending{it.source.turnId})
            while(moments.size>RelationDecay.MOMENT_LIMIT)moments.removeAt(moments.lastIndex)
        }
    }
    /** 撤回一笔：删掉它剩下的加减量，并把它对旧事做过的修改按剩余量还回去。 */
    private fun replaceMerged(ref:SourceRef,next:RelationImpact?){
        val old=mergedImpacts[ref]?:return
        val key=SourceRef(ref.characterId,if(old.lasting)-2L else -1L,1)
        impacts[key]?.let{b->
            fun amount(read:(RelationImpact)->Double)=read(b)-read(old)+(next?.let(read)?:0.0)
            impacts[key]=b.copy(trust=amount{it.trust},closeness=amount{it.closeness},
                tension=amount{it.tension}.coerceAtLeast(0.0),disappointment=amount{it.disappointment}.coerceAtLeast(0.0),
                happy=amount{it.happy}.coerceAtLeast(0.0),unhappy=amount{it.unhappy}.coerceAtLeast(0.0),
                hurt=amount{it.hurt}.coerceAtLeast(0.0),low=amount{it.low}.coerceAtLeast(0.0))
        }
        if(next==null)mergedImpacts.remove(ref)else mergedImpacts[ref]=next
    }
    private fun impact(ref:SourceRef)=impacts[ref]?:mergedImpacts[ref]
    private fun recordedApologies()=(undo.values.asSequence()+archivedUndo.values.asSequence())
        .flatMap{it.adjusts.asSequence()}.filter{it.apology}.map{it.target}.toSet()
    private fun replaceImpact(ref:SourceRef,value:RelationImpact){
        if(ref in mergedImpacts)replaceMerged(ref,value)else impacts[ref]=value
    }
    fun retract(ref:SourceRef){
        val now=at
        replaceMerged(ref,null)
        impacts.remove(ref);pending.remove(ref);apologized.remove(ref);explanations.remove(ref)
        moments.removeAll{it.source==ref}
        causes.entries.removeAll{it.value.source==ref}
        causeHistory.values.forEach{list->list.removeAll{it.source==ref}}
        causeHistory.entries.removeAll{it.value.isEmpty()}
        // 这一项退回上一条造成变化的消息；没有更早的就留空。
        causeHistory.forEach{(k,list)->causes[k]=list.last()}
        promises.remove(ref);fulfilledAt.remove(ref)
        promises.replaceAll{_,p->if(p.fulfilledBy==ref)p.copy(fulfilledBy=null)else p}
        repairPairs.removeAll{it.target==ref}
        explanations.entries.removeAll{it.value.source==ref}
        val fulfilled=fulfillmentSources.remove(ref)
        fulfilled?.let{fulfilledAt.remove(it);if(fulfillments>0)fulfillments--}
        val u=undo.remove(ref)?:archivedUndo.remove(ref)?:return
        archivedUndo.remove(ref)
        u.care?.let{cares.remove(it)}
        if(u.positive)positives[u.day]?.let{n->if(n<=1)positives.remove(u.day)else positives[u.day]=n-1}
        u.bigKind?.let{bigRecent[it]?.remove(u.at)}
        u.fulfilled?.takeIf{fulfilled==null}?.let{fulfilledAt.remove(it);if(fulfillments>0)fulfillments--}
        for(a in u.adjusts){
            val hours=((now?:a.at)-a.at).coerceAtLeast(0L).toDouble()/RelationshipProjector.HOUR
            (impacts[a.target]?:mergedImpacts[a.target])?.let{t->
                val f=impressionFactor(t.lasting,hours)
                fun mood(half:Double)=2.0.pow(-hours/half)
                val updated=t.copy(trust=t.trust-a.trust*f,disappointment=(t.disappointment-a.disappointment*f).coerceIn(0.0,1.0),
                    tension=(t.tension-a.tension).coerceIn(0.0,1.0),grudgeFloor=(t.grudgeFloor-a.grudgeFloor).coerceAtLeast(0.0),
                    unhappy=(t.unhappy-a.unhappy*mood(RelationDecay.ANGRY_H)).coerceIn(0.0,1.0),hurt=(t.hurt-a.hurt*mood(RelationDecay.HURT_H)).coerceIn(0.0,1.0),
                    low=(t.low-a.low*mood(RelationDecay.LOW_H)).coerceIn(0.0,1.0))
                if(a.target in mergedImpacts)replaceMerged(a.target,updated)else impacts[a.target]=updated
                a.pending?.let{p->pending[a.target]=p}
            }
            if(a.explained)explanations.remove(a.target)
            a.excuse?.let{e->excuses[e]?.let{n->if(n<=1)excuses.remove(e)else excuses[e]=n-1}}
            if(a.apology)apologized.remove(a.target)
            a.pair?.let{repairPairs.remove(it)}
        }
    }
    fun apply(row:RelationInput){
        advance(row.at);compact();applied++
        if(row.category=="普通信息")return
        val now=maxOf(at?:row.at,row.at)
        val adjusts=mutableListOf<RelationAdjust>()
        var explained=false;var apology=false;var repaired=false
        val release=row.explains
        if(release!=null){
            val old=impact(release)
            val debt=pending.remove(release)
            if(debt!=null){
                val misunderstanding=row.formatVersion>=2&&(row.releaseKind=="真误会")
                val category=if(misunderstanding)"真误会"else RelationExcuses.category(row.excuse)
                val count=if(misunderstanding)0 else ((excuses[category]?:0)+1).also{excuses[category]=it}
                val ratio=if(misunderstanding)1.0 else RelationExcuses.ratio(count,row.at-(row.explainedAt?:row.at))
                explanations[release]=RelationExplanation(row.source,release,category,ratio,row.at)
                explained=true
                if(old!=null){
                    val refund=(-old.trust).coerceAtLeast(0.0)*ratio
                    val updated=old.copy(trust=old.trust+refund,tension=old.tension*(1-ratio),disappointment=old.disappointment*(1-ratio),hurt=old.hurt*(1-ratio),unhappy=old.unhappy*(1-ratio),low=if(misunderstanding)0.0 else old.low,grudgeFloor=if(misunderstanding)0.0 else old.grudgeFloor)
                    replaceImpact(release,updated)
                    adjusts+=RelationAdjust(release,now,refund,updated.disappointment-old.disappointment,updated.tension-old.tension,updated.unhappy-old.unhappy,updated.hurt-old.hurt,updated.low-old.low,updated.grudgeFloor-old.grudgeFloor,
                        debt,category.takeIf{!misunderstanding},true)
                    if(refund>0)cause("trust",row,"↑")
                    for((key,value) in listOf("tension" to old.tension,"disappointment" to old.disappointment,"hurt" to old.hurt,"unhappy" to old.unhappy))if(value*ratio>=EPSILON)cause(key,row,"↓")
                } else adjusts+=RelationAdjust(release,now,pending=debt,excuse=category.takeIf{!misunderstanding},explained=true)
            }
            if((debt!=null||row.explanationPolicy==0||row.correction=="不介意了")&&!(row.repairCompleted&&row.repairs!=null||(row.c?:0.0)>0&&row.fulfills!=null)){
                remember(row,RelationUndo(now,row.day,adjusts=adjusts),if(explained)"explained" else null,true);return
            }
        }
        if(row.correction=="道歉和好"&&row.previous!=null&&row.previous !in recordedApologies()&&impact(row.previous)?.let{!forgotten(it)}==true&&apologized.add(row.previous)){
            impact(row.previous)?.let{old->
                val factor=1-RelationDecay.APOLOGY_SURFACE
                val updated=old.copy(disappointment=old.disappointment*factor,hurt=old.hurt*factor,unhappy=old.unhappy*factor,low=old.low*factor)
                replaceImpact(row.previous,updated)
                adjusts+=RelationAdjust(row.previous,now,disappointment=updated.disappointment-old.disappointment,unhappy=updated.unhappy-old.unhappy,hurt=updated.hurt-old.hurt,low=updated.low-old.low,apology=true)
                apology=true
                for((key,value) in listOf("disappointment" to old.disappointment,"hurt" to old.hurt,"unhappy" to old.unhappy,"low" to old.low))if(value*RelationDecay.APOLOGY_SURFACE>=EPSILON)cause(key,row,"↓")
            }
        }
        val expectation=RelationDecay.expectation(closeness)
        val care=if((row.p?:0.0)>0)row.at.also{cares.add(it)}else null
        val count=positives[row.day]?:0;val p=if(count<2)row.p?:0.0 else 0.0
        if(p>0)positives[row.day]=count+1
        val promise=promises[row.fulfills]
        var fulfilled:SourceRef?=null
        val c=if((row.c?:0.0)>0&&promise!=null&&promise.fulfilledBy==null){
            promises[promise.source]=promise.copy(fulfilledBy=row.source);fulfilledAt[promise.source]=row.at;fulfillments++
            if(fulfillments%3==0)excuses.replaceAll{_,n->(n-1).coerceAtLeast(0)}
            fulfilled=promise.source
            fulfillmentSources[row.source]=promise.source
            row.c!!
        }else 0.0
        val basis=row.repairBasis?:row.source
        if(row.repairCompleted&&(basis==row.source||basis.characterId==row.source.characterId&&basis.turnId in 1 until row.source.turnId)&&(row.fulfills==null||c>0))row.repairs?.let{target->
            val pair=RelationRepairPair(basis,target)
            impact(target)?.takeIf{target.characterId==row.source.characterId&&target.turnId in 1 until row.source.turnId&&it.tension>=EPSILON&&repairPairs.add(pair)}?.let{impact->
                val updated=impact.copy(tension=impact.tension*RelationDecay.FULFILL_GRUDGE,grudgeFloor=impact.grudgeFloor*RelationDecay.FULFILL_GRUDGE)
                replaceImpact(target,updated)
                adjusts+=RelationAdjust(target,now,tension=updated.tension-impact.tension,grudgeFloor=updated.grudgeFloor-impact.grudgeFloor,pair=pair)
                repaired=true
                cause("tension",row,"↓")
            }}
        if(row.promise)promises[row.source]=RelationPromise(row.source,row.at)
        val v=row.v?:0.0;val b=row.b?:0.0;val ignored=row.ignored?:0.0
        val negative=v>0||b>0||(row.disappointment?:0.0)>0||ignored>0
        val low=if(negative&&total{it.tension}>RelationDecay.LOW_TRIGGER)RelationDecay.LOW_AMOUNT*expectation else 0.0
        val trustBefore=trust;val loss=parameters.trustLoss*(v+b)*trustBefore
        val trustDelta=(trustBefore+parameters.trustGain*(p+c)*(1-trustBefore)-loss).coerceIn(0.0,1.0)-trustBefore
        if(trustDelta!=0.0)cause("trust",row,if(trustDelta>0)"↑"else"↓")
        val closenessBefore=closeness
        val gain=(parameters.closenessGain*(p+.5*c)*(1-closenessBefore)).coerceIn(0.0,1.0-closenessBefore)
        if(gain>0)cause("closeness",row)
        // A V with a supplied expectation uses that quantity once; otherwise its strength supplies disappointment.
        val gap=mapOf("小" to .2,"中" to .5,"大" to 1.0)[row.gap]
        val importance=mapOf("低" to .3,"中" to .6,"高" to .9)[row.importance]
        val disappointed=if(ignored>0)ignored*expectation else ((if(gap!=null&&importance!=null)gap*importance*expectation else row.disappointment?: (v*expectation))*parameters.sensitivity).coerceIn(0.0,1.0)
        fun increment(key:String,x:Double,value:(RelationImpact)->Double):Double=(x*(1-total(value))).also{if(it>0)cause(key,row)}
        val h=maxOf(v,b)*expectation;val big=h>=RelationDecay.BIG_EVENT
        val kind=if(b>0)"B" else if(v>0)"V" else if(ignored>0)"ignored" else if(disappointed>0)"gap" else null
        val repeats=bigRecent[kind].orEmpty().count{row.at-it<=RelationDecay.REPEAT_WINDOW_DAYS*RelationshipProjector.DAY}
        val bigKind=if(big&&kind!=null)kind.also{bigRecent.getOrPut(it){mutableListOf()}.add(row.at)}else null
        if(kind!=null&&(negative&&loss>0||row.explanationPolicy>=1&&disappointed>0))pending[row.source]=RelationPending(loss,big)
        val tension=if(big)increment("tension",RelationDecay.GRUDGE_SHARE*h){it.tension}else 0.0
        // 小星已定：失约、越界、标成重要的落空、被感动（P或C为重）算大事，60天减半；其余小事14天减半。
        val lasting=v>0||b>0||row.importance=="高"||maxOf(row.p?:0.0,row.c?:0.0)>=.9
        impacts[row.source]=RelationImpact(loss,tension,increment("disappointment",disappointed){it.disappointment},
            increment("happy",RelationDecay.POSITIVE_WEIGHT*maxOf(p,c)*expectation){it.happy},increment("unhappy",b*expectation){it.unhappy},
            increment("hurt",(if(ignored>0)ignored else .5*v)*expectation){it.hurt},increment("low",low){it.low},
            row.at,kind,big,minOf(RelationDecay.REPEAT_SLOW_MAX,RelationDecay.REPEAT_SLOW.pow(repeats)),
            RelationDecay.grudgeFloor(kind,tension),trustDelta,gain,lasting)
        peak=maxOf(peak,closeness)
        val added=impacts.getValue(row.source)
        val happyTotal=total{it.happy};val lowTotal=total{it.low}
        val happyFactor=if(happyTotal>0)(1-RelationDecay.OPPOSE*(added.disappointment+added.hurt+added.unhappy+added.low)/happyTotal).coerceAtLeast(0.0)else 1.0
        val lowFactor=if(lowTotal>0)(1-RelationDecay.OPPOSE*added.happy/lowTotal).coerceAtLeast(0.0)else 1.0
        impacts.replaceAll{_,impact->impact.copy(happy=impact.happy*happyFactor,low=impact.low*lowFactor)}
        mergedImpacts.replaceAll{_,impact->impact.copy(happy=impact.happy*happyFactor,low=impact.low*lowFactor)}
        // 晚到的一笔（改判、重新生成）仍从当初那个时间点起算，已经过去的日子照常变淡。
        if(now>row.at)impacts[row.source]=fade(impacts.getValue(row.source),row.at,(now-row.at).toDouble()/RelationshipProjector.HOUR)
        val reaction=when{
            ignored>0->"ignored";b>0->"boundary";v>0->if(tension>0)"broken_big" else "broken"
            explained->"explained";repaired->"repaired";apology->"apology";disappointed>0->"gap"
            c>0->"fulfilled";p>=.9->"moved";row.promise->"promise";p>=.6->"warm";p>0->"happy";gain>0->"closer";else->null
        }
        remember(row,RelationUndo(now,row.day,p>0,care,bigKind,fulfilled,adjusts),reaction,reaction !in setOf("ignored","boundary","broken","broken_big","gap"))
    }
    fun state():RelationState {
        // Attribution makes these targets addressable; aggregate totals still use impacts only.
        val addressable=impacts+mergedImpacts
        val alreadyApologized=apologized+recordedApologies()
        return RelationState(trust,closeness,total{it.tension},total{it.disappointment},total{it.happy},total{it.unhappy},missing,
        causes.toMap(),contact,promises.values.toList(),peak,total{it.hurt},total{it.low},explanations.values.toList(),excuses.toMap(),pending.toMap(),
        addressable.filter{(ref,v)->ref.turnId>0&&v.kind!=null&&ref !in alreadyApologized&&!forgotten(v)}.keys.toSet(),
        addressable.filter{(ref,v)->ref.turnId>0&&v.tension>=EPSILON}.keys.toSet(),repairPairs.toSet(),moments.toList())
    }
}
/** 真正参与计算的各笔：有效、去重、旧格式「误会」作废的去掉，并补上解释时间和弥补依据是否有效。 */
internal fun relationEffectiveInputs(inputs:List<RelationInput>,eventTime:Long):List<RelationInput> {
    val valid=inputs.filter{it.status=="valid"&&it.at<=eventTime}.sortedWith(compareBy<RelationInput>{it.at}.thenBy{it.source.turnId}).distinctBy{it.source}
    val voids=valid.filter{it.correction=="误会"&&it.formatVersion<2}.mapNotNull{it.previous}.toSet()
    val effective=valid.filter{it.source !in voids}
    val bySource=effective.associateBy{it.source}
    return effective.map{row->val target=bySource[row.explains]
        val basisValid=row.repairBasis==null||row.repairBasis==row.source||bySource[row.repairBasis]?.let{it.at<=row.at&&it.source.turnId<row.source.turnId&&it.source.characterId==row.source.characterId}==true
        // A fulfilled promise is a versioned dependency too. Normalize it before fingerprinting,
        // so a saved feeling retracts its old contribution when the promise becomes invalid.
        // Keep the original rows untouched, and never turn a rejected fulfilment into an unpromised repair.
        val fulfillmentValid=row.fulfills==null||bySource[row.fulfills]?.promise==true
        row.copy(explainedAt=target?.at,c=row.c.takeIf{fulfillmentValid},fulfills=row.fulfills.takeIf{fulfillmentValid},
            repairs=row.repairs.takeIf{basisValid&&fulfillmentValid},repairCompleted=row.repairCompleted&&basisValid&&fulfillmentValid)}
}

internal object RelationExcuses {
    val categories=listOf("工作忙","身体不舒服","忘了","家里有事","其他")
    fun category(value:String?)=value?.takeIf{it in categories}?:"其他"
    fun ratio(count:Int,elapsed:Long):Double {
        val base=when(count){1->.7;2->.5;3->.2;else->.1}
        val delay=((elapsed/RelationshipProjector.DAY)-1).coerceAtLeast(0)
        return maxOf(if(count==1).3 else 0.0,base-.1*delay)
    }
}

/** Ordered timeline is shared by cold replay and append. Queries never advance the cached cursor. */
internal class RelationProjectionCache(private val snapshots:RelationSnapshotStore=MemoryRelationSnapshots(),private val sparse:Boolean=true,private val trackMerged:Boolean=false) {
    private data class Step(val at:Long,val kind:Int,val order:Long,val input:RelationInput?=null,val change:RelationChange?=null,val correction:RelationCorrection?=null)
    var rebuilds=0;private set
    var applied=0;private set
    var processed=0;private set
    var restoredPosition=0;private set
    private var previousBase=""
    private var previousSteps=emptyList<Step>()
    private var previousPrefixes=emptyList<String>()
    var fingerprints=0;private set
    private var activeCursor:RelationCursor?=null
    private var headerMarker:String?=null
    private var cachedHeaders=emptyList<RelationSnapshotHeader>()
    fun forecast()=activeCursor?.copy()
    fun project(parameters:RelationParameters,inputs:List<RelationInput>,now:Long,start:RelationStart,
        contacts:List<Long>,changes:List<RelationChange> = emptyList(),reachOuts:List<Long> = emptyList(),
        corrections:List<RelationCorrection> = emptyList(),templateId:String="",eventTime:Long=now,readOnly:Boolean=false):RelationState {
        return try { projectStored(parameters,inputs,now,start,contacts,changes,reachOuts,corrections,templateId,eventTime,readOnly) }
        catch(e:Exception){
            if(e is java.util.concurrent.CancellationException)throw e
            Diagnostics.event("relation.snapshot.failed",mapOf("code" to e.javaClass.simpleName,"stage" to "projection"))
            if(!readOnly)try{snapshots.truncate(0)}catch(cleanup:Exception){if(cleanup is java.util.concurrent.CancellationException)throw cleanup}
            val fallback=RelationProjectionCache()
            fallback.projectStored(parameters,inputs,now,start,contacts,changes,reachOuts,corrections,templateId,eventTime,true).also{activeCursor=fallback.forecast()}
        }
    }
    private fun projectStored(parameters:RelationParameters,inputs:List<RelationInput>,now:Long,start:RelationStart,
        contacts:List<Long>,changes:List<RelationChange>,reachOuts:List<Long>,corrections:List<RelationCorrection>,
        templateId:String,eventTime:Long,readOnly:Boolean):RelationState {
        val newSteps=(contacts.filter{it<=eventTime}.distinct().map{Step(it,0,0)}+
            reachOuts.filter{it<=eventTime}.mapIndexed{i,t->Step(t,3,i.toLong())}+
            changes.filter{it.at<=eventTime}.mapIndexed{i,c->Step(c.at,1,i.toLong(),change=c)}+
            corrections.filter{it.at<=eventTime}.mapIndexed{i,c->Step(c.at,4,i.toLong(),correction=c)}+
            relationEffectiveInputs(inputs,eventTime).map{row->Step(row.at,2,row.source.turnId,input=row)})
            .sortedWith(compareBy<Step>{it.at}.thenBy{when(it.kind){0->Long.MIN_VALUE;3->Long.MIN_VALUE+1;4->Long.MAX_VALUE;1->it.change!!.afterMessage*2+1;else->it.input!!.source.turnId*2}}.thenBy{it.order})
        val base=RelationSnapshotCodec.fingerprint(listOf(RelationDecay.ENGINE,RelationSnapshotCodec.CODEC,parameters,start))
        val reuse=if(base==previousBase)newSteps.zip(previousSteps).takeWhile{(a,b)->a==b}.size else 0
        val prefixes=previousPrefixes.take(reuse).toMutableList()
        var prefix=prefixes.lastOrNull()?:base
        newSteps.drop(reuse).forEach{prefix=RelationSnapshotCodec.hash(prefix+RelationSnapshotCodec.fingerprint(it));prefixes.add(prefix);fingerprints++}
        previousBase=base;previousSteps=newSteps;previousPrefixes=prefixes
        val marker=snapshots.marker()
        val saved=if(marker!=null&&marker==headerMarker)cachedHeaders else snapshots.headers().also{cachedHeaders=it;headerMarker=marker}
        val intact=snapshots.complete(saved)&&saved.withIndex().all{(i,h)->h.position>0&&h.engine==RelationDecay.ENGINE&&h.base==base&&(i==0||h.sequence>saved[i-1].sequence&&h.position>saved[i-1].position)}
        var common=if(intact)saved.lastOrNull{it.position<=prefixes.size&&it.prefix==prefixes[it.position-1]}?.position?:0 else 0
        // Distinguish an authoritative input edit from damaged snapshot metadata at the divergence.
        saved.firstOrNull{it.position>common}?.let{h->val raw=snapshots.payload(h.sequence)
            if(raw==null||RelationSnapshotCodec.checksum(h,raw)!=h.checksum)common=0
        }
        fun params(position:Int)=newSteps.take(position).lastOrNull{it.change!=null}?.change?.new?:parameters
        var current:RelationCursor?=null
        if(common>0){
            val h=saved.first{it.position==common};val raw=snapshots.payload(h.sequence)
            if(raw!=null&&RelationSnapshotCodec.checksum(h,raw)==h.checksum)current=RelationSnapshotCodec.decode(raw,start,params(common))
            if(current==null)common=0
        }
        restoredPosition=common
        if(current==null){current=RelationCursor(start,parameters).also{it.trackMerged=trackMerged};if(saved.isNotEmpty()||newSteps.isNotEmpty())rebuilds++}
        // Database callers wrap this entire operation in the input transaction. No in-memory cursor
        // can outlive a rolled-back SQL transaction; on the next call we re-read committed snapshots.
        if(!readOnly&&(saved.any{it.position>common}||!intact))snapshots.truncate(common)
        val active=current
        fun template(change:RelationChange)=RelationTemplate.named(change.label)?.name
            ?:RelationTemplate.entries.firstOrNull{change.label=="自定义（基于${it.label}）"}?.let{"CUSTOM:${it.name}"}?:"CUSTOM"
        var template=newSteps.take(common).lastOrNull{it.change!=null}?.change?.let(::template)?:templateId
        val keep=RelationSnapshotRetention.positions(newSteps.mapIndexed{i,s->i+1 to s.at},eventTime)
        if(!readOnly&&sparse)snapshots.prune(eventTime)
        newSteps.drop(common).forEachIndexed{index,step->
            when(step.kind){0->active.contact(step.at);3->active.reachOut(step.at);1->{active.advance(step.at);active.parameters=step.change!!.new;template=template(step.change)};2->{active.apply(step.input!!);applied++}}
            processed++
            active.compact()
            if(readOnly||sparse&&common+index+1 !in keep)return@forEachIndexed
            val position=common+index+1;val raw=RelationSnapshotCodec.encode(active)
            val event=when(step.kind){2->"input:${step.input!!.source.turnId}:${step.input.source.version}";4->"correction:${step.correction!!.source.turnId}:${step.order}";else->"${step.kind}:${step.at}:${step.order}"}
            val header=RelationSnapshotHeader(0,position,event,step.at,RelationDecay.ENGINE,base,prefixes[position-1],template,"")
            val bytes=raw.toByteArray(Charsets.UTF_8).size
            if(bytes>RelationSnapshotRetention.WARN_BYTES)Diagnostics.event("relation.snapshot.oversize",mapOf("bytes" to bytes))
            if(bytes<=RelationSnapshotCodec.MAX_BYTES){snapshots.append(header.copy(checksum=RelationSnapshotCodec.checksum(header,raw)),raw);if(sparse)snapshots.prune(eventTime)}
        }
        if(!readOnly&&sparse)snapshots.prune(eventTime)
        activeCursor=active.copy()
        return active.copy().also{it.advance(now);it.compact()}.state()
    }
}
