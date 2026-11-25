package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow

enum class MemoryImportance(val halfLife:Double,val weight:Double){HIGH(3650.0,1.5),NORMAL(120.0,1.0),TRIVIAL(21.0,.5)}
internal enum class RecallSource { CUE_HIT, TOOL_HIT, HISTORY_HIT, PRIVATE_LIFE }
internal interface MemoryRecallSink { fun recalled(db:SQLiteDatabase,friendId:String,memoryId:String,at:Long,source:RecallSource) }
internal data class MemoryRecallState(val at:Long,val count:Int,val halfLife:Double,val anchor:Double,val day:String?=null){
    fun clarity(now:Long)=(anchor*0.5.pow((now.toDouble()-at).coerceAtLeast(0.0)/86400000/halfLife)).coerceIn(0.0,100.0)
    fun reinforce(now:Long):MemoryRecallState {
        val today=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if(today==day)return this
        val a=clarity(now);return MemoryRecallState(now,count+1,(halfLife*2).coerceAtMost(MemoryForgetting.MAX_HALF_LIFE),a+.2*(100-a),today)
    }
}
internal object MemoryForgetting:MemoryRecallSink {
    /** One-off marker: the "proactive recall on by default" migration already ran on this database. */
    const val RECALL_DEFAULT_MIGRATED="memory-recall-default-on-v1"
    fun metadata(m:StoredMemory,j:JSONObject,old:StoredMemory?):StoredMemory {
        fun text(key:String,max:Int)=j.opt(key)?.takeIf{it is String}?.toString()?.takeIf{it.isNotBlank()}?.take(max)
        return m.copy(createdAt=old?.createdAt?:m.createdAt,importance=runCatching{MemoryImportance.valueOf(j.optString("importance"))}.getOrNull()?:old?.importance,
            gist=text("gist",40)?:old?.gist,trace=text("trace",8)?:old?.trace)
    }
    fun importance(m:StoredMemory):MemoryImportance=when {
        m.manual||m.locked||m.dateType=="birthday"||m.category in setOf(MemoryCategory.CHARACTER_PROFILE,MemoryCategory.PROMISE)||m.category==MemoryCategory.COMMITMENT_OPEN&&m.life==MemoryLife.ACTIVE->MemoryImportance.HIGH
        m.importance!=null->if(m.core&&m.importance==MemoryImportance.TRIVIAL)MemoryImportance.NORMAL else m.importance
        m.category==MemoryCategory.PREFERENCE_BOUNDARY->MemoryImportance.HIGH
        else->MemoryImportance.NORMAL
    }
    fun create(db:SQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS memory_recall_state(memory_id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,last_recalled_at INTEGER NOT NULL,recall_count INTEGER NOT NULL DEFAULT 0,half_life_days REAL NOT NULL,anchor_clarity REAL NOT NULL,last_reinforce_day TEXT,memory_created_at INTEGER NOT NULL)")}
    const val CORE_BYTES=6*1024 // UTF-8，包含核心标题和结尾空行。
    const val GIST_BATCH=20 // 同轮缺失标记与补写上限。
    const val MAX_HALF_LIFE=36500.0 // 加固后的半衰期上限（天）。
    fun put(db:SQLiteDatabase,m:StoredMemory,s:MemoryRecallState,conflict:Int=SQLiteDatabase.CONFLICT_REPLACE){db.insertWithOnConflict("memory_recall_state",null,ContentValues().apply{
        put("memory_id",m.id);put("friend_id",m.friendId);put("last_recalled_at",s.at);put("recall_count",s.count);put("half_life_days",s.halfLife);put("anchor_clarity",s.anchor);put("last_reinforce_day",s.day);put("memory_created_at",m.createdAt)
    },conflict)}
    fun state(db:SQLiteDatabase,m:StoredMemory,now:Long,initial:Long=m.createdAt):MemoryRecallState {
        val old=db.rawQuery("SELECT last_recalled_at,recall_count,half_life_days,anchor_clarity,last_reinforce_day,memory_created_at FROM memory_recall_state WHERE memory_id=? AND friend_id=?",arrayOf(m.id,m.friendId)).use{c->
            if(c.moveToFirst()&&(!m.createdAtStored||c.getLong(5)==m.createdAt))MemoryRecallState(c.getLong(0),c.getInt(1),c.getDouble(2),c.getDouble(3),if(c.isNull(4))null else c.getString(4))else null}
        return old?:MemoryRecallState(initial,0,importance(m).halfLife,100.0)
    }
    fun ensure(db:SQLiteDatabase,m:StoredMemory,now:Long):MemoryRecallState {
        check(db.inTransaction()&&!db.isReadOnly)
        // An overwritten ID with another creation identity is a new memory.
        db.delete("memory_recall_state","memory_id=? AND (friend_id<>? OR memory_created_at<>?)",arrayOf(m.id,m.friendId,m.createdAt.toString()))
        put(db,m,state(db,m,now),SQLiteDatabase.CONFLICT_IGNORE)
        return state(db,m,now)
    }
    fun reclassify(s:MemoryRecallState,importance:MemoryImportance,now:Long)=s.copy(at=now,anchor=s.clarity(now),
        halfLife=(importance.halfLife*2.0.pow(s.count.toDouble())).coerceAtMost(MAX_HALF_LIFE))
    fun migrate(db:SQLiteDatabase,at:Long){
        create(db)
        val migrated=db.rawQuery("SELECT 1 FROM preferences WHERE key=?",arrayOf(RECALL_DEFAULT_MIGRATED)).use{it.moveToFirst()}
        db.rawQuery("SELECT json FROM memory_current",null).use{c->while(c.moveToNext()){
            val j=JSONObject(c.getString(0));val old=StoredMemory.from(j)
            val created=if(old.createdAtStored)old.createdAt else old.evidence.minOfOrNull{it.sourceAt}?:at
            val m=old.copy(createdAt=created)
            if(!old.createdAtStored){
                j.put("createdAt",created)
                db.execSQL("UPDATE memory_current SET json=? WHERE id=?",arrayOf(j.toString(),m.id))
                // Archive validation does not require equal current/history JSON; leave history verbatim.
            }
            put(db,m,MemoryRecallState(at,0,importance(m).halfLife,100.0),SQLiteDatabase.CONFLICT_IGNORE)
        }}
        if(!migrated){
            val raw=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(MemoryRules.KEY)).use{if(it.moveToFirst())it.getString(0)else null}
            if(raw!=null)db.execSQL("UPDATE preferences SET value=? WHERE key=?",arrayOf(JSONObject(raw).put("proactiveRecall",true).toString(),MemoryRules.KEY))
            db.execSQL("INSERT OR IGNORE INTO preferences(key,value) VALUES(?,'true')",arrayOf(RECALL_DEFAULT_MIGRATED))
        }
    }
    fun text(m:StoredMemory,clarity:Double,original:()->String):String=when {
        clarity>60->original()
        clarity>=20->m.gist?.takeIf{it.isNotBlank()}?:m.trace.orEmpty()
        else->m.trace.orEmpty()
    }
    fun core(repo:ProcessingRepository,db:SQLiteDatabase,role:String,values:List<StoredMemory>,clarity:Map<String,Double>):List<StoredMemory>{
        val key="memory-core-set:$role"
        val previous=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())runCatching{org.json.JSONArray(it.getString(0))}.getOrNull()else null}
        val ids=previous?.let{a->(0 until a.length()).map{a.optString(it)}.toSet()}.orEmpty()
        val chosen=selectCore(values,clarity,ids,repo.policy(db,role).first.coreGoal){repo.describe(it)}
        db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",org.json.JSONArray(chosen.map{it.id}).toString())},SQLiteDatabase.CONFLICT_REPLACE)
        return chosen
    }
    fun selectCore(values:List<StoredMemory>,clarity:Map<String,Double>,ids:Set<String>,goal:Int,describe:(StoredMemory)->String):List<StoredMemory>{
        val pinned=values.filter{it.manual&&it.core&&text(it,clarity.getValue(it.id)){describe(it)}.isNotBlank()};val chosen=pinned.toMutableList()
        fun body(m:StoredMemory)=text(m,clarity.getValue(m.id)){describe(m)}
        fun bytes(m:StoredMemory)=("核心记忆 ${m.id}@${m.version}:\n"+body(m)+"\n\n").toByteArray().size
        var usedBytes=pinned.sumOf{bytes(it)};var tokens=pinned.sumOf{ConservativeTokens.tokens(body(it))+ConservativeTokens.tokens("核心记忆 ${it.id}@${it.version}")+16}
        values.filter{it !in pinned&&body(it).isNotBlank()&&(clarity.getValue(it.id)>=60||it.id in ids&&clarity.getValue(it.id)>=50)}
            .sortedWith(compareByDescending<StoredMemory>{clarity.getValue(it.id)*importance(it).weight}.thenBy{it.id}).forEach{m->
                val b=bytes(m);val t=ConservativeTokens.tokens(body(m))+ConservativeTokens.tokens("核心记忆 ${m.id}@${m.version}")+16
                if(usedBytes+b<=CORE_BYTES&&tokens+t<=goal){chosen.add(m);usedBytes+=b;tokens+=t}
            }
        return chosen
    }
    override fun recalled(db:SQLiteDatabase,friendId:String,memoryId:String,at:Long,source:RecallSource){
        val m=db.rawQuery("SELECT json FROM memory_current WHERE friend_id=? AND id=? AND valid=1",arrayOf(friendId,memoryId)).use{if(it.moveToFirst())StoredMemory.from(JSONObject(it.getString(0)))else null}?:return
        val old=ensure(db,m,at);val next=old.reinforce(at);if(next!=old)put(db,m,next)
    }
}
