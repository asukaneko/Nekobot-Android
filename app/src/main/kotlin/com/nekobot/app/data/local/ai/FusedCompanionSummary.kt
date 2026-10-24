package com.nekobot.app.data.local.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 同一次总结记忆等待中的顺序执行；每包重新读取原话和回执，失败或停止立即向外传播。 */
internal object FusedCompanionSummary {
    suspend fun <T> waiting(notice: suspend (Boolean) -> Unit, action: suspend () -> T): T {
        notice(true)
        try { return action() }
        finally { withContext(NonCancellable) { notice(false) } }
    }

    suspend fun advance(before: Set<String>, summarize: suspend () -> Boolean, reload: suspend () -> Set<String>) {
        if (!summarize()) throw FusedSummaryFailure(FusedSummaryFailure.Reason.UNKNOWN)
        val after = FusedSummaryRetry.saving { reload() }
        if (!after.containsAll(before)) throw FusedSummaryFailure(FusedSummaryFailure.Reason.SOURCE_CHANGED)
        if (after.size <= before.size) throw FusedSummaryFailure(FusedSummaryFailure.Reason.UNKNOWN)
    }

    suspend fun run(
        nextPlan: suspend () -> FusedCompanionPolicy.WindowPlan?,
        summarize: suspend (FusedCompanionPolicy.WindowPlan) -> Int
    ): Int {
        val seen = mutableSetOf<String>()
        var changed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val plan = nextPlan() ?: break
            if (!plan.needsSummary) break
            if (plan.batch.isEmpty()) throw FusedSummaryFailure(FusedSummaryFailure.Reason.CONTEXT_LIMIT)
            val key = CompanionMemoryPolicy.digest(plan.batch.joinToString { CompanionMemoryPolicy.fingerprint(it) })
            require(seen.add(key)) { FusedMemoryText.RETRY_CHANGED.value() }
            currentCoroutineContext().ensureActive()
            changed += summarize(plan)
        }
        return changed
    }
}
