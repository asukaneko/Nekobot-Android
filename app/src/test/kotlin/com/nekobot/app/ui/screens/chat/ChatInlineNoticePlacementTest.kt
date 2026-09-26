package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ai.AgentMemoryNotice
import com.nekobot.app.data.local.ai.AgentSkillNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内联提示的统一行为：压缩 / 技能沉淀 / 长期记忆都写入同一份
 * [ChatSessionState.inlineNotices]，并统一锚定到触发它的消息下方。
 */
class ChatInlineNoticePlacementTest {

    @Test
    fun compressionNoticeAnchorsToTriggeringUserMessage() {
        val state = ChatSessionState("session")

        state.applyContextCompressionNotice(inProgress = true, anchorContent = "刚发送的消息")

        val notice = state.inlineNoticeOrNull<ContextCompressionUiState>()
        assertEquals(ContextCompressionUiState(anchorContent = "刚发送的消息"), notice)
        val messages = listOf(
            message(id = "m1", content = "上一条回复", isUser = false),
            message(id = "u1", content = "刚发送的消息", isUser = true)
        )
        // 压缩由用户消息触发，锚点允许匹配用户消息。
        assertEquals(1, resolveInlineNoticeAnchorIndex(notice!!, messages))
    }

    @Test
    fun compressionNoticeOnlyKeepsLatestAnchor() {
        val state = ChatSessionState("session")

        state.applyContextCompressionNotice(inProgress = true, anchorContent = "第一次")
        state.applyContextCompressionNotice(inProgress = true, anchorContent = "第二次")

        assertEquals(
            ContextCompressionUiState(anchorContent = "第二次"),
            state.inlineNoticeOrNull<ContextCompressionUiState>()
        )
        assertEquals(1, state.inlineNotices.value.size)
    }

    @Test
    fun compressionNoticeRemovesOnlyItselfOnCompletion() {
        val state = ChatSessionState("session")
        state.applyContextCompressionNotice(inProgress = true, anchorContent = "消息")
        state.applyAutoSkillNotice(AgentSkillNotice("session", skillName = "nginx-tls", created = true))
        state.applyAutoMemoryNotice(AgentMemoryNotice("session", changedItems = 2))

        state.applyContextCompressionNotice(inProgress = false)

        assertNull(state.inlineNoticeOrNull<ContextCompressionUiState>())
        assertEquals(2, state.inlineNotices.value.size)
    }

    @Test
    fun noticesKeepStableRenderOrderOnSameMessage() {
        val state = ChatSessionState("session")

        // 乱序写入：统一列表按 order 排序，保证同一锚点消息上提示顺序稳定。
        state.applyAutoMemoryNotice(AgentMemoryNotice("session", changedItems = 2))
        state.applyAutoSkillNotice(AgentSkillNotice("session", skillName = "nginx-tls", created = false))
        state.applyContextCompressionNotice(inProgress = true, anchorContent = "消息")

        assertTrue(state.inlineNotices.value[0] is ContextCompressionUiState)
        assertTrue(state.inlineNotices.value[1] is AutoSkillUiState)
        assertTrue(state.inlineNotices.value[2] is AutoMemoryUiState)
    }

    @Test
    fun updatedNoticeReplacesSameTypeInPlace() {
        val state = ChatSessionState("session")
        state.applyAutoSkillNotice(AgentSkillNotice("session", skillName = "first", created = true))
        state.applyAutoMemoryNotice(AgentMemoryNotice("session", changedItems = 1))

        state.applyAutoSkillNotice(AgentSkillNotice("session", skillName = "second", created = false))

        assertEquals(2, state.inlineNotices.value.size)
        assertEquals(
            "second",
            state.inlineNoticeOrNull<AutoSkillUiState>()?.skillName
        )
    }

    @Test
    fun anchorFallsBackWhenContentNoLongerMatches() {
        val state = ChatSessionState("session")
        state.applyContextCompressionNotice(inProgress = true, anchorContent = "已被删除的消息")
        val notice = state.inlineNoticeOrNull<ContextCompressionUiState>()!!

        assertNull(
            resolveInlineNoticeAnchorIndex(
                notice,
                listOf(message(id = "m1", content = "别的消息", isUser = false))
            )
        )
    }

    @Test
    fun blankAnchorNeverMatches() {
        val state = ChatSessionState("session")
        state.applyContextCompressionNotice(inProgress = true, anchorContent = "  ")

        val notice = state.inlineNoticeOrNull<ContextCompressionUiState>()!!
        assertNull(
            resolveInlineNoticeAnchorIndex(
                notice,
                listOf(message(id = "m1", content = "任意内容", isUser = false))
            )
        )
    }

    private fun message(id: String, content: String, isUser: Boolean) =
        com.nekobot.app.data.model.Message(
            id = id,
            role = if (isUser) "user" else "assistant",
            content = content,
            timestamp = "2026-01-01T00:00:00Z"
        )
}
