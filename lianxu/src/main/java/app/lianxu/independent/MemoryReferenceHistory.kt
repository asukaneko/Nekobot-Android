package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Our CompanionReferences snapshot/retainedIds policy, adapted to the existing preference store. */
internal object MemoryReferenceHistory {
    private fun key(friend:String,message:Long)="memory-reference-v1:$friend:$message"
    fun retain(db:SQLiteDatabase,friend:String,visible:Set<Long>):List<String> = visible.flatMap{message->
        db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key(friend,message))).use{c->
            if(!c.moveToFirst())emptyList() else runCatching{val j=JSONObject(c.getString(0));require(j.getInt("format")==1);val a=j.getJSONArray("memoryIds");(0 until a.length()).map{a.getString(it)}}.getOrDefault(emptyList())
        }
    }.distinct()
    fun snapshot(db:SQLiteDatabase,friend:String,message:Long,ids:List<String>) {
        // Only fresh hits are saved: retained copies cannot extend their own lifetime forever.
        db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key(friend,message));put("value",JSONObject().put("format",1).put("memoryIds",JSONArray(ids.distinct())).toString())},SQLiteDatabase.CONFLICT_IGNORE)
    }
}
