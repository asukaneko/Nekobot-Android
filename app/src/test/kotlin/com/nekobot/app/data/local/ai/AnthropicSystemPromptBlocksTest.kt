package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 多段 system 的协议级回归。
 *
 * 修复前 `AnthropicMessagesProtocol` 只取第一条 system，其余 system 段落
 * （世界书按深度插入、回查收尾说明等）被静默丢掉，等于没发给模型。
 * 本文件锁住四件事：全部收下、顺序不变、空段落跳过、缓存断点只在最后一块；
 * 并用 openai_chat 做对照，确认它的消息列表仍原样透传，没有被这套改动带偏。
 */
class AnthropicSystemPromptBlocksTest {

    private val gson = Gson()

    private fun system(text: String) = mapOf<String, Any>("role" to "system", "content" to text)
    private val user = mapOf<String, Any>("role" to "user", "content" to "今晚有点累")
    private val assistant = mapOf<String, Any>("role" to "assistant", "content" to "那就早点休息")

    private fun anthropicPayload(messages: List<Map<String, Any>>) =
        AnthropicMessagesProtocol.buildPayload(
            model = "claude-test",
            messages = messages,
            stream = false,
            extra = emptyMap()
        )

    @Suppress("UNCHECKED_CAST")
    private fun blocksOf(payload: Map<String, Any>): List<Map<String, Any>> =
        payload["system"] as List<Map<String, Any>>

    private fun textsOf(payload: Map<String, Any>): List<String> =
        blocksOf(payload).map { it["text"] as String }

    private fun hasCacheControl(block: Map<String, Any>): Boolean = block.containsKey("cache_control")

    /** 顶部规则、世界书补充、收尾说明三段 system 夹在对话中间时必须全部被收下，且顺序不变。 */
    @Test
    fun allSystemSegmentsReachAnthropicInOrder() {
        val messages = listOf(
            system("顶部规则：你是星伴，说话简短自然。"),
            user,
            system("资料补充：对方上周提过想去看海。"),
            assistant,
            system("收尾说明：想不起来就直说，别编。")
        )

        val payload = anthropicPayload(messages)

        assertEquals(
            listOf(
                "顶部规则：你是星伴，说话简短自然。",
                "资料补充：对方上周提过想去看海。",
                "收尾说明：想不起来就直说，别编。"
            ),
            textsOf(payload)
        )

        // 对话本身只保留 user/assistant，system 不会混进 messages。
        @Suppress("UNCHECKED_CAST")
        val conversation = payload["messages"] as List<Map<String, Any>>
        assertEquals(listOf("user", "assistant"), conversation.map { it["role"] })
        val conversationJson = gson.toJson(conversation)
        assertFalse("system 段不得重复出现在对话里", conversationJson.contains("顶部规则"))
        assertFalse("system 段不得重复出现在对话里", conversationJson.contains("收尾说明"))
    }

    /** 单段 system 时仍是一个 text 块，形状与修复前可缓存时的表现一致。 */
    @Test
    fun singleSystemSegmentProducesSingleBlock() {
        val payload = anthropicPayload(listOf(system("只有一段设定"), user))

        assertEquals(listOf("只有一段设定"), textsOf(payload))
        assertFalse("短提示词不打缓存断点", hasCacheControl(blocksOf(payload).single()))
    }

    /** 总长度够长时，缓存断点只打最后一块——缓存前缀才能覆盖全部 system。 */
    @Test
    fun cacheBreakpointOnlyOnLastSystemBlock() {
        val first = "甲".repeat(1_500)
        val second = "乙".repeat(1_500)
        val third = "丙".repeat(1_500)

        val blocks = blocksOf(
            anthropicPayload(listOf(system(first), user, system(second), system(third)))
        )

        assertEquals(3, blocks.size)
        assertFalse(hasCacheControl(blocks[0]))
        assertFalse(hasCacheControl(blocks[1]))
        assertTrue(hasCacheControl(blocks[2]))
        assertEquals("ephemeral", (blocks[2]["cache_control"] as Map<*, *>)["type"])
    }

    /** 总长度不足最小缓存长度时，任何一块都不打缓存断点。 */
    @Test
    fun shortSystemSegmentsGetNoCacheBreakpoint() {
        val blocks = blocksOf(
            anthropicPayload(listOf(system("短规则一"), user, system("短规则二")))
        )

        assertEquals(2, blocks.size)
        assertTrue("短提示词不应打缓存断点", blocks.none { hasCacheControl(it) })
    }

    /** 空白 system 段直接跳过，不占块也不影响顺序。 */
    @Test
    fun blankSystemSegmentsAreSkipped() {
        val payload = anthropicPayload(
            listOf(system("第一段"), system("   "), system(""), user, system("第三段"))
        )

        assertEquals(listOf("第一段", "第三段"), textsOf(payload))
    }

    /** 整份请求没有任何 system 时不写 system 字段（与修复前一致）。 */
    @Test
    fun noSystemSegmentLeavesFieldAbsent() {
        val payload = anthropicPayload(listOf(user, assistant))

        assertFalse(payload.containsKey("system"))
    }

    /**
     * 对照：openai_chat 的消息列表必须原样透传。
     *
     * 三个 system 段与其原始位置、原始顺序、原始内容都要一个字节不变；
     * 这条对照用来确认 Anthropic 的合并改动没有波及别的协议。
     */
    @Test
    fun openAiChatStillSendsMessagesVerbatim() {
        val messages = listOf(
            system("顶部规则"),
            user,
            system("资料补充"),
            assistant,
            system("收尾说明")
        )

        val payload = OpenAIChatProtocol.buildPayload(
            model = "gpt-test",
            messages = messages,
            stream = false,
            extra = emptyMap()
        )

        @Suppress("UNCHECKED_CAST")
        val sent = payload["messages"] as List<Map<String, Any>>
        assertEquals("openai_chat 不得改写消息列表", gson.toJson(messages), gson.toJson(sent))
        assertEquals(listOf("system", "user", "system", "assistant", "system"), sent.map { it["role"] })
        assertFalse("openai_chat 不应另起 system 字段", payload.containsKey("system"))
    }
}
