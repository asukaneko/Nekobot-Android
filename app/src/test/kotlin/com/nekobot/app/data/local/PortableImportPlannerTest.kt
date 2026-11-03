package com.nekobot.app.data.local

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

internal class JdbcPortableImportSql(val connection: Connection) : PortableImportSql {
    override fun execute(sql: String, args: List<Any?>) { connection.prepareStatement(sql).use { statement -> args.forEachIndexed { i, v -> statement.setObject(i + 1, v) }; statement.execute() } }
    override fun query(sql: String, args: List<Any?>): List<JsonObject> = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { i, v -> statement.setObject(i + 1, v) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(JsonObject().apply {
            for (i in 1..rows.metaData.columnCount) {
                val name = rows.metaData.getColumnLabel(i)
                when (val value = rows.getObject(i)) {
                    null -> add(name, JsonNull.INSTANCE)
                    is Number -> addProperty(name, value)
                    is ByteArray -> add(name, JsonObject().apply { addProperty("__base64_blob__", java.util.Base64.getEncoder().encodeToString(value)) })
                    else -> addProperty(name, value.toString())
                }
            }
        }) } }
    }
}

class PortableImportPlannerTest {
    private lateinit var sql: JdbcPortableImportSql
    private lateinit var planner: PortableImportPlanner
    @Before fun setup() {
        sql = JdbcPortableImportSql(DriverManager.getConnection("jdbc:sqlite::memory:"))
        sql.execute("PRAGMA foreign_keys=ON")
        sql.execute("CREATE TABLE local_characters(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,description TEXT,updated_at TEXT)")
        sql.execute("CREATE TABLE local_sessions(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,character_id TEXT)")
        sql.execute("CREATE TABLE companion_scopes(id TEXT PRIMARY KEY NOT NULL,sessionId TEXT NOT NULL,characterId TEXT,profile TEXT,baselineRowId INTEGER,archiveRowId INTEGER,revision INTEGER)")
        sql.execute("CREATE TABLE companion_memories(id TEXT PRIMARY KEY NOT NULL,scopeId TEXT NOT NULL,content TEXT,category TEXT,alwaysProvide INTEGER NOT NULL DEFAULT 0)")
        sql.execute("CREATE TABLE local_ai_models(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,api_key TEXT NOT NULL DEFAULT '',enabled INTEGER NOT NULL DEFAULT 1,active INTEGER NOT NULL DEFAULT 1)")
        sql.execute("CREATE TABLE local_world_books(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL UNIQUE)")
        sql.execute("CREATE TABLE local_world_book_entries(id TEXT PRIMARY KEY NOT NULL,book_id TEXT NOT NULL,content TEXT,FOREIGN KEY(book_id) REFERENCES local_world_books(id))")
        planner = PortableImportPlanner(sql, "synthetic.db")
    }
    @After fun close() { PortableImportGate.profile = null; sql.connection.close() }
    private fun row(table: String, json: String) = PortableIncomingRow(table, JsonParser.parseString(json).asJsonObject)
    private fun rows(vararg rows: PortableIncomingRow): PortableRowVisitor = { consume -> rows.forEach(consume) }
    private val defaults = PortableImportChoices()
    private fun seed() = sql.execute("INSERT INTO local_characters VALUES ('same-id','Current name','User edited description','later')")

    @Test fun operationProgressRequiresMemoryFromSameScopeAndCannotChangeItsOwner() {
        sql.execute("CREATE TABLE companion_applied_ops(scopeId TEXT NOT NULL,opKey TEXT NOT NULL,memoryId TEXT,createdAt TEXT NOT NULL,PRIMARY KEY(scopeId,opKey))")
        sql.execute("INSERT INTO companion_scopes(id,sessionId) VALUES('a','session'),('b','other')")
        sql.execute("INSERT INTO companion_memories(id,scopeId,content) VALUES('memory','b','synthetic')")
        val bad = rows(row("companion_applied_ops", """{"scopeId":"a","opKey":"key","memoryId":"memory","createdAt":"now"}"""))
        assertTrue(planner.preview(bad, defaults).issues.any { it.contains("不属于同一会话") })
        val good = rows(row("companion_applied_ops", """{"scopeId":"b","opKey":"key","memoryId":"memory","createdAt":"now"}"""))
        assertEquals(emptyList<String>(), planner.preview(good, defaults).issues)
        assertEquals(1, planner.apply(good, defaults))
        assertEquals(0, planner.apply(good, defaults))
        val rebind = rows(row("companion_applied_ops", """{"scopeId":"b","opKey":"key","memoryId":null,"createdAt":"now"}"""))
        assertTrue(planner.preview(rebind, defaults).issues.any { it.contains("归属与本地不同") })
    }

