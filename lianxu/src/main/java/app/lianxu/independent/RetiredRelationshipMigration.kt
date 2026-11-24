package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Runs in SQLiteOpenHelper's upgrade transaction. No chat or companion-learning table is touched. */
internal object RetiredRelationshipMigration {
    fun remove(db:SQLiteDatabase){
        RetiredRelationshipData.tables.forEach{db.execSQL("DROP TABLE IF EXISTS $it")}
        val policies=db.rawQuery("SELECT friend_id,json FROM processing_policies",null).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
        for((id,raw) in policies){val value=JSONObject(raw);RetiredRelationshipData.policyFields.forEach(value::remove)
            db.update("processing_policies",ContentValues().apply{put("json",value.toString())},"friend_id=?",arrayOf(id))
        }
        val prompts=db.rawQuery("SELECT key,value FROM preferences WHERE substr(key,1,13)='role-prompts:'",null).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
        for((key,raw) in prompts){val value=JSONObject(raw);value.remove("initialScores")
            db.update("preferences",ContentValues().apply{put("value",value.toString())},"key=?",arrayOf(key))
        }
    }
}
