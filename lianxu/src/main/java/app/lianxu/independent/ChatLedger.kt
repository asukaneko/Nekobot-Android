package app.lianxu.independent

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.security.MessageDigest

enum class TurnState { PENDING,COMPLETED,FAILED,CANCELLED,INTERRUPTED,SUPERSEDED }
data class ChatTurn(val id:String,val friendId:String,val userMessageId:Long,val assistantMessageId:Long?,val state:TurnState,val attempt:Int,val configId:String,val failure:ChatFailure?,val toolActivities:List<ChatToolActivity> = emptyList(),val failureDetail:String="",val replyLayout:String?=null,val failureMeta:ChatFailureMeta?=null)

/** Local exactly-once persistence, not a promise of remote provider billing idempotency. */
class ChatLedger(private val store:Store,private val session:String=processSession) {
    companion object {
        private val processSession=UUID.randomUUID().toString()
        /** The provider's own words for a failed turn (keys already masked). Local only: never backed up or archived. */
        const val ERROR_PREFIX="chat-error:"
        /** Structured failure stage/model metadata kept separate from raw HTTP provider bodies. Local only. */
        const val ERROR_META_PREFIX="chat-error:meta:"
        fun errorKey(turn:String)=ERROR_PREFIX+turn
        fun errorMetaKey(turn:String)=ERROR_META_PREFIX+turn
        fun createSchema(db:SQLiteDatabase){
            db.execSQL("CREATE TABLE chat_turns(id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id),user_message_id INTEGER NOT NULL UNIQUE REFERENCES messages(id),assistant_message_id INTEGER UNIQUE REFERENCES messages(id),state TEXT NOT NULL,attempt INTEGER NOT NULL,config_id TEXT NOT NULL,error_code TEXT,owner_session TEXT NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX one_pending_chat_per_friend ON chat_turns(friend_id) WHERE state='PENDING'")
        }
    }
    private fun read(c:Cursor)=ChatTurn(c.getString(0),c.getString(1),c.getLong(2),if(c.isNull(3))null else c.getLong(3),TurnState.valueOf(c.getString(4)),c.getInt(5),c.getString(6),if(c.isNull(7))null else ChatFailure.valueOf(c.getString(7)))
    private fun turn(db:SQLiteDatabase,id:String)=db.rawQuery("SELECT id,friend_id,user_message_id,assistant_message_id,state,attempt,config_id,error_code FROM chat_turns WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())read(it) else null}
    private fun <T> atomic(block:(SQLiteDatabase)->T):T = AppMutationGate.read {val db=store.writableDatabase;db.beginTransaction();try{val value=block(db);db.setTransactionSuccessful();value}finally{db.endTransaction()}}
    // A reload can land inside the turn's own write transaction. Retry a transient lock instead of
    // failing the chat page (and, through changed(), the turn that is still writing).
    suspend fun all():List<ChatTurn> = withContext(Dispatchers.IO){
        var locked:android.database.sqlite.SQLiteDatabaseLockedException?=null
        repeat(4){attempt->
            try{return@withContext queryAll()}
            catch(e:android.database.sqlite.SQLiteDatabaseLockedException){locked=e;Thread.sleep(20L*(attempt+1))}
        }
        throw locked!!
    }
    private fun deleteError(db:SQLiteDatabase,id:String){
        db.delete("preferences","key IN (?,?)",arrayOf(errorKey(id),errorMetaKey(id)))
    }
    private fun queryAll():List<ChatTurn> {
        val details=mutableMapOf<String,String>()
        val metas=mutableMapOf<String,ChatFailureMeta>()
        store.readableDatabase.rawQuery("SELECT key,value FROM preferences WHERE key LIKE ?",arrayOf("$ERROR_PREFIX%")).use{c->while(c.moveToNext()){
            val key=c.getString(0);val value=c.getString(1)
            if(key.startsWith(ERROR_META_PREFIX))ChatFailureMeta.decode(value)?.let{metas[key.removePrefix(ERROR_META_PREFIX)]=it}
            else details[key.removePrefix(ERROR_PREFIX)]=value
        }}
        return store.readableDatabase.rawQuery("SELECT id,friend_id,user_message_id,assistant_message_id,state,attempt,config_id,error_code,p.value AS reply_layout FROM chat_turns LEFT JOIN preferences p ON p.key='${OrderedChatReply.PREFIX}'||chat_turns.id ORDER BY user_message_id",null).use{c->buildList{while(c.moveToNext()){val t=read(c);add(t.copy(replyLayout=c.getString(c.getColumnIndexOrThrow("reply_layout")),toolActivities=ChatToolJournal.read(store.readableDatabase,t.id).map{if(t.state!=TurnState.PENDING&&it.state=="running")it.copy(state="unknown")else it},failureDetail=if(t.state==TurnState.FAILED)details[t.id].orEmpty() else "",failureMeta=if(t.state==TurnState.FAILED)metas[t.id] else null))}}}
    }
    internal suspend fun activities(id:String)=withContext(Dispatchers.IO){ChatToolJournal.read(store.readableDatabase,id)}
    internal suspend fun recordActivity(id:String,attempt:Int,item:ChatToolActivity)=withContext(Dispatchers.IO){atomic{db->
        val t=turn(db,id)?:return@atomic false
        if(t.attempt!=attempt||t.state!=TurnState.PENDING)return@atomic false
        ChatToolJournal.put(db,id,item);true
    }}
    internal suspend fun settleActivities(id:String,attempt:Int,committed:Boolean,recordSaved:Boolean,memorySaved:Boolean)=withContext(Dispatchers.IO){atomic{db->ChatToolJournal.settle(db,id,attempt,committed,recordSaved,memorySaved)}}
    suspend fun recoverInterrupted()=withContext(Dispatchers.IO){atomic{db->
        db.update("chat_turns",ContentValues().apply{put("state",TurnState.INTERRUPTED.name);put("error_code",ChatFailure.INTERRUPTED.name)},
            "state='PENDING' AND owner_session<>? AND NOT EXISTS(SELECT 1 FROM received_chat_replies r WHERE r.turn_id=chat_turns.id AND r.attempt=chat_turns.attempt)",arrayOf(session))
    }}
    internal suspend fun saveReceived(t:ChatTurn,reply:ReceivedChatReply,lease:ChatAttemptLease,current:()->Boolean)=withContext(Dispatchers.IO){
        val payload=com.google.gson.Gson().toJson(reply)
        atomic{db->lease.guard{
            val owned=db.rawQuery("SELECT 1 FROM chat_turns WHERE id=? AND attempt=? AND owner_session=? AND state='PENDING'",arrayOf(t.id,t.attempt.toString(),session)).use{it.moveToFirst()}
            if(!owned||!current()||snapshot(db,t.id).stamp!=reply.contextStamp)return@guard false
            db.insertOrThrow("received_chat_replies",null,ContentValues().apply{
                put("turn_id",t.id);put("attempt",t.attempt);put("payload",payload);put("companion_epoch",reply.companion.epoch)
            })
            true
        }}
    }
    internal fun hasReceived(id:String,attempt:Int)=store.readableDatabase.rawQuery(
        "SELECT 1 FROM received_chat_replies WHERE turn_id=? AND attempt=?",arrayOf(id,attempt.toString())
    ).use{it.moveToFirst()}
    /** Only the UI's local delivery runner claims receipts. Background workers leave them alone. */
    internal suspend fun claimReceived():List<Pair<ChatTurn,ReceivedChatReply>> = withContext(Dispatchers.IO){atomic{db->
        val results=mutableListOf<Pair<ChatTurn,ReceivedChatReply>>()
        db.rawQuery("""SELECT t.id,r.payload,r.companion_epoch FROM received_chat_replies r JOIN chat_turns t ON t.id=r.turn_id AND t.attempt=r.attempt
            WHERE t.state='PENDING' AND t.owner_session<>?""",arrayOf(session)).use{c->while(c.moveToNext()){
            val t=requireNotNull(turn(db,c.getString(0)))
            val result=com.google.gson.Gson().fromJson(c.getString(1),ReceivedChatReply::class.java)
            results+=t to result.copy(companion=result.companion.copy(epoch=c.getLong(2)))
        }}
        for((t,_) in results)db.update("chat_turns",ContentValues().apply{put("owner_session",session)},"id=? AND attempt=?",arrayOf(t.id,t.attempt.toString()))
        results
    }}
    suspend fun begin(friendId:String,input:String,configId:String,id:String=UUID.randomUUID().toString(),expectedEpoch:Long?=null,image:PendingChatImage?=null):ChatTurn = withContext(Dispatchers.IO){atomic{db->
        check(expectedEpoch==null||expectedEpoch==AppMutationGate.epoch())
        require((input.isNotBlank()||image!=null)&&input.length<=2000)
        turn(db,id)?.let{old->require(old.friendId==friendId);require(ChatImages.ref(db,old.userMessageId)?.hash==image?.hash);require(db.rawQuery("SELECT body FROM messages WHERE id=?",arrayOf(old.userMessageId.toString())).use{it.moveToFirst()&&it.getString(0)==input});return@atomic old}
        check(!db.rawQuery("SELECT 1 FROM chat_turns WHERE friend_id=? AND state='PENDING'",arrayOf(friendId)).use{it.moveToFirst()})
        // Once a new input follows, the old failed/recovered turn is immutable history, not retryable.
        // Its real user text and optional spoken prefix must then be eligible for memory compaction.
        db.execSQL("UPDATE chat_turns SET state='SUPERSEDED' WHERE friend_id=? AND user_message_id=(SELECT MAX(user_message_id) FROM chat_turns WHERE friend_id=?) AND state IN ('FAILED','CANCELLED','INTERRUPTED')",arrayOf(friendId,friendId))
        val message=db.insertOrThrow("messages",null,ContentValues().apply{put("friend_id",friendId);put("body",input);put("mine",1);put("created",System.currentTimeMillis())})
        image?.let{ChatImages.put(db,friendId,message,it)}
        db.insertOrThrow("chat_turns",null,ContentValues().apply{put("id",id);put("friend_id",friendId);put("user_message_id",message);put("state",TurnState.PENDING.name);put("attempt",1);put("config_id",configId);put("owner_session",session);put("source_zone",java.time.ZoneId.systemDefault().id)})
        // Accepting this input and invalidating old plans is one transaction. A Worker
        // already waiting on its model can no longer commit or requeue its old claim.
        CompanionRepository.bump(db,friendId)
        ReplyAccountingQueue.enqueue(db,id)
        turn(db,id)!!
    }}
    suspend fun retry(id:String,configId:String,expectedEpoch:Long?=null):ChatTurn?=withContext(Dispatchers.IO){atomic{db->
        if(expectedEpoch!=null&&expectedEpoch!=AppMutationGate.epoch())return@atomic null
        val old=turn(db,id)?:return@atomic null
        if(old.state !in setOf(TurnState.FAILED,TurnState.CANCELLED,TurnState.INTERRUPTED))return@atomic null
        val newer=db.rawQuery("SELECT 1 FROM chat_turns WHERE friend_id=? AND user_message_id>?",arrayOf(old.friendId,old.userMessageId.toString())).use{it.moveToFirst()}
        if(newer)return@atomic null
        db.update("chat_turns",ContentValues().apply{put("state",TurnState.PENDING.name);put("attempt",old.attempt+1);put("config_id",configId);putNull("error_code");put("owner_session",session)},"id=?",arrayOf(id))
        deleteError(db,id)
        ReplyAccountingQueue.enqueue(db,id)
        turn(db,id)
    }}
    suspend fun finish(id:String,attempt:Int,text:String,lease:ChatAttemptLease?=null,expectedStamp:String?=null,configUnchanged:()->Boolean={true}):Boolean=
        finishWithMemory(id,attempt,text,lease,expectedStamp,{},configUnchanged)
    internal suspend fun finishWithMemory(id:String,attempt:Int,text:String,lease:ChatAttemptLease?=null,expectedStamp:String?=null,onCompleted:(SQLiteDatabase)->Unit,configUnchanged:()->Boolean={true}):Boolean=
        finishWithMemory(id,attempt,text,lease,expectedStamp,onCompleted,"",configUnchanged)
    /** Kept separate from the old overload so existing positional/trailing callbacks remain valid. */
    internal suspend fun finishWithMemory(id:String,attempt:Int,text:String,lease:ChatAttemptLease?=null,expectedStamp:String?=null,onCompleted:(SQLiteDatabase)->Unit,displayReasoning:String,configUnchanged:()->Boolean={true}):Boolean=withContext(Dispatchers.IO){
        val write={atomic{db->
        val current=turn(db,id)?:return@atomic false
        if(current.state!=TurnState.PENDING||current.attempt!=attempt)return@atomic false
        if(!db.rawQuery("SELECT 1 FROM chat_turns WHERE id=? AND owner_session=?",arrayOf(id,session)).use{it.moveToFirst()})return@atomic false
        if(expectedStamp!=null&&snapshot(db,id).stamp!=expectedStamp)return@atomic false
        if(!configUnchanged())return@atomic false
        require(text.isNotBlank());require(displayReasoning.length<=256*1024)
        val message=requireNotNull(saveSpoken(db,current,text))
        if(displayReasoning.isNotEmpty())db.insertOrThrow("message_reasoning",null,ContentValues().apply{put("message_id",message);put("display_reasoning",displayReasoning)})
        db.update("chat_turns",ContentValues().apply{put("state",TurnState.COMPLETED.name);put("assistant_message_id",message);putNull("error_code")},"id=?",arrayOf(id))
        deleteError(db,id);onCompleted(db);true
    }}
        // Repository/tool writes already own the mutation gate before checking the attempt.
        AppMutationGate.read{if(lease==null)write() else lease.commit(write)}
    }
    suspend fun fail(id:String,attempt:Int,error:ChatFailure,detail:String="",meta:ChatFailureMeta?=null)=transition(id,attempt,TurnState.FAILED,error,detail,meta)
    suspend fun cancel(id:String,attempt:Int)=transition(id,attempt,TurnState.CANCELLED,null)
    internal suspend fun completedMessageId(expected:ChatTurn):Long? = withContext(Dispatchers.IO) {
        turn(store.readableDatabase, expected.id)?.takeIf {
            it.state == TurnState.COMPLETED && it.attempt == expected.attempt && it.friendId == expected.friendId
        }?.assistantMessageId
    }
    internal suspend fun spoken(id:String):String=withContext(Dispatchers.IO){
        store.readableDatabase.rawQuery("SELECT m.body FROM chat_turns t JOIN messages m ON m.id=t.assistant_message_id WHERE t.id=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0)else ""}
    }
    /** A UI acknowledgement is saved independently of network success and accounting. */
    internal suspend fun recordSpoken(id:String,attempt:Int,text:String,expectedEpoch:Long,configUnchanged:()->Boolean={true},lease:ChatAttemptLease?=null)=withContext(Dispatchers.IO){
        // Same order as completion and repository/tool writes: mutation gate, then attempt.
        // Neither side can hold the attempt while waiting for the other side's mutation gate.
        val write={atomic{db->
        if(expectedEpoch!=AppMutationGate.epoch()||!configUnchanged())return@atomic false
        val t=turn(db,id)?:return@atomic false
        if(t.attempt!=attempt||t.state !in setOf(TurnState.PENDING,TurnState.FAILED,TurnState.CANCELLED,TurnState.INTERRUPTED))return@atomic false
        if(!db.rawQuery("SELECT 1 FROM chat_turns WHERE id=? AND owner_session=?",arrayOf(id,session)).use{it.moveToFirst()})return@atomic false
        saveSpoken(db,t,text);true
        }}
        AppMutationGate.read{if(lease==null)write()else lease.guard(write)}
    }
    private fun saveSpoken(db:SQLiteDatabase,t:ChatTurn,text:String):Long? {
        val id=t.assistantMessageId
        if(id!=null){
            val old=db.rawQuery("SELECT body FROM messages WHERE id=?",arrayOf(id.toString())).use{check(it.moveToFirst());it.getString(0)}
            val merged=SpokenReply.merge(old,text)
            if(merged!=old)db.update("messages",ContentValues().apply{put("body",merged)},"id=?",arrayOf(id.toString()))
            return id
        }
        if(text.isBlank())return null
        val message=db.insertOrThrow("messages",null,ContentValues().apply{put("friend_id",t.friendId);put("body",text);put("mine",0);put("created",System.currentTimeMillis())})
        db.update("chat_turns",ContentValues().apply{put("assistant_message_id",message)},"id=?",arrayOf(t.id))
        return message
    }
    /** End a user-stopped reply without inventing an assistant message for its unseen tail. */
    internal suspend fun interrupt(id:String,attempt:Int,spoken:String)=withContext(Dispatchers.IO){atomic{db->
        val t=turn(db,id)?:return@atomic false
        if(t.attempt!=attempt||t.state !in setOf(TurnState.PENDING,TurnState.CANCELLED,TurnState.FAILED))return@atomic false
        if(!db.rawQuery("SELECT 1 FROM chat_turns WHERE id=? AND owner_session=?",arrayOf(id,session)).use{it.moveToFirst()})return@atomic false
        val message=saveSpoken(db,t,spoken)
        db.update("chat_turns",ContentValues().apply{
            put("state",TurnState.SUPERSEDED.name);putNull("error_code")
            if(message==null)putNull("assistant_message_id")else put("assistant_message_id",message)
        },"id=?",arrayOf(id))
        deleteError(db,id);true
    }}
    private suspend fun transition(id:String,attempt:Int,state:TurnState,error:ChatFailure?,detail:String="",meta:ChatFailureMeta?=null)=withContext(Dispatchers.IO){
        // Plain transaction, as before outside the mutation gate: status and its detail land together.
        val db=store.writableDatabase
        db.beginTransaction()
        val changed=try{
            val moved=db.update("chat_turns",ContentValues().apply{put("state",state.name);if(error==null)putNull("error_code") else put("error_code",error.name)},"id=? AND attempt=? AND state='PENDING' AND owner_session=?",arrayOf(id,attempt.toString(),session))==1
            if(moved){
                deleteError(db,id)
                if(detail.isNotBlank())db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",errorKey(id));put("value",detail.take(HttpsTextChatClient.DETAIL_LIMIT))},SQLiteDatabase.CONFLICT_REPLACE)
                if(meta!=null&&meta.hasAny())db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",errorMetaKey(id));put("value",meta.encode())},SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful();moved
        }finally{db.endTransaction()}
        // The turn's ending and why is the first thing to check when a chat does not complete.
        if(changed)Diagnostics.event("chat.transition",mapOf("turn" to id,"attempt" to attempt,"state" to state.name,"reason" to error?.name))
        changed
    }
    data class Context(val friend:Friend,val recent:List<Message>,val input:String,val rawPrompts:String,val stamp:String,val persona:UserPersona=UserPersona(),val openingEligible:Boolean=false,val image:ChatImageRef?=null)
    private fun snapshot(db:SQLiteDatabase,id:String):Context {
        val t=turn(db,id)?:error("Missing turn")
        val friend=db.rawQuery("SELECT id,name,bio,prompt,tone FROM friends WHERE id=?",arrayOf(t.friendId)).use{c->check(c.moveToFirst());Friend(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4))}
        val recent=completed(db,t.friendId)
        val input=db.rawQuery("SELECT body FROM messages WHERE id=?",arrayOf(t.userMessageId.toString())).use{c->check(c.moveToFirst());c.getString(0)}
        val prompts=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(t.friendId))).use{c->if(c.moveToFirst())c.getString(0) else ""}
        val persona=store.persona(db)
        val openingEligible=!db.rawQuery("SELECT 1 FROM chat_turns WHERE friend_id=? AND state IN ('COMPLETED','SUPERSEDED') LIMIT 1",arrayOf(t.friendId)).use{it.moveToFirst()}
        val image=ChatImages.ref(db,t.userMessageId)
        val raw=listOf(friend.toString(),recent.joinToString("\u0000"),t.userMessageId.toString(),input,prompts,persona.encode(),openingEligible.toString(),image.toString(),if(image!=null||recent.any{it.image!=null})ChatImages.routeStamp(db)else "").joinToString("\u0001")
        val stamp=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)}
        return Context(friend,recent,input,prompts,stamp,persona,openingEligible,image)
    }
    suspend fun context(id:String):Context=withContext(Dispatchers.IO){atomic{snapshot(it,id)}}
    private fun completed(db:SQLiteDatabase,friendId:String):List<Message> {
        val dialogue=db.rawQuery("SELECT m.id,m.friend_id,m.body,m.mine,m.created,t.user_message_id FROM messages m JOIN chat_turns t ON m.id=t.user_message_id OR m.id=t.assistant_message_id WHERE t.friend_id=? AND t.state IN ('COMPLETED','SUPERSEDED') AND NOT EXISTS(SELECT 1 FROM memory_retired r WHERE r.friend_id=t.friend_id AND r.source_user_id=t.user_message_id AND r.source_version=t.source_revision) AND NOT EXISTS(SELECT 1 FROM message_state ms WHERE ms.message_id IN(t.user_message_id,t.assistant_message_id) AND ms.deleted=1) ORDER BY m.id",arrayOf(friendId)).use{c->buildList{while(c.moveToNext())if(CompanionRepository.aiAllowed(db,friendId,c.getLong(5)))add(Message(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getLong(4),image=ChatImages.ref(db,c.getLong(0))))}}
        val proactive=db.rawQuery("SELECT m.id,m.friend_id,m.body,m.mine,m.created,o.source_id,o.revision FROM messages m JOIN companion_receipts r ON r.message_id=m.id JOIN companion_opportunities o ON o.id=r.opportunity_id WHERE m.friend_id=? ORDER BY m.id",arrayOf(friendId)).use{c->buildList{while(c.moveToNext()){
            val source=SourceRef(friendId,c.getLong(5),c.getLong(6))
            if(CompanionRepository.aiAllowed(db,friendId,c.getLong(0))&&ProcessingRepository(store).sourceValid(db,source))add(Message(c.getLong(0),c.getString(1),c.getString(2),false,c.getLong(4)))
        }}}
        return (dialogue+proactive).sortedBy{it.id}
    }
    /** Real conversation: completed replies and the actually spoken prefix of stopped replies. */
    suspend fun completedMessages(friendId:String):List<Message> = withContext(Dispatchers.IO){completed(store.readableDatabase,friendId)}
}
