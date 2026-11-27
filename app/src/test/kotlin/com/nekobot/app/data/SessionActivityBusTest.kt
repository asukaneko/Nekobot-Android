package com.nekobot.app.data

import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局会话运行阶段总线：多次会话并存、重复上报去重、结束清理。
 *
 * 纯内存对象，不依赖 Android 环境。
 */
class SessionActivityBusTest {

    @After fun tearDown() {
        SessionActivityBus.clearAll()
    }

    @Test fun tracksMultipleSessionsIndependently() {
        SessionActivityBus.onThinking("s1")
        SessionActivityBus.onToolStart("s2", "file_read")
        val all = SessionActivityBus.activities.value
        assertEquals(SessionActivityPhase.THINKING, all.getValue("s1").phase)
        assertEquals(SessionActivityPhase.TOOL, all.getValue("s2").phase)
        assertEquals("file_read", all.getValue("s2").toolName)
    }

    @Test fun repeatedSamePhaseDoesNotEmitNewValue() {
        SessionActivityBus.publish("s1", SessionActivityPhase.THINKING, now = 10L)
        val first = SessionActivityBus.activities.value.getValue("s1")
        // 流式思考每个分片都会上报，同一阶段必须原样保留（含时间戳），否则首页会持续重组
        SessionActivityBus.publish("s1", SessionActivityPhase.THINKING, now = 99L)
        assertEquals(first, SessionActivityBus.activities.value.getValue("s1"))
    }

    @Test fun switchingToolUpdatesState() {
        SessionActivityBus.onToolStart("s1", "file_read")
        SessionActivityBus.onToolStart("s1", "grep")
        assertEquals("grep", SessionActivityBus.activities.value.getValue("s1").toolName)
    }

    @Test fun toolDoneFallsBackToThinking() {
        SessionActivityBus.onToolStart("s1", "file_read")
        SessionActivityBus.onToolDone("s1")
        val activity = SessionActivityBus.activities.value.getValue("s1")
        assertEquals(SessionActivityPhase.THINKING, activity.phase)
        assertNull(activity.toolName)
    }

    @Test fun toolNameIsDroppedOutsideToolPhase() {
        SessionActivityBus.publish("s1", SessionActivityPhase.THINKING, toolName = "file_read")
        assertNull(SessionActivityBus.activities.value.getValue("s1").toolName)
    }

    @Test fun waitingStateIsTracked() {
        SessionActivityBus.onWaiting("s1")
        assertEquals(SessionActivityPhase.WAITING, SessionActivityBus.activities.value.getValue("s1").phase)
    }

    @Test fun clearOnlyRemovesTargetSession() {
        SessionActivityBus.onThinking("s1")
        SessionActivityBus.onThinking("s2")
        SessionActivityBus.clear("s1")
        assertTrue("s1" !in SessionActivityBus.activities.value)
        assertTrue("s2" in SessionActivityBus.activities.value)
    }

    @Test fun blankSessionIdIsIgnored() {
        SessionActivityBus.onThinking("")
        SessionActivityBus.onThinking("   ")
        assertTrue(SessionActivityBus.activities.value.isEmpty())
    }

    @Test fun clearAllResetsEverything() {
        SessionActivityBus.onThinking("s1")
        SessionActivityBus.onToolStart("s2", "grep")
        SessionActivityBus.clearAll()
        assertTrue(SessionActivityBus.activities.value.isEmpty())
    }

    @Test fun progressCardWithRunningToolMapsToToolPhase() {
        val card = ThinkingCard(
            id = "card",
            content = "调用工具",
            steps = listOf(
                ThinkingStep(type = "thinking", name = "思考", status = "done"),
                ThinkingStep(type = "tool", name = "file_read", status = "running")
            ),
            isAgent = true
        )
        assertEquals(SessionActivityPhase.TOOL, sessionActivityFromProgressCard(card))
        assertEquals("file_read", sessionActivityToolFromProgressCard(card))
    }

    @Test fun progressCardWithActiveThinkingMapsToThinkingPhase() {
        val card = ThinkingCard(
            id = "card",
            content = "思考中",
            steps = listOf(ThinkingStep(type = "thinking", name = "思考", status = "active")),
            isAgent = true
        )
        assertEquals(SessionActivityPhase.THINKING, sessionActivityFromProgressCard(card))
        assertNull(sessionActivityToolFromProgressCard(card))
    }

    @Test fun finishedToolIsNotReportedAsRunningTool() {
        val card = ThinkingCard(
            id = "card",
            content = "工具完成",
            steps = listOf(ThinkingStep(type = "tool", name = "grep", status = "done")),
            isAgent = true
        )
        assertEquals(SessionActivityPhase.THINKING, sessionActivityFromProgressCard(card))
        assertNull(sessionActivityToolFromProgressCard(card))
    }
}
