package app.lianxu.independent

/** Revalidate cached visual edges against the currently displayed versions. */
internal fun visibleSkyEdges(memories:List<StoredMemory>,edges:List<MemoryRecall.Edge>):List<MemoryRecall.Edge>{
    val byId=memories.associateBy{it.id}
    return edges.filter{e->
        val a=byId[e.sourceId];val b=byId[e.targetId]
        a!=null&&b!=null&&a.friendId==e.friendId&&b.friendId==e.friendId&&
            a.version==e.sourceVersion&&b.version==e.targetVersion&&a.valid&&b.valid&&
            a.life!=MemoryLife.RETRACTED&&b.life!=MemoryLife.RETRACTED
    }
}
