package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionRecallPresentationTest {
    private val partialNotice = "这次只翻到这些，还有一些没翻完。"
    private val body = "10月12日：我陪他改好了简历。"
    private val tools = listOf("agent_memory_read" to "memories", "agent_recall_search" to "results")

    private fun message(name: String, result: Map<String, Any>): Map<String, Any> = mapOf(
        "role" to "tool", "name" to name, "tool_call_id" to "call", "content" to Gson().toJson(result))

    @Test fun nonemptyPartialResultsKeepTheirMeaningWithoutTechnicalFields() {
        val signals = listOf(mapOf("status" to "truncated"), mapOf("budget_limited" to true),
            mapOf("more_candidates" to true), emptyMap())
        for ((name, key) in tools) for (signal in signals) {
            val row = mapOf("content" to body, "truncated" to signal.isEmpty())
            val source = message(name, mapOf("success" to true, key to listOf(row),
                "rounds_left" to 2, "tokens_left" to 1000) + signal)
            val original = source["content"]
            val sent = CompanionPromptLanguage.message(source)
            assertEquals("$body\n$partialNotice", sent["content"])
            assertEquals("call", sent["tool_call_id"])
            assertEquals(original, source["content"])
            for (technical in listOf("status", "Token", "tokens_left", "rounds_left", "预算", "调用")) {
                assertFalse(technical, sent["content"].toString().contains(technical, ignoreCase = true))
            }
        }
    }

    @Test fun aPartialResultWithNoBodyDoesNotClaimNothingWasFound() {
        for ((name, key) in tools) {
            val sent = CompanionPromptLanguage.message(message(name, mapOf(
                "success" to true, key to emptyList<Any>(), "status" to "truncated")))
            assertEquals("这次还没翻完。", sent["content"])
        }
    }

    @Test fun completeResultsKeepExactlyTheirOriginalNaturalText() {
        for ((name, key) in tools) {
            val sent = CompanionPromptLanguage.message(message(name, mapOf("success" to true,
                key to listOf(mapOf("content" to body, "truncated" to false)), "status" to "found",
                "budget_limited" to false, "more_candidates" to false)))
            assertEquals(body, sent["content"])
        }
    }

    @Test fun partialNoticeDoesNotReplaceFailureOrStoppedMeaning() {
        for ((name, key) in tools) {
            val failed = CompanionPromptLanguage.message(message(name, mapOf("success" to false,
                key to emptyList<Any>(), "status" to "error", "more_candidates" to true)))
            assertEquals("这次没能翻到记忆，记不清的地方就直说。", failed["content"])
            val stopped = CompanionPromptLanguage.message(message(name, mapOf("success" to true,
                key to emptyList<Any>(), "recall_stopped" to true, "status" to "quota_exhausted",
                "more_candidates" to true)))
            assertEquals("先用已经想起来的事回答，记不清的地方就直说。", stopped["content"])
        }
    }

    @Test fun unverifiedEvidenceAndBodySurviveThePartialNotice() {
        val evidence = CompanionImportPolicy.UNVERIFIED_EVIDENCE
        for ((name, key) in tools) {
            val source = message(name, mapOf("success" to true, "budget_limited" to true,
                key to listOf(mapOf("content" to body, "evidence" to evidence))))
            assertEquals("（$evidence）$body\n$partialNotice", CompanionPromptLanguage.message(source)["content"])
        }
    }

    @Test fun renderingAgainDoesNotAppendTheNoticeTwiceOrChangeOtherTools() {
        val source = message("agent_memory_read", mapOf("success" to true, "budget_limited" to true,
            "memories" to listOf(mapOf("content" to body))))
        val once = CompanionPromptLanguage.message(source)
        assertEquals(once, CompanionPromptLanguage.message(once))
        val other = message("agent_tools_list", mapOf("success" to true, "status" to "truncated",
            "messages" to listOf(mapOf("content" to body))))
        assertEquals(other, CompanionPromptLanguage.message(other))
    }

    @Test fun actualBudgetTruncationReachesTheModelPresentationAndKeepsRemainingAllowance() = runBlocking {
        var saved: String? = null
        val guard = CompanionRecallBudget({ CompanionRecallSettings() }, { saved }, { saved = it })
        guard.prepare("main", emptyList(), buildAgentRecallToolDefinitions(), 32000, 4000)
        val result = guard.read("main") { mapOf("success" to true,
            "memories" to (1..40).map { mapOf("id" to "m$it", "content" to "一条很长的回忆".repeat(80)) }) }
        assertEquals("truncated", result["status"])
        assertEquals(2, result["rounds_left"])
        assertTrue((result["tokens_left"] as Int) > 0)
        val source = message("agent_memory_read", result)
        val raw = source["content"]
        val sent = CompanionPromptLanguage.message(source)
        assertTrue(sent["content"].toString().endsWith(partialNotice))
        assertEquals(raw, source["content"])
        assertFalse(guard.prepare("main", emptyList(), buildAgentRecallToolDefinitions(), 32000, 4000).mustAnswer)
    }
}
