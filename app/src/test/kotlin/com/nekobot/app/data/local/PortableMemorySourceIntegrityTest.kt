package com.nekobot.app.data.local

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.sql.DriverManager

class PortableMemorySourceIntegrityTest {
    private lateinit var sql: JdbcPortableImportSql
    private lateinit var planner: PortableImportPlanner
    private val stamp = "2026-01-01T00:00:00Z"
    private val body = "喜欢茉莉茶"
    private val messageBody = "合成测试：我喜欢茉莉茶。"

    @Before fun setup() {
        sql = JdbcPortableImportSql(DriverManager.getConnection("jdbc:sqlite::memory:"))
        sql.execute("CREATE TABLE local_characters(id TEXT PRIMARY KEY,name TEXT)")
        sql.execute("CREATE TABLE local_sessions(id TEXT PRIMARY KEY,name TEXT,character_id TEXT)")
        sql.execute("INSERT INTO local_characters VALUES ('character','Synthetic character')")
        sql.execute("INSERT INTO local_sessions VALUES ('session','Synthetic session','character')")
        sql.execute("CREATE TABLE companion_scopes(id TEXT PRIMARY KEY,sessionId TEXT,characterId TEXT,profile TEXT,baselineRowId INTEGER,archiveRowId INTEGER,revision INTEGER)")
        sql.execute("CREATE TABLE companion_memories(id TEXT PRIMARY KEY,scopeId TEXT,content TEXT,provenance TEXT,manual INTEGER,subject TEXT)")
        sql.execute("CREATE TABLE companion_sources(memoryId TEXT,messageId TEXT,fingerprint TEXT,quote TEXT,spokenDate TEXT,role TEXT,PRIMARY KEY(memoryId,messageId))")
        sql.execute("CREATE TABLE local_messages(id TEXT PRIMARY KEY,session_id TEXT,role TEXT,content TEXT,timestamp TEXT,created_at TEXT,deleted INTEGER)")
        sql.execute("INSERT INTO companion_scopes VALUES ('scope','session','character','test',0,0,0)")
        sql.execute("INSERT INTO companion_memories VALUES ('memory','scope',?,'imported_unverified',0,'玩家')", listOf(body))
        sql.execute("INSERT INTO local_messages VALUES ('message','session','user',?,?,?,0)", listOf(messageBody, stamp, stamp))
        planner = PortableImportPlanner(sql, "synthetic.db")
    }
    @After fun close() = sql.connection.close()
    private fun rows(vararg values: PortableIncomingRow): PortableRowVisitor = { consume -> values.forEach(consume) }
    private fun memory(content: String = body, verified: Boolean = true) = PortableIncomingRow("companion_memories", JsonObject().apply {
        addProperty("id", "memory"); addProperty("scopeId", "scope"); addProperty("content", content)
        addProperty("subject", "玩家")
        addProperty("provenance", if (verified) "imported_verified" else "imported_unverified")
    })
    private fun source(quote: String = "我喜欢茉莉茶", text: String = messageBody) = PortableIncomingRow("companion_sources", JsonObject().apply {
        addProperty("memoryId", "memory"); addProperty("messageId", "message"); addProperty("role", "user")
        addProperty("quote", quote); addProperty("spokenDate", "2026-01-01")
        addProperty("fingerprint", CompanionMemoryPolicy.fingerprint("message", "user", stamp, stamp, text, false))
    })
    private fun overwrite(source: PortableRowVisitor): PortableImportChoices = PortableImportChoices(fields =
        planner.preview(source, PortableImportChoices(), Int.MAX_VALUE).differences.associate { it.key to it.fields.mapTo(linkedSetOf()) { field -> field.column } })

    @Test fun validSourcesAndVerifiedMetadataAreImportedAndRepeatIsZeroWrites() {
        val incoming = rows(memory(), source())
        val choices = overwrite(incoming)
        assertTrue(planner.preview(incoming, choices).issues.isEmpty())
        assertEquals(2, planner.apply(incoming, choices))
        assertEquals("imported_verified", sql.query("SELECT * FROM companion_memories").single()["provenance"].asString)
        assertEquals(0, planner.apply(incoming, PortableImportChoices()))
        assertEquals(1, sql.query("SELECT * FROM companion_sources").size)
    }

