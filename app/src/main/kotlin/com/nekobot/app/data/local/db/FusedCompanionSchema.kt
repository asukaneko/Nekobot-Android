package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Added in v52 → v53: additive, no rewriting of old sessions or their memories. */
internal object FusedCompanionSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE local_sessions ADD COLUMN companion_mode INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE companion_memories ADD COLUMN category TEXT NOT NULL DEFAULT 'user_profile'")
        db.execSQL("ALTER TABLE companion_memories ADD COLUMN alwaysProvide INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE companion_memories ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_owners (characterId TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_companion_owners_sessionId ON companion_owners(sessionId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_context_materials (scopeId TEXT NOT NULL, messageId TEXT NOT NULL, sequence INTEGER NOT NULL, revision INTEGER NOT NULL, configuration TEXT NOT NULL, startMessageId TEXT NOT NULL, content TEXT NOT NULL, timeKey TEXT NOT NULL, createdAt INTEGER NOT NULL, retrieval TEXT NOT NULL, userPayload TEXT NOT NULL, PRIMARY KEY(scopeId,messageId))")
        installTriggers(db)
    }

    fun installTriggers(db: SupportSQLiteDatabase) {
        // Tests and earlier migrations may install the legacy triggers before the companion columns exist.
        if (!db.query("PRAGMA table_info(local_sessions)").use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "companion_mode") found = true
            found
        }) return
        val checks = """
            SELECT CASE WHEN NEW.companion_mode = 1 AND
              (NEW.character_id IS NULL OR NEW.character_id = '' OR lower(NEW.session_mode) != 'agent'
               OR NEW.inherit_character != 1 OR NEW.long_conversation_enabled != 1)
              THEN RAISE(ABORT, '长久陪伴必须绑定角色并保留 Agent 角色模式') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM companion_owners o WHERE
              (o.characterId = NEW.character_id OR instr(COALESCE(NEW.character_ids,''), '"' || o.characterId || '"') > 0)
              AND (o.sessionId != NEW.id OR NEW.companion_mode != 1))
              THEN RAISE(ABORT, '该角色已有长久陪伴主窗口，不能创建第二个窗口或直接转换模式') END;
            SELECT CASE WHEN NEW.companion_mode = 1 AND EXISTS(SELECT 1 FROM local_sessions s WHERE s.id != NEW.id
              AND (s.character_id = NEW.character_id OR instr(COALESCE(s.character_ids,''), '"' || NEW.character_id || '"') > 0))
              THEN RAISE(ABORT, '长久陪伴只接受全新角色；已有角色须备份后由外部 Agent 转换') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM local_sessions s WHERE s.id = NEW.id AND
              (s.companion_mode != NEW.companion_mode OR
               (s.companion_mode = 1 AND s.character_id IS NOT NEW.character_id)))
              THEN RAISE(ABORT, '不能直接切换长久陪伴模式或更换绑定角色') END;
        """.trimIndent()
        for (action in listOf("INSERT", "UPDATE")) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS fused_session_guard_${action.lowercase()} BEFORE $action ON local_sessions BEGIN $checks END")
            // An outer UPDATE OR ABORT (portable merge) overrides OR IGNORE inside triggers.
            // Avoid attempting the duplicate insert at all; also refresh pre-fix installations.
            db.execSQL("DROP TRIGGER IF EXISTS fused_session_owner_${action.lowercase()}")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS fused_session_owner_${action.lowercase()} AFTER $action ON local_sessions
              WHEN NEW.companion_mode = 1 BEGIN
                INSERT INTO companion_owners(characterId,sessionId)
                  SELECT NEW.character_id,NEW.id WHERE NOT EXISTS
                    (SELECT 1 FROM companion_owners WHERE characterId=NEW.character_id AND sessionId=NEW.id);
              END""")
        }
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS fused_owner_guard_insert BEFORE INSERT ON companion_owners
            WHEN EXISTS(SELECT 1 FROM companion_owners WHERE
              (characterId = NEW.characterId AND sessionId != NEW.sessionId) OR
              (sessionId = NEW.sessionId AND characterId != NEW.characterId))
            BEGIN SELECT RAISE(ABORT, '导入的长久陪伴归属与本机已有窗口冲突'); END""")
        // 归属只对仍存在的窗口有意义：窗口被删除（含导入/恢复流程）后必须一并释放，
        // 否则角色页会读到悬空归属而打不开，该角色也无法再建窗口。
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS fused_session_owner_deleted AFTER DELETE ON local_sessions
            BEGIN DELETE FROM companion_owners WHERE sessionId = OLD.id; END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS fused_material_scope_deleted AFTER DELETE ON companion_scopes
            BEGIN DELETE FROM companion_context_materials WHERE scopeId = OLD.id; END""")
    }
}
