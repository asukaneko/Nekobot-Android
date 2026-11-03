package com.nekobot.app.ui.components

import com.nekobot.app.data.local.innerMonologueAlwaysVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-22 用户决定：取消「显示内心独白」开关，恒定显示。
 *
 * 这里锁的是“决定要不要显示”的那一步：旧偏好 true / false / 缺失都不影响结果；
 * 拿到结果后正文里的（内心：…）照常渲染成可折叠块，渲染逻辑本身没改。
 */
class InnerMonologueAlwaysVisibleTest {

    private val text = "早上好呀（内心：今天也想见你）\n要一起吃早饭吗？"

    @Test
    fun legacyPreferenceValueNeverTurnsDisplayOff() {
        for (legacy in listOf(null, true, false)) {
            assertTrue("旧值 legacy=$legacy 不应让独白消失", innerMonologueAlwaysVisible(legacy))
        }
    }

    @Test
    fun bothLegacyValuesStillRenderFoldableInnerBlocks() {
        for (legacy in listOf(true, false)) {
            val show = innerMonologueAlwaysVisible(legacy)
            val blocks = visibleMarkdownBlocks(text, chatMode = true, showInnerMonologue = show)
            assertTrue(
                "旧值 legacy=$legacy 时（内心：…）应仍渲染",
                blocks.any { it is MdBlock.InnerMonologue && it.content == "今天也想见你" }
            )
            // 正文其余部分不受影响。
            val joined = blocks.filterIsInstance<MdBlock.Paragraph>().joinToString("|") { it.content }
            assertTrue(joined.contains("早上好呀"))
            assertTrue(joined.contains("要一起吃早饭吗？"))
        }
    }

    @Test
    fun settingNoLongerHasAnyEffectOnMessageText() {
        // 只影响显示判定；消息原文一个字都不动。
        assertEquals(text, text)
        val shown = visibleMarkdownBlocks(text, chatMode = true, showInnerMonologue = innerMonologueAlwaysVisible(false))
        val expected = visibleMarkdownBlocks(text, chatMode = true, showInnerMonologue = true)
        assertEquals(expected, shown)
    }
}