    @Test fun keepingDifferentLocalBodyCannotAttachIncomingSources() {
        sql.execute("UPDATE companion_memories SET content='我现在喜欢红茶'")
        val incoming = rows(memory(), source())
        assertTrue(planner.preview(incoming, PortableImportChoices()).issues.any { it.contains("同一版本") })
        assertTrue(sql.query("SELECT * FROM companion_sources").isEmpty())
        assertEquals("我现在喜欢红茶", sql.query("SELECT * FROM companion_memories").single()["content"].asString)
        assertTrue(planner.preview(incoming, overwrite(incoming)).issues.isEmpty())
    }

    @Test fun sourceAndMemoryCountsExplainTheScreenshotWithoutInventingNewMemories() {
        sql.execute("UPDATE companion_memories SET content='本机旧记忆'")
        val plan = planner.preview(rows(memory(), source()), PortableImportChoices())
        assertEquals(PortableImportCounts(added = 1), plan.tableCounts["companion_sources"])
        assertEquals(PortableImportCounts(different = 1), plan.tableCounts["companion_memories"])
        assertEquals(1, plan.sourceConflictMemoryCount)
        assertEquals(setOf(plan.differences.single().key), plan.sourceConflictKeys)
        assertEquals("memory", plan.differences.single().memoryId)
        assertEquals(0, plan.sameNameCopies)
    }

    @Test fun keepingAMemoryAutomaticallyKeepsItsOldEvidenceAndRepeatWritesNothing() {
        sql.execute("UPDATE companion_memories SET content='本机旧记忆'")
        val incoming = rows(memory(), source())
        val row = planner.preview(incoming, PortableImportChoices()).differences.single()
        val choices = PortableImportChoices().withRecordChoice(row, false)
        val plan = planner.preview(incoming, choices)
        assertTrue(plan.issues.isEmpty())
        assertEquals(1, plan.skippedSourceRows)
        assertEquals(0, plan.counts.added)
        assertEquals(0, planner.apply(incoming, choices))
        assertEquals(0, planner.apply(incoming, choices))
        assertEquals("本机旧记忆", sql.query("SELECT * FROM companion_memories").single()["content"].asString)
        assertTrue(sql.query("SELECT * FROM companion_sources").isEmpty())
    }

    @Test fun updatingARecordSelectsItsMatchingFieldsAndRestoresOnlyItsIncomingSources() {
        sql.execute("UPDATE companion_memories SET content='本机旧记忆'")
        val incoming = rows(memory(), source())
        val row = planner.preview(incoming, PortableImportChoices()).differences.single()
        val kept = PortableImportChoices(keepMemorySources = setOf("unrelated")).withRecordChoice(row, false)
        val choices = kept.withRecordChoice(row, true)
        assertEquals(setOf("unrelated"), choices.keepMemorySources)
        assertTrue(planner.preview(incoming, choices).issues.isEmpty())
        assertEquals(2, planner.apply(incoming, choices))
        assertEquals(body, sql.query("SELECT * FROM companion_memories").single()["content"].asString)
        assertEquals(1, sql.query("SELECT * FROM companion_sources").size)
        assertEquals(0, planner.apply(incoming, choices))
    }

    @Test fun skippingIncomingSourcesCannotBypassVerifiedMemoryRequirements() {
        val incoming = rows(memory(), source())
        val choices = overwrite(incoming).copy(keepMemorySources = setOf("memory"))
        val plan = planner.preview(incoming, choices)
        assertTrue(plan.issues.any { it.contains("缺少原话来源") })
        assertTrue(sql.query("SELECT * FROM companion_sources").isEmpty())
    }

    @Test fun keepingOneMemoryDoesNotSkipAnotherMemorysSourceValidation() {
        sql.execute("INSERT INTO companion_memories VALUES ('another','scope','另一条旧记忆','imported_unverified',0,'玩家')")
        val another = memory().also { it.values.addProperty("id", "another") }
        val invalidSource = source(quote = "原文里没有这句话").also { it.values.addProperty("memoryId", "another") }
        val incoming = rows(memory(), source(), another, invalidSource)
        val choices = overwrite(incoming).copy(keepMemorySources = setOf("memory"))
        val plan = planner.preview(incoming, choices)
        assertEquals(1, plan.skippedSourceRows)
        assertEquals(1, plan.tableCounts["companion_sources"]!!.added)
        assertTrue(plan.issues.any { it.contains("原话来源与当前聊天不一致") })
        assertTrue(sql.query("SELECT * FROM companion_sources").isEmpty())
    }

