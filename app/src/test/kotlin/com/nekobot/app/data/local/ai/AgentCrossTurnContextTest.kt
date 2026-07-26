package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCrossTurnContextTest {

    @Test
    fun currentTurnTraceExcludesExistingContextAndKeepsFullToolResult() {
        val fileContent = "TXT正文-".repeat(4_000)
        val existing = listOf(
            mapOf<String, Any>("role" to "user", "content" to "上一轮"),
            mapOf<String, Any>("role" to "assistant", "content" to "上一轮回复")
        )
        val current = listOf(
            mapOf<String, Any>(
                "role" to "assistant",
                "content" to "",
                "tool_calls" to listOf(
                    mapOf(
                        "id" to "call-read",
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "workspace_read_file",
                            "arguments" to """{"path":"notes/source.txt"}"""
                        )
                    )
                )
            ),
            mapOf<String, Any>(
                "role" to "tool",
                "tool_call_id" to "call-read",
                "name" to "workspace_read_file",
                "content" to """{"success":true,"path":"notes/source.txt","content":"$fileContent"}"""
            )
        )

        val trace = extractCurrentTurnToolCallHistory(existing + current, existing.size)

        assertEquals(2, trace.size)
        assertFalse(trace.any { it["content"] == "上一轮回复" })
        assertTrue((trace.last()["content"] as String).contains(fileContent))
    }

    @Test
    fun storedToolHistoryIsExpandedIntoNextAgentRequestWithoutLosingTxt() {
        val fileContent = "需要跨轮保留的原文。".repeat(1_000)
        val toolHistory = listOf(
            mapOf<String, Any>(
                "role" to "assistant",
                "content" to "",
                "tool_calls" to listOf(
                    mapOf(
                        "id" to "call-read",
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "workspace_read_file",
                            "arguments" to """{"path":"novel/chapter.txt"}"""
                        )
                    )
                )
            ),
            mapOf<String, Any>(
                "role" to "tool",
                "tool_call_id" to "call-read",
                "name" to "workspace_read_file",
                "content" to """{"success":true,"path":"novel/chapter.txt","content":"$fileContent"}"""
            )
        )
        val encoded = encodeToolCallHistory(toolHistory)
        val session = LocalSessionEntity(
            id = "agent-session",
            name = "Agent",
            createdAt = "2026-07-26T00:00:00Z",
            updatedAt = "2026-07-26T00:00:00Z",
            sessionMode = "agent"
        )
        val history = listOf(
            LocalMessageEntity(
                id = "user-1",
                sessionId = session.id,
                role = "user",
                content = "读取章节",
                timestamp = "1",
                createdAt = "2026-07-26T00:00:00Z"
            ),
            LocalMessageEntity(
                id = "assistant-1",
                sessionId = session.id,
                role = "assistant",
                content = "已经读取完成。",
                timestamp = "2",
                createdAt = "2026-07-26T00:00:01Z",
                toolCallHistory = encoded
            )
        )

        val storedMessages = LocalPromptBuilder.build(
            session = session,
            character = null,
            history = history,
            userInput = "原文里提到了什么？"
        )
        val expanded = expandHiddenToolHistory(storedMessages)
        val restoredToolMessage = expanded.last { it["role"] == "tool" }
        val toolIndex = expanded.indexOf(restoredToolMessage)
        val finalAssistantIndex = expanded.indexOfFirst { it["content"] == "已经读取完成。" }
        val followUpIndex = expanded.indexOfFirst { it["content"] == "原文里提到了什么？" }

        assertEquals(toolHistory, decodeToolCallHistory(encoded))
        assertTrue((restoredToolMessage["content"] as String).contains(fileContent))
        assertTrue(toolIndex < finalAssistantIndex)
        assertTrue(finalAssistantIndex < followUpIndex)
    }
}
