package app.lianxu.independent

/** Conservative identity only: never infer that two different sentences mean the same fact. */
internal object MemoryEvidenceRules {
    private fun origin(e:MemoryEvidence)=Triple(e.source,e.side,e.image)
    private fun body(s:String)=s.trim().replace(Regex("\\s+")," ")
    fun sameFact(a:StoredMemory,b:StoredMemory)=a.friendId==b.friendId&&a.category==b.category&&a.about==b.about&&body(a.text)==body(b.text)&&
        a.life==b.life&&a.dateExpression==b.dateExpression&&a.eventDate==b.eventDate&&a.dateType==b.dateType&&a.annualMonthDay==b.annualMonthDay&&
        a.dueExpression==b.dueExpression&&a.dueDate==b.dueDate&&a.expiresAt==b.expiresAt
    /** Hash of the fact content itself: normalized body, category, about, lifecycle and resolved dates. */
    fun contentHash(m:StoredMemory)=stableDigest(listOf(m.category.name,m.about?.name,body(m.text),m.life.name,m.dateExpression,m.eventDate,m.dateType,m.annualMonthDay,m.dueExpression,m.dueDate,m.expiresAt).joinToString("\u0000"){it?.toString()?:"\u0001"})
    /** Retry-safe dedup identity: source message + source version (+ side) combined with the content hash. */
    fun dedupKeys(m:StoredMemory,proof:List<MemoryEvidence> = m.evidence)=proof.map{"${it.source.characterId}:${it.source.turnId}@${it.source.version}:${it.side}:${it.image?.observationId.orEmpty()}:${it.image?.hash.orEmpty()}#${contentHash(m)}"}.toSet()
    fun sameSourceContent(old:StoredMemory,fact:StoredMemory,proof:List<MemoryEvidence>)=old.friendId==fact.friendId&&dedupKeys(old).intersect(dedupKeys(fact,proof)).isNotEmpty()
    fun overlaps(a:List<MemoryEvidence>,b:List<MemoryEvidence>)=a.map(::origin).toSet().intersect(b.map(::origin).toSet()).isNotEmpty()
    fun repeated(old:StoredMemory,proposed:StoredMemory,proof:List<MemoryEvidence>)=proof.isNotEmpty()&&sameFact(old,proposed)&&
        proof.all{origin(it) in old.evidence.map(::origin).toSet()}
    /** One citation per source message. Different quoted spans do not become independent support. */
    fun merge(old:List<MemoryEvidence>,added:List<MemoryEvidence>):List<MemoryEvidence> = (old+added).groupBy(::origin).values.map{group->
        val first=group.first()
        rejectUnless(group.all{it.snapshot==first.snapshot&&it.sourceAt==first.sourceAt&&it.zone==first.zone},"SOURCE_CHANGED")
        if(group.map{it.quote}.distinct().size==1)first else first.copy(quote=first.snapshot)
    }
    fun contextCurrent(captured:Int?,own:Int?,current:Int?)=current!=null&&current==maxOf(captured?:0,own?:0)
}
