package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionRecallStatusTest {
    private var saved: String? = null
    private fun guard(rounds: Int = 3, tokens: Int = 4000) = CompanionRecallBudget(
        { CompanionRecallSettings(rounds, tokens) }, { saved }, { saved = it })
    private suspend fun prepare(g: CompanionRecallBudget, context: Int = 32000) =
        g.prepare("main", emptyList(), buildAgentRecallToolDefinitions(), context, 4000)
    private fun memories(vararg bodies: String) = mapOf<String, Any>("success" to true,
        "memories" to bodies.mapIndexed { i, b -> mapOf("id" to "f$i", "content" to b) })

    @Test fun defaultsAreThreeCallsAndFourThousandTokens() {
        assertEquals(CompanionRecallSettings(3, 4000), CompanionRecallSettings())
    }

    @Test fun foundAndEmptyCarryStatusAndRemainingAllowance() = runBlocking {
        val g = guard(); prepare(g)
        val found = g.read("main") { memories("你换了部门") }
        assertEquals("found", found["status"])
        assertEquals(2, found["rounds_left"])
        assertTrue((found["tokens_left"] as Int) in 1 until 4000)
        assertNull(found["note"])
        prepare(g)
        val empty = g.read("main") { memories() }
        assertEquals("empty", empty["status"])
        assertEquals(CompanionRecallBudget.EMPTY_NOTE, empty["note"])
    }

    @Test fun oneLargeResultIsCappedPerCall() = runBlocking {
        val g = guard(); prepare(g)
        val result = g.read("main") { memories(*Array(40) { "很长的一条记忆正文".repeat(40) }) }
        assertEquals("truncated", result["status"])
        assertTrue((result["tokens_left"] as Int) >= 4000 - CompanionRecallBudget.PER_CALL_TOKENS)
        prepare(g)
        assertNotEquals("quota_exhausted", g.read("main") { memories("第二次") }["status"])
    }

    @Test fun totalIsAlsoLimitedByContextShare() {
        assertEquals(1500, CompanionRecallBudget.totalLimit(4000, 10000))
        assertEquals(4000, CompanionRecallBudget.totalLimit(4000, 128000))
        assertEquals(1500, CompanionRecallBudget.perCallAllowance(4000, 9000))
        assertEquals(300, CompanionRecallBudget.perCallAllowance(300, 9000))
    }

    @Test fun exhaustedAndErrorAreReportedAsStatus() = runBlocking {
        val g = guard(rounds = 1); prepare(g)
        g.read("main") { memories("一次") }
        prepare(g)
        val stopped = g.read("main") { error("不应读取") }
        assertEquals("quota_exhausted", stopped["status"])
        assertEquals(0, stopped["rounds_left"])
        saved = null
        val h = guard(); prepare(h)
        val failed = h.read("main") { error("数据库忙") }
        assertEquals("error", failed["status"])
        assertEquals(false, failed["success"])
    }

    @Test fun clearingTheTurnUsageGivesRegenerateAFullAllowance() = runBlocking {
        val g = guard(rounds = 1); prepare(g)
        g.read("main") { memories("一次") }
        saved = null // 重新生成时清除这句话的用量记录
        val again = guard(rounds = 1); prepare(again)
        val result = again.read("main") { memories("再次") }
        assertEquals("found", result["status"])
        assertEquals(0, result["rounds_left"])
    }

    @Test fun memoryReadKeepsOnlyRelevantItems() {
        val picked = FusedCompanionTools.relevant(listOf("a" to 0f, "b" to 0.3f, "c" to 0.9f) + (1..20).map { "x$it" to 0.1f })
        assertEquals(listOf("c", "b"), picked.take(2))
        assertFalse("a" in picked)
        assertEquals(FusedCompanionTools.READ_LIMIT, picked.size)
    }

    @Test fun rulesUseNaturalRecallWithoutTechnicalWords() {
        val rules = CompanionRecallBudget.QUERY_RULES
        for (word in listOf("status", "rounds_left", "tokens_left", "quota_exhausted")) assertFalse(word, rules.contains(word))
        assertFalse(rules.contains("本轮回查已结束"))
    }
}
