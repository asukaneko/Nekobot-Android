package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.ZoneId

internal data class RelationSent(val source:SourceRef,val at:Long)
internal object RelationshipProactive {
    fun next(p:RelationParameters,lastContact:Long?,sent:List<RelationSent>,now:Long,dayStart:Long,
        forecast:((Long)->RelationState)?=null):Long? {
        if(lastContact==null||p.dailyMax<=0)return null
        val unanswered=sent.filter{it.at>lastContact}.sortedBy{it.at}
        if(unanswered.size>=1+p.resendCount.coerceIn(0,3))return null
        fun crossed(time:Long):Boolean {
            val state=forecast?.invoke(time)?:RelationCursor(RelationStart.NEW,p).also{cursor->
                cursor.contact(lastContact);sent.filter{it.at>=lastContact&&it.at<=time}.sortedBy{it.at}.forEach{cursor.reachOut(it.at)};cursor.advance(time)
            }.state()
            return state.missing>=RelationDecay.theta(RelationDecay.thetaBase(p.silenceDays),state.tension)
        }
        var lo=now;var hi=now+14*RelationshipProjector.DAY
        if(!crossed(hi))return null
        if(crossed(now))hi=now else while(hi-lo>60_000){val mid=lo+(hi-lo)/2;if(crossed(mid))hi=mid else lo=mid}
        var due=hi
        if(unanswered.isNotEmpty())due=maxOf(due,unanswered.last().at+(p.resendDays*RelationshipProjector.DAY).toLong())
        if(sent.count{it.at>=dayStart&&it.at<=now}>=p.dailyMax.coerceIn(0,5))due=maxOf(due,dayStart+RelationshipProjector.DAY)
        return due
    }
}
internal fun CompanionRepository.relationshipSent(db:SQLiteDatabase,role:String)=db.rawQuery("SELECT m.id,m.created FROM companion_receipts r JOIN messages m ON m.id=r.message_id LEFT JOIN message_state s ON s.message_id=m.id WHERE r.role=? AND r.opportunity_id IS NOT NULL AND COALESCE(s.deleted,0)=0 ORDER BY m.created",arrayOf(role)).use{c->buildList{while(c.moveToNext())add(RelationSent(SourceRef(role,c.getLong(0),1),c.getLong(1)))}}
internal fun relationshipDayStart(now:Long)=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
/** Compatibility cleanup only: scores no longer create or reschedule contact plans. */
internal suspend fun CompanionRepository.refreshRelationshipContacts()=ProcessingRepository(store).tx{db->
    db.execSQL("UPDATE companion_opportunities SET state='cancelled',attempt=attempt+1,owner='' WHERE id LIKE 'relationship:%' AND state IN ('queued','running')")
}