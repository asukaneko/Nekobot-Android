package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase

object ProcessingSchema {
    fun create(db:SQLiteDatabase){
        val columns=db.rawQuery("PRAGMA table_info(chat_turns)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
        if("source_revision" !in columns)db.execSQL("ALTER TABLE chat_turns ADD COLUMN source_revision INTEGER NOT NULL DEFAULT 1")
        if("source_zone" !in columns)db.execSQL("ALTER TABLE chat_turns ADD COLUMN source_zone TEXT NOT NULL DEFAULT 'UTC'")
        listOf(
            "CREATE TABLE IF NOT EXISTS role_assets(friend_id TEXT NOT NULL REFERENCES friends(id),path TEXT NOT NULL,kind TEXT NOT NULL,mime TEXT NOT NULL,data BLOB NOT NULL,PRIMARY KEY(friend_id,path))",
            "CREATE TABLE IF NOT EXISTS processing_policies(friend_id TEXT PRIMARY KEY REFERENCES friends(id),json TEXT NOT NULL,version INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS message_state(message_id INTEGER PRIMARY KEY REFERENCES messages(id),revision INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS message_history(message_id INTEGER NOT NULL,revision INTEGER NOT NULL,body TEXT NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(message_id,revision))",
            "CREATE TABLE IF NOT EXISTS memory_current(id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id),json TEXT NOT NULL,version INTEGER NOT NULL,valid INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS memory_by_friend ON memory_current(friend_id,valid)",
            "CREATE TABLE IF NOT EXISTS memory_history(memory_id TEXT NOT NULL,version INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(memory_id,version))",
            "CREATE TABLE IF NOT EXISTS memory_candidates(id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id),target_id TEXT,json TEXT NOT NULL,resolved INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS candidate_injections(candidate_id TEXT NOT NULL,user_message_id INTEGER NOT NULL,friend_id TEXT NOT NULL,PRIMARY KEY(candidate_id,user_message_id))",
            "CREATE TABLE IF NOT EXISTS memory_batches(id TEXT PRIMARY KEY,friend_id TEXT NOT NULL REFERENCES friends(id),json TEXT NOT NULL,state TEXT NOT NULL,attempt INTEGER NOT NULL DEFAULT 0,error TEXT,next_retry INTEGER NOT NULL DEFAULT 0,owner_session TEXT,config_stamp TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS one_running_memory_per_friend ON memory_batches(friend_id) WHERE state='RUNNING'",
            "CREATE TABLE IF NOT EXISTS memory_operations(batch_id TEXT NOT NULL,op_key TEXT NOT NULL,json TEXT NOT NULL,digest TEXT NOT NULL,result_id TEXT,result_version INTEGER,saved INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(batch_id,op_key))",
            "CREATE TABLE IF NOT EXISTS memory_batch_reviews(batch_id TEXT NOT NULL,memory_id TEXT NOT NULL,version INTEGER NOT NULL,digest TEXT NOT NULL,decision TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(batch_id,memory_id))",
            "CREATE TABLE IF NOT EXISTS memory_retired(friend_id TEXT NOT NULL,source_user_id INTEGER NOT NULL,source_version INTEGER NOT NULL,batch_id TEXT NOT NULL,PRIMARY KEY(friend_id,source_user_id,source_version))",
            "CREATE TABLE IF NOT EXISTS memory_progress(friend_id TEXT PRIMARY KEY REFERENCES friends(id),cursor INTEGER NOT NULL DEFAULT 0)"
        ).forEach{db.execSQL(it)}
    }
}
