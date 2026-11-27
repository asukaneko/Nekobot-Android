package app.lianxu.independent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Own app-private database. Primary key on friend is also the unique conversation key. */
class Store(context: Context, name: String = AppServices.databaseName) : SQLiteOpenHelper(context, name, null, 21) {
    init {ImageDisk.cacheDirectory=context.applicationContext.cacheDir}
    internal val applicationContext=context.applicationContext
    internal val memorySummary=MemorySummaryGuard()
    /**
     * A page reload or a test's poll can open the database while the running turn holds a write
     * transaction; Android has no busy timeout here, so wait briefly instead of throwing at the user.
     */
    override fun getReadableDatabase():SQLiteDatabase{
        var attempt=0
        while(true){
            try{return super.getReadableDatabase()}
            catch(e:android.database.sqlite.SQLiteDatabaseLockedException){if(++attempt>=10)throw e;Thread.sleep(25L*attempt)}
        }
    }
    override fun getWritableDatabase():SQLiteDatabase{
        var attempt=0
        while(true){
            try{return super.getWritableDatabase()}
            catch(e:android.database.sqlite.SQLiteDatabaseLockedException){if(++attempt>=10)throw e;Thread.sleep(25L*attempt)}
        }
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE friends(id TEXT PRIMARY KEY, name TEXT NOT NULL, bio TEXT NOT NULL, prompt TEXT NOT NULL, tone INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, friend_id TEXT NOT NULL REFERENCES friends(id), body TEXT NOT NULL, mine INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX messages_friend ON messages(friend_id,id)")
        db.execSQL("CREATE TABLE preferences(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        ChatLedger.createSchema(db)
        ProcessingSchema.create(db)
        CompanionRepository.create(db)
        ContactFailures.migrate(db)
        dailyColumns(db)
        createReasoningSchema(db)
        DatabaseRelationSnapshots.create(db)
        ChatMemoryPills.createSchema(db)
        MemoryForgetting.migrate(db,AppServices.companionClock())
        ChatImages.create(db)
        ReceivedChatReply.create(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion<2)ChatLedger.createSchema(db);if(oldVersion<5)ProcessingSchema.create(db);if(oldVersion<7)CompanionRepository.create(db);if(oldVersion in 7 until 8)CompanionRepository.create(db);if(oldVersion<9)RetiredRelationshipMigration.remove(db);if(oldVersion<10)createReasoningSchema(db);if(oldVersion<11){
        db.execSQL("ALTER TABLE companion_opportunities RENAME TO relation_old_opportunities")
        CompanionRepository.create(db)
        // Copy only the columns both shapes share, so a development database whose table is newer than its version number still opens.
        val shared=columns(db,"relation_old_opportunities").filter{it in columns(db,"companion_opportunities")}.joinToString(","){"\"$it\""}
        db.execSQL("INSERT INTO companion_opportunities($shared) SELECT $shared FROM relation_old_opportunities")
        db.execSQL("DROP TABLE relation_old_opportunities")
    };if(oldVersion<12)DatabaseRelationSnapshots.create(db);if(oldVersion<13)MemoryForgetting.migrate(db,AppServices.companionClock());if(oldVersion<14)ChatMemoryPills.createSchema(db);if(oldVersion<15){
        // Databases created by an earlier build of this version already have the table but not the column.
        ChatMemoryPills.createSchema(db)
        if(!hasColumn(db,"memory_operations","saved"))db.execSQL("ALTER TABLE memory_operations ADD COLUMN saved INTEGER NOT NULL DEFAULT 0")
    };if(oldVersion<16)ContactFailures.migrate(db);if(oldVersion<17)ChatImages.create(db);if(oldVersion<18)ChatImages.migrateOriginals(db)
        if(oldVersion<19)ReceivedChatReply.create(db)
        if(oldVersion<20){
            db.execSQL("ALTER TABLE companion_opportunities RENAME TO old_contact_schedule")
            CompanionRepository.create(db)
            ContactFailures.migrate(db)
            val shared=columns(db,"old_contact_schedule").filter{it in columns(db,"companion_opportunities")}.joinToString(",")
            db.execSQL("INSERT INTO companion_opportunities($shared) SELECT $shared FROM old_contact_schedule")
            db.execSQL("DROP TABLE old_contact_schedule")
        }
        if(oldVersion<21)dailyColumns(db)
    }
    private fun dailyColumns(db:SQLiteDatabase){
        for(column in listOf("plan_due","plan_user","plan_retry","plan_ran_at"))if(!hasColumn(db,"companion_state",column))db.execSQL("ALTER TABLE companion_state ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
        if(!hasColumn(db,"companion_state","plan_error"))db.execSQL("ALTER TABLE companion_state ADD COLUMN plan_error TEXT NOT NULL DEFAULT ''")
        if(!hasColumn(db,"companion_state","plan_failed_at"))db.execSQL("ALTER TABLE companion_state ADD COLUMN plan_failed_at INTEGER NOT NULL DEFAULT 0")
        if(!hasColumn(db,"companion_opportunities","retry_count"))db.execSQL("ALTER TABLE companion_opportunities ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
    }
    private fun hasColumn(db:SQLiteDatabase,table:String,column:String)=column in columns(db,table)
    private fun columns(db:SQLiteDatabase,table:String)=db.rawQuery("PRAGMA table_info($table)",null).use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
    private fun createReasoningSchema(db:SQLiteDatabase){
        // A display-only child of the reply: never a preference or part of source text/history.
        db.execSQL("CREATE TABLE IF NOT EXISTS message_reasoning(message_id INTEGER PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,display_reasoning TEXT NOT NULL CHECK(length(display_reasoning)<=262144))")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS reasoning_retracted_insert AFTER INSERT ON message_state WHEN NEW.deleted=1 BEGIN DELETE FROM message_reasoning WHERE message_id=NEW.message_id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS reasoning_retracted_update AFTER UPDATE OF deleted ON message_state WHEN NEW.deleted=1 BEGIN DELETE FROM message_reasoning WHERE message_id=NEW.message_id; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS reasoning_edited_message AFTER UPDATE OF body,mine ON messages WHEN NEW.body<>OLD.body OR NEW.mine<>OLD.mine BEGIN DELETE FROM message_reasoning WHERE message_id=NEW.id; END")
    }
    private fun insertFriend(db: SQLiteDatabase, f: Friend): Boolean = db.insertWithOnConflict("friends", null, ContentValues().apply {
        put("id", f.id); put("name", f.name.trim()); put("bio", f.bio.trim()); put("prompt", f.prompt); put("tone", f.tone)
    }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    private fun insertMessage(db: SQLiteDatabase, id: String, text: String, mine: Boolean) {
        db.insertOrThrow("messages", null, ContentValues().apply { put("friend_id", id); put("body", text); put("mine", if (mine) 1 else 0); put("created", System.currentTimeMillis()) })
    }
    private val messageSelect="SELECT m.id,m.friend_id,CASE WHEN COALESCE(s.deleted,0)=1 THEN '【已撤回】' ELSE m.body END,m.mine,m.created,CASE WHEN m.mine=0 AND COALESCE(s.deleted,0)=0 THEN COALESCE(r.display_reasoning,'') ELSE '' END,COALESCE(s.deleted,0) FROM messages m LEFT JOIN message_state s ON s.message_id=m.id LEFT JOIN message_reasoning r ON r.message_id=m.id"
    private fun readMessages(extra:String,args:Array<String>?=null):List<Message> =
        readableDatabase.rawQuery("$messageSelect $extra",args).use { c -> buildList { while (c.moveToNext()) add(Message(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getLong(4),c.getString(5),if(c.getInt(6)==1)null else ChatImages.ref(readableDatabase,c.getLong(0)))) } }
    suspend fun friends(): List<Friend> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT id,name,bio,prompt,tone FROM friends ORDER BY rowid", null).use { c -> buildList { while (c.moveToNext()) add(Friend(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4))) } }
    }
    suspend fun messages(): List<Message> = withContext(Dispatchers.IO) { readMessages("ORDER BY m.id") }
    suspend fun messagesFor(friendId:String):List<Message> = withContext(Dispatchers.IO) { readMessages("WHERE m.friend_id=? ORDER BY m.id",arrayOf(friendId)) }
    suspend fun messageFingerprints():Map<String,MessageFingerprint> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT m.friend_id,count(*),max(m.id),sum(COALESCE(s.deleted,0)),sum(COALESCE(s.revision,0)),sum(CASE WHEN m.mine=0 AND COALESCE(s.deleted,0)=0 THEN length(COALESCE(r.display_reasoning,'')) ELSE 0 END),sum(length(m.body)) FROM messages m LEFT JOIN message_state s ON s.message_id=m.id LEFT JOIN message_reasoning r ON r.message_id=m.id GROUP BY m.friend_id",null).use{c->buildMap{while(c.moveToNext())put(c.getString(0),MessageFingerprint(c.getString(0),c.getInt(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getLong(5),c.getLong(6)))}}
    }
    suspend fun add(friends: List<Friend>, demoGreeting:Boolean=true) = withContext(Dispatchers.IO) {
        require(friends.all { it.id.isNotBlank() && it.name.isNotBlank() && it.bio.isNotBlank() })
        val db = writableDatabase; db.beginTransaction()
        try { friends.forEach { if (insertFriend(db,it)) { if(demoGreeting) insertMessage(db,it.id,"【演示开场】你好，我是${it.name}。很高兴认识你。",false) } }; db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    suspend fun exchange(friend: Friend, input: String, reply: String) = withContext(Dispatchers.IO) {
        val db=writableDatabase; db.beginTransaction()
        try { insertMessage(db,friend.id,input,true); insertMessage(db,friend.id,reply,false); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    suspend fun preference(key: String, default: String): String = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else default }
    }
    suspend fun savePreference(key: String, value: String) = withContext(Dispatchers.IO) { AppMutationGate.read{
        writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)
    } }
    internal suspend fun updateRolePrompts(id:String,change:(RolePrompts)->RolePrompts)=withContext(Dispatchers.IO){AppMutationGate.read{
        LocalStep.ROLE_PROMPTS.observe(result = LocalResult::insert) {
            val db=writableDatabase;db.beginTransaction()
            try{
                require(db.rawQuery("SELECT 1 FROM friends WHERE id=?",arrayOf(id)).use{it.moveToFirst()}){"角色已不存在，请返回列表。"}
                val key=RolePromptStore.key(id)
                val old=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())RolePromptStore.decode(it.getString(0)) else RolePrompts()}
                val row=db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",key);put("value",RolePromptStore.encode(change(old)))},SQLiteDatabase.CONFLICT_REPLACE)
                db.setTransactionSuccessful()
                row
            }finally{db.endTransaction()}
        }
        Unit
    }}
    internal fun persona(db:SQLiteDatabase):UserPersona {
        fun value(key:String)=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0)else""}
        return UserPersona.decode(value(UserPersona.KEY),value("nickname"))
    }
    suspend fun persona()=withContext(Dispatchers.IO){AppMutationGate.read{persona(readableDatabase)}}
    suspend fun savePersona(value:UserPersona)=withContext(Dispatchers.IO){AppMutationGate.read{
        LocalStep.PROFILE.observe(result = { it }) {
            val p=value.normalized();val db=writableDatabase;db.beginTransaction()
            try{
                val profile=db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",UserPersona.KEY);put("value",p.encode())},SQLiteDatabase.CONFLICT_REPLACE)
                val nickname=if(p.name.isBlank()){db.delete("preferences","key=?",arrayOf("nickname"));true}else db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key","nickname");put("value",p.name)},SQLiteDatabase.CONFLICT_REPLACE) != -1L
                db.setTransactionSuccessful()
                if(profile == -1L || !nickname) LocalResult.WRITE_REJECTED else LocalResult.COMPLETED
            }finally{db.endTransaction()}
        }
        Unit
    }}
    internal fun seedOpening(db:SQLiteDatabase,id:String,opening:String){
        if(opening.isNotBlank()&&!db.rawQuery("SELECT 1 FROM messages WHERE friend_id=? LIMIT 1",arrayOf(id)).use{it.moveToFirst()}){
            val name=db.rawQuery("SELECT name FROM friends WHERE id=?",arrayOf(id)).use{check(it.moveToFirst());it.getString(0)}
            insertMessage(db,id,UserPersona.expand(opening,name,persona(db)),false)
        }
    }
    internal suspend fun addConfigured(friend:Friend,prompts:RolePrompts)=withContext(Dispatchers.IO){AppMutationGate.read{
        require(friend.name.isNotBlank()&&friend.name.length<=30&&prompts.characterDefinition.isNotBlank()&&prompts.characterDefinition.length<=8000&&prompts.expression.length<=8000&&prompts.opening.length<=8000)
        val db=writableDatabase;db.beginTransaction()
        try{
            check(insertFriend(db,friend))
            db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",RolePromptStore.key(friend.id));put("value",RolePromptStore.encode(prompts))},SQLiteDatabase.CONFLICT_REPLACE)
            seedOpening(db,friend.id,prompts.opening);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }}
    internal suspend fun saveConfigured(id:String,name:String,change:(RolePrompts)->RolePrompts)=withContext(Dispatchers.IO){AppMutationGate.read{
        require(name.isNotBlank()&&name.trim().length<=30)
        val db=writableDatabase;db.beginTransaction()
        try{
            val raw=db.rawQuery("SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(id))).use{if(it.moveToFirst())it.getString(0)else""}
            val p=change(RolePromptStore.decode(raw));require(p.characterDefinition.isNotBlank()&&p.initialRelationship.length<=4000&&listOf(p.characterDefinition,p.expression,p.opening,p.extra,p.thinkingPrompt).all{it.length<=8000})
            check(db.update("friends",ContentValues().apply{put("name",name.trim())},"id=?",arrayOf(id))==1)
            db.insertWithOnConflict("preferences",null,ContentValues().apply{put("key",RolePromptStore.key(id));put("value",RolePromptStore.encode(p))},SQLiteDatabase.CONFLICT_REPLACE)
            seedOpening(db,id,p.opening);db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }}
    suspend fun setPreference(key: String, value: String) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict("preferences",null,ContentValues().apply { put("key",key); put("value",value) },SQLiteDatabase.CONFLICT_REPLACE)
    }
}

data class MessageFingerprint(val friendId:String,val count:Int,val maxId:Long,val deletedSum:Long,val revisionSum:Long,val reasoningChars:Long,val bodyChars:Long)
