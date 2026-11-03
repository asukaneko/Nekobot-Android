package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.*
import java.time.Instant

/** Explicit conversion decisions, separate from successful-summary receipts and from user forgetting.
 * Only automatic recent input/extraction uses this ledger; original-message lookup remains available.
 */
internal object FusedSourceExclusions {
    const val PREFIX = "conversion_excluded:"
    fun key(row: LocalMessageEntity) = PREFIX + CompanionMemoryPolicy.fingerprint(row)
    fun eligible(rows: List<LocalMessageEntity>, keys: Set<String>) = rows.filterNot { key(it) in keys }
    suspend fun eligible(dao: CompanionMemoryDao, scopeId: String, rows: List<LocalMessageEntity>) =
        eligible(rows, dao.appliedOpKeys(scopeId, PREFIX).toSet())
    suspend fun save(dao: CompanionMemoryDao, scopeId: String, rows: List<LocalMessageEntity>) {
        val existing = dao.appliedOpKeys(scopeId, PREFIX).toSet()
        val now = Instant.now().toString()
        for (key in rows.map(::key).distinct().filterNot(existing::contains))
            dao.insertAppliedOp(CompanionAppliedOpEntity(scopeId, key, null, now))
    }
}
