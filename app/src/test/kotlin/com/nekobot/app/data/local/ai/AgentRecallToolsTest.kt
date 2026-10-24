package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRecallToolsTest {
    private class FakeReader : AgentRecallReader {
        var available = true
        var candidates = emptyList<AgentRecallCandidate>()
        var history = emptyList<AgentHistoryMessage>()
        var searchSessionId: String? = null
        var readSessionId: String? = null
        var receivedTerms = emptyList<String>()

        override suspend fun isAvailable(sessionId: String): Boolean = available

        override suspend fun searchRecall(
            sessionId: String,
            terms: List<String>,
            limit: Int
        ): List<AgentRecallCandidate> {
            searchSessionId = sessionId
            receivedTerms = terms
            return candidates
        }

        override suspend fun searchHistory(
            sessionId: String,
            terms: List<String>,
            limit: Int
        ): List<AgentHistoryMessage> {
            searchSessionId = sessionId
            receivedTerms = terms
            return history
        }

        override suspend fun readHistory(
            sessionId: String,
            anchorMessageId: String,
            before: Int,
            after: Int
        ): List<AgentHistoryMessage> {
            readSessionId = sessionId
            return history
        }
    }

    @Test
    fun `definitions are offered only when the long mode is enabled`() {
        val ordinary = buildLocalAgentToolDefinitions().mapNotNull(::toolNameOf).toSet()
        val longMode = buildLocalAgentToolDefinitions(recallEnabled = true)
            .mapNotNull(::toolNameOf).toSet()
        assertTrue(agentRecallToolIds.intersect(ordinary).isEmpty())
        assertTrue(longMode.containsAll(agentRecallToolIds))
        assertTrue(SessionToolCatalog.categoryById("memory")!!.toolIds.containsAll(agentRecallToolIds))
    }

    @Test
    fun `single session rejects retired facts even with legacy history arguments`() = runBlocking {
        val reader = FakeReader().apply {
            candidates = listOf(
                AgentRecallCandidate("new", "companion_memory", "偏好", "目前喜欢红茶", emptyList()),
                AgentRecallCandidate("old", "companion_memory", "偏好", "以前喜欢绿茶", emptyList(), current = false))
        }
        val handler = AgentRecallToolHandler("s1", reader, currentOnly = true)
        val current = handler.execute("agent_recall_search", mapOf("query" to "喜欢"))
        assertEquals(1, (current["results"] as List<*>).size)
        val history = handler.execute("agent_recall_search", mapOf("query" to "喜欢", "include_history" to true))
        assertEquals(1, (history["results"] as List<*>).size)
        assertFalse(Gson().toJson(buildAgentRecallToolDefinitions(currentOnly = true)).contains("include_history"))
        val legacy = AgentRecallToolHandler("s1", reader).execute("agent_recall_search", mapOf("query" to "喜欢"))
        assertEquals(2, (legacy["results"] as List<*>).size)
    }

    @Test
    fun `combined lookup ranks tags and returns both source types without duplicates`() = runBlocking {
        val reader = FakeReader().apply {
            candidates = listOf(
                AgentRecallCandidate("m1", "character_memory", "喜欢蛋黄月饼", "角色喜欢蛋黄月饼", listOf("月饼")),
                AgentRecallCandidate("e1", "episode", "一起过中秋", "曾经一起买月饼", listOf("中秋", "月饼"),
                    sourceSessionId = "s1", startMessageId = "u1", endMessageId = "a1"),
                AgentRecallCandidate("e1", "episode", "重复档案", "重复", listOf("中秋"))
            )
        }
        val result = AgentRecallToolHandler("s1", reader) { 3000 }
            .execute("agent_recall_search", mapOf("query" to "去年中秋的月饼", "limit" to 5))
        assertEquals(true, result["success"])
        assertEquals("s1", reader.searchSessionId)
        assertTrue("中秋" in reader.receivedTerms)
        val rows = result["results"] as List<*>
        assertEquals(2, rows.size)
        val types = rows.map { (it as Map<*, *>)["source_type"] }.toSet()
        assertEquals(setOf("character_memory", "episode"), types)
        assertTrue(rows.any { (it as Map<*, *>)["start_message_id"] == "u1" })
    }

    @Test
    fun `history tools return bounded visible text and support continuation`() = runBlocking {
        val longText = "中秋礼物是一本相册。" + "细节".repeat(3000)
        val reader = FakeReader().apply {
            history = listOf(
                AgentHistoryMessage("u1", "user", longText, "2025-10-06T10:00:00Z"),
                AgentHistoryMessage("hidden", "tool", "不可展示的工具轨迹", "2025-10-06T10:00:01Z")
            )
        }
        val handler = AgentRecallToolHandler("s1", reader) { 1200 }
        val found = handler.execute("agent_history_search", mapOf("query" to "中秋礼物"))
        val searchRows = found["results"] as List<*>
        assertEquals(1, searchRows.size)
        assertFalse(Gson().toJson(found).contains("不可展示"))

        val first = handler.execute("agent_history_read", mapOf("message_id" to "u1", "before" to 0, "after" to 0))
        val firstRow = (first["messages"] as List<*>).single() as Map<*, *>
        assertEquals(true, firstRow["truncated"])
        val offset = firstRow["next_text_offset"] as Int
        assertTrue(offset > 0)
        assertEquals("s1", reader.readSessionId)

        val next = handler.execute("agent_history_read", mapOf("message_id" to "u1", "text_offset" to offset))
        val nextRow = (next["messages"] as List<*>).single() as Map<*, *>
        assertEquals(offset, nextRow["text_offset"])
        val nextContent = nextRow["content"] as String
        assertEquals(longText.substring(offset).take(nextContent.length), nextContent)
    }

    @Test
    fun `disabled session refuses a queued recall call`() = runBlocking {
        val reader = FakeReader().apply { available = false }
        val result = AgentRecallToolHandler("s1", reader) { 1200 }
            .execute("agent_recall_search", mapOf("query" to "中秋"))
        assertEquals(false, result["success"])
        assertEquals(null, reader.searchSessionId)
    }

    @Test
    fun `custom tool set can disable all recall tools`() {
        val registry = SessionToolRegistry(
            loadEnabled = { setOf("agent_memory_read") },
            saveEnabled = { _, _ -> }
        )
        val visible = registry.filterDefinitions(
            "s1", buildLocalAgentToolDefinitions(recallEnabled = true)
        ).mapNotNull(::toolNameOf).toSet()
        assertTrue("agent_memory_read" in visible)
        assertTrue(agentRecallToolIds.intersect(visible).isEmpty())
    }
}
