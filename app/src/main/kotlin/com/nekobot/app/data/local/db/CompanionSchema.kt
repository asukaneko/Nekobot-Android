package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive migration only: old messages, archives, source maps and manual edits are untouched. */
internal object CompanionSchema {
    fun createSearch(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_vectors (scopeId TEXT NOT NULL, itemType TEXT NOT NULL, itemId TEXT NOT NULL, modelKey TEXT NOT NULL, fingerprint TEXT NOT NULL, vectorJson TEXT NOT NULL, updatedAt TEXT NOT NULL, PRIMARY KEY(scopeId,itemType,itemId,modelKey))")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_clarifications (scopeId TEXT NOT NULL, factKey TEXT NOT NULL, memoryId TEXT NOT NULL, question TEXT NOT NULL, questionMessageId TEXT, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, PRIMARY KEY(scopeId,factKey))")
        db.execSQL("DROP TRIGGER IF EXISTS companion_session_deleted")
        installTriggers(db)
    }
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_experience_archives ADD COLUMN character_id TEXT")
        db.execSQL("UPDATE local_experience_archives SET character_id = (SELECT character_id FROM local_sessions WHERE local_sessions.id = local_experience_archives.session_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_scopes (id TEXT NOT NULL PRIMARY KEY, profile TEXT NOT NULL, sessionId TEXT NOT NULL, characterId TEXT NOT NULL, baselineRowId INTEGER NOT NULL, archiveRowId INTEGER NOT NULL, archiveMessageId TEXT, historyAccess INTEGER NOT NULL, revision INTEGER NOT NULL, error TEXT, updatedAt TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_companion_scopes_sessionId ON companion_scopes(sessionId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_memories (id TEXT NOT NULL PRIMARY KEY, scopeId TEXT NOT NULL, factKey TEXT NOT NULL, version INTEGER NOT NULL, kind TEXT NOT NULL, subject TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, eventDate TEXT, annualMonthDay TEXT, tagsJson TEXT NOT NULL, current INTEGER NOT NULL, valid INTEGER NOT NULL, locked INTEGER NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_companion_memories_scopeId ON companion_memories(scopeId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_companion_memories_scopeId_factKey_version ON companion_memories(scopeId,factKey,version)")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_sources (memoryId TEXT NOT NULL, messageId TEXT NOT NULL, fingerprint TEXT NOT NULL, quote TEXT NOT NULL, spokenDate TEXT, role TEXT NOT NULL, PRIMARY KEY(memoryId,messageId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_companion_sources_messageId ON companion_sources(messageId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_receipts (scopeId TEXT NOT NULL, messageId TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(scopeId,messageId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_companion_receipts_messageId ON companion_receipts(messageId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_forgotten_sources (scopeId TEXT NOT NULL, messageId TEXT NOT NULL, PRIMARY KEY(scopeId,messageId))")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_pending (scopeId TEXT NOT NULL, messageId TEXT NOT NULL, PRIMARY KEY(scopeId,messageId))")
        installTriggers(db)
    }

    fun installTriggers(db: SupportSQLiteDatabase) {
        val hasV2 = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='companion_applied_ops'").use { it.moveToFirst() }
        if (hasV2) {
            val updated = db.query("SELECT sql FROM sqlite_master WHERE type='trigger' AND name='companion_message_changed'")
                .use { it.moveToFirst() && it.getString(0).contains("companion_mode") }
            if (!updated) db.execSQL("DROP TRIGGER IF EXISTS companion_message_changed")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_v2_message_snapshot BEFORE UPDATE OF content,deleted ON local_messages
                WHEN (OLD.content IS NOT NEW.content OR OLD.deleted IS NOT NEW.deleted)
                  AND EXISTS(SELECT 1 FROM local_sessions WHERE id=OLD.session_id AND companion_mode=1)
                BEGIN UPDATE companion_sources SET messageSnapshot=COALESCE(messageSnapshot,OLD.content) WHERE messageId=OLD.id;
                  UPDATE companion_scopes SET revision=revision+1 WHERE sessionId=OLD.session_id; END""")
        }
        // The core tables are created before the search tables.
        val hasSearch = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='companion_vectors'").use { it.moveToFirst() }
        if (hasSearch) {
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_fact_search_invalidated
                AFTER UPDATE OF valid, content, subject, kind, tagsJson, eventDate ON companion_memories
                BEGIN DELETE FROM companion_vectors WHERE itemType = 'fact' AND itemId = NEW.id;
                  DELETE FROM companion_clarifications WHERE memoryId = NEW.id AND NEW.valid = 0; END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_fact_search_deleted AFTER DELETE ON companion_memories
                BEGIN DELETE FROM companion_vectors WHERE itemType = 'fact' AND itemId = OLD.id;
                  DELETE FROM companion_clarifications WHERE memoryId = OLD.id; END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_archive_search_changed
                AFTER UPDATE OF summary, tags_json, status, character_id ON local_experience_archives
                BEGIN DELETE FROM companion_vectors WHERE itemType = 'archive' AND itemId = NEW.id; END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_archive_search_deleted AFTER DELETE ON local_experience_archives
                BEGIN DELETE FROM companion_vectors WHERE itemType = 'archive' AND itemId = OLD.id; END""")
        }
        fun invalidate(id: String) = """
            INSERT OR IGNORE INTO companion_pending(scopeId,messageId)
              SELECT scopeId, $id FROM companion_receipts WHERE messageId = $id;
            UPDATE companion_memories SET valid = 0, current = 0
              WHERE id IN (SELECT memoryId FROM companion_sources WHERE messageId = $id);
            UPDATE local_experience_archives SET status = 'stale'
              WHERE id IN (SELECT archive_id FROM local_experience_sources WHERE message_id = $id);
            DELETE FROM companion_receipts WHERE messageId = $id;
            UPDATE companion_scopes SET revision = revision + 1
              WHERE sessionId IN (SELECT session_id FROM local_messages WHERE id = $id)
                 OR id IN (SELECT m.scopeId FROM companion_memories m JOIN companion_sources x ON x.memoryId = m.id WHERE x.messageId = $id)
                 OR id IN (SELECT p.scopeId FROM companion_pending p WHERE p.messageId = $id);
        """.trimIndent()
        // Timestamp changes matter even when the original text is unchanged.
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_message_changed
            AFTER UPDATE OF content, deleted, created_at, timestamp, role, session_id ON local_messages
            WHEN (OLD.content IS NOT NEW.content OR OLD.deleted != NEW.deleted OR OLD.created_at IS NOT NEW.created_at
              OR OLD.timestamp IS NOT NEW.timestamp OR OLD.role IS NOT NEW.role OR OLD.session_id IS NOT NEW.session_id)
              ${if (hasV2) "AND (OLD.created_at IS NOT NEW.created_at OR OLD.timestamp IS NOT NEW.timestamp OR OLD.role IS NOT NEW.role OR OLD.session_id IS NOT NEW.session_id OR NOT EXISTS(SELECT 1 FROM local_sessions WHERE id=OLD.session_id AND companion_mode=1))" else ""}
            BEGIN ${invalidate("NEW.id")}
              UPDATE companion_scopes SET revision = revision + 1
                WHERE sessionId = OLD.session_id AND OLD.session_id IS NOT NEW.session_id;
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_message_deleted AFTER DELETE ON local_messages
            BEGIN ${invalidate("OLD.id")}
              DELETE FROM companion_sources WHERE messageId = OLD.id;
              UPDATE companion_scopes SET revision = revision + 1 WHERE sessionId = OLD.session_id;
            END""")
        // Room's existing message DAO uses INSERT OR REPLACE; AFTER INSERT also covers replacement.
        // BEFORE INSERT can still see the old owner, even when SQLite's implicit REPLACE
        // deletion does not fire DELETE triggers. This also protects imported raw-only evidence.
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_message_owner_replacing BEFORE INSERT ON local_messages
            WHEN EXISTS(SELECT 1 FROM local_messages WHERE id = NEW.id)
            BEGIN UPDATE companion_scopes SET revision = revision + 1
              WHERE sessionId IN (SELECT session_id FROM local_messages WHERE id = NEW.id); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_message_replaced AFTER INSERT ON local_messages
            WHEN EXISTS(SELECT 1 FROM companion_sources WHERE messageId = NEW.id)
              OR EXISTS(SELECT 1 FROM companion_receipts WHERE messageId = NEW.id)
            BEGIN ${invalidate("NEW.id")} END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_new_message AFTER INSERT ON local_messages
            WHEN NEW.deleted = 0 AND NEW.role IN ('user','assistant')
            BEGIN INSERT OR IGNORE INTO companion_pending(scopeId,messageId)
              SELECT s.id, NEW.id FROM companion_scopes s JOIN local_sessions c ON c.id = s.sessionId
              WHERE s.sessionId = NEW.session_id AND s.characterId = c.character_id
                AND c.long_conversation_enabled = 1 AND c.inherit_character = 1 AND lower(c.session_mode) = 'agent';
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_session_changed
            AFTER UPDATE OF character_id, long_conversation_enabled, inherit_character, session_mode ON local_sessions
            WHEN OLD.character_id IS NOT NEW.character_id OR OLD.long_conversation_enabled != NEW.long_conversation_enabled
              OR OLD.inherit_character != NEW.inherit_character OR OLD.session_mode IS NOT NEW.session_mode
            BEGIN UPDATE companion_scopes SET revision = revision + 1 WHERE sessionId = NEW.id; END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_session_deleted AFTER DELETE ON local_sessions
            BEGIN
              ${if (hasSearch) "DELETE FROM companion_vectors WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id); DELETE FROM companion_clarifications WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);" else ""}
              DELETE FROM companion_sources WHERE memoryId IN (SELECT id FROM companion_memories WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id));
              DELETE FROM companion_memories WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);
              DELETE FROM companion_receipts WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);
              DELETE FROM companion_forgotten_sources WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);
              DELETE FROM companion_pending WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);
              DELETE FROM companion_scopes WHERE sessionId = OLD.id;
            END""")
    }
}
