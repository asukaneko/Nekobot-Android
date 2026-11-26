package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

internal data class QuietHours(val enabled:Boolean=true,val start:Int=1380,val end:Int=480) {
    init { require(start in 0..1439 && end in 0..1439 && start!=end) }
    fun contains(at:Long,zone:ZoneId):Boolean {
        val local=Instant.ofEpochMilli(at).atZone(zone)
        val minute=local.hour*60+local.minute
        return enabled && (if(start<end) minute in start until end else minute>=start || minute<end)
    }
    fun nextMorning(at:Long,zone:ZoneId):Long {
        val local=Instant.ofEpochMilli(at).atZone(zone)
        val minute=if(enabled)end else 0
        val today=local.toLocalDate().atTime(minute/60,minute%60)
        return (if(today.atZone(zone).toInstant().toEpochMilli()>at)today else today.plusDays(1)).atZone(zone).toInstant().toEpochMilli()
    }
    fun encode()="${if(enabled)1 else 0},$start,$end"
    companion object {
        const val KEY="proactive-quiet-hours"
        fun decode(raw:String):QuietHours {
            val fields=raw.split(',')
            require(fields.size==3 && fields[0] in setOf("0","1"))
            return QuietHours(fields[0]=="1",fields[1].toInt(),fields[2].toInt())
        }
        fun read(db:SQLiteDatabase)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(KEY)).use{if(it.moveToFirst())decode(it.getString(0)) else QuietHours()}
    }
}
internal data class DailyPoint(val at:Long,val reason:String)
internal data class ContactPace(val lastUser:Long,val streak:Int,val today:Int){
    fun skip(at:Long)=streak>=3 || today>=2 || lastUser>0 && at-lastUser<900_000
    fun facts(at:Long)="上次用户发言："+(if(lastUser>0)ProactiveMaterial.ago(at-lastUser)+"前" else "尚无记录")+"。此后连续主动发出 $streak 条，其中今天 $today 条。"
    companion object {
        fun read(db:SQLiteDatabase,role:String,at:Long):ContactPace {
            val last=db.rawQuery("""SELECT m.id,m.created FROM messages m LEFT JOIN message_state s ON s.message_id=m.id
                WHERE m.friend_id=? AND m.mine=1 AND COALESCE(s.deleted,0)=0 ORDER BY m.id DESC LIMIT 1""",arrayOf(role)).use{if(it.moveToFirst())it.getLong(0) to it.getLong(1) else 0L to 0L}
            val day=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return db.rawQuery("""SELECT COUNT(*),COALESCE(SUM(CASE WHEN m.created>=? THEN 1 ELSE 0 END),0)
                FROM companion_receipts r JOIN messages m ON m.id=r.message_id LEFT JOIN message_state s ON s.message_id=m.id
                WHERE r.role=? AND r.opportunity_id IS NOT NULL AND m.id>? AND COALESCE(s.deleted,0)=0""",arrayOf(day.toString(),role,last.first.toString())).use{it.moveToFirst();ContactPace(last.second,it.getInt(0),it.getInt(1))}
        }
    }
}
internal object DailyAgenda {
    fun nextDue(scheduled: Long, newConversation: Boolean, lastUserAt: Long, lastPlanAt: Long): Long {
        if (!newConversation || scheduled == 0L) return scheduled
        val idleAt = lastUserAt + 30 * 60_000L
        val cooldownEnds = lastPlanAt + 4 * 3_600_000L
        return minOf(scheduled, maxOf(idleAt, cooldownEnds))
    }

    fun parse(raw:String,at:Long,quiet:QuietHours,zone:ZoneId=ZoneId.systemDefault(),jitter:()->Int={Random.nextInt(-15,16)}):List<DailyPoint>{
        val root=JsonParser.parseString(raw)
        require(root.isJsonObject && root.asJsonObject.get("points")?.isJsonArray==true){"DAILY_POINTS_REQUIRED"}
        val rows=root.asJsonObject.getAsJsonArray("points")
        return rows.mapNotNull { row->runCatching {
            val item=row.asJsonObject
            val time=ContactTime.parse(item["when"].asString,at,zone)
            val reason=item["reason"].asJsonPrimitive.also{require(it.isString)}.asString.trim()
            fun valid(t:Long)=t>at && t-at<=RelationshipProjector.DAY && !quiet.contains(t,zone)
            require(valid(time) && reason.length in 1..240 && reason.none{it.isISOControl()})
            DailyPoint((time+jitter()*60000L).takeIf(::valid)?:time,reason)
        }.getOrNull() }.distinctBy{it.at/60000}.sortedBy{it.at}.take(5)
    }
}
