package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** 升级和便携导入共用同一清理范围；普通会话不会进入这里的目标集合。 */
internal object CompanionMemoryReplacementSql {
    fun prune(db: SupportSQLiteDatabase, scopeId: String? = null) {
        val scopeFilter = if (scopeId == null) "" else " AND old.scopeId=?"
        val replaced = "SELECT old.id FROM companion_memories old JOIN companion_scopes s ON s.id=old.scopeId JOIN local_sessions session ON session.id=s.sessionId WHERE session.companion_mode=1 AND old.locked=0 AND old.status NOT IN ('conflict','locked_conflict')$scopeFilter AND ((old.status='retracted' AND old.valid=1) OR EXISTS (SELECT 1 FROM companion_memories next WHERE next.scopeId=old.scopeId AND next.factKey=old.factKey AND next.version>old.version AND next.valid=1 AND ((next.current=1 AND next.status IN ('active','completed','cancelled')) OR next.status='retracted')))"
        for (sql in listOf(
            "DELETE FROM companion_sources WHERE memoryId IN ($replaced)",
            "DELETE FROM companion_vectors WHERE itemType IN ('memory','fact') AND itemId IN ($replaced)",
            "DELETE FROM companion_memories WHERE id IN ($replaced)"
        )) {
            if (scopeId == null) db.execSQL(sql) else db.execSQL(sql, arrayOf(scopeId))
        }
    }
}
