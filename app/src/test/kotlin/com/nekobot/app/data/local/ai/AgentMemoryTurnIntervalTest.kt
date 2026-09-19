package com.nekobot.app.data.local.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆抽取的轮次间隔判定：首轮必定抽取，之后每 N 轮一次。
 */
class AgentMemoryTurnIntervalTest {

    @Test
    fun `first turn always extracts`() {
        assertTrue(AgentMemoryExtractor.shouldExtractOnTurn(1, interval = 10))
    }

    @Test
    fun `extracts on interval multiples only`() {
        // 间隔 3：第 3、6、9 轮抽取，其余跳过。
        assertFalse(AgentMemoryExtractor.shouldExtractOnTurn(2, interval = 3))
        assertTrue(AgentMemoryExtractor.shouldExtractOnTurn(3, interval = 3))
        assertFalse(AgentMemoryExtractor.shouldExtractOnTurn(4, interval = 3))
        assertTrue(AgentMemoryExtractor.shouldExtractOnTurn(6, interval = 3))
    }

    @Test
    fun `interval one extracts every turn`() {
        assertTrue(AgentMemoryExtractor.shouldExtractOnTurn(2, interval = 1))
        assertTrue(AgentMemoryExtractor.shouldExtractOnTurn(7, interval = 1))
    }

    @Test
    fun `larger interval extracts less often`() {
        assertFalse(AgentMemoryExtractor.shouldExtractOnTurn(9, interval = 10))
        assertTrue(AgentMemoryExtractor.shouldExtractOnTurn(10, interval = 10))
    }

    @Test
    fun `non positive interval falls back to every turn`() {
        // 防御非法配置：间隔小于 1 时按每轮抽取处理，而不是除零或永不抽取。
        assertTrue(AgentMemoryExtractor.shouldExtractOnTurn(5, interval = 0))
        assertTrue(AgentMemoryExtractor.shouldExtractOnTurn(5, interval = -3))
    }
}
