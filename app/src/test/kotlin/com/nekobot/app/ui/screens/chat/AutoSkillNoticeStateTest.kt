package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.local.ai.AgentSkillNotice
import com.nekobot.app.data.local.ai.AgentSkillPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动技能沉淀提示的状态机：进行中、完成（新建/更新）、本轮无产出回退、以及进入会话时恢复。
 *
 * 沉淀结果本身由 PrefsManager 持久化（见 LocalRepository 写入），这里通过注入的读取器
 * 验证"持久显示"的界面语义：不自动消失，只被下一次结果覆盖。
 */
class AutoSkillNoticeStateTest {

    @Test
    fun runningPhaseShowsProgressHint() {
        val state = ChatSessionState("session")

        state.applyAutoSkillNotice(
            AgentSkillNotice("session", skillName = "", created = false, phase = AgentSkillPhase.RUNNING)
        )

        assertEquals(AutoSkillUiState(running = true), state.autoSkillNotice.value)
    }

    @Test
    fun doneWithNewSkillShowsCreatedNameAndStaysVisible() {
        val state = ChatSessionState("session")

        state.applyAutoSkillNotice(AgentSkillNotice("session", skillName = "nginx-tls", created = true))

        assertEquals(
            AutoSkillUiState(skillName = "nginx-tls", created = true, running = false),
            state.autoSkillNotice.value
        )
        // 提示是持久显示的：不会随时间自动消失（状态里没有过期逻辑）。
        assertEquals(
            AutoSkillUiState(skillName = "nginx-tls", created = true, running = false),
            state.autoSkillNotice.value
        )
    }

    @Test
    fun doneWithExistingSkillShowsUpdatedState() {
        val state = ChatSessionState("session")

        state.applyAutoSkillNotice(AgentSkillNotice("session", skillName = "nginx-tls", created = false))

        assertEquals(
            AutoSkillUiState(skillName = "nginx-tls", created = false, running = false),
            state.autoSkillNotice.value
        )
    }

    @Test
    fun doneWithoutDistilledSkillFallsBackToPersistedNotice() {
        val state = ChatSessionState("session", loadSkillNotice = { "android-release-build" to false })
        state.applyAutoSkillNotice(
            AgentSkillNotice("session", skillName = "", created = false, phase = AgentSkillPhase.RUNNING)
        )

        state.applyAutoSkillNotice(
            AgentSkillNotice("session", skillName = "", created = false, phase = AgentSkillPhase.DONE)
        )

        // 收起"正在总结"，回退显示上一次的沉淀结果（而不是把提示清空）。
        assertEquals(
            AutoSkillUiState(skillName = "android-release-build", created = false, running = false),
            state.autoSkillNotice.value
        )
    }

    @Test
    fun doneWithoutDistilledSkillHidesHintWhenNothingPersisted() {
        val state = ChatSessionState("session", loadSkillNotice = { null })
        state.applyAutoSkillNotice(
            AgentSkillNotice("session", skillName = "", created = false, phase = AgentSkillPhase.RUNNING)
        )

        state.applyAutoSkillNotice(
            AgentSkillNotice("session", skillName = "", created = false, phase = AgentSkillPhase.DONE)
        )

        assertNull(state.autoSkillNotice.value)
    }

    @Test
    fun restoreShowsPersistedNoticeAfterSessionReopen() {
        val state = ChatSessionState("session", loadSkillNotice = { "nginx-tls" to true })

        state.restoreAutoSkillNotice()

        assertEquals(
            AutoSkillUiState(skillName = "nginx-tls", created = true, running = false),
            state.autoSkillNotice.value
        )
    }

    @Test
    fun restoreKeepsRunningHintWhileReviewIsStillInFlight() {
        val state = ChatSessionState("session", loadSkillNotice = { "nginx-tls" to true })
        state.applyAutoSkillNotice(
            AgentSkillNotice("session", skillName = "", created = false, phase = AgentSkillPhase.RUNNING)
        )

        state.restoreAutoSkillNotice()

        assertEquals(AutoSkillUiState(running = true), state.autoSkillNotice.value)
    }
}
