package com.nekobot.app.data.local.sessionimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * DeepSeek 官方导出归档解析的回归测试。
 *
 * 全部数据都是合成的占位内容，不包含任何真实会话、账号或文件路径。
 */
class DeepSeekArchiveReaderTest {

    @get:Rule
    val temp = TemporaryFolder(File("build/session-import-tests").apply { mkdirs() })

    @Test
    fun `index drives conversation list and skips entries that are not in the archive`() {
        val archive = archive(
            "manifest.json" to """{"export_format_version":3}""",
            "chat_session_index.json" to """
                [
                  {"id":"session-old","title":"较早的会话","pinned":false,"message_count":2,"inserted_at":"2025-12-01T00:00:00.000Z","updated_at":"2025-12-02T00:00:00.000Z"},
                  {"id":"session-new","title":"较新的会话","pinned":true,"message_count":1,"inserted_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-03T00:00:00.000Z"},
                  {"id":"session-missing","title":"索引里有但归档里没有","message_count":4,"updated_at":"2026-01-04T00:00:00.000Z"},
                  {"id":"../escape","title":"非法 ID","message_count":1,"updated_at":"2026-01-05T00:00:00.000Z"}
                ]
            """.trimIndent(),
            "chat_sessions/session-old.json" to session("session-old", "较早的会话", messages = 2),
            "chat_sessions/session-new.json" to session("session-new", "较新的会话", messages = 1)
        )

        val conversations = DeepSeekArchiveReader.open(archive).use { it.listConversations() }

        assertEquals(listOf("session-new", "session-old"), conversations.map { it.id })
        assertEquals("较新的会话", conversations.first().title)
        assertTrue(conversations.first().pinned)
        assertEquals(2, conversations.last().messageCount)
        val version = DeepSeekArchiveReader.open(archive).use { it.exportFormatVersion }
        assertEquals(3, version ?: -1)
    }

    @Test
    fun `fragments become user and assistant messages with reasoning and attachment names`() {
        val archive = archive(
            "manifest.json" to """{"export_format_version":3}""",
            "chat_session_index.json" to
                """[{"id":"session-fragments","title":"片段示例","message_count":3,"updated_at":"2026-01-03T00:00:00.000Z"}]""",
            "chat_sessions/session-fragments.json" to fragmentsSession()
        )

        val session = DeepSeekArchiveReader.open(archive).use { it.buildSession("session-fragments") }!!

        assertEquals("片段示例", session["name"].asString)
        assertEquals("agent", session["session_mode"].asString)
        val messages = session.getAsJsonArray("messages")
        assertEquals(2, messages.size())

        val user = messages[0].asJsonObject
        assertEquals("user", user["role"].asString)
        assertEquals("合成的用户提问\n\n📎 合成图片.png", user["content"].asString)
        assertEquals("2026-01-02T03:04:06.000Z", user["created_at"].asString)

        val assistant = messages[1].asJsonObject
        assertEquals("assistant", assistant["role"].asString)
        assertEquals("合成的助手回答", assistant["content"].asString)
        assertEquals("合成的推理过程", assistant["reasoning_content"].asString)
        // 风险提示片段不应混进正文
        assertTrue(!assistant["content"].asString.contains("AI-generated"))
    }