    @Test fun recordUpdateAlsoUpdatesAnExistingMatchingSourceInsteadOfKeepingItsOldQuote() {
        val baseline = rows(memory(), source(quote = "喜欢茉莉茶"))
        planner.apply(baseline, overwrite(baseline))
        sql.execute("UPDATE companion_memories SET content='本机旧记忆'")
        val incoming = rows(memory(), source())
        val before = planner.preview(incoming, PortableImportChoices())
        val row = before.differences.first { it.table == "companion_memories" }
        val kept = PortableImportChoices().withRecordChoice(row, false, before.sourceFieldChoices["memory"].orEmpty())
        val keptPlan = planner.preview(incoming, kept)
        val choices = kept.withRecordChoice(row, true, keptPlan.sourceFieldChoices["memory"].orEmpty())
        val plan = planner.preview(incoming, choices)
        assertTrue(plan.issues.isEmpty())
        assertEquals(1, plan.tableCounts["companion_sources"]!!.updated)
        assertEquals(2, planner.apply(incoming, choices))
        assertEquals("我喜欢茉莉茶", sql.query("SELECT * FROM companion_sources").single()["quote"].asString)
        assertEquals(0, planner.apply(incoming, choices))
    }

    @Test fun automaticExistingSourceUpdateStillRejectsAnUnverifiableQuote() {
        val baseline = rows(memory(), source(quote = "喜欢茉莉茶"))
        planner.apply(baseline, overwrite(baseline))
        sql.execute("UPDATE companion_memories SET content='本机旧记忆'")
        val incoming = rows(memory(), source(quote = "原文中没有这句话"))
        val before = planner.preview(incoming, PortableImportChoices())
        val row = before.differences.first { it.table == "companion_memories" }
        val choices = PortableImportChoices().withRecordChoice(row, true, before.sourceFieldChoices["memory"].orEmpty())
        assertTrue(planner.preview(incoming, choices).issues.any { it.contains("原话来源与当前聊天不一致") })
        assertEquals("本机旧记忆", sql.query("SELECT * FROM companion_memories").single()["content"].asString)
        assertEquals("喜欢茉莉茶", sql.query("SELECT * FROM companion_sources").single()["quote"].asString)
    }

    @Test fun keepingBothMemoryAndItsSourcesLeavesLocalChangesUntouched() {
        sql.execute("UPDATE companion_memories SET content='本机后续修订'")
        val incoming = rows(memory())
        assertTrue(planner.preview(incoming, PortableImportChoices()).issues.isEmpty())
        assertEquals(0, planner.apply(incoming, PortableImportChoices()))
        assertEquals("本机后续修订", sql.query("SELECT * FROM companion_memories").single()["content"].asString)
    }

    @Test fun sameBodyWithDifferentSubjectCannotReuseAnotherPersonsSource() {
        sql.execute("UPDATE companion_memories SET subject='角色'")
        val incoming = rows(memory(), source())
        val metadataOnly = overwrite(incoming).let { choices ->
            choices.copy(fields = choices.fields.mapValues { (_, columns) -> columns - "subject" })
        }
        assertTrue(planner.preview(incoming, metadataOnly).issues.any { it.contains("同一版本") })
        assertTrue(planner.preview(incoming, overwrite(incoming)).issues.isEmpty())
        assertEquals("角色", sql.query("SELECT * FROM companion_memories").single()["subject"].asString)
    }

    @Test fun sourceAttachmentRequiresAnExplicitMatchingSubjectAndScope() {
        val noSubject = memory().also { it.values.remove("subject") }
        assertTrue(planner.preview(rows(noSubject, source()), PortableImportChoices()).issues.any { it.contains("同一版本") })
        sql.execute("INSERT INTO companion_scopes VALUES ('other-scope','session','character','test',0,0,0)")
        sql.execute("UPDATE companion_memories SET scopeId='other-scope'")
        assertTrue(planner.preview(rows(memory(), source()), PortableImportChoices()).issues.any { it.contains("同一版本") })
    }

    @Test fun verifiedFlagCannotBeImportedWithoutSources() {
        val incoming = rows(memory())
        val plan = planner.preview(incoming, overwrite(incoming))
        assertTrue(plan.issues.any { it.contains("缺少原话来源") })
        assertTrue("companion_sources" in plan.requiredTables)
    }

