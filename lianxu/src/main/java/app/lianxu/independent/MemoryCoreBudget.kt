package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase

data class CoreBudgetState(val goal:Int,val used:Int,val protected:Int,val entries:List<StoredMemory>,val protectedIds:Set<String> = emptySet()){val overGoal get()=used>goal}
// Capacity is not evidence that an older fact, boundary or unfinished commitment stopped mattering.
private fun StoredMemory.releasableCore()=!manual&&!locked&&category in setOf(MemoryCategory.EXPERIENCE_MEMORY,MemoryCategory.EXPERIENCE)
private fun ProcessingRepository.coreCost(m:StoredMemory)=ConservativeTokens.tokens(describe(m))+ConservativeTokens.tokens("核心记忆 ${m.id}@${m.version}")+16
internal fun ProcessingRepository.coreBudget(db:SQLiteDatabase,friendId:String):CoreBudgetState {
    // Match the actual prompt: expired, disputed or unusable sources must not evict valid core.
    val entries=usableMemories(db,friendId).filter{it.manual&&it.core}
    val pending=db.rawQuery("SELECT DISTINCT o.result_id FROM memory_operations o JOIN memory_batches b ON o.batch_id=b.id WHERE b.friend_id=? AND b.state<>'COMMITTED'",arrayOf(friendId)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}
    val protected=entries.filter{!it.releasableCore()||it.id in pending}
    return CoreBudgetState(policy(db,friendId).first.coreGoal,entries.sumOf{coreCost(it)},protected.sumOf{coreCost(it)},entries,protected.map{it.id}.toSet())
}
suspend fun ProcessingRepository.coreBudget(friendId:String)=read{coreBudget(it,friendId)}
/** Capacity check for manually pinned core only. Automatic selection never edits content versions. */
internal fun ProcessingRepository.maintainCore(db:SQLiteDatabase,friendId:String):CoreBudgetState {
    return coreBudget(db,friendId)
}
suspend fun ProcessingRepository.maintainCore(friendId:String)=tx{maintainCore(it,friendId)}
suspend fun ProcessingRepository.chooseCore(friendId:String,id:String,expectedVersion:Int,value:Boolean)=tx{db->
    store.memorySummary.requireEditable(friendId)
    val old=memory(db,friendId,id)?:throw ProcessingRejected("SOURCE_CHANGED")
    rejectUnless(old.version==expectedVersion,"SOURCE_CHANGED")
    saveMemory(db,old.copy(core=value,manual=true,version=old.version+1))
}
