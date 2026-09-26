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
 *
 * 提示与压缩/记忆共用统一的内联提示列表（[ChatSessionState.inlineNotices]），
 * 并携带触发它的回复正文锚点。
 */
class AutoSkillNoticeStateTest {

    @Test
    fun runningPhaseShowsProgressHint() {
        val state = ChatSessionState("session")

        state.applyAutoSkillNotice(
            AgentSkillNotice("session", skillName = "", created = false, phase = AgentSkillPhase.RUNNING)
        )

        assertEquals(AutoSkillUiState(running = true), state.inlineNoticeOrNull<AutoSkillUiState>())
    }

    @Test
    fun doneWithNewSkillShowsCreatedNameAndStaysVisible() {
        val state = ChatSessionState("session")

        state.applyAutoSkillNotice(AgentSkillNotice("session", skillName = "nginx-tls", created = true))

        assertEquals(
            AutoSkillUiState(skillName = "nginx-tls", created = true, running = false),
            state.inlineNoticeOrNull<AutoSkillUiState>()
        )
        // 提示是持久显示的：不会随时间自动消失（状态里没有过期逻辑）。
        assertEquals(
            AutoSkillUiState(skillName = "nginx-tls", created = true, running = false),
            state.inlineNoticeOrNull<AutoSkillUiState>()
        )
    }

    @Test
    fun doneWithExistingSkillShowsUpdatedState() {
        val state = ChatSessionState("session")

        state.applyAutoSkillNotice(AgentSkillNotice("session", skillName = "nginx-tls", created = false))

        assertEquals(
            AutoSkillUiState(skillName = "nginx-tls", created = false, running = false),
            state.inlineNoticeOrNull<AutoSkillUiState>()
        )
    }

    @Test
    fun noticeCarriesAnchorFromTriggeringReply() {
        val state = ChatSessionState("session")

        state.applyAutoSkillNotice(
            AgentSkillNotice(
                "session",
                skillName = "nginx-tls",
                created = true,
                anchorContent = "这是触发沉淀的回复"
            )
        )

        assertEquals(
            "这是触发沉淀的回复",
            state.inlineNoticeOrNull<AutoSkillUiState>()?.anchorContent
        )
    }

    /**
     * 锚点解析：提示要停在触发它的那条回复下面，而不是永远贴列表末尾。
     */
    @Test
    fun anchorResolvesToTriggeringMessage() {
        val state = ChatSessionState("session")
        state.applyAutoSkillNotice(
            AgentSkillNotice(
                "session",
                skillName = "nginx-tls",
                created = true,
                anchorContent = "第二条回复"
            )
        )
        val messages = listOf(
            message(id = "m1", content = "第一条回复", isUser = false),
            message(id = "m2", content = "第二条回复", isUser = false)
        )

        val notice = state.inlineNoticeOrNull<AutoSkillUiState>()!!

        assertEquals(1, resolveInlineNoticeAnchorIndex(notice, messages))
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
            state.inlineNoticeOrNull<AutoSkillUiState>()
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

        assertNull(state.inlineNoticeOrNull<AutoSkillUiState>())
    }

    @Test
    fun restoreShowsPersistedNoticeAfterSessionReopen() {
        val state = ChatSessionState(
            "session",
            loadSkillNotice = { "nginx-tls" to true },
            loadSkillAnchor = { "上次触发沉淀的回复" }
        )

        state.restoreAutoSkillNotice()

        assertEquals(
            AutoSkillUiState(
                skillName = "nginx-tls",
                created = true,
                running = false,
                anchorContent = "上次触发沉淀的回复"
            ),
            state.inlineNoticeOrNull<AutoSkillUiState>()
        )
    }

    @Test
    fun restoreKeepsRunningHintWhileReviewIsStillInFlight() {
        val state = ChatSessionState("session", loadSkillNotice = { "nginx-tls" to true })
        state.applyAutoSkillNotice(
            AgentSkillNotice("session", skillName = "", created = false, phase = AgentSkillPhase.RUNNING)
        )

        state.restoreAutoSkillNotice()

        assertEquals(AutoSkillUiState(running = true), state.inlineNoticeOrNull<AutoSkillUiState>())
    }

    private fun message(id: String, content: String, isUser: Boolean) =
        com.nekobot.app.data.model.Message(
            id = id,
            role = if (isUser) "user" else "assistant",
            content = content,
            timestamp = "2026-01-01T00:00:00Z"
        )
}
