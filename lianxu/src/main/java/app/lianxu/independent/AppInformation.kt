package app.lianxu.independent

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read actual installed metadata; a failed lookup must not masquerade as a fabricated version. */
internal data class InstalledBuild(val packageName:String,val versionName:String,val versionCode:Long) {
    companion object {
        @Suppress("DEPRECATION")
        fun read(context:Context):InstalledBuild {
            val info=context.packageManager.getPackageInfo(context.packageName,0)
            val code=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
            return InstalledBuild(context.packageName,info.versionName.orEmpty(),code)
        }
    }
}

internal object AppInformation {
    const val AUTHOR="姜小星"
    const val REPOSITORY="https://github.com/Jiang-Xiaoxing/Lianxu"
    const val LICENSE="GPL-3.0-only"
    const val LICENSE_URL="$REPOSITORY/blob/main/LICENSE"
    const val NOTICES_URL="$REPOSITORY/blob/main/THIRD_PARTY_NOTICES.md"
}

/** No UI or automatic acknowledgement: the page will acknowledge only after being shown. */
internal class LocalUpdateRepository(private val store:Store) {
    private fun receipt(db:SQLiteDatabase)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(LocalUpdateReceipt.KEY)).use{LocalUpdateReceipt.decode(if(it.moveToFirst())it.getString(0)else"")}
    suspend fun pending(installedCode:Long)=withContext(Dispatchers.IO){AppMutationGate.read{LocalReleaseNotes.pending(installedCode,receipt(store.readableDatabase))}}
    suspend fun acknowledge(installedCode:Long)=withContext(Dispatchers.IO){AppMutationGate.read{
        require(LocalReleaseNotes.forVersion(installedCode)!=null){"此安装版本没有随包更新说明，未写入阅读记录"}
        val db=store.writableDatabase;db.beginTransaction()
        try{
            val next=receipt(db).acknowledge(installedCode)
            db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",LocalUpdateReceipt.KEY);put("value",next.encode())},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }}
}
