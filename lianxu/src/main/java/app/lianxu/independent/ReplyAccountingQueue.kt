package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fixed source/body versions; retries may append speech without changing successful tool identity. */
internal object ReplyAccountingQueue {
    const val PREFIX="reply-accounting:"
    const val BATCH_PREFIX="reply-review-batch:"
    const val READ_PREFIX="reply-review-reads:"
    fun key(turn:String)=PREFIX+turn
    fun batchKey(role:String)=BATCH_PREFIX+role
    fun readsKey(turn:String)=READ_PREFIX+turn
    private val gson=Gson()
    internal data class Marker(val revision:Long,val attempt:Int,val state:String,val reviewedHash:String?=null){
        fun finished(reviewedAttempt:Int,hash:String)=copy(state=if(attempt==reviewedAttempt)"done"else state,reviewedHash=hash)
    }
    private fun marker(db:SQLiteDatabase,turn:String):Marker?=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key(turn))).use{if(it.moveToFirst())gson.fromJson(it.getString(0),Marker::class.java)else null}
    private fun put(db:SQLiteDatabase,key:String,value:Any){db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",gson.toJson(value))},SQLiteDatabase.CONFLICT_REPLACE)}
    fun reads(db:SQLiteDatabase,turn:String):List<SourceRef> = db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(readsKey(turn))).use{if(it.moveToFirst())gson.fromJson(it.getString(0),Array<SourceRef>::class.java).toList()else emptyList()}
    fun rememberReads(db:SQLiteDatabase,turn:String,refs:List<SourceRef>){put(db,readsKey(turn),(reads(db,turn)+refs).distinct())}
    fun enqueue(db:SQLiteDatabase,turn:String){
        val (revision,attempt)=db.rawQuery("SELECT source_revision,attempt FROM chat_turns WHERE id=?",arrayOf(turn)).use{check(it.moveToFirst());it.getLong(0) to it.getInt(1)}
        val old=marker(db,turn)
        if(old?.revision==revision&&old.attempt==attempt)return
        put(db,key(turn),Marker(revision,attempt,"pending",old?.takeIf{it.revision==revision}?.reviewedHash))
    }
    data class Item(val turn:ChatTurn,val source:SourceRef,val input:String,val spoken:String,val at:Long,val state:String,val readSources:List<SourceRef> = emptyList())
    data class Member(val task_id:String,val revision:Long,val attempt:Int,val state:String,val inputHash:String,val spokenLength:Int,val spokenHash:String,val readSources:List<SourceRef>)
    data class Batch(val id:String,val members:List<Member>,val remaining:List<Member> = emptyList()){
        /** Only a failed capacity check may split. Every frozen member remains in original order. */
        fun smaller():Batch?=if(members.size<2)null else copy(members=members.take(members.size/2),remaining=members.drop(members.size/2)+remaining)
    }
    private fun signature(item:Item)=stableDigest(item.input+"\u0000"+item.spoken)
    private fun rows(db:SQLiteDatabase,role:String,pendingOnly:Boolean):List<Item> = db.rawQuery("SELECT t.id,t.user_message_id,t.assistant_message_id,t.state,t.attempt,t.config_id,t.source_revision,u.body,u.created,a.body,p.value FROM chat_turns t JOIN preferences p ON p.key=?||t.id JOIN messages u ON u.id=t.user_message_id LEFT JOIN messages a ON a.id=t.assistant_message_id WHERE t.friend_id=? ORDER BY t.user_message_id",arrayOf(PREFIX,role)).use{c->buildList{
        while(c.moveToNext()){
            val m=gson.fromJson(c.getString(10),Marker::class.java)
            if(pendingOnly&&m.state!="pending")continue
            val t=ChatTurn(c.getString(0),role,c.getLong(1),if(c.isNull(2))null else c.getLong(2),TurnState.valueOf(c.getString(3)),c.getInt(4),c.getString(5),null)
            add(Item(t,SourceRef(role,t.userMessageId,m.revision),c.getString(7),if(c.isNull(9))""else c.getString(9),c.getLong(8),if(m.revision!=c.getLong(6))"stale"else c.getString(3),reads(db,t.id)))
        }
    }}
    fun pending(db:SQLiteDatabase,role:String)=rows(db,role,true)
    fun ready(item:Item)=item.state in setOf("COMPLETED","SUPERSEDED","FAILED","CANCELLED","INTERRUPTED","stale")
    /** Review alone may use a saved failed prefix; memory-summary eligibility stays unchanged. */
    fun valid(db:SQLiteDatabase,item:Item):Boolean = item.state!="stale"&&CompanionRepository.aiAllowed(db,item.source.characterId,item.source.turnId)&&db.rawQuery("SELECT 1 FROM chat_turns t JOIN messages u ON u.id=t.user_message_id LEFT JOIN messages a ON a.id=t.assistant_message_id LEFT JOIN message_state us ON us.message_id=t.user_message_id LEFT JOIN message_state ats ON ats.message_id=t.assistant_message_id WHERE t.id=? AND t.friend_id=? AND t.source_revision=? AND u.body=? AND COALESCE(us.deleted,0)=0 AND COALESCE(ats.deleted,0)=0 AND substr(COALESCE(a.body,''),1,?)=?",arrayOf(item.turn.id,item.source.characterId,item.source.version.toString(),item.input,item.spoken.codePointCount(0,item.spoken.length).toString(),item.spoken)).use{it.moveToFirst()}
    fun batch(db:SQLiteDatabase,role:String):Batch?=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(batchKey(role))).use{if(it.moveToFirst())gson.fromJson(it.getString(0),Batch::class.java).let{b->b.copy(remaining=b.remaining.orEmpty())}else null}
    /** Only the already-shown prefix captured at claim time belongs to this batch. */
    fun frozen(db:SQLiteDatabase,role:String,batch:Batch):List<Item> {
        val current=rows(db,role,false).associateBy{it.turn.id}
        return batch.members.mapNotNull{m->current[m.task_id]?.let{freeze(it,m)}}
    }
    internal fun freeze(row:Item,m:Member):Item {
        val prefix=row.spoken.take(m.spokenLength)
        val unchanged=row.source.version==m.revision&&stableDigest(row.input)==m.inputHash&&stableDigest(prefix)==m.spokenHash&&row.state!="stale"
        return row.copy(turn=row.turn.copy(attempt=m.attempt,state=TurnState.valueOf(m.state),configId=""),source=row.source.copy(version=m.revision),spoken=prefix,state=if(unchanged)m.state else "stale",readSources=m.readSources)
    }
    fun claim(db:SQLiteDatabase,role:String,rounds:Int):Batch? {
        batch(db,role)?.let{return it}
        val rows=pending(db,role).takeWhile(::ready).filter{item->
            val needs=valid(db,item)&&marker(db,item.turn.id)?.reviewedHash!=signature(item)
            if(!needs)done(db,item)
            needs
        }
        val count=ReviewBatchTrigger.prefix(rows.map{it.spoken.isNotBlank()},rounds)?:return null
        return Batch(UUID.randomUUID().toString(),rows.take(count).map{Member(it.turn.id,it.source.version,it.turn.attempt,it.state,stableDigest(it.input),it.spoken.length,stableDigest(it.spoken),it.readSources)}).also{put(db,batchKey(role),it)}
    }
    fun done(db:SQLiteDatabase,item:Item){
        val m=marker(db,item.turn.id)?:return
        if(m.revision!=item.source.version)return
        // A retry may already be pending. Remember the reviewed prefix without completing that retry.
        put(db,key(item.turn.id),m.finished(item.turn.attempt,signature(item)))
    }
    fun splitForCapacity(db:SQLiteDatabase,role:String,expected:Batch):Boolean {
        if(batch(db,role)!=expected)return false
        val next=expected.smaller()?:return false
        put(db,batchKey(role),next);return true
    }
    fun finish(db:SQLiteDatabase,role:String,expected:Batch){
        check(batch(db,role)==expected)
        // The rest belongs to the already-triggered batch, even if it is below the user's threshold.
        if(expected.remaining.isEmpty())db.delete("preferences","key=?",arrayOf(batchKey(role)))
        else put(db,batchKey(role),Batch(UUID.randomUUID().toString(),expected.remaining))
    }
    fun roles(db:SQLiteDatabase)=db.rawQuery("SELECT DISTINCT t.friend_id FROM chat_turns t JOIN preferences p ON p.key=?||t.id WHERE p.value LIKE '%\"state\":\"pending\"%'",arrayOf(PREFIX)).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
}

internal object ReviewBatchTrigger {
    /** A stopped prefix counts as an answer; user-only turns travel with it but do not fill the threshold. */
    fun prefix(spoken:List<Boolean>,rounds:Int):Int? {require(rounds>0);var count=0;spoken.forEachIndexed{i,hasReply->if(hasReply&&++count==rounds)return i+1};return null}
}

/** Independent from the reply lock: a slow review never holds up another answer. */
internal object AccountingDrain {
    private val locks=ConcurrentHashMap<String,Mutex>()
    suspend fun run(role:String,next:suspend()->String?,record:suspend(String)->Boolean):Boolean=locks.getOrPut(role){Mutex()}.withLock{
        while(true){val id=next()?:return@withLock true;if(!record(id))return@withLock false}
        @Suppress("UNREACHABLE_CODE") true
    }
}
