package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionExamplesEntity
import com.nekobot.app.data.local.db.LocalMessageCursor
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalMessageRow

/** 顺序分页，只保留一页和相邻消息；达到所需完整轮数/候选数立即结束。 */
internal class CompanionExamplesHistory(
    private val readPage: suspend (LocalMessageCursor, Int) -> List<LocalMessageRow>
) {
    suspend fun offer(companionMode: Boolean, row: CompanionExamplesEntity?): CompanionExamplesEntity? {
        // 老会话/转换没有资格记录，导入为 disabled，均不查询历史或补建资格。
        if (!companionMode || row?.state != "waiting") return row
        var previous: LocalMessageEntity? = null
        var rounds = 0
        visit { message ->
            previous?.let { if (CompanionExamplesPolicy.pairs(listOf(it, message)).isNotEmpty()) rounds++ }
            previous = message
            rounds < CompanionExamplesPolicy.INVITATION_ROUNDS
        }
        return CompanionExamplesPolicy.offer(row, rounds)
    }

    suspend fun candidates(): List<CompanionExampleCandidate> {
        val pool = mutableListOf<CompanionExampleCandidate>()
        var first: LocalMessageEntity? = null
        var second: LocalMessageEntity? = null
        fun collect(next: LocalMessageEntity?) {
            val user = first ?: return
            val assistant = second ?: return
            CompanionExamplesPolicy.candidate(user, assistant, next)?.let(pool::add)
        }
        val exhausted = visit { message ->
            // 多看一条再接纳，紧接着的纠正即使跨页也不能漏掉。
            collect(message)
            first = second
            second = message
            pool.size < CANDIDATE_POOL_LIMIT
        }
        if (exhausted) collect(null)
        return CompanionExamplesPolicy.balancedCandidates(pool)
    }

    /** true 表示到达末尾；false 表示已满足需要，后续历史不再读取。 */
    private suspend fun visit(consume: (LocalMessageEntity) -> Boolean): Boolean {
        var cursor = LocalMessageCursor("", Long.MIN_VALUE)
        while (true) {
            val page = readPage(cursor, PAGE_SIZE)
            for (row in page) if (!consume(row.message)) return false
            if (page.size < PAGE_SIZE) return true
            val last = page.last()
            cursor = LocalMessageCursor(last.message.createdAt, last.rowId)
        }
    }

    companion object {
        const val PAGE_SIZE = 100
        const val CANDIDATE_POOL_LIMIT = 50
    }
}
