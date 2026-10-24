package com.nekobot.app.data.local

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.sql.DriverManager

class PortableImportLabelsTest {
    private lateinit var sql: JdbcPortableImportSql
    @Before fun setup() {
        sql = JdbcPortableImportSql(DriverManager.getConnection("jdbc:sqlite::memory:"))
        sql.execute("CREATE TABLE local_characters(id TEXT PRIMARY KEY,name TEXT)")
        sql.execute("CREATE TABLE local_sessions(id TEXT PRIMARY KEY,title TEXT,character_id TEXT)")
        sql.execute("CREATE TABLE companion_scopes(id TEXT PRIMARY KEY,sessionId TEXT,characterId TEXT,profile TEXT,baselineRowId INTEGER,archiveRowId INTEGER)")
        sql.execute("CREATE TABLE companion_memories(id TEXT PRIMARY KEY,scopeId TEXT,content TEXT,category TEXT,alwaysProvide INTEGER)")
        sql.execute("INSERT INTO local_characters VALUES('role','软件中改过的角色名')")
        sql.execute("INSERT INTO local_sessions VALUES('session','合成春日会话','role')")
        sql.execute("INSERT INTO companion_scopes VALUES('scope','session','role','synthetic',0,0)")
        sql.execute("INSERT INTO companion_memories VALUES('memory','scope','合成旧内容','user_profile',1)")
    }
    @After fun close() { sql.connection.close() }
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
    @Test fun workspaceResolvesCurrentConversationAndCharacterWithoutWriting() {
        val before = sql.query("SELECT total_changes() AS n").single()["n"].asInt
        val owner = PortableImportLabels(sql).workspace("workspace", "session/合成说明.json")
        assertEquals("合成春日会话", owner.session)
        assertEquals("软件中改过的角色名", owner.character)
        assertEquals(before, sql.query("SELECT total_changes() AS n").single()["n"].asInt)
    }
    @Test fun newConversationCanUseArchiveNamesButExistingNamesWin() {
        val labels = PortableImportLabels(sql, mapOf(
            "local_sessions" to mapOf("new-session" to json("""{"id":"new-session","title":"资料包新会话","character_id":"new-role"}""")),
            "local_characters" to mapOf("new-role" to json("""{"id":"new-role","name":"资料包新角色"}"""), "role" to json("""{"id":"role","name":"旧角色名"}"""))))
        assertEquals(PortableImportOwner("资料包新角色", "资料包新会话"), labels.workspace("workspace", "new-session/file.txt"))
        assertEquals("软件中改过的角色名", labels.workspace("workspace", "session/file.txt").character)
    }
    @Test fun unknownWorkspaceDoesNotGuessAnOwnerAndSharedIsExplicit() {
        val labels = PortableImportLabels(sql)
        assertEquals(PortableImportOwner(), labels.workspace("workspace", "unknown/file.txt"))
        assertEquals(PortableImportOwner(shared = true), labels.workspace("workspace", "shared/file.txt"))
        assertEquals(PortableImportOwner(), labels.workspace("portraits", "session/file.png"))
    }
    @Test fun memoryDifferencesShowReadableContentAndOwnerRatherThanOnlyFactIds() {
        val planner = PortableImportPlanner(sql, "synthetic")
        val rows: PortableRowVisitor = { it(PortableIncomingRow("companion_memories", json("""{"id":"memory","scopeId":"scope","content":"合成新内容"}"""))) }
        val diff = planner.preview(rows, PortableImportChoices()).differences.single()
        assertEquals("合成旧内容", diff.label)
        assertEquals("软件中改过的角色名", diff.owner.character)
        assertEquals("合成春日会话", diff.owner.session)
    }
    @Test fun refreshingPreviewPicksUpLaterOwnerNameEdits() {
        val planner = PortableImportPlanner(sql, "synthetic")
        val rows: PortableRowVisitor = { it(PortableIncomingRow("companion_memories", json("""{"id":"memory","content":"合成新内容"}"""))) }
        assertEquals("软件中改过的角色名", planner.preview(rows, PortableImportChoices()).differences.single().owner.character)
        sql.execute("UPDATE local_characters SET name='随后修改的名称'")
        assertEquals("随后修改的名称", planner.preview(rows, PortableImportChoices()).differences.single().owner.character)
    }
    @Test fun longCommonPrefixStillShowsTheActualDifference() {
        val prefix = "共同的合成段落".repeat(80)
        sql.execute("UPDATE companion_memories SET content=?", listOf(prefix + "软件结尾"))
        val incoming = json("""{"id":"memory"}""").apply { addProperty("content", prefix + "数据包结尾") }
        val rows: PortableRowVisitor = { it(PortableIncomingRow("companion_memories", incoming)) }
        val field = PortableImportPlanner(sql, "synthetic").preview(rows, PortableImportChoices()).differences.single().fields.single()
        assertTrue(field.current.contains("软件结尾")); assertTrue(field.incoming.contains("数据包结尾"))
        assertNotEquals(field.current, field.incoming)
    }
    @Test fun deselectingTheLastFieldReturnsToTheOriginalChoice() {
        val empty = PortableImportChoices()
        assertEquals(empty, empty.copy(fields = mapOf("row" to emptySet())).normalized())
        val chosen = empty.copy(fields = mapOf("row" to setOf("content")))
        assertNotEquals(empty, chosen.normalized())
        assertEquals(empty, chosen.copy(fields = chosen.fields + ("row" to emptySet())).normalized())
    }
}
