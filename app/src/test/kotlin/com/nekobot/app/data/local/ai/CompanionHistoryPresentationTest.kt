package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionHistoryPresentationTest {
    private val gson = Gson()
    private val tools = listOf("agent_history_search" to "results", "agent_history_read" to "messages")
    private val partial = "这次只翻到这些，还有一些没翻完。"

    private fun message(name: String, result: Map<String, Any>): Map<String, Any> = mapOf(
        "role" to "tool", "name" to name, "tool_call_id" to "call-history", "content" to gson.toJson(result))

    private fun parsed(message: Map<String, Any>): Map<*, *> = gson.fromJson(message["content"] as String, Map::class.java)

    @Test fun removesInternalFieldsWhilePreservingOriginalTextAndLookupAddresses() {
        val row = mapOf("message_id" to "quote-1", "role" to "user", "timestamp" to "2026-10-01T08:00:00Z",
            "content" to "原话里也可以讨论 rounds_left 和 tokens_left。", "snippet" to "原话片段",
            "text_offset" to 12, "next_text_offset" to 32, "truncated" to true,
            "source_revision" to "internal-revision", "charged" to 400, "unknown_internal" to "private")
        for ((name, key) in tools) {
            val source = message(name, mapOf("success" to true, "historical_data_only" to true,
                "query" to "相遇", "anchor_message_id" to "quote-1", key to listOf(row),
                "rounds_left" to 1, "tokens_left" to 708, "charged" to 521, "room" to 90000,
                "unknown_internal" to "private", "status" to "truncated"))
            val original = source["content"]
            val sentMessage = CompanionPromptLanguage.message(source)
            val sent = parsed(sentMessage)
            assertEquals("call-history", sentMessage["tool_call_id"])
            assertEquals(original, source["content"])
            for (field in listOf("success", "historical_data_only", "query", "anchor_message_id"))
                assertEquals(parsed(source)[field], sent[field])
            val shown = (sent[key] as List<*>).single() as Map<*, *>
            for (field in listOf("message_id", "role", "timestamp", "content", "snippet", "text_offset", "next_text_offset", "truncated"))
                assertEquals((parsed(source)[key] as List<*>).single().let { (it as Map<*, *>)[field] }, shown[field])
            for (field in listOf("rounds_left", "tokens_left", "charged", "room", "unknown_internal", "status"))
                assertFalse(field, sent.containsKey(field))
            for (field in listOf("source_revision", "charged", "unknown_internal")) assertFalse(field, shown.containsKey(field))
            assertEquals(partial, sent["note"])
        }
    }

    @Test fun incompleteMeaningSurvivesEveryExistingSignalAndRepeatedRendering() {
        for ((name, key) in tools) for (signal in listOf(mapOf("status" to "truncated"),
            mapOf("budget_limited" to true), mapOf("more_candidates" to true), emptyMap())) {
            val source = message(name, mapOf("success" to true, key to listOf(mapOf(
                "message_id" to "quote", "content" to "原文", "truncated" to signal.isEmpty()))) + signal)
            val sent = CompanionPromptLanguage.message(source)
            assertEquals(partial, parsed(sent)["note"])
            assertEquals(sent, CompanionPromptLanguage.message(sent))
            val empty = CompanionPromptLanguage.message(message(name, mapOf("success" to true,
                key to emptyList<Any>(), "budget_limited" to true)))
            assertEquals("这次还没翻完。", parsed(empty)["note"])
            assertEquals(empty, CompanionPromptLanguage.message(empty))
        }
    }

    @Test fun failedStoppedDuplicateAndEmptyResultsKeepDifferentMeanings() {
        for ((name, key) in tools) {
            val base = mapOf("success" to true, key to emptyList<Any>(), "rounds_left" to 1, "tokens_left" to 0)
            val cases = listOf(
                mapOf("success" to false, "error" to "当前会话找不到这条可读取的消息", "budget_limited" to true) to
                    "这次没能翻到原话，记不清的地方就直说。",
                mapOf("recall_stopped" to true, "status" to "quota_exhausted", "more_candidates" to true) to
                    "先用已经想起来的事回答，记不清的地方就直说。",
                mapOf("visible_duplicates" to 1) to "相关内容前面已经翻到了，可以接着那些回忆说。",
                emptyMap<String, Any>() to "这次没有找到相关原话；不代表这件事没有发生过。")
            for ((fields, note) in cases) {
                val sent = CompanionPromptLanguage.message(message(name, base + fields))
                assertEquals(note, parsed(sent)["note"])
                assertEquals(sent, CompanionPromptLanguage.message(sent))
                assertFalse(parsed(sent).containsKey("rounds_left"))
                assertFalse(parsed(sent).containsKey("tokens_left"))
                if (fields["success"] == false) assertEquals(fields["error"], parsed(sent)["error"])
            }
        }
    }

    @Test fun completeResultsDoNotAcquireAFailureOrPartialNoticeOrInventATime() {
        for ((name, key) in tools) {
            val sent = parsed(CompanionPromptLanguage.message(message(name, mapOf("success" to true,
                "historical_data_only" to true, key to listOf(mapOf("message_id" to "quote", "role" to "assistant",
                    "content" to "一段完整的原文", "truncated" to false)), "status" to "found"))))
            assertFalse(sent.containsKey("note"))
            assertFalse(((sent[key] as List<*>).single() as Map<*, *>).containsKey("timestamp"))
        }
    }

    @Test fun ordinaryMessagesAndOtherToolsStayUnchanged() {
        val other = message("agent_tools_list", mapOf("rounds_left" to 7, "content" to "普通工具结果"))
        assertEquals(other, CompanionPromptLanguage.message(other))
        val ordinary = other + mapOf("role" to "assistant", "name" to "agent_history_read")
        assertEquals(ordinary, CompanionPromptLanguage.message(ordinary))
    }

    @Test fun rawHistoryStillSupportsBudgetDeduplicationAfterSendingASanitizedCopy() = runBlocking {
        var saved: String? = null
        val diagnostics = mutableListOf<Map<String, Any?>>()
        val guard = CompanionRecallBudget({ CompanionRecallSettings() }, { saved }, { saved = it },
            { _, value -> diagnostics += value })
        val tools = buildAgentRecallToolDefinitions()
        guard.prepare("main", emptyList(), tools, 32000, 4000)
        val body = "这是一条用来核对原话去重的虚构消息。"
        val raw = mapOf("success" to true, "messages" to listOf(mapOf("message_id" to "quote",
            "source_revision" to CompanionMemoryPolicy.digest(body), "content" to body, "text_offset" to 0)))
        val first = guard.read("main") { raw }
        val stored = message("agent_history_read", first)
        val usage = saved
        CompanionPromptLanguage.message(stored)
        assertEquals(usage, saved)
        assertTrue(parsed(stored).containsKey("rounds_left"))
        assertTrue(diagnostics.any { it.containsKey("tokens") })
        guard.prepare("main", listOf(stored), tools, 32000, 4000)
        val second = guard.read("main") { raw }
        assertEquals(1, second["visible_duplicates"])
        assertTrue((second["messages"] as List<*>).isEmpty())
        assertEquals("相关内容前面已经翻到了，可以接着那些回忆说。",
            parsed(CompanionPromptLanguage.message(message("agent_history_read", second)))["note"])
    }
}
