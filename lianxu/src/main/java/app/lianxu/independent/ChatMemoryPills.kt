package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** One committed memory batch as it appeared in that conversation; the text is derived, never stored twice. */
internal data class MemoryPill(val batchId:String,val friendId:String,val afterMessageId:Long,val savedCount:Int,val created:Long) {
    /** Zero saved memories still means the batch succeeded; never show "0 条". */
    val text get()=if(savedCount>0)"已整理 $savedCount 条记忆" else "记忆已整理"
}

/**
 * A pill is written inside the batch's own commit transaction, so it can only exist for a batch that
 * really committed, and the batch id primary key keeps retries, rebuilds and restarts from adding a
 * second one. Placement is the newest message at that moment: a blocking summary finishes after the
 * user's message and before the resumed reply, a background one finishes at the end of the chat.
 */
internal object ChatMemoryPills {
    fun createSchema(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_memory_pills(batch_id TEXT PRIMARY KEY, friend_id TEXT NOT NULL REFERENCES friends(id), after_message_id INTEGER NOT NULL, saved_count INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS chat_memory_pills_friend ON chat_memory_pills(friend_id, after_message_id)")
    }
    /** Call inside the committing transaction, after the batch state became COMMITTED. */
    fun record(db:SQLiteDatabase,friendId:String,batchId:String,savedCount:Int){
        val anchor=db.rawQuery("SELECT MAX(id) FROM messages WHERE friend_id=?",arrayOf(friendId)).use{if(it.moveToFirst()&&!it.isNull(0))it.getLong(0) else 0L}
        if(anchor<=0L)return
        db.insertWithOnConflict("chat_memory_pills",null,ContentValues().apply{
            put("batch_id",batchId);put("friend_id",friendId);put("after_message_id",anchor)
            put("saved_count",maxOf(0,savedCount));put("created",System.currentTimeMillis())
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun read(db:SQLiteDatabase,friendId:String?=null):List<MemoryPill>{
        val sql="SELECT batch_id,friend_id,after_message_id,saved_count,created FROM chat_memory_pills"+
            (if(friendId==null)"" else " WHERE friend_id=?")+" ORDER BY after_message_id,batch_id"
        return db.rawQuery(sql,friendId?.let{arrayOf(it)}).use{c->buildList{while(c.moveToNext())add(MemoryPill(c.getString(0),c.getString(1),c.getLong(2),c.getInt(3),c.getLong(4)))}}
    }
    /**
     * Memories this batch really created or updated. Only operations that executed a save count; life
     * state changes, duplicates, unchanged facts and protected items that became candidates do not.
     */
    fun savedCount(db:SQLiteDatabase,batchId:String):Int {
        val ids=mutableSetOf<String>()
        db.rawQuery("SELECT json,result_id FROM memory_operations WHERE batch_id=? AND saved=1",arrayOf(batchId)).use{c->
            while(c.moveToNext()){
                val kind=runCatching{org.json.JSONObject(c.getString(0)).optString("op")}.getOrDefault("")
                if(kind=="ADD"||kind=="UPDATE")ids+=c.getString(1)
            }
        }
        return ids.size
    }
}
