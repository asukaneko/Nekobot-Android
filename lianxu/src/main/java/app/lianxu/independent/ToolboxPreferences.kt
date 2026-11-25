package app.lianxu.independent

import org.json.JSONArray

internal object ToolboxPreferences {
    const val ORDER_KEY="toolbox-order-v1"
    const val VIDEO_KEY="video-understanding-mode-v1"
    const val VIDEO_REVISION="video-understanding-revision-v1"
    val defaultOrder=listOf("image","video","memory","contact")
    enum class VideoMode { ORIGINAL, FRAMES }
    fun order(raw:String):List<String> {
        val array=JSONArray(raw)
        val values=(0 until array.length()).map{array.getString(it)}
        if (values.size == defaultOrder.size + 1 && values.toSet() == defaultOrder.toSet() + "voice") {
            return values.filterNot { it == "voice" }
        }
        require(values.size==defaultOrder.size&&values.toSet()==defaultOrder.toSet())
        return values
    }
    fun encodeOrder(values:List<String>):String=JSONArray(values).toString().also{order(it)}
    suspend fun loadOrder(store:Store)=order(store.preference(ORDER_KEY,encodeOrder(defaultOrder)))
    suspend fun saveOrder(store:Store,values:List<String>)=LocalStep.TOOLBOX_ORDER.observe(result = LocalResult::insert) { store.setPreference(ORDER_KEY,encodeOrder(values)) }
    fun videoMode(stored:String)=if(stored.isBlank()) VideoMode.FRAMES else VideoMode.valueOf(stored)
    suspend fun videoMode(store:Store)=videoMode(store.preference(VIDEO_KEY,""))
    fun videoMode(db:android.database.sqlite.SQLiteDatabase)=videoMode(ChatImages.setting(db,VIDEO_KEY))
    suspend fun saveVideoMode(store:Store,mode:VideoMode)=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
        LocalStep.VIDEO_MODE.observe(result = { it }) {
            var result = LocalResult.COMPLETED
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                for ((key, value) in listOf(VIDEO_KEY to mode.name, VIDEO_REVISION to java.util.UUID.randomUUID().toString())) {
                    val values = android.content.ContentValues().apply { put("key",key); put("value",value) }
                    val row = db.insertWithOnConflict("preferences", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                    if (row == -1L) result = LocalResult.WRITE_REJECTED
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            result
        }
        Unit
    }
}
