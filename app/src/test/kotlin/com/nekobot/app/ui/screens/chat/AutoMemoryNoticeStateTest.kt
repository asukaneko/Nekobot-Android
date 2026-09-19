package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ai.AgentMemoryNotice
import com.nekobot.app.data.local.ai.AgentMemoryPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动长期记忆提示的状态机：进行中、完成（有/无改动）、以及进入会话时恢复。
 *
 * 与 [AutoSkillNoticeStateTest] 同构：写入结果由 PrefsManager 持久化
 * （见 LocalRepository 写入），这里通过注入的读取器验证"持久显示"的界面语义。
 */
class AutoMemoryNoticeStateTest {

    @Test
    fun runningPhaseShowsOrganizingHint() {
        val state = ChatSessionState("session")

        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 0, phase = AgentMemoryPhase.RUNNING)
        )

        assertEquals(AutoMemoryUiState(running = true), state.autoMemoryNotice.value)
    }

    @Test
    fun doneWithChangesShowsCountAndStaysVisible() {
        val state = ChatSessionState("session")

        state.applyAutoMemoryNotice(AgentMemoryNotice("session", changedItems = 3))

        assertEquals(
            AutoMemoryUiState(changedItems = 3, running = false),
            state.autoMemoryNotice.value
        )
        // 提示是持久显示的：不会随时间自动消失（状态里没有过期逻辑）。
        assertEquals(
            AutoMemoryUiState(changedItems = 3, running = false),
            state.autoMemoryNotice.value
        )
    }

    @Test
    fun noticeCarriesAnchorFromTriggeringReply() {
        val state = ChatSessionState("session")

        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 2, anchorContent = "这是触发记忆的回复")
        )

        assertEquals("这是触发记忆的回复", state.autoMemoryNotice.value?.anchorContent)
    }

    /**
     * 锚点解析：提示要停在触发它的那条回复下面，而不是永远贴列表末尾。
     */
    @Test
    fun anchorResolvesToTriggeringMessage() {
        val state = ChatSessionState("session")
        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 1, anchorContent = "第二条回复")
        )
        val messages = listOf(
            message(id = "m1", content = "第一条回复", isUser = false),
            message(id = "m2", content = "第二条回复", isUser = false)
        )

        assertEquals("m2", state.resolveAutoMemoryAnchorMessageId(messages))
    }

    @Test
    fun anchorMatchesTruncatedPrefix() {
        val state = ChatSessionState("session")
        // 锚点只存正文前缀：整条回复更长时也要能匹配上。
        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 1, anchorContent = "前缀内容")
        )
        val messages = listOf(message(id = "m1", content = "前缀内容后面还有很多字", isUser = false))

        assertEquals("m1", state.resolveAutoMemoryAnchorMessageId(messages))
    }

    @Test
    fun anchorFallsBackWhenMessageIsGone() {
        val state = ChatSessionState("session")
        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 1, anchorContent = "已被删除的回复")
        )
        val messages = listOf(message(id = "m1", content = "别的回复", isUser = false))

        // 找不到锚点消息时返回 null，界面据此回退到列表末尾，而不是让提示消失。
        assertNull(state.resolveAutoMemoryAnchorMessageId(messages))
    }

    @Test
    fun anchorDoesNotMatchUserMessages() {
        val state = ChatSessionState("session")
        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 1, anchorContent = "同样的文字")
        )
        val messages = listOf(message(id = "u1", content = "同样的文字", isUser = true))

        assertNull("记忆锚点是回复，不应匹配到用户消息", state.resolveAutoMemoryAnchorMessageId(messages))
    }

    @Test
    fun doneWithoutChangesFallsBackToPersistedNotice() {
        val state = ChatSessionState("session", loadMemoryNotice = { 5 })
        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 0, phase = AgentMemoryPhase.RUNNING)
        )

        state.applyAutoMemoryNotice(AgentMemoryNotice("session", changedItems = 0))

        // 收起"正在整理"，回退显示上一次的写入结果（而不是把提示清空）。
        assertEquals(
            AutoMemoryUiState(changedItems = 5, running = false),
            state.autoMemoryNotice.value
        )
    }

    @Test
    fun doneWithoutChangesHidesHintWhenNothingPersisted() {
        val state = ChatSessionState("session", loadMemoryNotice = { null })
        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 0, phase = AgentMemoryPhase.RUNNING)
        )

        state.applyAutoMemoryNotice(AgentMemoryNotice("session", changedItems = 0))

        assertNull(state.autoMemoryNotice.value)
    }

    @Test
    fun zeroPersistedCountDoesNotShowHint() {
        val state = ChatSessionState("session", loadMemoryNotice = { 0 })

        state.restoreAutoMemoryNotice()

        assertNull("没有真正写入过内容时不应显示提示", state.autoMemoryNotice.value)
    }

    @Test
    fun restoreShowsPersistedNoticeAfterSessionReopen() {
        val state = ChatSessionState("session", loadMemoryNotice = { 2 })

        state.restoreAutoMemoryNotice()

        assertEquals(
            AutoMemoryUiState(changedItems = 2, running = false),
            state.autoMemoryNotice.value
        )
    }

    @Test
    fun restoreKeepsRunningHintWhileExtractionIsStillInFlight() {
        val state = ChatSessionState("session", loadMemoryNotice = { 2 })
        state.applyAutoMemoryNotice(
            AgentMemoryNotice("session", changedItems = 0, phase = AgentMemoryPhase.RUNNING)
        )

        state.restoreAutoMemoryNotice()

        assertEquals(AutoMemoryUiState(running = true), state.autoMemoryNotice.value)
    }

    @Test
    fun restoreKeepsPersistedAnchor() {
        val state = ChatSessionState(
            "session",
            loadMemoryNotice = { 4 },
            loadMemoryAnchor = { "上次触发记忆的回复" }
        )

        state.restoreAutoMemoryNotice()

        assertEquals(
            AutoMemoryUiState(changedItems = 4, running = false, anchorContent = "上次触发记忆的回复"),
            state.autoMemoryNotice.value
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
