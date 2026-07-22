package com.nekobot.app.data.local.ai

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTokenUsageTest {

    @Test
    fun `reported usage accepts compatible aliases and number types`() {
        val result = resolveLocalTokenUsage(
            usage = mapOf(
                "prompt_tokens" to 125.0,
                "completion_tokens" to 25L,
                "total_tokens" to 150
            ),
            messages = listOf(mapOf("role" to "user", "content" to "不会采用估算")),
            outputText = "回复"
        )

        assertEquals(125, result.inputTokens)
        assertEquals(25, result.outputTokens)
        assertFalse(result.estimated)
    }

    @Test
    fun `missing usage falls back to request and response estimate`() {
        val result = resolveLocalTokenUsage(
            usage = emptyMap<String, Any>(),
            messages = listOf(mapOf("role" to "user", "content" to "测试文本")),
            outputText = "abcd"
        )

        assertTrue(result.inputTokens > 0)
        assertEquals(1, result.outputTokens)
        assertTrue(result.estimated)
    }

    @Test
    fun `current context starts from latest measured prompt instead of summing every turn`() {
        val messages = listOf(
            LocalContextTokenMessage("第一轮问题", null, null),
            LocalContextTokenMessage("第一轮回答", 100, 20),
            LocalContextTokenMessage("abcd", null, null)
        )

        assertEquals(125L, currentLocalContextTokens(messages))
    }

    @Test
    fun `text estimate handles mixed chinese and latin`() {
        assertEquals(4, estimateLocalTextTokens("测试文本"))
        assertEquals(1, estimateLocalTextTokens("abcd"))
        assertEquals(3, estimateLocalTextTokens("测试abcd"))
    }

    @Test
    fun `missing usage record is recovered from persisted assistant message`() {
        val result = reconcileLocalTokenUsageRecords(
            records = emptyList(),
            messages = listOf(persistedMessage("message-1"))
        )

        assertEquals(1, result.recoveredCount)
        assertEquals(1, result.records.size)
        assertEquals("message-1", result.records.single().get("message_id").asString)
        assertTrue(result.records.single().get("recovered").asBoolean)
    }

    @Test
    fun `legacy matching record is linked without duplicate usage`() {
        val result = reconcileLocalTokenUsageRecords(
            records = listOf(usageRecord()),
            messages = listOf(persistedMessage("message-1"))
        )

        assertEquals(0, result.recoveredCount)
        assertEquals(1, result.records.size)
        assertEquals("message-1", result.records.single().get("message_id").asString)
    }

    @Test
    fun `legacy matching consumes one record when token counts repeat`() {
        val result = reconcileLocalTokenUsageRecords(
            records = listOf(usageRecord()),
            messages = listOf(persistedMessage("message-1"), persistedMessage("message-2"))
        )

        assertEquals(1, result.recoveredCount)
        assertEquals(2, result.records.size)
        assertEquals(setOf("message-1", "message-2"), result.records.map { it.get("message_id").asString }.toSet())
    }

    @Test
    fun `linked usage record is not recovered twice`() {
        val result = reconcileLocalTokenUsageRecords(
            records = listOf(usageRecord(messageId = "message-1")),
            messages = listOf(persistedMessage("message-1"))
        )

        assertEquals(0, result.recoveredCount)
        assertEquals(1, result.records.size)
        assertFalse(result.changed)
    }

    @Test
    fun `fork copy becomes inherited usage instead of duplicate model usage`() {
        val original = persistedMessage("message-1")
        val forkCopy = persistedMessage("message-2", sessionId = "session-2")
        val result = reconcileLocalTokenUsageRecords(
            records = listOf(usageRecord(messageId = original.id)),
            messages = listOf(original, forkCopy)
        )

        assertEquals(0, result.recoveredCount)
        assertEquals(2, result.records.size)
        val inherited = result.records.single { it.get("message_id").asString == forkCopy.id }
        assertTrue(inherited.get("inherited").asBoolean)
        assertEquals("fork", inherited.get("source").asString)
    }

    @Test
    fun `previously recovered fork copy is migrated to inherited usage`() {
        val original = persistedMessage("message-1")
        val forkCopy = persistedMessage("message-2", sessionId = "session-2")
        val copiedRecord = usageRecord(messageId = forkCopy.id).apply {
            addProperty("id", "usage-2")
            addProperty("session_id", forkCopy.sessionId)
        }
        val result = reconcileLocalTokenUsageRecords(
            records = listOf(usageRecord(messageId = original.id), copiedRecord),
            messages = listOf(original, forkCopy)
        )

        assertEquals(2, result.records.size)
        val inherited = result.records.single { it.get("message_id").asString == forkCopy.id }
        assertTrue(inherited.get("inherited").asBoolean)
        assertEquals("fork", inherited.get("source").asString)
    }

    private fun persistedMessage(id: String, sessionId: String = "session-1") = LocalPersistedTokenMessage(
        id = id,
        sessionId = sessionId,
        model = "model-1",
        inputTokens = 12,
        outputTokens = 3,
        timestamp = "2026-07-22T12:00:00Z",
        createdAt = "2026-07-22T12:00:00Z",
        sessionCreatedAt = if (sessionId == "session-1") "2026-07-22T11:00:00Z" else "2026-07-22T13:00:00Z",
        content = "同一条回复"
    )

    private fun usageRecord(messageId: String? = null) = JsonObject().apply {
        addProperty("id", "usage-1")
        messageId?.let { addProperty("message_id", it) }
        addProperty("session_id", "session-1")
        addProperty("model", "model-1")
        addProperty("input_tokens", 12)
        addProperty("output_tokens", 3)
        addProperty("total_tokens", 15)
        addProperty("timestamp", "2026-07-22T12:00:00Z")
        addProperty("source", "chat")
        addProperty("purpose", TokenStatsManager.PURPOSE_CHAT)
        addProperty("date", "2026-07-22")
    }
}
