package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Disposable cache, deliberately absent from both archive table allowlists. */
internal class DatabaseRelationSnapshots(db:SQLiteDatabase,private val role:String):RelationSnapshotStore {
    // RelationCaches uses weak database keys; its values must not strongly retain those keys.
    private val database=java.lang.ref.WeakReference(db)
    private val db get()=checkNotNull(database.get())
    override fun marker()=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf("relationship-cache:$role")).use{if(it.moveToFirst())it.getString(0)else ""}
    override fun prune(now:Long){
        val all=headers();val keep=RelationSnapshotRetention.positions(all.map{it.position to it.at},now)
        all.filter{it.position !in keep}.forEach{db.delete("relationship_snapshots","role=? AND sequence=?",arrayOf(role,it.sequence.toString()))};head()
    }
    companion object {
        fun create(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS relationship_snapshots(sequence INTEGER PRIMARY KEY AUTOINCREMENT,role TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,position INTEGER NOT NULL,event TEXT NOT NULL,event_at INTEGER NOT NULL,engine INTEGER NOT NULL,base TEXT NOT NULL,prefix TEXT NOT NULL,template TEXT NOT NULL,checksum TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(role,position))")
        }
    }
    override fun headers()=db.rawQuery("SELECT sequence,position,event,event_at,engine,base,prefix,template,checksum FROM relationship_snapshots WHERE role=? ORDER BY position",arrayOf(role)).use{c->buildList{
        while(c.moveToNext())add(RelationSnapshotHeader(c.getLong(0),c.getInt(1),c.getString(2),c.getLong(3),c.getInt(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8)))
    }}
    override fun payload(sequence:Long):String? {
        val args=arrayOf(role,sequence.toString())
        val bytes=db.rawQuery("SELECT length(CAST(payload AS BLOB)) FROM relationship_snapshots WHERE role=? AND sequence=?",args).use{if(it.moveToFirst())it.getLong(0)else return null}
        if(bytes>RelationSnapshotCodec.MAX_BYTES)return null
        return db.rawQuery("SELECT payload FROM relationship_snapshots WHERE role=? AND sequence=?",args).use{if(it.moveToFirst())it.getString(0)else null}
    }
    // A small cache-only head detects even a missing final snapshot, where positions still look dense.
    override fun complete(headers:List<RelationSnapshotHeader>)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf("relationship-cache:$role")).use{
        val expected=if(it.moveToFirst())it.getString(0)else ""
        expected==RelationSnapshotCodec.head(headers)
    }
    private fun head(value:String=RelationSnapshotCodec.head(headers())){db.insertWithOnConflict("preferences",null,ContentValues().apply{
        put("key","relationship-cache:$role");put("value",value)
    },SQLiteDatabase.CONFLICT_REPLACE)}
    override fun truncate(position:Int){db.delete("relationship_snapshots","role=? AND position>?",arrayOf(role,position.toString()));head()}
    override fun append(header:RelationSnapshotHeader,payload:String){
        val sequence=db.insertOrThrow("relationship_snapshots",null,ContentValues().apply{
            put("role",role);put("position",header.position);put("event",header.event);put("event_at",header.at)
            put("engine",header.engine);put("base",header.base);put("prefix",header.prefix);put("template",header.template)
            put("checksum",header.checksum);put("payload",payload)
        })
        val count=db.rawQuery("SELECT COUNT(*) FROM relationship_snapshots WHERE role=?",arrayOf(role)).use{it.moveToFirst();it.getInt(0)}
        head("$count:$sequence:${header.prefix}")
    }
}