    @Test
    fun `inverted or duplicated source timestamps are normalized to archive order`() {
        val archive = archive(
            "manifest.json" to """{"export_format_version":3}""",
            "chat_session_index.json" to
                """[{"id":"session-order","title":"顺序示例","message_count":3,"updated_at":"2026-01-03T00:00:00.000Z"}]""",
            // 合成数据复刻 DeepSeek 的常见现象：助手回复的 inserted_at 比提问早几毫秒，
            // 且相邻消息可能共享同一时间戳。
            "chat_sessions/session-order.json" to """
                {"id":"session-order","title":"顺序示例",
                 "inserted_at":"2026-01-02T03:04:00.000Z","updated_at":"2026-01-02T03:05:00.000Z",
                 "messages":[
                   {"id":1,"role":"USER","inserted_at":"2026-01-02T03:04:08.417Z","fragments":[{"type":"REQUEST","content":"合成提问"}]},
                   {"id":2,"role":"ASSISTANT","inserted_at":"2026-01-02T03:04:08.413Z","fragments":[{"type":"RESPONSE","content":"合成回答"}]},
                   {"id":3,"role":"USER","inserted_at":"2026-01-02T03:04:08.417Z","fragments":[{"type":"REQUEST","content":"合成追问"}]},
                   {"id":4,"role":"ASSISTANT","inserted_at":"2026-01-02T03:04:08.417Z","fragments":[{"type":"RESPONSE","content":"合成再答"}]}
                 ]}
            """.trimIndent()
        )

        val session = DeepSeekArchiveReader.open(archive).use { it.buildSession("session-order") }!!
        val messages = session.getAsJsonArray("messages")
        val times = messages.map { it.asJsonObject["created_at"].asString }

        assertEquals(
            listOf("user", "assistant", "user", "assistant"),
            messages.map { it.asJsonObject["role"].asString }
        )
        // 时间戳严格递增，且顺序与归档中的消息顺序一致
        assertEquals(times.sorted(), times)
        assertEquals(times.size, times.toSet().size)
        // 已经是递增关系的首条消息保留原始时间
        assertEquals("2026-01-02T03:04:08.417Z", times.first())
        assertEquals("2026-01-02T03:04:08.418Z", times[1])
    }

    @Test
    fun `search and tool fragments never leak into message content`() {
        val archive = archive(
            "manifest.json" to """{"export_format_version":3}""",
            "chat_session_index.json" to
                """[{"id":"session-tools","title":"工具片段","message_count":1,"updated_at":"2026-01-03T00:00:00.000Z"}]""",
            "chat_sessions/session-tools.json" to """
                {"id":"session-tools","title":"工具片段","pinned":false,
                 "inserted_at":"2026-01-02T00:00:00.000Z","updated_at":"2026-01-02T00:00:10.000Z",
                 "messages":[
                   {"id":1,"parent_id":null,"role":"ASSISTANT","status":"FINISHED","inserted_at":"2026-01-02T00:00:05.000Z",
                    "fragments":[
                      {"type":"TOOL_SEARCH","queries":["合成的查询词"],"results":[{"title":"合成结果","url":"https://example.com/a"}]},
                      {"type":"TOOL_OPEN","status":"FINISHED","url":"https://example.com/b","title":"合成页面"},
                      {"type":"RESPONSE","content":"只有结论"}
                    ]},
                   {"id":2,"parent_id":1,"role":"ASSISTANT","status":"TIMEOUT","inserted_at":"2026-01-02T00:00:06.000Z",
                    "fragments":[{"type":"THINK","content":"只有推理，没有正文"}]}
                 ]}
            """.trimIndent()
        )

        val session = DeepSeekArchiveReader.open(archive).use { it.buildSession("session-tools") }!!

        val messages = session.getAsJsonArray("messages")
        assertEquals(1, messages.size())
        assertEquals("只有结论", messages[0].asJsonObject["content"].asString)
        assertNull(messages[0].asJsonObject["reasoning_content"])
    }

    @Test
    fun `conversation without readable messages is skipped`() {
        val archive = archive(
            "chat_session_index.json" to
                """[{"id":"session-empty","title":"空会话","message_count":0,"updated_at":"2026-01-03T00:00:00.000Z"}]""",
            "chat_sessions/session-empty.json" to """
                {"id":"session-empty","title":"空会话","messages":[
                  {"id":1,"role":"USER","fragments":[]},
                  {"id":2,"role":"ASSISTANT","fragments":[{"type":"CONTENT_RISK","content":"提示"}]}
                ]}
            """.trimIndent()
        )

        assertNull(DeepSeekArchiveReader.open(archive).use { it.buildSession("session-empty") })
    }

