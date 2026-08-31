package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手动压缩保留窗口的判定逻辑。
 *
 * 覆盖需求场景：会话消息数少，但上下文占用已超过 10% 时也允许压缩
 * （缩小保留窗口，保证至少有 1 条消息可被压缩）。
 */
class ManualCompressionKeepCountTest {

    @Test
    fun keepsDefaultWindowWhenMessageCountIsSufficient() {
        // 普通会话：超过 keepRecent + 2 即按原窗口压缩
        assertEquals(10, resolveManualCompressionKeepCount(13, 10, 0f, margin = 2))
        assertEquals(10, resolveManualCompressionKeepCount(50, 10, 0.9f, margin = 2))
        // Agent 会话：超过 keepRecent 即按原窗口压缩
        assertEquals(10, resolveManualCompressionKeepCount(11, 10, 0f, margin = 0))
    }

    @Test
    fun refusesCompressionWhenMessagesAreFewAndUsageIsLow() {
        assertEquals(-1, resolveManualCompressionKeepCount(5, 10, 0.05f, margin = 2))
        // 恰好等于 10% 不算"超过"
        assertEquals(-1, resolveManualCompressionKeepCount(5, 10, 0.10f, margin = 2))
        assertEquals(-1, resolveManualCompressionKeepCount(8, 10, 0.02f, margin = 0))
    }

    @Test
    fun compressesFewMessagesWhenUsageExceedsThreshold() {
        // 5 条消息：保留最近 2 条，压缩前 3 条
        assertEquals(2, resolveManualCompressionKeepCount(5, 10, 0.2f, margin = 2))
        // 2 条消息：保留 1 条，压缩 1 条
        assertEquals(1, resolveManualCompressionKeepCount(2, 10, 0.5f, margin = 2))
        // 3 条消息：保留 2 条，压缩 1 条
        assertEquals(2, resolveManualCompressionKeepCount(3, 10, 0.3f, margin = 0))
        // 12 条消息（普通会话原规则会拒绝）：占用超阈值时保留一半
        assertEquals(6, resolveManualCompressionKeepCount(12, 10, 0.3f, margin = 2))
    }

    @Test
    fun cannotCompressSingleMessageSession() {
        assertEquals(-1, resolveManualCompressionKeepCount(1, 10, 0.9f, margin = 2))
        assertEquals(-1, resolveManualCompressionKeepCount(0, 10, 0.9f, margin = 2))
        assertEquals(-1, resolveManualCompressionKeepCount(1, 10, 0.9f, margin = 0))
    }

    @Test
    fun shrunkWindowNeverExceedsDefaultKeepRecent() {
        // 消息数在 (keepRecent, keepRecent + margin] 区间且占用超阈值时，
        // 缩小后的窗口不能大于 keepRecent
        assertEquals(5, resolveManualCompressionKeepCount(11, 10, 0.5f, margin = 2))
        assertEquals(10, resolveManualCompressionKeepCount(30, 10, 0.5f, margin = 2))
    }
}