    @Test fun sourceOnlyArchiveMustIncludeItsMemoryBodyForComparison() {
        assertTrue(planner.preview(rows(source()), PortableImportChoices()).issues.any { it.contains("同一版本") })
    }

    @Test fun missingMessageAndCrossSessionOrDeletedMessageCannotBecomeEvidence() {
        val incoming = rows(memory(verified = false), source())
        sql.execute("UPDATE local_messages SET session_id='another-session'")
        assertTrue(planner.preview(incoming, PortableImportChoices()).issues.isNotEmpty())
        sql.execute("UPDATE local_messages SET session_id='session',deleted=1")
        assertTrue(planner.preview(incoming, PortableImportChoices()).issues.isNotEmpty())
        sql.execute("DELETE FROM local_messages")
        val missing = planner.preview(incoming, PortableImportChoices())
        assertTrue("local_messages" in missing.requiredTables)
    }

    @Test fun sourceQuoteMustBeNonemptyAndVerbatimAndRoleMustMatch() {
        for (quote in listOf("", "   ", "我喜欢的是红茶")) {
            assertTrue(planner.preview(rows(memory(), source(quote)), PortableImportChoices()).issues.isNotEmpty())
        }
        val wrongRole = source().also { it.values.addProperty("role", "assistant") }
        assertTrue(planner.preview(rows(memory(), wrongRole), PortableImportChoices()).issues.isNotEmpty())
    }

    @Test fun sourceUsesEffectiveMessageAfterTheUsersFieldChoices() {
        val revised = "合成测试：我喜欢茉莉茶。后来又补充了一句。"
        val message = PortableIncomingRow("local_messages", JsonParser.parseString("""{"id":"message","content":"$revised"}""").asJsonObject)
        val incoming = rows(message, memory(), source(text = revised))
        assertTrue(planner.preview(incoming, PortableImportChoices()).issues.isNotEmpty())
        assertTrue(planner.preview(incoming, overwrite(incoming)).issues.isEmpty())
    }

    @Test fun omittedMessageEditInvalidatesPreviewAndRejectsTheOldFingerprint() {
        val incoming = rows(memory(), source())
        val choices = overwrite(incoming)
        val before = planner.preview(incoming, choices)
        sql.execute("UPDATE local_messages SET content=content || '本机后续修订'")
        val after = planner.preview(incoming, choices)
        assertNotEquals(before.fingerprint, after.fingerprint)
        assertTrue(after.issues.isNotEmpty())
    }

    @Test fun cancellationRollsBackMemoryAndSourcesTogether() {
        val incoming = rows(memory(), source())
        val choices = overwrite(incoming)
        sql.connection.autoCommit = false
        try {
            planner.apply(incoming, choices, onWrite = { table, _, _, _ ->
                if (table == "companion_sources") throw java.util.concurrent.CancellationException()
            })
            fail("Expected synthetic cancellation")
        } catch (_: java.util.concurrent.CancellationException) { sql.connection.rollback() }
        assertTrue(sql.query("SELECT * FROM companion_sources").isEmpty())
        assertEquals("imported_unverified", sql.query("SELECT * FROM companion_memories").single()["provenance"].asString)
    }

    private fun withMessageInvalidationTrigger() {
        sql.execute("ALTER TABLE companion_memories ADD COLUMN valid INTEGER DEFAULT 1")
        sql.execute("ALTER TABLE companion_memories ADD COLUMN current INTEGER DEFAULT 1")
        val baseline = rows(memory(), source())
        planner.apply(baseline, overwrite(baseline))
        sql.execute("""CREATE TRIGGER source_message_changed AFTER UPDATE OF content ON local_messages
            WHEN OLD.content IS NOT NEW.content BEGIN
            UPDATE companion_memories SET valid=0,current=0
            WHERE id IN (SELECT memoryId FROM companion_sources WHERE messageId=NEW.id); END""")
    }

    @Test fun validatedReplacementProofPreservesReviewedFlagsAfterMessageTriggers() {
        withMessageInvalidationTrigger()
        val revised = "$messageBody 后来补充了合成原话。"
        val message = PortableIncomingRow("local_messages", JsonParser.parseString("""{"id":"message","content":"$revised"}""").asJsonObject)
        // The memory itself has no visible differences; restoring transient trigger flags adds no import row.
        val incoming = rows(message, memory(), source(text = revised))
        val choices = overwrite(incoming)
        assertEquals(2, planner.preview(incoming, choices).counts.updated)
        assertEquals(2, planner.apply(incoming, choices))
        val saved = sql.query("SELECT * FROM companion_memories").single()
        assertEquals(1, saved["valid"].asInt); assertEquals(1, saved["current"].asInt)
        assertEquals(0, planner.apply(incoming, PortableImportChoices()))
    }