    @Test fun newRowsAreInsertedAndRepeatingSameIdsIsZeroWrites() {
        val source = rows(row("local_characters", """{"id":"a","name":"Fixture"}"""))
        assertEquals(1, planner.preview(source, defaults).counts.added)
        assertEquals(1, planner.apply(source, defaults))
        assertEquals(1, planner.preview(source, defaults).counts.same)
        assertEquals(0, planner.apply(source, defaults))
        assertEquals(1, sql.query("SELECT * FROM local_characters").size)
    }
    @Test fun sparseChangesDefaultToKeepingLaterLocalEdits() {
        seed()
        val source = rows(row("local_characters", """{"id":"same-id","name":"Old name"}"""))
        val preview = planner.preview(source, defaults)
        assertEquals(PortableImportCounts(different = 1), preview.counts)
        assertEquals(0, planner.apply(source, defaults))
        assertEquals("User edited description", sql.query("SELECT * FROM local_characters").single()["description"].asString)
        assertEquals("Current name", sql.query("SELECT * FROM local_characters").single()["name"].asString)
    }
    @Test fun explicitFieldUpdatePreservesAllOtherColumns() {
        seed()
        val source = rows(row("local_characters", """{"id":"same-id","name":"New name","description":"Archive description"}"""))
        val diff = planner.preview(source, defaults).differences.single()
        val choices = PortableImportChoices(fields = mapOf(diff.key to setOf("name")))
        assertEquals(1, planner.preview(source, choices).counts.updated)
        assertEquals(1, planner.apply(source, choices))
        val saved = sql.query("SELECT * FROM local_characters").single()
        assertEquals("New name", saved["name"].asString)
        assertEquals("User edited description", saved["description"].asString)
        assertEquals("later", saved["updated_at"].asString)
    }
    @Test fun omittedColumnsAndExplicitNullHaveDifferentMeaning() {
        seed()
        val source = rows(row("local_characters", """{"id":"same-id","description":null}"""))
        val diff = planner.preview(source, defaults).differences.single()
        planner.apply(source, PortableImportChoices(fields = mapOf(diff.key to setOf("description"))))
        val saved = sql.query("SELECT * FROM local_characters").single()
        assertTrue(saved["description"].isJsonNull)
        assertEquals("Current name", saved["name"].asString)
    }
    @Test fun changedTargetRowInvalidatesFingerprintEvenForAnOmittedColumn() {
        seed()
        val source = rows(row("local_characters", """{"id":"same-id","name":"Old"}"""))
        val before = planner.preview(source, defaults)
        sql.execute("UPDATE local_characters SET description='changed after preview'")
        assertNotEquals(before.fingerprint, planner.preview(source, defaults).fingerprint)
    }
    @Test fun unrelatedRecordDoesNotInvalidateSelectedRowPreview() {
        seed()
        val source = rows(row("local_characters", """{"id":"same-id","name":"Old"}"""))
        val before = planner.preview(source, defaults)
        sql.execute("INSERT INTO local_characters VALUES ('unrelated','Someone else',NULL,NULL)")
        assertEquals(before.fingerprint, planner.preview(source, defaults).fingerprint)
    }
    @Test fun overwriteChoicesArePartOfTheFingerprint() {
        seed()
        val source = rows(row("local_characters", """{"id":"same-id","name":"Old"}"""))
        val before = planner.preview(source, defaults)
        assertNotEquals(before.fingerprint, planner.preview(source, defaults.copy(fields = mapOf(before.differences.single().key to setOf("name")))).fingerprint)
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateSourceIdsAreRejectedBeforeApplying() {
        val row = row("local_characters", """{"id":"a","name":"Fixture"}""")
        planner.preview(rows(row, row), defaults)
    }
    @Test fun sameNameDifferentIdNeedsExplicitCopyConsent() {
        seed()
        val source = rows(row("local_characters", """{"id":"new-id","name":"Current name"}"""))
        assertTrue(planner.preview(source, defaults).issues.isNotEmpty())
        assertEquals(1, planner.preview(source, defaults.copy(allowSameName = true)).sameNameCopies)
        assertTrue(planner.preview(source, defaults.copy(allowSameName = true)).issues.isEmpty())
        planner.apply(source, defaults.copy(allowSameName = true))
        assertEquals(2, sql.query("SELECT * FROM local_characters").size)
    }
    @Test fun missingLogicalParentIsReportedWithoutWritingAnything() {
        val source = rows(row("companion_memories", """{"id":"m","scopeId":"missing","content":"Fixture"}"""))
        assertTrue(planner.preview(source, defaults).issues.any { it.contains("companion_scopes") })
        assertTrue(sql.query("SELECT * FROM companion_memories").isEmpty())
    }
    @Test fun existingParentsAllowMemoryOnlyImportWithoutAnyChatRows() {
        sql.execute("INSERT INTO companion_scopes(id,sessionId) VALUES ('scope','existing-session')")
        val source = rows(row("companion_memories", """{"id":"m","scopeId":"scope","content":"Fixture","alwaysProvide":1}"""))
        assertTrue(planner.preview(source, defaults).issues.isEmpty())
        planner.apply(source, defaults)
        assertEquals(0, sql.query("SELECT * FROM local_sessions").size)
        assertEquals(1, sql.query("SELECT * FROM companion_memories").size)
    }
    @Test fun selectedCrossTableParentsAreRecognizedRegardlessOfPreviewOrder() {
        val source = rows(row("local_world_book_entries", """{"id":"e","book_id":"b","content":"Fixture"}"""),
            row("local_world_books", """{"id":"b","name":"Fixture book"}"""))
        assertTrue(planner.preview(source, defaults).issues.isEmpty())
    }
    @Test fun uniqueValueConflictDoesNotFallBackToOverwritingAnotherId() {
        sql.execute("INSERT INTO local_world_books VALUES ('existing','Unique title')")
        val source = rows(row("local_world_books", """{"id":"new","name":"Unique title"}"""))
        assertTrue(planner.preview(source, defaults).issues.isNotEmpty())
        assertEquals("existing", sql.query("SELECT * FROM local_world_books").single()["id"].asString)
    }
    @Test fun newModelIsInactiveAndCannotImportPlaintextCredentials() {
        val source = rows(row("local_ai_models", """{"id":"a","name":"Fixture","api_key":"SYNTHETIC_NON_SECRET","enabled":true,"active":true}"""))
        val preview = planner.preview(source, defaults)
        assertEquals(1, preview.sensitiveFieldsOmitted)
        assertFalse(preview.toString().contains("SYNTHETIC_NON_SECRET"))
        planner.apply(source, defaults)
        val model = sql.query("SELECT * FROM local_ai_models").single()
        assertEquals("", model["api_key"].asString)
        assertEquals(0, model["enabled"].asInt)
        assertEquals(0, model["active"].asInt)
    }
    @Test fun updatingExistingModelPreservesItsSecretAndDisablesExecution() {
        sql.execute("INSERT INTO local_ai_models VALUES ('a','Current','SYNTHETIC_LOCAL',1,1)")
        val source = rows(row("local_ai_models", """{"id":"a","name":"Imported","api_key":"SYNTHETIC_INCOMING","enabled":1,"active":1}"""))
        val diff = planner.preview(source, defaults).differences.single()
        assertEquals(listOf("name"), diff.fields.map { it.column })
        planner.apply(source, defaults.copy(fields = mapOf(diff.key to setOf("name", "api_key", "enabled"))))
        val model = sql.query("SELECT * FROM local_ai_models").single()
        assertEquals("SYNTHETIC_LOCAL", model["api_key"].asString)
        assertEquals(0, model["enabled"].asInt)
    }
    @Test fun identicalEnabledModelRemainsEnabledAndWritesNothing() {
        sql.execute("INSERT INTO local_ai_models VALUES ('a','Current','SYNTHETIC_LOCAL',1,1)")
        val source = rows(row("local_ai_models", """{"id":"a","name":"Current","enabled":0,"active":0}"""))
        assertEquals(1, planner.preview(source, defaults).counts.same)
        assertEquals(0, planner.apply(source, defaults))
        assertEquals(1, sql.query("SELECT * FROM local_ai_models").single()["enabled"].asInt)
    }
    @Test fun coreMarkAndLongContentAreKeptWithoutApplyingManualEditorBudget() {
        sql.execute("INSERT INTO companion_scopes(id,sessionId) VALUES ('scope','s')")
        val longText = "合成内容".repeat(2500)
        val values = JsonObject().apply { addProperty("id", "m"); addProperty("scopeId", "scope"); addProperty("content", longText); addProperty("alwaysProvide", 1) }
        val source = rows(PortableIncomingRow("companion_memories", values))
        planner.apply(source, defaults)
        val result = sql.query("SELECT * FROM companion_memories").single()
        assertEquals(longText, result["content"].asString)
        assertEquals(1, result["alwaysProvide"].asInt)
    }
    @Test(expected = IllegalArgumentException::class) fun unknownExpectedHashIsRejectedRatherThanPretendingToEnforceIt() {
        planner.preview(rows(row("local_characters", """{"id":"a","name":"Fixture","expected_hash":"not-enforced"}""")), defaults)
    }
    @Test fun missingRequiredNewFieldIsAnIssueAndCannotCountAsReady() {
        val plan = planner.preview(rows(row("local_characters", """{"id":"a"}""")), defaults)
        assertTrue(plan.issues.any { it.contains("name") })
    }
    @Test fun cancellationInsideCallerTransactionRollsBackEarlierInserts() {
        val source = rows(row("local_characters", """{"id":"a","name":"A"}"""), row("local_characters", """{"id":"b","name":"B"}"""))
        sql.connection.autoCommit = false
        var processed = 0
        try {
            planner.apply(source, defaults, { if (++processed == 2) throw java.util.concurrent.CancellationException() })
            fail()
        } catch (_: java.util.concurrent.CancellationException) { sql.connection.rollback() }
        assertTrue(sql.query("SELECT * FROM local_characters").isEmpty())
    }
    @Test fun invalidationOnlyTouchesTheWrittenArchiveEvenInsideTheSameSession() {
        sql.execute("CREATE TABLE local_experience_archives(id TEXT PRIMARY KEY,session_id TEXT,status TEXT)")
        sql.execute("CREATE TABLE local_experience_sources(archive_id TEXT,message_id TEXT)")
        sql.execute("CREATE TABLE local_messages(id TEXT PRIMARY KEY,session_id TEXT,deleted INTEGER)")
        sql.execute("INSERT INTO local_experience_archives VALUES ('written','same','active'),('unrelated-same-session','same','active'),('unrelated-session','other','active')")
        sql.execute(portableExperienceInvalidationSql, listOf("written"))
        val states = sql.query("SELECT * FROM local_experience_archives").associate { it["id"].asString to it["status"].asString }
        assertEquals("stale", states["written"])
        assertEquals("active", states["unrelated-same-session"])
        assertEquals("active", states["unrelated-session"])
    }
    @Test fun secondConfirmedPlanBecomesStaleAfterTheFirstCommits() {
        val source = rows(row("local_characters", """{"id":"a","name":"Fixture"}"""))
        val a = planner.preview(source, defaults)
        val b = planner.preview(source, defaults)
        assertEquals(a.fingerprint, b.fingerprint)
        planner.apply(source, defaults)
        assertNotEquals(b.fingerprint, planner.preview(source, defaults).fingerprint)
        assertEquals(1, sql.query("SELECT * FROM local_characters").size)
    }
    @Test fun triggerCreatedChildrenStillReceiveTheConfirmedNewRecord() {
        sql.execute("CREATE TRIGGER fixture_child AFTER INSERT ON local_world_books BEGIN INSERT INTO local_world_book_entries VALUES ('child',NEW.id,'automatic placeholder'); END")
        val source = rows(row("local_world_books", """{"id":"book","name":"Fixture"}"""),
            row("local_world_book_entries", """{"id":"child","book_id":"book","content":"Source content"}"""))
        val preview = planner.preview(source, defaults)
        assertEquals(2, preview.counts.added)
        assertEquals(2, planner.apply(source, defaults, originallyNew = preview.newKeys))
        assertEquals("Source content", sql.query("SELECT * FROM local_world_book_entries").single()["content"].asString)
    }
    @Test fun switchingProfilesDuringImportIsRejectedWhileOwnedRefreshIsAllowed() {
        PortableImportGate.profile = "fixture"
        try { PortableImportGate.requireSwitchAllowed("other", false); fail() } catch (_: IllegalArgumentException) { }
        try { PortableImportGate.requireSwitchAllowed("fixture", false); fail() } catch (_: IllegalArgumentException) { }
        PortableImportGate.requireSwitchAllowed("fixture", true)
    }
    @Test fun existingMemoryCannotBeReassignedToAnotherScope() {
        sql.execute("INSERT INTO companion_scopes(id,sessionId) VALUES ('first','s1'),('second','s2')")
        sql.execute("INSERT INTO companion_memories(id,scopeId,content) VALUES ('memory','first','Local')")
        val source = rows(row("companion_memories", """{"id":"memory","scopeId":"second","content":"Incoming"}"""))
        val preview = planner.preview(source, defaults)
        assertTrue(preview.issues.any { it.contains("归属") })
        assertFalse(preview.differences.single().fields.any { it.column == "scopeId" })
        try { planner.apply(source, defaults); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("first", sql.query("SELECT * FROM companion_memories").single()["scopeId"].asString)
    }
    @Test fun existingRelationshipStateCannotBeReassignedToAnotherCharacter() {
        sql.execute("CREATE TABLE companion_relationship_state(sessionId TEXT PRIMARY KEY, characterId TEXT NOT NULL, narrativeJson TEXT)")
        sql.execute("INSERT INTO companion_relationship_state VALUES ('s','original-role','local')")
        val source = rows(row("companion_relationship_state", """{"sessionId":"s","characterId":"other-role","narrativeJson":"incoming"}"""))
        val preview = planner.preview(source, defaults)
        val overwrite = defaults.copy(fields = mapOf(preview.differences.single().key to setOf("characterId", "narrativeJson")))
        assertTrue(preview.issues.any { it.contains("归属") })
        assertFalse(preview.differences.single().fields.any { it.column == "characterId" })
        try { planner.apply(source, overwrite); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("original-role", sql.query("SELECT * FROM companion_relationship_state").single()["characterId"].asString)
        assertEquals("local", sql.query("SELECT * FROM companion_relationship_state").single()["narrativeJson"].asString)
    }
    @Test(expected = IllegalArgumentException::class) fun fractionalIntegerIsRejectedInsteadOfTruncated() {
        planner.preview(rows(row("companion_memories", """{"id":"m","scopeId":"s","alwaysProvide":1.2}""")), defaults)
    }
    @Test(expected = IllegalArgumentException::class) fun outOfRangeIntegerIsRejectedInsteadOfWrapped() {
        planner.preview(rows(row("companion_memories", """{"id":"m","scopeId":"s","alwaysProvide":9223372036854775808}""")), defaults)
    }
    private fun sessionGuards() {
        sql.execute("ALTER TABLE local_sessions ADD COLUMN companion_mode INTEGER NOT NULL DEFAULT 0")
        sql.execute("ALTER TABLE local_sessions ADD COLUMN session_mode TEXT NOT NULL DEFAULT 'chat'")
        sql.execute("ALTER TABLE local_sessions ADD COLUMN inherit_character INTEGER NOT NULL DEFAULT 0")
        sql.execute("ALTER TABLE local_sessions ADD COLUMN long_conversation_enabled INTEGER NOT NULL DEFAULT 0")
        sql.execute("CREATE TABLE companion_owners(characterId TEXT PRIMARY KEY,sessionId TEXT UNIQUE)")
        sql.execute("INSERT INTO local_characters(id,name) VALUES ('character','Fixture')")
    }
    @Test fun existingRoleOwnerPreventsSecondSessionInThePreview() {
        sessionGuards()
        sql.execute("INSERT INTO local_sessions VALUES ('bound','Bound','character',1,'agent',1,1)")
        sql.execute("INSERT INTO companion_owners VALUES ('character','bound')")
        val source = rows(row("local_sessions", """{"id":"second","name":"Copy","character_id":"character","companion_mode":1,"session_mode":"agent","inherit_character":1,"long_conversation_enabled":1}"""))
        assertTrue(planner.preview(source, defaults).issues.any { it.contains("第二个窗口") })
        try { planner.apply(source, defaults); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, sql.query("SELECT * FROM local_sessions").size)
    }
    @Test fun twoIncomingSessionsCannotAcquireTheSameIndependentRole() {
        sessionGuards()
        val first = row("local_sessions", """{"id":"one","name":"First","character_id":"character","companion_mode":1,"session_mode":"agent","inherit_character":1,"long_conversation_enabled":1}""")
        val second = row("local_sessions", """{"id":"two","name":"Second","character_id":"character","companion_mode":0}""")
        assertTrue(planner.preview(rows(first, second), defaults).issues.any { it.contains("第二个窗口") })
        assertTrue(sql.query("SELECT * FROM local_sessions").isEmpty())
    }
    @Test fun incompleteIndependentRoleModeIsNotReportedReady() {
        sessionGuards()
        val source = rows(row("local_sessions", """{"id":"one","name":"First","character_id":"character","companion_mode":1,"session_mode":"chat"}"""))
        assertTrue(planner.preview(source, defaults).issues.any { it.contains("模式与角色绑定") })
    }
    @Test fun existingScopeWithAnotherIdCannotBeDuplicatedForTheSameOwner() {
        sql.execute("INSERT INTO local_characters(id,name) VALUES ('c','Fixture')")
        sql.execute("INSERT INTO local_sessions(id,name,character_id) VALUES ('s','Fixture','c')")
        sql.execute("INSERT INTO companion_scopes(id,sessionId,characterId) VALUES ('local','s','c')")
        val source = rows(row("companion_scopes", """{"id":"imported","sessionId":"s","characterId":"c"}"""))
        assertTrue(planner.preview(source, defaults).issues.any { it.contains("第二份归属") })
        assertEquals(1, sql.query("SELECT * FROM companion_scopes").size)
    }
    private fun versionedMemory() {
        sql.execute("ALTER TABLE companion_memories ADD COLUMN factKey TEXT NOT NULL DEFAULT 'fixture'")
        sql.execute("ALTER TABLE companion_memories ADD COLUMN current INTEGER NOT NULL DEFAULT 1")
        sql.execute("ALTER TABLE companion_memories ADD COLUMN valid INTEGER NOT NULL DEFAULT 1")
        sql.execute("ALTER TABLE companion_memories ADD COLUMN status TEXT NOT NULL DEFAULT 'active'")
        sql.execute("INSERT INTO companion_scopes(id,sessionId) VALUES ('s','session')")
        sql.execute("INSERT INTO companion_memories(id,scopeId,content) VALUES ('local','s','Later local memory')")
    }
    @Test fun anotherCurrentVersionCannotSilentlyReplaceLaterLocalMemory() {
        versionedMemory()
        val source = rows(row("companion_memories", """{"id":"incoming","scopeId":"s","factKey":"fixture","content":"Incoming version","current":1,"valid":1,"status":"active"}"""))
        assertTrue(planner.preview(source, defaults).issues.any { it.contains("两个当前版本") })
        try { planner.apply(source, defaults); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("Later local memory", sql.query("SELECT * FROM companion_memories").single()["content"].asString)
    }
    @Test fun explicitlyRetiringTheProvidedOldVersionAllowsSupplementingNewVersionWithoutDeletingHistory() {
        versionedMemory()
        val source = rows(row("companion_memories", """{"id":"local","scopeId":"s","current":0}"""),
            row("companion_memories", """{"id":"incoming","scopeId":"s","factKey":"fixture","content":"Incoming version","current":1,"valid":1,"status":"active"}"""))
        val difference = planner.preview(source, defaults).differences.single()
        val chosen = defaults.copy(fields = mapOf(difference.key to setOf("current")))
        assertTrue(planner.preview(source, chosen).issues.isEmpty())
        planner.apply(source, chosen)
        val memories = sql.query("SELECT * FROM companion_memories")
        assertEquals(2, memories.size)
        assertEquals("Later local memory", memories.first { it["id"].asString == "local" }["content"].asString)
        assertEquals(1, memories.count { it["current"].asInt == 1 })
    }
}
