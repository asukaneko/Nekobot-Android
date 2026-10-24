package com.nekobot.app.data.local

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.CharacterThinkingSettings
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.ai.CompanionPromptLanguage
import com.nekobot.app.data.local.ai.DEFAULT_CHARACTER_THINKING_PROMPT
import com.nekobot.app.data.local.ai.ChatRequest
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.ai.LocalPipelineCallbacks
import com.nekobot.app.data.local.ai.PipelineContext
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class CompanionPromptStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun session(id: String, companion: Boolean = true) = LocalSessionEntity(id, "虚构测试$id", characterId = "character-$id",
        companionMode = companion, inheritCharacter = companion, longConversationEnabled = companion,
        sessionMode = if (companion) "agent" else "character", createdAt = "before", updatedAt = "before")

    @Test fun legacySettingsAreCopiedOnceAndChangesStayWithTheirRole() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        try {
            for (id in listOf("first", "second", "ordinary")) db.sessionDao().upsert(session(id, id != "ordinary"))
            db.messageDao().upsert(LocalMessageEntity("quote", "first", "user", "保留原话", timestamp = "before", createdAt = "before"))
            var legacy = CharacterThinkingSettings(false, "旧自定义全文")
            val store = CompanionPromptStore(db) { legacy }
            assertEquals("旧自定义全文", store.get("first").thinkingPrompt)
            assertFalse(store.get("first").thinkingEnabled)
            legacy = CharacterThinkingSettings(true, "全局后来修改")
            assertEquals("旧自定义全文", store.get("second").thinkingPrompt)
            coroutineScope {
                val speaking = async { store.saveSpeaking("first", "角色甲说话") }
                val thinking = async { store.saveThinking("first", "角色甲思考") }
                speaking.await(); thinking.await()
            }
            assertEquals("角色甲说话", store.get("first").speakingPrompt)
            assertEquals("角色甲思考", store.get("first").thinkingPrompt)
            assertEquals("旧自定义全文", store.get("second").thinkingPrompt)
            assertFalse(store.get("second").thinkingEnabled)
            assertNull(db.sessionDao().getById("ordinary")!!.companionThinkingPrompt)
            store.setThinkingEnabled("first", true)
            store.saveThinking("first", DEFAULT_CHARACTER_THINKING_PROMPT)
            store.saveSpeaking("first", CompanionPromptLanguage.speakingRule)
            assertEquals("", store.get("first").thinkingPrompt)
            assertEquals("", store.get("first").speakingPrompt)
            assertTrue(store.get("first").thinkingEnabled)
            assertEquals("保留原话", db.messageDao().getById("quote")!!.content)
            assertEquals("before", db.sessionDao().getById("first")!!.updatedAt)
            assertEquals("全局后来修改", legacy.customPrompt)
            assertTrue(runCatching { store.saveThinking("ordinary", "不允许") }.isFailure)
            assertTrue(runCatching { store.saveSpeaking("missing", "不允许") }.isFailure)
        } finally { db.close() }
    }

    @Test fun storedDefaultsDoNotInheritLegacyAndPersistenceSurvivesReopen() = runBlocking<Unit> {
        val name = "prompt-reopen-${UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(context, NekobotDatabase::class.java, name).build()
        var db = open()
        try {
            db.sessionDao().upsert(session("new").copy(companionSpeakingPrompt = "", companionThinkingPrompt = "", companionThinkingEnabled = false))
            var store = CompanionPromptStore(db) { CharacterThinkingSettings(true, "旧全局不能覆盖") }
            assertEquals("", store.get("new").thinkingPrompt)
            store.saveThinking("new", "保存全文\n{{char}}")
            db.close()
            db = open()
            store = CompanionPromptStore(db) { CharacterThinkingSettings() }
            assertEquals("保存全文\n{{char}}", store.get("new").thinkingPrompt)
            assertFalse(store.get("new").thinkingEnabled)
            store.saveThinking("new", "\n  ")
            assertEquals("", store.get("new").thinkingPrompt)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun productionCallbacksUseOneRoleSnapshotUntilTheNextPreparation() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        try {
            val row = session("snapshot").copy(companionSpeakingPrompt = "说话快照", companionThinkingPrompt = "思考快照", companionThinkingEnabled = false)
            db.sessionDao().upsert(row)
            val store = CompanionPromptStore(db) { CharacterThinkingSettings() }
            val model = LocalAiModelEntity("synthetic", "synthetic", protocol = "openai_chat", apiKey = "test-key",
                baseUrl = "http://127.0.0.1", model = "synthetic", createdAt = "before")
            val callbacks = LocalPipelineCallbacks(db, LocalAiClient(), model, row, null)
            val ctx = PipelineContext(ChatRequest.forLocal(row.id, "不会发送到模型"))
            callbacks.prepareCharacterPromptSettings(ctx)
            store.saveThinking(row.id, "下一轮思考")
            store.saveSpeaking(row.id, "下一轮说话")
            store.setThinkingEnabled(row.id, true)
            assertEquals("说话快照", callbacks.getCompanionSpeakingPrompt(ctx))
            assertEquals(CharacterThinkingSettings(false, "思考快照"), callbacks.getCharacterThinkingSettings(ctx))
            callbacks.prepareCharacterPromptSettings(ctx)
            assertEquals("下一轮说话", callbacks.getCompanionSpeakingPrompt(ctx))
            assertEquals(CharacterThinkingSettings(true, "下一轮思考"), callbacks.getCharacterThinkingSettings(ctx))
        } finally { db.close() }
    }

    @Test fun portableArchiveRoundTripKeepsRolePromptFieldsAndOriginalQuote() = runBlocking<Unit> {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator"))
        val original = ServiceContainer.prefs.activeDbName
        val source = "prompt-source-${UUID.randomUUID()}"
        val target = "prompt-target-${UUID.randomUUID()}"
        try {
            ServiceContainer.switchLocalDb(source)
            val sourceDb = NekobotDatabase.get(context, source)
            sourceDb.characterDao().upsert(LocalCharacterEntity("character-export", "林小满（虚构）", createdAt = "before", updatedAt = "before"))
            val row = session("export").copy(companionSpeakingPrompt = "单独说话", companionThinkingPrompt = "单独思考", companionThinkingEnabled = false)
            sourceDb.sessionDao().upsert(row)
            CompanionMemoryStore(sourceDb).ensureScope(row)
            sourceDb.messageDao().upsert(LocalMessageEntity("quote", "export", "user", "归档原话", timestamp = "before", createdAt = "before"))
            val bytes = ByteArrayOutputStream()
            val manager = PortableDataArchiveManager(context)
            manager.export(setOf(PortableDataCategory.CONVERSATIONS, PortableDataCategory.CHARACTERS), "", bytes, "synthetic")
            ServiceContainer.switchLocalDb(target)
            manager.prepareImport(ByteArrayInputStream(bytes.toByteArray()), "").use { archive ->
                val selected = archive.preview.categories.associate { it.category to it.details.mapTo(linkedSetOf()) { detail -> detail.key } }
                manager.commitImport(archive, manager.previewImport(archive, selected))
            }
            val targetDb = NekobotDatabase.get(context, target)
            val copied = CompanionPromptStore(targetDb) { CharacterThinkingSettings(true, "不能覆盖") }.get("export")
            assertEquals("单独说话", copied.speakingPrompt)
            assertEquals("单独思考", copied.thinkingPrompt)
            assertFalse(copied.thinkingEnabled)
            assertEquals("归档原话", targetDb.messageDao().getById("quote")!!.content)
        } finally {
            ServiceContainer.switchLocalDb(original)
            for (name in listOf(source, target)) {
                ServiceContainer.prefs.removeDbProfile(name)
                NekobotDatabase.deleteProfileFile(context, name)
            }
        }
    }
}
