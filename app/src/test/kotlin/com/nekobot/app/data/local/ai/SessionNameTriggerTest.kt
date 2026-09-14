package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNameTriggerTest {

    @Test
    fun firstCompletedTurnTriggersNaming() {
        assertTrue(
            shouldAutoRenameSession(
                isDefaultName = true,
                totalCount = 2,
                state = SessionNamingState()
            )
        )
    }

    @Test
    fun localizedAgentDefaultNamesStillTriggerFirstNaming() {
        listOf(
            "Agent 对话",
            "Agent chat",
            "エージェントチャット",
            "에이전트 대화"
        ).forEach { name ->
            assertTrue("$name should be treated as a default name", isDefaultAutoNamingSessionName(name))
        }
    }

    @Test
    fun persistedStateTriggersAgainAfterTenNewMessages() {
        val state = SessionNamingState(autoNamed = true, lastRenameCount = 2)

        assertFalse(shouldAutoRenameSession(false, 4, state))
        assertTrue(shouldAutoRenameSession(false, 12, state))
    }

    @Test
    fun legacySessionRecoversTheLatestRenameBoundary() {
        val atBoundary = recoverSessionNamingState(isDefaultName = false, totalCount = 12)
        assertEquals(2, atBoundary.lastRenameCount)
        assertTrue(shouldAutoRenameSession(false, 12, atBoundary))

        val afterBoundary = recoverSessionNamingState(isDefaultName = false, totalCount = 14)
        assertEquals(12, afterBoundary.lastRenameCount)
        assertFalse(shouldAutoRenameSession(false, 14, afterBoundary))
    }

    // ==================== 会话级命名间隔 ====================

    @Test
    fun sessionLevelIntervalControlsRenameCadence() {
        val state = SessionNamingState(autoNamed = true, lastRenameCount = 2)

        // 默认间隔仍是 10：4 条新消息不触发
        assertFalse(shouldAutoRenameSession(false, 6, state))
        // 会话设为每 4 条：同样 4 条新消息就能触发
        assertTrue(shouldAutoRenameSession(false, 6, state, interval = 4))
        // 会话设为每 20 条：12 条新消息仍不触发
        assertFalse(shouldAutoRenameSession(false, 14, state, interval = 20))
    }

    @Test
    fun zeroIntervalDisablesAutoNamingEntirely() {
        // 关闭后连首次命名也不做：会话始终保持默认名
        assertFalse(
            shouldAutoRenameSession(
                isDefaultName = true,
                totalCount = 2,
                state = SessionNamingState(),
                interval = 0
            )
        )
        assertFalse(
            shouldAutoRenameSession(
                isDefaultName = false,
                totalCount = 200,
                state = SessionNamingState(autoNamed = true, lastRenameCount = 0),
                interval = 0
            )
        )
    }

    @Test
    fun legacyRecoveryUsesSessionInterval() {
        val state = recoverSessionNamingState(isDefaultName = false, totalCount = 26, interval = 20)
        assertEquals(22, state.lastRenameCount)
        assertFalse(shouldAutoRenameSession(false, 26, state, interval = 20))
        assertTrue(shouldAutoRenameSession(false, 42, state, interval = 20))
    }

    @Test
    fun recoveryToleratesDisabledIntervalWithoutDividingByZero() {
        val state = recoverSessionNamingState(isDefaultName = false, totalCount = 10, interval = 0)
        assertEquals(9, state.lastRenameCount)
    }
}
