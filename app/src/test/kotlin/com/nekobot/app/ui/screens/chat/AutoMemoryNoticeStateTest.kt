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
}
