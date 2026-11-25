package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase

/** The result of a completed tool loop, separate from the already displayed transcript. */
internal data class ReceivedChatReply(
    val bubbles:List<String>,
    val reasoning:String,
    val contextStamp:String,
    val referencesStamp:String,
    val configStamp:String,
    val companion:CompanionSnapshot,
    val images:ReceivedImageEffects,
    val memory:ReceivedMemoryEffects,
    val recordAccepted:Boolean,
    val replyLayout:String?=null,
    val voiceTarget:VoiceTarget?=null
) {
    companion object {
        fun create(db:SQLiteDatabase){
            db.execSQL("""CREATE TABLE IF NOT EXISTS received_chat_replies(
                turn_id TEXT NOT NULL REFERENCES chat_turns(id) ON DELETE CASCADE,
                attempt INTEGER NOT NULL, companion_epoch INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(turn_id,attempt))""")
            // Covers stop, failure, retry and completion regardless of which repository changed the row.
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS discard_received_reply AFTER UPDATE OF state,attempt,source_revision ON chat_turns
                WHEN NEW.state!='PENDING' OR NEW.attempt!=OLD.attempt OR NEW.source_revision!=OLD.source_revision
                BEGIN DELETE FROM received_chat_replies WHERE turn_id=OLD.id; END""")
        }
    }
}

internal data class ReceivedMemoryEffects(val recalls:Map<String,RecallSource>, val at:Long,val rulesStamp:String)
internal data class ReceivedObservation(val ref:ChatImageRef,val revision:Long,val model:String,val text:String)
internal data class ReceivedImageEffects(
    val sensitive:Boolean,val route:String,val revisions:Map<Long,Long>,val used:List<ChatImageRef>,
    val observations:List<ReceivedObservation>,val auxiliaryConfig:String?
)
