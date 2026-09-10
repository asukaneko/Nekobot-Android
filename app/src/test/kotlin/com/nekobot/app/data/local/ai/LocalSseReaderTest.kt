package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * SSE 读取器：多 `data:` 行事件、非规范事件流、非法负载都不能被静默吞掉。
 */
class LocalSseReaderTest {

    private fun read(raw: String): List<String> {
        val payloads = mutableListOf<String>()
        runBlocking {
            readSseEvents(BufferedReader(StringReader(raw))) { payload ->
                payloads.add(payload)
                true
            }
        }
        return payloads
    }

    @Test
    fun `每行一个事件且没有空行分隔时逐个派发`() {
        val payloads = read(
            """
            data: {"a":1}
            data: {"b":2}
            data: [DONE]
            """.trimIndent()
        )

        assertEquals(listOf("""{"a":1}""", """{"b":2}""", "[DONE]"), payloads)
    }

    @Test
    fun `同一个事件跨多行 data 时合并为一个负载`() {
        val payloads = read(
            """
            data: {"choices":
            data: [{"delta":{"content":"hi"}}]}

            """.trimIndent() + "\n"
        )

        assertEquals(1, payloads.size)
        assertTrue("多行 data 应按 SSE 规范用换行拼接", payloads.first().contains(""""choices":"""))
        assertTrue(payloads.first().endsWith("]}"))
    }

    @Test
    fun `空行分隔的多个事件依次派发`() {
        val payloads = read(
            "data: {\"a\":1}\n\ndata: {\"b\":2}\n\ndata: [DONE]\n\n"
        )

        assertEquals(3, payloads.size)
    }

    @Test
    fun `忽略注释心跳与 event 字段`() {
        val payloads = read(
            """
            : ping
            event: message
            data: {"a":1}
            id: 42
            """.trimIndent()
        )

        assertEquals(listOf("""{"a":1}"""), payloads)
    }

    @Test
    fun `非法负载被丢弃且不影响后续事件`() {
        val payloads = read(
            """
            data: <html>502 Bad Gateway</html>
            data: {"b":2}
            """.trimIndent()
        )

        assertEquals("HTML 错误页不能被当成协议负载", listOf("""{"b":2}"""), payloads)
    }

    @Test
    fun `回调返回 false 时立即停止读取`() {
        val payloads = mutableListOf<String>()
        runBlocking {
            readSseEvents(
                BufferedReader(
                    StringReader("data: {\"a\":1}\ndata: [DONE]\ndata: {\"c\":3}\n")
                )
            ) { payload ->
                payloads.add(payload)
                payload != "[DONE]"
            }
        }

        assertEquals(listOf("""{"a":1}""", "[DONE]"), payloads)
    }

    @Test
    fun `流在事件中途结束时丢弃不完整负载`() {
        val payloads = read("data: {\"a\":1}\ndata: {\"b\":\n")

        assertEquals(listOf("""{"a":1}"""), payloads)
    }
}
