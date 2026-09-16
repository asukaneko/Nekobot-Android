package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * life_sim 心跳的二进制指数退避（对齐原仓库 nbot/gateway/heartbeat.py）。
 */
class LifeSimulatorBackoffTest {

    @Test
    fun `基础间隔按 2 的幂增长并以 8 小时封顶`() {
        assertEquals(60L, LifeSimulator.effectiveIntervalMinutes(0))
        assertEquals(120L, LifeSimulator.effectiveIntervalMinutes(1))
        assertEquals(240L, LifeSimulator.effectiveIntervalMinutes(2))
        assertEquals(480L, LifeSimulator.effectiveIntervalMinutes(3))
        // 60 * 2^4 = 960 分钟，被 8 小时硬上限截断
        assertEquals(480L, LifeSimulator.effectiveIntervalMinutes(4))
        // 越界输入同样被夹紧
        assertEquals(480L, LifeSimulator.effectiveIntervalMinutes(99))
        assertEquals(60L, LifeSimulator.effectiveIntervalMinutes(-3))
    }

    @Test
    fun `用户重新活跃时退避归零否则递增到上限`() {
        assertEquals(0, LifeSimulator.nextBackoff(currentBackoff = 3, userActiveSinceLastRun = true))
        assertEquals(1, LifeSimulator.nextBackoff(currentBackoff = 0, userActiveSinceLastRun = false))
        assertEquals(4, LifeSimulator.nextBackoff(currentBackoff = 4, userActiveSinceLastRun = false))
    }

    @Test
    fun `退避计数决定是否到点`() {
        val now = LocalDateTime.of(2026, 1, 1, 12, 0)
        val twoHoursAgo = now.minusHours(2).toString()

        // 从未生成 → 立即触发
        assertTrue(LifeSimulator.shouldTrigger(null, backoffCount = 4, now = now))
        // 基线间隔 60 分钟：2 小时前生成过 → 到点
        assertTrue(LifeSimulator.shouldTrigger(twoHoursAgo, backoffCount = 0, now = now))
        // 退避 1 次 → 间隔 120 分钟：刚好到点
        assertTrue(LifeSimulator.shouldTrigger(twoHoursAgo, backoffCount = 1, now = now))
        // 退避 2 次 → 间隔 240 分钟：未到点
        assertFalse(LifeSimulator.shouldTrigger(twoHoursAgo, backoffCount = 2, now = now))
    }

    @Test
    fun `scene 中持久化的退避计数兼容 JSON 回读的数字类型`() {
        assertEquals(0, LifeSimulator.sceneBackoff(null))
        assertEquals(0, LifeSimulator.sceneBackoff(emptyMap()))
        // Gson 反序列化 Map<String, Any> 后数字为 Double
        assertEquals(2, LifeSimulator.sceneBackoff(mapOf(LifeSimulator.SCENE_KEY_BACKOFF to 2.0)))
        assertEquals(3, LifeSimulator.sceneBackoff(mapOf(LifeSimulator.SCENE_KEY_BACKOFF to 3)))
        // 脏数据不越界
        assertEquals(4, LifeSimulator.sceneBackoff(mapOf(LifeSimulator.SCENE_KEY_BACKOFF to 42.0)))
        assertEquals(0, LifeSimulator.sceneBackoff(mapOf(LifeSimulator.SCENE_KEY_BACKOFF to -1.0)))
    }
}
