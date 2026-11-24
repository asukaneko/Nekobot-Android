package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import org.json.JSONObject

/** Ephemeral display values only. The existing history table remains the only persisted snapshot. */
internal data class BatchMemoryDetail(val id:String,val version:Int,val text:String?,val updated:Boolean) {
    val deleted get()=text==null
}
internal sealed interface MemoryPillDetails {
    data class Available(val items:List<BatchMemoryDetail>):MemoryPillDetails
    data object Unavailable:MemoryPillDetails
}

/** Read the same committed writes counted by savedCount, then their exact historical versions. */
internal object MemoryPillDetailReader {
    private data class Saved(val id:String,val version:Int,val kind:String)
    private fun unavailable():Nothing=throw IllegalStateException("Memory batch details unavailable")
    private fun deleted(value:JSONObject):Boolean = if(!value.has("userDeleted"))false else value.get("userDeleted") as? Boolean?:unavailable()
    private fun version(value:JSONObject):Int = (value.opt("version") as? Number)?.let{
        val number=it.toDouble();if(!number.isFinite()||number<1||number>Int.MAX_VALUE||number!=it.toInt().toDouble())unavailable()
        it.toInt()
    }?:unavailable()
    fun read(db:SQLiteDatabase,friend:String,batch:String):MemoryPillDetails {
        // Also prevents a deletion/restore from committing between the current-state and history reads.
        val ownTransaction=!db.inTransaction()
        var started=false
        var result:MemoryPillDetails=MemoryPillDetails.Unavailable
        try {
            if(ownTransaction){
                // Android 15+ supports a DEFERRED read snapshot. Older releases keep the short
                // compatibility transaction rather than risk mixing deletion and history reads.
                if(Build.VERSION.SDK_INT>=35)db.beginTransactionReadOnly() else db.beginTransactionNonExclusive()
                started=true
            }
            result=readSnapshot(db,friend,batch)
            if(ownTransaction)db.setTransactionSuccessful()
        }catch(_:Exception){result=MemoryPillDetails.Unavailable}
        finally{if(started)try{db.endTransaction()}catch(_:Exception){result=MemoryPillDetails.Unavailable}}
        return result
    }
    private fun readSnapshot(db:SQLiteDatabase,friend:String,batch:String):MemoryPillDetails {
        val expected=db.rawQuery("SELECT p.saved_count FROM chat_memory_pills p JOIN memory_batches b ON b.id=p.batch_id AND b.friend_id=p.friend_id JOIN friends f ON f.id=p.friend_id WHERE p.batch_id=? AND p.friend_id=? AND b.state='COMMITTED'",arrayOf(batch,friend)).use{c->if(!c.moveToFirst())return MemoryPillDetails.Unavailable;c.getInt(0)}
        if(expected<0)return MemoryPillDetails.Unavailable
        val saved=linkedMapOf<String,Saved>();var operations=0
        db.rawQuery("SELECT json,result_id,result_version,saved FROM memory_operations WHERE batch_id=? ORDER BY rowid",arrayOf(batch)).use{c->
            while(c.moveToNext()) {
                operations++
                if(c.getInt(3)!=1)continue
                val kind=JSONObject(c.getString(0)).getString("op")
                if(kind!="ADD"&&kind!="UPDATE")continue
                if(c.isNull(1)||c.isNull(2))unavailable()
                val id=c.getString(1);val version=c.getInt(2)
                if(id.isBlank()||version<1)unavailable()
                val previous=saved[id]
                if(previous!=null&&previous.version==version)unavailable()
                if(previous==null||previous.version<version)saved[id]=Saved(id,version,kind)
            }
        }
        // A legacy pill without its operation record is not evidence of an empty batch.
        if(operations==0||saved.size!=expected||ChatMemoryPills.savedCount(db,batch)!=expected)return MemoryPillDetails.Unavailable
        val items=saved.values.map { write->
            val owners=db.rawQuery("SELECT o.batch_id,o.json FROM memory_operations o JOIN memory_batches b ON b.id=o.batch_id WHERE o.result_id=? AND o.result_version=? AND o.saved=1 AND b.state='COMMITTED'",arrayOf(write.id,write.version.toString())).use{c->
                buildList{while(c.moveToNext())if(JSONObject(c.getString(1)).getString("op") in setOf("ADD","UPDATE"))add(c.getString(0))}
            }
            if(owners!=listOf(batch))unavailable()
            val current=db.rawQuery("SELECT friend_id,version,json FROM memory_current WHERE id=?",arrayOf(write.id)).use{c->
                if(!c.moveToFirst()||c.getString(0)!=friend||c.getInt(1)<write.version)unavailable()
                JSONObject(c.getString(2)).also{j->if(j.getString("id")!=write.id||j.getString("character")!=friend||version(j)!=c.getInt(1))unavailable()}
            }
            val updated=write.kind=="UPDATE"||write.version>1
            if(deleted(current))BatchMemoryDetail(write.id,write.version,null,updated)
            else {
                // Never read historical content for a currently deleted memory.
                val historical=db.rawQuery("SELECT json FROM memory_history WHERE memory_id=? AND version=?",arrayOf(write.id,write.version.toString())).use{c->if(!c.moveToFirst())unavailable();JSONObject(c.getString(0))}
                if(historical.getString("id")!=write.id||historical.getString("character")!=friend||version(historical)!=write.version||deleted(historical))unavailable()
                verifyLaterVersions(db,friend,write,version(current))
                val text=historical.opt("text") as? String?:unavailable();if(text.isBlank())unavailable()
                BatchMemoryDetail(write.id,write.version,text,updated)
            }
        }
        return MemoryPillDetails.Available(items)
    }
    /** A lost final write must not masquerade as an earlier snapshot with the same deduped count. */
    private fun verifyLaterVersions(db:SQLiteDatabase,friend:String,write:Saved,currentVersion:Int) {
        var next=write.version+1
        db.rawQuery("SELECT version,json FROM memory_history WHERE memory_id=? AND version>? ORDER BY version",arrayOf(write.id,write.version.toString())).use{c->
            while(c.moveToNext()) {
                val version=c.getInt(0);val value=JSONObject(c.getString(1))
                if(version!=next++||version>currentVersion||version(value)!=version||value.getString("id")!=write.id||value.getString("character")!=friend||deleted(value))unavailable()
                val manual=if(!value.has("manual"))false else value.get("manual") as? Boolean?:unavailable()
                val recorded=db.rawQuery("SELECT o.json FROM memory_operations o JOIN memory_batches b ON b.id=o.batch_id WHERE o.result_id=? AND o.result_version=? AND o.saved=1 AND b.friend_id=? AND b.state='COMMITTED'",arrayOf(write.id,version.toString(),friend)).use{rows->
                    var found=false
                    while(rows.moveToNext())if(JSONObject(rows.getString(0)).getString("op") in setOf("ADD","UPDATE","CANCEL","COMPLETE","RETRACT"))found=true
                    found
                }
                // Manual edits are explicitly external to a model batch; other untracked versions
                // cannot prove whether this batch lost its last operation, so fail closed.
                if(!manual&&!recorded)unavailable()
            }
        }
        if(next!=currentVersion+1)unavailable()
    }
}

internal suspend fun ProcessingRepository.memoryPillDetails(friend:String,batch:String):MemoryPillDetails =
    read{MemoryPillDetailReader.read(it,friend,batch)}
