package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Last explicit connection test for one saved configuration. Non-secret: no key, no fingerprint. */
internal data class ModelTestRecord(val at:Long,val baseUrl:String,val model:String,val ok:Boolean,val reason:String)
/** Most recent real model call of one kind ("总结" or "聊天") and how many of that kind failed in a row. */
internal data class ModelCallRecord(val at:Long,val kind:String,val ok:Boolean,val reason:String,val failures:Int)

internal object ModelStatusText {
    private val format=DateTimeFormatter.ofPattern("MM-dd HH:mm")
    fun time(at:Long,zone:ZoneId=ZoneId.systemDefault()):String=format.format(Instant.ofEpochMilli(at).atZone(zone))
    /** A record for another address or model is not this configuration's result. */
    fun test(record:ModelTestRecord?,config:ModelConfig,zone:ZoneId=ZoneId.systemDefault()):String =
        if(record==null||record.baseUrl!=config.baseUrl||record.model!=config.modelId)"未测试"
        else "上次测试：${time(record.at,zone)} · ${record.model} · "+(if(record.ok)"成功" else "失败：${record.reason}")
    fun calls(record:ModelCallRecord?,zone:ZoneId=ZoneId.systemDefault()):String=when{
        record==null->"最近一次真实调用结果：暂无"
        record.ok->"最近一次真实调用结果：${record.kind}成功（${time(record.at,zone)}）"
        record.failures>1->"最近 ${record.failures} 次${record.kind}失败：${record.reason}"
        else->"最近一次${record.kind}失败：${record.reason}（${time(record.at,zone)}）"
    }
    fun next(previous:ModelCallRecord?,at:Long,kind:String,ok:Boolean,reason:String)=
        ModelCallRecord(at,kind,ok,if(ok)"" else reason,if(ok)0 else if(previous!=null&&!previous.ok&&previous.kind==kind)previous.failures+1 else 1)
}

/** Plain preferences keyed by vault namespace and configuration id; classified as ignorable by backups. */
internal class ModelStatusStore(private val store:Store,private val namespace:String) {
    companion object {const val PREFIX="model-status:";private val lock=Any()}
    private fun testKey(id:String)="$PREFIX$namespace:test:$id"
    private fun callKey(id:String)="$PREFIX$namespace:calls:$id"
    private fun get(db:SQLiteDatabase,key:String)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun put(db:SQLiteDatabase,key:String,value:String){db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    suspend fun test(id:String):ModelTestRecord?=withContext(Dispatchers.IO){testNow(id)}
    internal fun testNow(id:String):ModelTestRecord?=runCatching{get(store.readableDatabase,testKey(id))?.let{v->JSONObject(v).let{ModelTestRecord(it.getLong("at"),it.getString("base"),it.getString("model"),it.getBoolean("ok"),it.optString("reason"))}}}.getOrNull()
    suspend fun saveTest(id:String,record:ModelTestRecord)=withContext(Dispatchers.IO){saveTestNow(id,record)}
    /** Called on IO while the caller holds the configuration mutation gate. */
    internal fun saveTestNow(id:String,record:ModelTestRecord){put(store.writableDatabase,testKey(id),JSONObject().put("at",record.at).put("base",record.baseUrl).put("model",record.model).put("ok",record.ok).put("reason",record.reason.take(160)).toString())}
    suspend fun clearTest(id:String)=withContext(Dispatchers.IO){clearTestNow(id)}
    internal fun clearTestNow(id:String){store.writableDatabase.delete("preferences","key=?",arrayOf(testKey(id)))}
    suspend fun clearAll(id:String)=withContext(Dispatchers.IO){store.writableDatabase.delete("preferences","key IN (?,?)",arrayOf(testKey(id),callKey(id)));Unit}
    private fun entry(j:JSONObject)=ModelCallRecord(j.getLong("at"),j.getString("kind"),j.getBoolean("ok"),j.optString("reason"),j.optInt("failures"))
    private fun json(r:ModelCallRecord)=JSONObject().put("at",r.at).put("kind",r.kind).put("ok",r.ok).put("reason",r.reason.take(160)).put("failures",r.failures)
    suspend fun lastCall(id:String):ModelCallRecord?=withContext(Dispatchers.IO){lastCallNow(id)}
    internal fun lastCallNow(id:String):ModelCallRecord?=runCatching{get(store.readableDatabase,callKey(id))?.let{v->val j=JSONObject(v);entry(j.getJSONObject("entries").getJSONObject(j.getString("last")))}}.getOrNull()
    /** Best effort: a status line must never change a chat or memory result. */
    suspend fun recordCall(id:String,kind:String,ok:Boolean,reason:String,at:Long=System.currentTimeMillis()){
        try{withContext(Dispatchers.IO){synchronized(lock){
            val db=store.writableDatabase
            val root=get(db,callKey(id))?.let{runCatching{JSONObject(it)}.getOrNull()}?:JSONObject().put("entries",JSONObject())
            val entries=root.optJSONObject("entries")?:JSONObject()
            val previous=entries.optJSONObject(kind)?.let{runCatching{entry(it)}.getOrNull()}
            entries.put(kind,json(ModelStatusText.next(previous,at,kind,ok,reason)))
            put(db,callKey(id),root.put("entries",entries).put("last",kind).toString())
        }}}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Diagnostics.failure("model.status.record_failed",e)}
    }
}
