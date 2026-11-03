package com.nekobot.app.data.local

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/** SQL/storage coverage of the actual public Room schema; not a substitute for Android UI checks. */
class PortableImportSchemaCoverageTest {
    @Test fun allPortableTablesRoundTripWithoutRecreatingRecords() {
        val schemaRelative = "schemas/com.nekobot.app.data.local.db.NekobotDatabase/64.json"
        val schemaFile = listOf(File(schemaRelative), File("app/$schemaRelative"),
            File(System.getProperty("nekobot.import.repo", "."), "app/$schemaRelative")).first { it.isFile }
        val database = JsonParser.parseString(schemaFile.readText()).asJsonObject.getAsJsonObject("database")
        val entities = database.getAsJsonArray("entities").associate { it.asJsonObject["tableName"].asString to it.asJsonObject }
        val portableTables = PortableDataCategory.entries.flatMap { it.tables }
        assertEquals(51, portableTables.size)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val sql = JdbcPortableImportSql(connection)
            sql.execute("PRAGMA foreign_keys=ON")
            entities.forEach { (table, entity) ->
                sql.execute(entity["createSql"].asString.replace("\${TABLE_NAME}", table))
                entity.getAsJsonArray("indices")?.forEach { index -> sql.execute(index.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", table)) }
            }
            val fixtures = portableTables.associateWith { table -> JsonObject().apply {
                val entity = entities.getValue(table)
                val primary = entity.getAsJsonObject("primaryKey").getAsJsonArray("columnNames").map { it.asString }.toSet()
                entity.getAsJsonArray("fields").forEach { field ->
                    val obj = field.asJsonObject
                    val name = obj["columnName"].asString
                    val required = obj["notNull"]?.asBoolean == true || name in primary
                    if (!required) add(name, JsonNull.INSTANCE) else when (obj["affinity"].asString) {
                        "INTEGER" -> addProperty(name, 0)
                        "REAL" -> addProperty(name, 0.0)
                        "BLOB" -> add(name, JsonObject().apply { addProperty("__base64_blob__", "") })
                        else -> addProperty(name, "synthetic-$table-$name")
                    }
                }
            } }.toMutableMap()
            // Propagate real FK identities, including keys that are themselves inherited from a parent.
            repeat(4) {
                portableTables.forEach { table -> entities.getValue(table).getAsJsonArray("foreignKeys")?.forEach { raw ->
                    val fk = raw.asJsonObject
                    val parent = fixtures[fk["table"].asString] ?: return@forEach
                    fk.getAsJsonArray("columns").zip(fk.getAsJsonArray("referencedColumns")).forEach { (child, target) ->
                        fixtures.getValue(table).add(child.asString, parent[target.asString])
                    }
                } }
            }
            val character = fixtures.getValue("local_characters")["id"]
            val session = fixtures.getValue("local_sessions")["id"]
            val scope = fixtures.getValue("companion_scopes")["id"]
            fixtures.getValue("local_sessions").add("character_id", character)
            fixtures.getValue("companion_scopes").apply { add("sessionId", session); add("characterId", character) }
            fixtures.getValue("local_character_memories").add("character_id", character)
            fixtures.filterKeys { it.startsWith("companion_") }.forEach { (_, row) ->
                if (row.has("sessionId")) row.add("sessionId", session)
                if (row.has("scopeId")) row.add("scopeId", scope)
                if (row.has("memoryId")) row.add("memoryId", fixtures.getValue("companion_memories")["id"])
                if (row.has("characterId")) row.add("characterId", character)
            }
            fixtures.getValue("companion_locked_reviews").apply {
                add("lockedMemoryId", fixtures.getValue("companion_memories")["id"])
                add("confirmedMemoryId", fixtures.getValue("companion_memories")["id"])
            }
            fixtures.getValue("companion_memories").apply {
                addProperty("voided", 1); addProperty("voidedReason", "update")
                addProperty("voidedAt", "2026-10-23T10:00:00Z")
                addProperty("supersededById", "synthetic-next")
                addProperty("pendingConfirm", 1); addProperty("uncertainInjectedTurns", 3)
                addProperty("uncertainTargetId", "synthetic-protected")
                addProperty("timeText", "下周三"); addProperty("modelDate", "2026-10-29")
                addProperty("eventDate", "2026-10-28"); addProperty("dateType", "day"); addProperty("dateSource", "program")
            }
            assertTrue("companion_applied_ops" in portableTables)
            val message = fixtures.getValue("local_messages")
            fixtures.getValue("companion_sources").apply {
                add("messageId", message["id"]); add("quote", message["content"]); add("role", message["role"])
                addProperty("fingerprint", com.nekobot.app.data.local.ai.CompanionMemoryPolicy.fingerprint(
                    message["id"].asString, message["role"].asString, message["created_at"].asString,
                    message["timestamp"].asString, message["content"].asString, false))
            }
            val visitor: PortableRowVisitor = { consume -> portableTables.forEach { consume(PortableIncomingRow(it, fixtures.getValue(it))) } }
            val planner = PortableImportPlanner(sql, "synthetic.db")
            val first = planner.preview(visitor, PortableImportChoices())
            assertEquals(emptyList<String>(), first.issues)
            assertEquals(51, first.counts.added)
            connection.autoCommit = false
            assertEquals(51, planner.apply(visitor, PortableImportChoices()))
            connection.commit()
            val repeat = planner.preview(visitor, PortableImportChoices())
            assertEquals(51, repeat.counts.same)
            assertEquals(0, planner.apply(visitor, PortableImportChoices()))
            val exported = portableTables.flatMap { table -> sql.query("SELECT * FROM `$table`").map { PortableIncomingRow(table, it) } }
            val exportedVisitor: PortableRowVisitor = { consume -> exported.forEach(consume) }
            assertEquals(0, planner.apply(exportedVisitor, PortableImportChoices()))
            assertEquals(fixtures.getValue("companion_applied_ops"), sql.query("SELECT * FROM companion_applied_ops").single())
            val actualMemory = sql.query("SELECT * FROM companion_memories").single()
            for (column in listOf("voided", "voidedAt", "voidedReason", "supersededById", "pendingConfirm", "uncertainTargetId",
                "uncertainInjectedTurns", "timeText", "modelDate", "dateType", "dateSource")) {
                assertEquals(column, fixtures.getValue("companion_memories")[column], actualMemory[column])
            }
            // 63版归档没有新增字段，由64版SQLite表的真实默认值补齐。
            val oldMemory = fixtures.getValue("companion_memories").deepCopy().apply {
                addProperty("id", "old-backup-memory"); addProperty("factKey", "old-backup-key")
                listOf("voided", "voidedAt", "voidedReason", "supersededById", "pendingConfirm", "uncertainTargetId",
                    "uncertainInjectedTurns", "timeText", "modelDate", "dateType", "dateSource").forEach(::remove)
            }
            val oldVisitor: PortableRowVisitor = { consume -> consume(PortableIncomingRow("companion_memories", oldMemory)) }
            assertEquals(1, planner.apply(oldVisitor, PortableImportChoices()))
            val importedOld = sql.query("SELECT * FROM companion_memories WHERE id='old-backup-memory'").single()
            for (column in listOf("voided", "pendingConfirm", "uncertainInjectedTurns")) assertEquals(column, 0, importedOld[column].asInt)
            for (column in listOf("messageSnapshot")) assertTrue(sql.query("SELECT * FROM companion_sources").single()[column].isJsonNull)
            assertTrue(importedOld["dateSource"].isJsonNull)
            assertTrue(sql.query("PRAGMA foreign_key_check").isEmpty())
            assertEquals("ok", sql.query("PRAGMA integrity_check").single().entrySet().single().value.asString)
        }
    }
}
