package com.nekobot.app.data.local

import com.google.gson.JsonObject
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager

/** Regression for sixteen text repairs of which only seven also introduce source links. */
class PortableMemoryRepairScopeTest {
    @Test fun completeMemoryReviewUpdatesAllCoreTextsWithoutInventingSourcesAndRepeatWritesNothing() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val sql = JdbcPortableImportSql(connection)
            sql.execute("CREATE TABLE local_characters(id TEXT PRIMARY KEY,name TEXT)")
            sql.execute("CREATE TABLE local_sessions(id TEXT PRIMARY KEY,name TEXT,character_id TEXT)")
            sql.execute("CREATE TABLE companion_scopes(id TEXT PRIMARY KEY,sessionId TEXT,characterId TEXT,profile TEXT,baselineRowId INTEGER,archiveRowId INTEGER,revision INTEGER)")
            sql.execute("CREATE TABLE companion_memories(id TEXT PRIMARY KEY,scopeId TEXT,content TEXT,provenance TEXT,manual INTEGER,subject TEXT,alwaysProvide INTEGER)")
            sql.execute("CREATE TABLE companion_sources(memoryId TEXT,messageId TEXT,fingerprint TEXT,quote TEXT,spokenDate TEXT,role TEXT,PRIMARY KEY(memoryId,messageId))")
            sql.execute("CREATE TABLE local_messages(id TEXT PRIMARY KEY,session_id TEXT,role TEXT,content TEXT,timestamp TEXT,created_at TEXT,deleted INTEGER)")
            sql.execute("INSERT INTO local_characters VALUES ('character','Synthetic character')")
            sql.execute("INSERT INTO local_sessions VALUES ('session','Synthetic session','character')")
            sql.execute("INSERT INTO companion_scopes VALUES ('scope','session','character','test',0,0,0)")
            val stamp = "2026-01-01T00:00:00Z"
            val incoming = buildList {
                (0 until 16).forEach { i ->
                    val text = "Synthetic preference $i"
                    sql.execute("INSERT INTO companion_memories VALUES (?,'scope',?,'imported_unverified',0,'user',1)",
                        listOf("memory-$i", "[PINNED] $text"))
                    add(PortableIncomingRow("companion_memories", JsonObject().apply {
                        addProperty("id", "memory-$i"); addProperty("scopeId", "scope")
                        addProperty("content", text); addProperty("subject", "user"); addProperty("alwaysProvide", 1)
                        addProperty("provenance", if (i < 7) "imported_verified" else "imported_unverified")
                    }))
                    if (i < 7) {
                        sql.execute("INSERT INTO local_messages VALUES (?,'session','user',?,?,?,0)", listOf("message-$i", text, stamp, stamp))
                        add(PortableIncomingRow("companion_sources", JsonObject().apply {
                            addProperty("memoryId", "memory-$i"); addProperty("messageId", "message-$i")
                            addProperty("role", "user"); addProperty("quote", text); addProperty("spokenDate", "2026-01-01")
                            addProperty("fingerprint", CompanionMemoryPolicy.fingerprint("message-$i", "user", stamp, stamp, text, false))
                        }))
                    }
                }
            }
            val rows: PortableRowVisitor = { consume -> incoming.forEach(consume) }
            val planner = PortableImportPlanner(sql, "synthetic.db")
            val limited = planner.preview(rows, PortableImportChoices(), 1)
            assertEquals(7, limited.sourceConflictMemoryCount)
            assertEquals(16, limited.sourceReviewKeys.size)
            assertEquals(1, limited.differences.size)
            val complete = planner.preview(rows, PortableImportChoices(), Int.MAX_VALUE)
            var choices = PortableImportChoices()
            complete.differences.filter { it.key in limited.sourceReviewKeys }.forEach {
                choices = choices.withRecordChoice(it, true, complete.sourceFieldChoices[it.memoryId].orEmpty())
            }
            val checked = planner.preview(rows, choices)
            assertTrue(checked.issues.toString(), checked.issues.isEmpty())
            assertEquals(16, checked.tableCounts["companion_memories"]!!.updated)
            assertEquals(23, planner.apply(rows, choices))
            assertEquals(16, sql.query("SELECT * FROM companion_memories WHERE alwaysProvide=1").size)
            assertEquals(0, sql.query("SELECT * FROM companion_memories WHERE content LIKE '[PINNED]%'").size)
            assertEquals(7, sql.query("SELECT * FROM companion_memories WHERE provenance='imported_verified'").size)
            assertEquals(9, sql.query("SELECT * FROM companion_memories WHERE provenance='imported_unverified'").size)
            assertEquals(7, sql.query("SELECT * FROM companion_sources").size)
            assertEquals(0, planner.apply(rows, choices))
        }
    }
}
