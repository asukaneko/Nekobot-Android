package com.nekobot.app.ui.screens.home

import com.nekobot.app.data.SessionActivity
import com.nekobot.app.data.SessionActivityPhase
import com.nekobot.app.ui.screens.sessions.SessionListRow
import com.nekobot.app.ui.screens.sessions.SessionModeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页 Agent 卡「运行中的会话」聚合逻辑。
 *
 * 只覆盖纯数据部分：文案与动画由 Compose 侧渲染，这里验证
 * 「筛哪个模式、按什么顺序、没阶段上报时怎么回退、超过上限怎么截断」。
 */
class AgentRunSummaryTest {

    private fun row(
        id: String,
        title: String,
        mode: SessionModeLabel,
        characters: String = ""
    ) = SessionListRow(
        key = id,
        id = id,
        name = id,
        displayName = title,
        portraitUrl = null,
        characterLabel = characters.ifBlank { null },
        updatedAt = "2026-01-02T03:04:05",
        lastMessage = null,
        messageCount = 1,
        pinned = false,
        favorite = false,
        archived = false,
        modeLabel = mode
    )

    @Test fun onlyRunningAgentSessionsAreListed() {
        val rows = listOf(
            row("agent-1", "Agent one", SessionModeLabel.AGENT),
            row("agent-2", "Agent two", SessionModeLabel.AGENT),
            row("char-1", "Character", SessionModeLabel.CHARACTER, "Demo")
        )
        val result = buildAgentRunUis(
            rows = rows,
            // 角色会话也在跑，但它属于角色聊天卡
            runningSessionIds = setOf("agent-1", "char-1", "missing"),
            activities = mapOf(
                "agent-1" to SessionActivity("agent-1", SessionActivityPhase.THINKING, null, 10L)
            )
        )
        assertEquals(listOf("agent-1"), result.map { it.sessionId })
    }

    @Test fun mostRecentlyActiveSessionComesFirst() {
        val rows = listOf(
            row("a", "A", SessionModeLabel.AGENT),
            row("b", "B", SessionModeLabel.AGENT),
            row("c", "C", SessionModeLabel.AGENT)
        )
        val result = buildAgentRunUis(
            rows = rows,
            runningSessionIds = setOf("a", "b", "c"),
            activities = mapOf(
                "a" to SessionActivity("a", SessionActivityPhase.TOOL, "file_read", 100L),
                "c" to SessionActivity("c", SessionActivityPhase.THINKING, null, 300L)
                // b 还没有阶段上报：排在最后
            )
        )
        assertEquals(listOf("c", "a", "b"), result.map { it.sessionId })
    }

    @Test fun phaseAndToolNameAreCarriedThrough() {
        val rows = listOf(row("a", "A", SessionModeLabel.AGENT))
        val result = buildAgentRunUis(
            rows = rows,
            runningSessionIds = setOf("a"),
            activities = mapOf(
                "a" to SessionActivity("a", SessionActivityPhase.TOOL, "file_read", 1L)
            )
        )
        assertEquals(SessionActivityPhase.TOOL, result.single().phase)
        assertEquals("file_read", result.single().toolName)
    }

    @Test fun sessionWithoutActivityFallsBackToUnknownPhase() {
        val rows = listOf(row("a", "A", SessionModeLabel.AGENT))
        val result = buildAgentRunUis(rows, setOf("a"), emptyMap())
        assertEquals("A", result.single().title)
        assertNull(result.single().phase)
        assertNull(result.single().toolName)
    }

    @Test fun waitingStateIsKeptSeparateFromToolCalls() {
        val rows = listOf(row("a", "A", SessionModeLabel.AGENT))
        val result = buildAgentRunUis(
            rows = rows,
            runningSessionIds = setOf("a"),
            activities = mapOf(
                "a" to SessionActivity("a", SessionActivityPhase.WAITING, null, 5L)
            )
        )
        assertEquals(SessionActivityPhase.WAITING, result.single().phase)
    }

    @Test fun listIsTruncatedToLimit() {
        val rows = (1..8).map { row("s$it", "Session $it", SessionModeLabel.AGENT) }
        val result = buildAgentRunUis(
            rows = rows,
            runningSessionIds = rows.mapNotNull { it.id }.toSet(),
            activities = emptyMap(),
            limit = 3
        )
        assertEquals(3, result.size)
        assertEquals(listOf("s1", "s2", "s3"), result.map { it.sessionId })
    }

    @Test fun titleFallsBackToSessionTitleOverride() {
        val base = row("a", "Fallback name", SessionModeLabel.AGENT)
        val result = buildAgentRunUis(
            rows = listOf(base.copy(listTitleOverride = "  自定义标题  ")),
            runningSessionIds = setOf("a"),
            activities = emptyMap()
        )
        assertEquals("自定义标题", result.single().title)
    }

    @Test fun emptyInputsProduceNoRuns() {
        assertTrue(buildAgentRunUis(emptyList(), setOf("a"), emptyMap()).isEmpty())
        assertTrue(buildAgentRunUis(listOf(row("a", "A", SessionModeLabel.AGENT)), emptySet(), emptyMap()).isEmpty())
    }
}