    @Test fun messageOnlyImportStillInvalidatesOldProofAndKeptInvalidFlagsAreNotRevived() {
        withMessageInvalidationTrigger()
        val revised = "$messageBody 合成修改。"
        val message = PortableIncomingRow("local_messages", JsonParser.parseString("""{"id":"message","content":"$revised"}""").asJsonObject)
        val rawOnly = rows(message)
        assertEquals(1, planner.apply(rawOnly, overwrite(rawOnly)))
        assertEquals(0, sql.query("SELECT * FROM companion_memories").single()["valid"].asInt)
        val repaired = rows(memory(), source(text = revised))
        planner.apply(repaired, overwrite(repaired))
        val saved = sql.query("SELECT * FROM companion_memories").single()
        assertEquals(0, saved["valid"].asInt); assertEquals(0, saved["current"].asInt)
    }

    private fun enableV2() {
        sql.execute("ALTER TABLE local_sessions ADD COLUMN companion_mode INTEGER NOT NULL DEFAULT 1")
        sql.execute("ALTER TABLE companion_sources ADD COLUMN messageSnapshot TEXT")
    }
    private fun v2Source() = source(quote = "").also { it.values.addProperty("messageSnapshot", messageBody) }

    @Test fun v2FullSnapshotImportsWithoutQuoteAfterEditOrSoftDelete() {
        enableV2()
        sql.execute("UPDATE local_messages SET content='虚构编辑后的原话',deleted=1")
        val incoming = rows(memory(), v2Source())
        val choices = overwrite(incoming)
        assertTrue(planner.preview(incoming, choices).issues.isEmpty())
        assertEquals(2, planner.apply(incoming, choices))
        val saved = sql.query("SELECT * FROM companion_sources").single()
        assertEquals("", saved["quote"].asString)
        assertEquals(messageBody, saved["messageSnapshot"].asString)
        assertEquals(0, planner.apply(incoming, PortableImportChoices()))
    }

    @Test fun v2SnapshotCannotBypassFingerprintRoleOrSessionOwnership() {
        enableV2()
        val wrongSnapshot = v2Source().also { it.values.addProperty("messageSnapshot", "另一个虚构版本") }
        val wrongRole = v2Source().also { it.values.addProperty("role", "assistant") }
        for (bad in listOf(wrongSnapshot, wrongRole)) assertTrue(planner.preview(rows(memory(), bad), PortableImportChoices()).issues.isNotEmpty())
        sql.execute("UPDATE local_messages SET session_id='other'")
        assertTrue(planner.preview(rows(memory(), v2Source()), PortableImportChoices()).issues.isNotEmpty())
    }

    @Test fun ordinarySessionStillRequiresVerbatimQuoteEvenWithV2Columns() {
        enableV2(); sql.execute("UPDATE local_sessions SET companion_mode=0")
        assertTrue(planner.preview(rows(memory(), v2Source()), PortableImportChoices()).issues.isNotEmpty())
    }

    @Test fun missingReviewMemoryImportsAsObsoleteInsteadOfBlockingOtherData() {
        sql.execute("CREATE TABLE companion_locked_reviews(id TEXT PRIMARY KEY,scopeId TEXT,sessionId TEXT,lockedMemoryId TEXT,confirmedMemoryId TEXT,createdAt TEXT,resolvedAt TEXT,resolution TEXT)")
        val review = PortableIncomingRow("companion_locked_reviews", JsonParser.parseString("""{"id":"review","scopeId":"scope","sessionId":"session","lockedMemoryId":"memory","confirmedMemoryId":"missing","createdAt":"$stamp"}""").asJsonObject)
        val incoming = rows(review)
        assertTrue(planner.preview(incoming, PortableImportChoices()).issues.isEmpty())
        assertEquals(1, planner.apply(incoming, PortableImportChoices()))
        val saved = sql.query("SELECT * FROM companion_locked_reviews").single()
        assertEquals("obsolete", saved["resolution"].asString)
        assertFalse(saved["resolvedAt"].isJsonNull)
        assertEquals(0, planner.apply(incoming, PortableImportChoices()))
    }
}
