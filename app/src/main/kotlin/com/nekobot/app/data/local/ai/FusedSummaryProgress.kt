package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionReceiptEntity
import com.nekobot.app.data.local.db.CompanionScopeEntity
import com.nekobot.app.data.local.db.LocalMessageEntity

/** Room receipts are durable, fingerprinted progress isolated by profile/session/character scope. */
internal object FusedSummaryProgress {
    fun summarizedIds(scope: CompanionScopeEntity, rows: List<LocalMessageEntity>,
        receipts: List<CompanionReceiptEntity>): Set<String> {
        val fingerprints = rows.filter { !it.deleted && it.sessionId == scope.sessionId }
            .associate { it.id to CompanionMemoryPolicy.fingerprint(it) }
        return receipts.filter { it.scopeId == scope.id && fingerprints[it.messageId] == it.fingerprint }
            .map { it.messageId }.toSet()
    }

    /** Called inside the receipt transaction; a changed batch never advances partially. */
    fun receipts(scope: CompanionScopeEntity, target: List<LocalMessageEntity>,
        fresh: List<LocalMessageEntity>): List<CompanionReceiptEntity> {
        val latest = fresh.associateBy { it.id }
        if (target.isEmpty() || target.any { row ->
            row.sessionId != scope.sessionId || latest[row.id]?.let {
                !it.deleted && it.sessionId == scope.sessionId &&
                    CompanionMemoryPolicy.fingerprint(it) == CompanionMemoryPolicy.fingerprint(row)
            } != true
        }) throw FusedSummaryFailure(FusedSummaryFailure.Reason.SOURCE_CHANGED)
        return target.map { CompanionReceiptEntity(scope.id, it.id, CompanionMemoryPolicy.fingerprint(it)) }
    }
}
