package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionAppliedOpEntity
import com.nekobot.app.data.local.db.CompanionMemoryDao
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import java.time.LocalDate

internal object FusedUncertainMemory {
    const val MAX_ITEMS = 3
    const val MAX_TOKENS = 500
    const val ACTIVE_TURNS = 3
    const val HEADER = "拿不准的记忆："
    const val INSTRUCTION = "这些是你拿不准的事，找自然的时机问一句；对方回答后用记忆工具改正。不要每条都问，也不要一次问很多。"

    fun injectionPrefix(messageId: String) = "uncertain_turn:${CompanionMemoryPolicy.digest(messageId)}:"
    fun select(rows: List<CompanionMemoryEntity>, hits: Set<String>, alreadyInjected: Set<String> = emptySet(),
        today: LocalDate = LocalDate.now()): List<CompanionMemoryEntity> {
        val selected = mutableListOf<CompanionMemoryEntity>()
        for (row in rows.filter { it.pendingConfirm && it.current && it.valid && !it.voided && CompanionImportPolicy.usable(it) &&
            (it.uncertainInjectedTurns < ACTIVE_TURNS || it.id in hits || it.id in alreadyInjected) }
            .sortedWith(compareByDescending<CompanionMemoryEntity> { it.createdAt }.thenBy { it.id })) {
            if (selected.size >= MAX_ITEMS) break
            if (estimateLocalTextTokens(render(selected + row, today)) + (selected.size + 1) * 8 <= MAX_TOKENS) selected += row
        }
        return selected
    }

    fun render(rows: List<CompanionMemoryEntity>, today: LocalDate, prefix: (String) -> String = { "" }): String =
        if (rows.isEmpty()) "" else "$HEADER\n$INSTRUCTION\n" + rows.joinToString("\n") {
            prefix(it.id) + CompanionPromptLanguage.memory(it, today)
        }

    /** 调用者提供事务。相同用户消息的重试、重新生成和变体只记一次。 */
    suspend fun recordInjected(dao: CompanionMemoryDao, scopeId: String, messageId: String,
        rows: List<CompanionMemoryEntity>, now: String) {
        for (row in rows) {
            val key = injectionPrefix(messageId) + row.id
            if (dao.appliedOp(scopeId, key) != null) continue
            dao.insertAppliedOp(CompanionAppliedOpEntity(scopeId, key, row.id, now))
            dao.incrementUncertainInjected(listOf(row.id))
        }
    }
}
