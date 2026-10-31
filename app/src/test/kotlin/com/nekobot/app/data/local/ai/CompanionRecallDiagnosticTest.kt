package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionRecallDiagnosticTest {
    @Test fun observerReportsActualQuotaCauseAndReturnsNoBody() = runBlocking {
        var saved: String? = null
        val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        val guard = CompanionRecallBudget({ CompanionRecallSettings(1,4000) }, { saved }, { saved=it }, { k,v -> events += k to v })
        guard.prepare("main",emptyList(),buildAgentRecallToolDefinitions(),32000,4000)
        guard.read("main") { mapOf("success" to true,"memories" to listOf(mapOf("id" to "secret-id","content" to "private quote"))) }
        guard.prepare("main",emptyList(),buildAgentRecallToolDefinitions(),32000,4000)
        assertEquals("rounds", events.last().second["reason"])
        assertEquals(true, events.last().second["exhausted"])
        assertEquals("found",events.first { it.first=="recall_result" }.second["status"])
        assertFalse(events.toString().contains("private quote")); assertFalse(events.toString().contains("secret-id"))
    }
    @Test fun diagnosticFailureCannotBreakRecallOrChangeItsResult() = runBlocking {
        var saved: String?=null
        val guard=CompanionRecallBudget({CompanionRecallSettings()}, {saved}, {saved=it}, {_,_->error("recorder unavailable")})
        guard.prepare("main",emptyList(),buildAgentRecallToolDefinitions(),32000,4000)
        assertEquals("empty",guard.read("main") {mapOf("success" to true,"memories" to emptyList<Any>())}["status"])
    }
    @Test fun contextRestrictionIsDistinctFromRoundExhaustion() = runBlocking {
        val events=mutableListOf<Map<String,Any?>>()
        var saved: String?=null
        val guard=CompanionRecallBudget({CompanionRecallSettings()}, {saved}, {saved=it}, {_,v->events+=v})
        guard.prepare("main",listOf(mapOf("role" to "system","content" to "context".repeat(1000))),emptyList(),2000,1500)
        assertEquals("context",events.last()["reason"])
        assertEquals(0,events.last()["rounds"])
    }
}
