package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.*
import java.time.LocalDate
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FusedUncertainMemoryTest {
    private val today = LocalDate.of(2026, 10, 23)
    private fun memory(id: Int) = CompanionMemoryEntity("m$id", "scope", "key$id", 1, "fact", "虚构角色",
        "我记得他之前在杭州，现在好像搬到上海了？", "active", pendingConfirm = true,
        createdAt = "2026-10-${10 + id}", updatedAt = "now")

    @Test fun onlyLatestThreeFitAndLongEntriesDoNotExceedBudget() {
        val selected = FusedUncertainMemory.select((1..5).map(::memory), emptySet(), today = today)
        assertEquals(listOf("m5", "m4", "m3"), selected.map { it.id })
        val huge = memory(6).copy(content = "字".repeat(3000))
        val bounded = FusedUncertainMemory.select(selected + huge, emptySet(), today = today)
        assertFalse(huge in bounded)
        assertTrue(estimateLocalTextTokens(FusedUncertainMemory.render(bounded, today)) <= 500)
    }
    @Test fun dormantAfterThreeTurnsAndRecallWakesItWithoutResettingCounter() {
        val row = memory(1).copy(uncertainInjectedTurns = 3)
        assertTrue(FusedUncertainMemory.select(listOf(row), emptySet()).isEmpty())
        assertEquals(listOf(row), FusedUncertainMemory.select(listOf(row), setOf(row.id)))
        assertEquals(3, row.uncertainInjectedTurns)
        assertEquals(listOf(row), FusedUncertainMemory.select(listOf(row), emptySet(), setOf(row.id)))
    }
    @Test fun voidedExpiredInvalidAndConfirmedEntriesNeverAppear() {
        val row = memory(1)
        val excluded = listOf(row.copy(voided = true), row.copy(current = false), row.copy(valid = false),
            row.copy(pendingConfirm = false), row.copy(expiresAt = "2000-01-01"))
        assertTrue(FusedUncertainMemory.select(excluded, setOf(row.id)).isEmpty())
        assertTrue(CompanionCoreMemory.rows(listOf(row.copy(kind = "core", alwaysProvide = true))).isEmpty())
        assertTrue(CompanionReferences.merge(emptyList(), listOf(row.id), listOf(row), listOf(row)).isEmpty())
    }
    @Test fun uncertainBlockIsSeparateBetweenReferencesAndCurrentUser() {
        val raw = LocalMessageEntity("u", "s", "user", "对，上个月搬的", timestamp = "now", createdAt = "now")
        val messages = FusedCompanionContext.assembleMessages("规则", emptyList(), emptyList(), "普通参考", "时间",
            mapOf("role" to "user", "content" to raw.content), raw, uncertain = FusedUncertainMemory.render(listOf(memory(1)), today))
        assertEquals(listOf("规则", "普通参考", FusedUncertainMemory.render(listOf(memory(1)), today), raw.content, "时间"),
            messages.map { it["content"] })
        assertTrue(messages[2]["content"].toString().contains(FusedUncertainMemory.INSTRUCTION))
    }
    @Test fun repeatedBuildRegenerationAndVariantsOnlyCountOncePerUserMessage() = runBlocking {
        val markers = mutableMapOf<String, CompanionAppliedOpEntity>(); var count = 0
        val dao = Proxy.newProxyInstance(CompanionMemoryDao::class.java.classLoader, arrayOf(CompanionMemoryDao::class.java)) { _, method, args ->
            when (method.name) {
                "appliedOp" -> markers[args!![1]]
                "insertAppliedOp" -> { val row = args!![0] as CompanionAppliedOpEntity; markers[row.opKey] = row; Unit }
                "incrementUncertainInjected" -> { count++; Unit }
                else -> error(method.name)
            }
        } as CompanionMemoryDao
        repeat(4) { FusedUncertainMemory.recordInjected(dao, "scope", "u1", listOf(memory(1)), "now") }
        assertEquals(1, count)
        FusedUncertainMemory.recordInjected(dao, "scope", "u2", listOf(memory(1)), "now")
        assertEquals(2, count)
    }
}
