package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AGENT_CONTEXT_SUMMARY_PREFIX
import com.nekobot.app.data.local.AGENT_CONTEXT_SUMMARY_SOURCE
import com.nekobot.app.data.local.PortableDataArchiveManager
import com.nekobot.app.data.local.PortableDataCategory
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Room loader and prompt pipeline; never calls an external model. */
@RunWith(AndroidJUnit4::class)
class AgentSummaryContextAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val model = LocalAiModelEntity(
        id = "summary-test-model", name = "Offline fixture", protocol = "openai_chat",
        apiKey = "", baseUrl = "http://127.0.0.1:1", model = "offline", supportsVision = false,
        createdAt = "now"
    )

    private fun database() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
    private fun row(session: String, suffix: String, role: String, content: String, order: Int) = LocalMessageEntity(
        id = "$session-$suffix", sessionId = session, role = role, content = content,
        timestamp = "2026-10-05T09:00:%02dZ".format(order),
        createdAt = "2026-10-05T09:00:%02dZ".format(order)
    )

    private suspend fun seed(db: NekobotDatabase, id: String, inherit: Boolean): LocalSessionEntity {
        if (inherit) db.characterDao().upsert(LocalCharacterEntity(
            id = "$id-character", name = "Test character", systemPrompt = "ROLE_IDENTITY",
            createdAt = "now", updatedAt = "now"
        ))
        val session = LocalSessionEntity(
            id = id, name = "Summary continuity test", sessionMode = "agent",
            characterId = "$id-character".takeIf { inherit }, inheritCharacter = inherit,
            longConversationEnabled = true, longConversationTailUntilId = "$id-a2",
            systemPrompt = "SESSION_RULE", createdAt = "now", updatedAt = "now"
        )
        db.sessionDao().upsert(session)
        db.messageDao().upsertAll(listOf(
            row(id, "u0", "user", "OLD_USER_RAW", 0), row(id, "a0", "assistant", "OLD_REPLY_RAW", 1),
            row(id, "u1", "user", "RECENT_USER_ONE", 2), row(id, "a1", "assistant", "RECENT_REPLY_ONE", 3),
            row(id, "u2", "user", "RECENT_USER_TWO", 4), row(id, "a2", "assistant", "RECENT_REPLY_TWO", 5),
            row(id, "summary", "system", "$AGENT_CONTEXT_SUMMARY_PREFIX\nEARLY_HISTORY_FACT", 6)
                .copy(source = "$AGENT_CONTEXT_SUMMARY_SOURCE:$id-a0")
        ))
        return session
    }

    private suspend fun prepare(db: NekobotDatabase, session: LocalSessionEntity): PipelineContext {
        val character = session.characterId?.let { db.characterDao().getById(it) }
        val runtime = character?.let {
            CharacterRuntime(profileRepo = object : ProfileRepository {
                override suspend fun getById(id: String) = CharacterProfile(
                    id = id, name = it.name, systemPrompt = it.systemPrompt.orEmpty()
                )
            })
        }
        val identity = character?.let {
            CharacterIdentity(characterId = it.id, targetId = "test-user", scopeId = session.id)
        }
        val callbacks = LocalPipelineCallbacks(
            db, LocalAiClient(), model, session, character,
            characterRuntime = runtime, characterIdentity = identity
        )
        val ctx = PipelineContext(ChatRequest.forLocal(
            sessionId = session.id, content = "Current question.",
            metadata = mapOf("session_mode" to "agent", "inherit_character" to session.inheritCharacter)
        )).apply { metadata.putAll(chatRequest.metadata) }
        AIPipeline().prepareContext(ctx, callbacks)
        return ctx
    }

    private fun assertContainsOnce(ctx: PipelineContext, marker: String) {
        val text = ctx.messages.joinToString("\n") { it["content"] as? String ?: "" }
        assertEquals(marker, 1, Regex(Regex.escape(marker)).findAll(text).count())
    }

    @Test
    fun nativeInheritedAgentKeepsSummaryAndRecentTextWithoutReplayingOldRawHistory() = runBlocking {
        val db = database()
        try {
            val session = seed(db, "native", inherit = true)
            val before = db.messageDao().listBySession(session.id)
            val ctx = prepare(db, session)
            for (marker in listOf("EARLY_HISTORY_FACT", "ROLE_IDENTITY", "RECENT_USER_ONE", "RECENT_REPLY_TWO")) {
                assertContainsOnce(ctx, marker)
            }
            assertFalse(ctx.messages.any { it["content"] == "OLD_USER_RAW" })
            assertEquals(before, db.messageDao().listBySession(session.id))
        } finally { db.close() }
    }

    @Test
    fun unboundAgentKeepsRealStoredSummaryAndSessionRules() = runBlocking {
        val db = database()
        try {
            val session = seed(db, "unbound", inherit = false)
            val ctx = prepare(db, session)
            assertContainsOnce(ctx, "EARLY_HISTORY_FACT")
            assertContainsOnce(ctx, "SESSION_RULE")
            assertContainsOnce(ctx, "RECENT_USER_TWO")
        } finally { db.close() }
    }

    @Test
    fun updatingStoredSummaryBoundaryIsVisibleOnNextRequest() = runBlocking {
        val db = database()
        try {
            val session = seed(db, "rolling", inherit = true)
            assertContainsOnce(prepare(db, session), "EARLY_HISTORY_FACT")
            val summary = db.messageDao().getById("${session.id}-summary")!!
            db.messageDao().upsert(summary.copy(
                content = "$AGENT_CONTEXT_SUMMARY_PREFIX\nUPDATED_HISTORY_FACT",
                source = "$AGENT_CONTEXT_SUMMARY_SOURCE:${session.id}-a1"
            ))
            val ctx = prepare(db, session)
            assertContainsOnce(ctx, "UPDATED_HISTORY_FACT")
            assertContainsOnce(ctx, "ROLE_IDENTITY")
            assertContainsOnce(ctx, "RECENT_USER_TWO")
            assertFalse(ctx.messages.any { (it["content"] as? String)?.contains("EARLY_HISTORY_FACT") == true })
            assertFalse(ctx.messages.any { it["content"] == "RECENT_USER_ONE" })
            assertEquals(7, db.messageDao().listBySession(session.id).size)
        } finally { db.close() }
    }

    @Test
    fun portableImportAndReimportKeepSummaryVisibleAlongsideNewChat() = runBlocking {
        val id = "summary-import-${UUID.randomUUID()}"
        fun activeDatabase() = NekobotDatabase.get(context, ServiceContainer.prefs.activeDbName)
        var db = activeDatabase()
        try {
            seed(db, id, inherit = true)
            val manager = PortableDataArchiveManager(context)
            val output = ByteArrayOutputStream()
            val selected = setOf(PortableDataCategory.CONVERSATIONS, PortableDataCategory.CHARACTERS)
            manager.export(selected, "", output, appVersion = "summary-test")
            db.sessionDao().deleteById(id)
            manager.import(ByteArrayInputStream(output.toByteArray()), "", selected)
            // Portable import refreshes the repository and closes the previous Room handle.
            db = activeDatabase()
            db.messageDao().upsert(row(id, "new", "user", "AFTER_IMPORT_NEW_MESSAGE", 7))
            manager.import(ByteArrayInputStream(output.toByteArray()), "", selected)
            db = activeDatabase()
            val restored = db.sessionDao().getById(id)!!
            val ctx = prepare(db, restored)
            for (marker in listOf("EARLY_HISTORY_FACT", "ROLE_IDENTITY", "RECENT_USER_TWO", "AFTER_IMPORT_NEW_MESSAGE")) {
                assertContainsOnce(ctx, marker)
            }
            assertEquals(8, db.messageDao().listBySession(id).size)
            assertTrue(db.messageDao().getById("$id-u0") != null)
        } finally {
            val cleanup = activeDatabase()
            cleanup.sessionDao().deleteById(id)
            cleanup.characterDao().deleteById("$id-character")
        }
    }
}
