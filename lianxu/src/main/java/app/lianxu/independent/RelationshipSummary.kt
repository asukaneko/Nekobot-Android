package app.lianxu.independent

internal data class RelationMood(val key:String,val label:String,val value:Double) {
    val score:Int get()=kotlin.math.floor(value*100+.5).toInt()
    val degree:String get()=RelationshipSummary.degree(value)
}
internal data class RelationMoodView(val primary:RelationMood,val secondary:RelationMood?,val sentence:String)
internal object RelationshipSummary {
    private fun band(n:Double)=when{n<.25->0;n<.45->1;n<.65->2;n<=.85->3;else->4}
    fun degree(n:Double)=when{n<.05->"";n<.2->"有一点";n<=.45->"挺";else->"很"}
    fun trust(n:Double)=listOf("还不太信任","有一点信任","比较信任","很信任","非常信任")[band(n)]
    fun closeness(n:Double)=listOf("还生疏","有点熟","挺亲近","很亲近","非常亲近")[band(n)]
    fun mood(s:RelationState):RelationMoodView {
        // 心情只看当下的开心、委屈、生气、想念、低落；失望是会留几周的印象，单独显示。
        val sorted=listOf(RelationMood("happy","开心",s.happy),
            RelationMood("hurt","委屈",s.hurt),RelationMood("unhappy","生气",s.unhappy),RelationMood("missing","想念",s.missing),RelationMood("low","低落",s.low)).sortedByDescending{it.value}
        val a=sorted[0];val b=sorted[1].takeIf{it.value>=.5*a.value&&it.value>=.2}
        return RelationMoodView(a,b,if(a.value<.05)"平静"else degree(a.value)+a.label+(b?.let{"，但还有点${it.label}"}?:""))
    }
    private data class Item(val key:String,val text:String,val deviation:Double)
    /** [addressee] is the configured name for the user; "对方" when none is set. */
    fun render(s:RelationState,now:Long,addressee:String="对方",quote:(SourceRef)->String?):String {
        val who=addressee.ifBlank{"对方"}
        val mood=mood(s)
        val candidates=listOf(Item("trust",trust(s.trust),kotlin.math.abs(s.trust-.5)),Item("closeness",closeness(s.closeness),kotlin.math.abs(s.closeness-.4)),
            Item("tension",if(s.tension<=.3)"还有点在意那件事"else"心里还有疙瘩",s.tension),Item(mood.primary.key,mood.sentence,mood.primary.value))
        val chosen=candidates.filter{it.key!="tension"||s.tension>=.1}.sortedWith(compareByDescending<Item>{it.deviation}.thenByDescending{s.causes[it.key]?.at?:Long.MIN_VALUE}).take(2)
        val named=chosen.map{it.key}.toSet()+if(chosen.any{it.key==mood.primary.key})listOfNotNull(mood.secondary?.key)else emptyList()
        val other=when{
            "tension" !in named&&s.tension>.3||"disappointment" !in named&&s.disappointment>.2->"心里还有点别的小疙瘩"
            "unhappy" !in named&&s.unhappy>=.2||"hurt" !in named&&s.hurt>=.2||"low" !in named&&s.low>=.2->"最近总体有点闷"
            else->"其他都还好"
        }
        val repeated=s.excuses.entries.filter{it.value>=2}.maxByOrNull{it.value}
        fun count(n:Int):String=when(n){2->"二";3->"三";4->"四";5->"五";6->"六";7->"七";8->"八";9->"九";10->"十";else->"多"}
        fun compose(limit:Int):String {
            if(chosen.map{it.key}.toSet()==setOf("missing","tension")){
                val originals=chosen.mapNotNull{s.causes[it.key]?.source?.let(quote)}.distinct().joinToString("，"){"『${it.take(limit)}${if(it.length>limit)"…"else""}』"}
                return (listOf(degree(s.missing)+"想${who}，但"+if(s.tension<=.3)"还有点在意那件事"else"心里还有点疙瘩")+
                    listOfNotNull(originals.takeIf{it.isNotEmpty()}?.let{"因为$it"})+other+
                    listOfNotNull(repeated?.let{"${who}这已经是第${count(it.value)}次说〈${it.key}〉了"})).joinToString("；")
            }
            return (chosen.map{item->
            val cause=s.causes[item.key];val original=cause?.source?.let(quote)
            item.text+(if(cause!=null&&original!=null)cause.direction+"，因为『${original.take(limit)}${if(original.length>limit)"…"else""}』"else "")
        }+other+listOfNotNull(repeated?.let{"${who}这已经是第${count(it.value)}次说〈${it.key}〉了"})).joinToString("；")
        }
        var limit=70;var result=compose(limit)
        while(result.length>250&&limit>0){limit--;result=compose(limit)}
        return result
    }
}