    @Test
    fun `archive nested in a single top level folder is still recognized`() {
        val archive = archive(
            "deepseek_data-2026-01-01/manifest.json" to """{"export_format_version":3}""",
            "deepseek_data-2026-01-01/chat_session_index.json" to
                """[{"id":"session-nested","title":"重新打包的会话","message_count":1,"updated_at":"2026-01-03T00:00:00.000Z"}]""",
            "deepseek_data-2026-01-01/chat_sessions/session-nested.json" to session("session-nested", "重新打包的会话", messages = 1)
        )

        val conversations = DeepSeekArchiveReader.open(archive).use { it.listConversations() }

        assertEquals(1, conversations.size)
        assertEquals("重新打包的会话", conversations.first().title)
    }

    @Test
    fun `missing index falls back to session files`() {
        val archive = archive(
            "chat_sessions/session-fallback.json" to session("session-fallback", "无索引会话", messages = 2),
            "chat_sessions/session-fallback_files/attachment.txt" to "synthetic attachment"
        )

        val conversations = DeepSeekArchiveReader.open(archive).use { it.listConversations() }

        assertEquals(listOf("session-fallback"), conversations.map { it.id })
        assertEquals(2, conversations.first().messageCount)
    }

    @Test(expected = SessionImportException.UnsupportedArchive::class)
    fun `future export format version is rejected`() {
        val archive = archive(
            "manifest.json" to """{"export_format_version":99}""",
            "chat_session_index.json" to "[]"
        )
        DeepSeekArchiveReader.open(archive)
    }

    @Test(expected = SessionImportException.UnsupportedArchive::class)
    fun `file that is not a zip is rejected`() {
        val file = temp.newFile("not-a-zip.zip").apply { writeText("synthetic plain text") }
        DeepSeekArchiveReader.open(file)
    }

    @Test(expected = SessionImportException.UnsupportedArchive::class)
    fun `unrelated zip is rejected`() {
        DeepSeekArchiveReader.open(archive("readme.txt" to "synthetic text"))
    }

    // ---------------------------------------------------------------- 构造工具

    private fun session(id: String, title: String, messages: Int): String = buildString {
        append("""{"id":"$id","title":"$title","pinned":false,"inserted_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-02T00:00:00.000Z","messages":[""")
        repeat(messages) { index ->
            if (index > 0) append(',')
            append("""{"id":$index,"role":"USER","inserted_at":"2026-01-01T00:00:0$index.000Z","fragments":[{"type":"REQUEST","content":"合成的消息 $index"}]}""")
        }
        append("]}")
    }

    private fun fragmentsSession(): String = """
        {
          "id": "session-fragments",
          "title": "片段示例",
          "pinned": true,
          "inserted_at": "2026-01-02T03:04:00.000Z",
          "updated_at": "2026-01-02T03:05:00.000Z",
          "messages": [
            {"id":1,"parent_id":null,"role":"USER","status":"FINISHED","thinking_enabled":false,
             "inserted_at":"2026-01-02T03:04:06.000Z",
             "fragments":[
               {"type":"FILE","files":[{"id":"file-1","file_name":"合成图片.png","export_path":"chat_sessions/session-fragments_files/file-1_合成图片.png","is_image":true}]},
               {"type":"REQUEST","content":"合成的用户提问"}
             ]},
            {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","thinking_enabled":true,
             "inserted_at":"2026-01-02T03:04:07.000Z",
             "fragments":[
               {"type":"THINK","content":"合成的推理过程"},
               {"type":"RESPONSE","content":"合成的助手回答"},
               {"type":"CONTENT_RISK","content":"This response is AI-generated, for reference only.","style":"WARNING"}
             ]}
          ]
        }
    """.trimIndent()

    private fun archive(vararg entries: Pair<String, String>): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
