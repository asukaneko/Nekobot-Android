package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.ZoneId

internal data class RelationSent(val source:SourceRef,val at:Long)
internal fun CompanionRepository.relationshipSent(db:SQLiteDatabase,role:String)=db.rawQuery("SELECT m.id,m.created FROM companion_receipts r JOIN messages m ON m.id=r.message_id LEFT JOIN message_state s ON s.message_id=m.id WHERE r.role=? AND r.opportunity_id IS NOT NULL AND COALESCE(s.deleted,0)=0 ORDER BY m.created",arrayOf(role)).use{c->buildList{while(c.moveToNext())add(RelationSent(SourceRef(role,c.getLong(0),1),c.getLong(1)))}}
internal fun relationshipDayStart(now:Long)=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
/** Compatibility cleanup only: scores no longer create or reschedule contact plans. */
internal suspend fun CompanionRepository.refreshRelationshipContacts()=ProcessingRepository(store).tx{db->
    db.execSQL("UPDATE companion_opportunities SET state='cancelled',attempt=attempt+1,owner='' WHERE id LIKE 'relationship:%' AND state IN ('queued','running')")
}
