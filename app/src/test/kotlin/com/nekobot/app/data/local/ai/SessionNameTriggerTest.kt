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
}
