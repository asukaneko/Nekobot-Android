package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.db.CompanionMemoryEntity

internal data class CompanionMessageChange(val sessionId: String, val messageId: String,
    val content: String? = null, val regenerate: Boolean = false, val selectedIds: List<String> = emptyList())
internal data class CompanionMessageDecisionState(val change: CompanionMessageChange,
    val messageIds: List<String>, val memories: List<CompanionMemoryEntity>) {
    fun visibleMemories(expanded: Boolean) = if (expanded) memories else memories.take(5)
}

/** 查询、选择、提交共用一个流程；取消不写消息，确认期间新增的关联也必须重新展示。 */
internal class CompanionMessageDecision(
    private val query: suspend (CompanionMessageChange) -> CompanionMessageDecisionState,
    private val apply: suspend (CompanionMessageDecisionState, Boolean?) -> Unit,
    private val cancel: suspend (CompanionMessageDecisionState) -> Unit
) {
    var pending: CompanionMessageDecisionState? = null
        private set
    suspend fun start(change: CompanionMessageChange): CompanionMessageDecisionState? {
        check(pending == null)
        val state = query(change)
        if (state.memories.isNotEmpty()) { pending = state; return null }
        // null要求事务再次检查关联，查询后新增了记忆时不能替用户选择。
        apply(state, null)
        return state
    }
    suspend fun choose(keep: Boolean?): CompanionMessageDecisionState? {
        val previous = pending ?: return null
        if (keep == null) { try { cancel(previous) } finally { pending = null }; return null }
        val fresh = query(previous.change)
        pending = fresh
        if (fresh.messageIds != previous.messageIds || fresh.memories != previous.memories) return null
        apply(fresh, keep)
        pending = null
        return fresh
    }
    suspend fun refresh(change: CompanionMessageChange) { pending = query(change) }
}
