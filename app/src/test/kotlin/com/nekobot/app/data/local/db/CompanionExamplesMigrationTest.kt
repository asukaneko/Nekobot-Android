package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/** 真 SQLite 执行生产迁移/清理/单次领取 SQL，无 APK、无网络。 */
class CompanionExamplesMigrationTest {
    private fun prepare(c: Connection) {
        c.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("CREATE TABLE local_sessions(id TEXT PRIMARY KEY, companion_mode INTEGER)")
            s.execute("CREATE TABLE companion_scopes(id TEXT PRIMARY KEY, sessionId TEXT)")
            s.execute("CREATE TABLE companion_memories(id TEXT PRIMARY KEY,scopeId TEXT,factKey TEXT,version INTEGER,current INTEGER,status TEXT,valid INTEGER,locked INTEGER)")
            s.execute("CREATE TABLE companion_sources(memoryId TEXT,messageId TEXT)")
            s.execute("CREATE TABLE companion_vectors(scopeId TEXT,itemType TEXT,itemId TEXT)")
            s.execute("INSERT INTO local_sessions VALUES('new',1),('old',1),('ordinary',0),('unrelated',1)")
            s.execute("INSERT INTO companion_scopes VALUES('s','old'),('o','ordinary'),('u','unrelated')")
        }
    }
    private fun bridge(c: Connection): SupportSQLiteDatabase = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
        require(method.name == "execSQL")
        c.prepareStatement(args!![0] as String).use { s ->
            (args.getOrNull(1) as? Array<*>)?.forEachIndexed { i, value -> s.setObject(i + 1, value) }
            s.execute()
        }
        null
    } as SupportSQLiteDatabase
    private fun count(c: Connection, sql: String): Int = c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } }

    @Test fun upgradeGivesNoOldConvertedOrImportedSessionInvitation() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            prepare(c)
            NekobotDatabase.MIGRATION_60_61.migrate(bridge(c))
            assertEquals(0, count(c, "SELECT COUNT(*) FROM companion_examples"))
        }
    }
    @Test fun claimPersistsAcrossReopenAndCanOnlySucceedOnce() {
        val file = File.createTempFile("companion-examples-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                prepare(c); CompanionExamplesSchema.create(bridge(c))
                c.createStatement().use { it.execute("INSERT INTO companion_examples VALUES('new','offered','[]',NULL,0)") }
                c.prepareStatement(CompanionExamplesSql.CLAIM).use { s ->
                    s.setString(1, "new"); assertEquals(1, s.executeUpdate()); assertEquals(0, s.executeUpdate())
                }
            }
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                c.prepareStatement(CompanionExamplesSql.CLAIM).use { s -> s.setString(1, "new"); assertEquals(0, s.executeUpdate()) }
                assertEquals(1, count(c, "SELECT COUNT(*) FROM companion_examples WHERE state='selecting' AND optedIn=1"))
            }
        } finally { file.delete() }
    }
    @Test fun migrationDeletesReplacedRowsAndSourcesButPreservesUnrelatedAndProtectedRows() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            prepare(c)
            c.createStatement().use { s ->
                s.execute("INSERT INTO companion_memories VALUES('old','s','job',1,0,'active',1,0),('new','s','job',2,1,'active',1,0),('unrelated','s','hobby',1,1,'active',1,0),('protected','s','job',0,0,'active',1,1),('question','s','job',3,0,'conflict',1,0),('ordinary-old','o','job',1,0,'active',1,0),('ordinary-new','o','job',2,1,'active',1,0)")
                s.execute("INSERT INTO companion_sources VALUES('old','m1'),('unrelated','m2')")
                s.execute("INSERT INTO companion_vectors VALUES('s','fact','old'),('s','fact','unrelated')")
            }
            NekobotDatabase.MIGRATION_60_61.migrate(bridge(c))
            assertEquals(0, count(c, "SELECT COUNT(*) FROM companion_memories WHERE id='old'"))
            assertEquals(0, count(c, "SELECT COUNT(*) FROM companion_sources WHERE memoryId='old'"))
            assertEquals(0, count(c, "SELECT COUNT(*) FROM companion_vectors WHERE itemId='old'"))
            assertEquals(6, count(c, "SELECT COUNT(*) FROM companion_memories"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM companion_sources WHERE memoryId='unrelated'"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM companion_vectors WHERE itemId='unrelated'"))
        }
    }
    @Test fun importPrunesOnlyTheRestoredScope() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            prepare(c)
            c.createStatement().use { it.execute("INSERT INTO companion_memories VALUES('old-s','s','job',1,0,'active',1,0),('new-s','s','job',2,1,'active',1,0),('old-u','u','job',1,0,'active',1,0),('new-u','u','job',2,1,'active',1,0)") }
            CompanionMemoryReplacementSql.prune(bridge(c), "s")
            assertEquals(0, count(c, "SELECT COUNT(*) FROM companion_memories WHERE id='old-s'"))
            assertEquals(1, count(c, "SELECT COUNT(*) FROM companion_memories WHERE id='old-u'"))
        }
    }
    @Test fun exampleStateIsDeletedWithItsSession() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            prepare(c); CompanionExamplesSchema.create(bridge(c))
            c.createStatement().use { it.execute("INSERT INTO companion_examples VALUES('new','declined','[]',NULL,0)"); it.execute("DELETE FROM local_sessions WHERE id='new'") }
            assertEquals(0, count(c, "SELECT COUNT(*) FROM companion_examples"))
        }
    }
    @Test fun uncertainInvalidOrEqualVersionSuccessorsDoNotDeleteOlderFacts() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            prepare(c)
            c.createStatement().use { it.execute("INSERT INTO companion_memories VALUES('old','s','job',1,1,'active',1,0),('conflict','s','job',2,1,'conflict',1,0),('invalid','s','job',3,1,'active',0,0),('same-version','s','job',1,1,'active',1,0)") }
            CompanionMemoryReplacementSql.prune(bridge(c), "s")
            assertEquals(4, count(c, "SELECT COUNT(*) FROM companion_memories"))
        }
    }
    @Test fun invalidRetractionIsNotEvidenceForDeletingHistory() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            prepare(c)
            c.createStatement().use { it.execute("INSERT INTO companion_memories VALUES('old','s','job',1,1,'active',1,0),('invalid','s','job',2,0,'retracted',0,0)") }
            CompanionMemoryReplacementSql.prune(bridge(c), "s")
            assertEquals(2, count(c, "SELECT COUNT(*) FROM companion_memories"))
        }
    }
}
