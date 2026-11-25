package app.lianxu.independent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

enum class SourceMemoryChoice { KEEP, INVALIDATE }

data class SourceImpact(val dialogue:SourceDialogue,val side:String,val text:String,val retract:Boolean,val memories:List<StoredMemory>,val candidates:List<ProcessingRepository.Candidate>,val stamp:String,val choice:SourceMemoryChoice=SourceMemoryChoice.KEEP)
data class SourceVersion(val revision:Long,val body:String,val deleted:Boolean)

/** Preview and confirmation use the same source+affected-memory stamp; cancellation never writes. */
class SourceChanges(private val repo:ProcessingRepository){
    private fun preview(db:SQLiteDatabase,friendId:String,turnId:Long,side:String,text:String,retract:Boolean):SourceImpact {
        require(side in setOf("user","assistant"));require(retract||text.isNotBlank()&&text.length<=8000)
        val source=repo.sources(db,friendId).firstOrNull{it.source.turnId==turnId}?:throw ProcessingRejected("SOURCE_CHANGED")
        rejectUnless(side=="user"||source.assistant!=null,"SOURCE_CHANGED")
        val affected=repo.memories(db,friendId).filter{m->m.evidence.any{(it.source.turnId==turnId&&it.source.characterId==friendId)||(it.image?.imageSource?.turnId==turnId&&it.image?.imageSource?.characterId==friendId)}}
        val candidates=repo.candidates(db,friendId).filter{c->c.proposed.evidence.any{(it.source.turnId==turnId&&it.source.characterId==friendId)||(it.image?.imageSource?.turnId==turnId&&it.image?.imageSource?.characterId==friendId)}}
        val stamp=stableDigest(source.json().toString()+affected.joinToString{it.json().toString()}+candidates.joinToString{it.toString()})
        return SourceImpact(source,side,text,retract,affected,candidates,stamp)
    }
    suspend fun preview(friendId:String,turnId:Long,side:String,text:String,retract:Boolean)=repo.read{preview(it,friendId,turnId,side,text,retract)}
    suspend fun confirm(impact:SourceImpact)=repo.tx{db->
        repo.store.memorySummary.requireEditable(impact.dialogue.source.characterId)
        val old=impact.dialogue;val check=preview(db,old.source.characterId,old.source.turnId,impact.side,impact.text,impact.retract)
        rejectUnless(check.stamp==impact.stamp,"SOURCE_CHANGED")
        val message=if(impact.side=="user")old.user else requireNotNull(old.assistant)
        val state=db.rawQuery("SELECT revision,deleted FROM message_state WHERE message_id=?",arrayOf(message.id.toString())).use{if(it.moveToFirst())it.getLong(0) to it.getInt(1) else 1L to 0}
        db.insertWithOnConflict("message_history",null,ContentValues().apply{put("message_id",message.id);put("revision",state.first);put("body",message.text);put("deleted",state.second)},SQLiteDatabase.CONFLICT_IGNORE)
        val revision=state.first+1;val body=if(impact.retract)message.text else impact.text
        db.update("messages",ContentValues().apply{put("body",body)},"id=? AND friend_id=?",arrayOf(message.id.toString(),old.source.characterId))
        db.insertWithOnConflict("message_state",null,ContentValues().apply{put("message_id",message.id);put("revision",revision);put("deleted",if(impact.retract)1 else 0)},SQLiteDatabase.CONFLICT_REPLACE)
        db.insertOrThrow("message_history",null,ContentValues().apply{put("message_id",message.id);put("revision",revision);put("body",body);put("deleted",if(impact.retract)1 else 0)})
        db.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE id=?",arrayOf(old.requestId))
        CompanionRepository.bump(db,old.source.characterId)
        db.execSQL("DELETE FROM companion_vectors WHERE role=? AND source_id=?",arrayOf<Any>(old.source.characterId,old.source.turnId))
        impact.candidates.forEach{repo.invalidateCandidate(db,old.source.characterId,it.id)}
        impact.memories.forEach{m->
            val life=if(impact.choice==SourceMemoryChoice.INVALIDATE&&!m.manual&&!m.locked)MemoryLife.RETRACTED else m.life
            // Evidence may already be invalid after KEEP; lifecycle changes still need a version.
            if(m.valid||life!=m.life)repo.saveMemory(db,m.copy(version=m.version+1,valid=false,life=life))
            if(m.locked||m.manual)repo.candidate(db,m.friendId,m.id,m,"来源已修改或撤回，请人工核对；保留原话快照。","source-${message.id}-$revision-${m.id}")
        }
        CompanionRepository(repo.store).relationship(db,old.source.characterId);Unit
    }
    suspend fun history(friendId:String,messageId:Long)=repo.read{db->
        rejectUnless(db.rawQuery("SELECT 1 FROM messages WHERE id=? AND friend_id=?",arrayOf(messageId.toString(),friendId)).use{it.moveToFirst()},"SOURCE_CHANGED")
        db.rawQuery("SELECT revision,body,deleted FROM message_history WHERE message_id=? ORDER BY revision DESC",arrayOf(messageId.toString())).use{c->buildList{while(c.moveToNext())add(SourceVersion(c.getLong(0),c.getString(1),c.getInt(2)==1))}}
    }
}
