package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionNativeThinkingTest {

    private val prompt = "【思考方式】用简体中文、第一人称想事情。"

    private fun messages() = listOf(
        mapOf("role" to "system", "content" to "人设"),
        mapOf("role" to "user", "content" to "在吗"),
        mapOf("role" to "assistant", "content" to "在"),
        mapOf("role" to "user", "content" to "今天累不累")
    )

    @Test
    fun promptIsInjectedOnceWithoutChangingPlayerMessages() {
        val result = withCharacterThinkingRule(messages(), prompt, enabled = true)
        assertEquals(5, result.size)
        // 最后一条 system 是提示词
        assertEquals("system", result.last()["role"])
        assertEquals(prompt, result.last()["content"])
        assertEquals("user", result[3]["role"])
        assertEquals(messages()[3], result[3])
        // 其他消息不变
        assertEquals(listOf(messages()[0], messages()[1], messages()[2]), result.take(3))
    }

    @Test
    fun promptIsNotInjectedWhenDisabled() {
        assertEquals(messages(), withCharacterThinkingRule(messages(), prompt, enabled = false))
    }

    @Test
    fun blankPromptIsNotInjected() {
        assertEquals(messages(), withCharacterThinkingRule(messages(), "  \n ", enabled = true))
        assertEquals(messages(), withCharacterThinkingRule(messages(), null, enabled = true))
    }

    @Test
    fun injectionIsIdempotent() {
        val once = withCharacterThinkingRule(messages(), prompt, enabled = true)
        assertEquals(once, withCharacterThinkingRule(once, prompt, enabled = true))
    }

    @Test
    fun emptyMessagesStayEmpty() {
        assertTrue(withCharacterThinkingRule(emptyList(), prompt, enabled = true).isEmpty())
    }

    /** 工具续接时最后一条不是用户消息，也要找到真正的最后一条用户消息。 */
    @Test
    fun promptFollowsLastUserMessageEvenAfterToolMessages() {
        val toolTurn = listOf(
            mapOf("role" to "system", "content" to "人设"),
            mapOf("role" to "user", "content" to "在吗"),
            mapOf("role" to "assistant", "content" to ""),
            mapOf("role" to "tool", "content" to "结果")
        )
        val result = withCharacterThinkingRule(toolTurn, prompt, enabled = true)
        assertEquals("在吗", result[1]["content"])
        assertEquals("system", result.last()["role"])
        assertEquals(prompt, result.last()["content"])
    }

    /** 默认提示词：坚持简体中文和第一人称，且不再要求正文伪思考。 */
    @Test
    fun defaultPromptKeepsChineseRequirementAndDropsPseudoThinking() {
        assertTrue(DEFAULT_CHARACTER_THINKING_PROMPT.contains("简体中文"))
        assertTrue(DEFAULT_CHARACTER_THINKING_PROMPT.contains("第一人称"))
        assertTrue(!DEFAULT_CHARACTER_THINKING_PROMPT.contains("（内心：…）"))
        assertTrue(!DEFAULT_CHARACTER_THINKING_PROMPT.contains("<||>"))
        assertTrue(!DEFAULT_CHARACTER_THINKING_PROMPT.contains("<think>"))
    }

    /** 单会话允许原生思考进入显示（原生已由末尾规则强制中文）。 */
    @Test
    fun bodyOnlyCanStillDisplayNativeThinking() {
        val result = LocalAiResult("正文", thinkingContent = "我在想他现在累不累。")
        val displayed = characterThinkingResult(result, true, bodyOnly = true, allowNativeDisplay = true)
        assertEquals("我在想他现在累不累。", displayed.thinkingContent)
        assertEquals("正文", displayed.content)
    }

    /** 未显式允许时保持旧行为：单会话不显示原生思考。 */
    @Test
    fun bodyOnlyWithoutFlagKeepsHidingNativeThinking() {
        val result = LocalAiResult("正文", thinkingContent = "native reasoning")
        val displayed = characterThinkingResult(result, true, bodyOnly = true)
        assertEquals("", displayed.thinkingContent)
    }
}
