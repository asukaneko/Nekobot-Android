package com.nekobot.app.data.local.ai

import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalAgentToolingTest {

    @Test
    fun toolDefinitionsExposeOnlyExecutableTools() {
        val names = buildLocalAgentToolDefinitions().mapNotNull { tool ->
            @Suppress("UNCHECKED_CAST")
            (tool["function"] as? Map<String, Any>)?.get("name") as? String
        }

        assertTrue("get_date_time" in names)
        assertTrue("exec_command" in names)
        assertTrue("workspace_read_file" in names)
        assertTrue("workspace_extract_epub" in names)
        assertFalse("save_to_memory" in names)
        assertEquals(names.distinct().size, names.size)
    }

    @Test
    fun commandPolicyAllowsBareReadOnlyCommandsAndBlocksDestructivePatterns() {
        val safe = evaluateLocalCommand("ls -la")
        assertEquals("ls", safe.mainCommand)
        assertFalse(safe.requiresAuthorization)
        assertEquals(null, safe.blockedReason)

        val approval = evaluateLocalCommand("git status")
        assertEquals("git", approval.mainCommand)
        assertTrue(approval.requiresAuthorization)

        val blocked = evaluateLocalCommand("rm -rf .")
        assertNotNull(blocked.blockedReason)
    }

    @Test
    fun alwaysAuthorizationIsReusedForSameCommandInSession() {
        val manager = LocalExecAuthorizationManager(authorizationTimeoutMs = 2000)
        val requestRef = AtomicReference<ExecConfirmationRequest>()
        val requestReady = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<ExecAuthorization> {
                manager.requestAuthorization(
                    sessionId = "session-1",
                    command = "git status",
                    mainCommand = "git"
                ) {
                    requestRef.set(it)
                    requestReady.countDown()
                }
            }

            assertTrue(requestReady.await(1, TimeUnit.SECONDS))
            val request = requestRef.get()
            assertTrue(
                manager.resolve(
                    requestId = request.requestId,
                    sessionId = "session-1",
                    authorization = ExecAuthorization.Always
                )
            )
            assertEquals(ExecAuthorization.Always, first.get(1, TimeUnit.SECONDS))

            var requestedAgain = false
            val reused = manager.requestAuthorization(
                sessionId = "session-1",
                command = "git diff",
                mainCommand = "git"
            ) { requestedAgain = true }
            assertEquals(ExecAuthorization.Always, reused)
            assertFalse(requestedAgain)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun yoloSkipsAuthorizationWithinSessionOnly() {
        val manager = LocalExecAuthorizationManager(authorizationTimeoutMs = 100)
        manager.enableYolo("session-yolo")

        var requested = false
        val result = manager.requestAuthorization(
            sessionId = "session-yolo",
            command = "git status",
            mainCommand = "git"
        ) { requested = true }

        assertEquals(ExecAuthorization.Once, result)
        assertFalse(requested)
        assertFalse(manager.isYoloEnabled("other-session"))
    }

    @Test
    fun workspaceToolsReadWriteAndRejectPathTraversal() {
        val root = Files.createTempDirectory("nekobot-agent-tools").toFile()
        try {
            val executor = LocalAgentToolExecutor(
                sessionId = "session-1",
                workspaceRoot = root,
                authorizationManager = LocalExecAuthorizationManager(100),
                onConfirmationRequired = {},
                thinkingHistoryProvider = { emptyList() }
            )

            val created = executor.execute(
                "workspace_create_file",
                mapOf("path" to "notes/todo.txt", "content" to "完成工具测试")
            )
            assertEquals(true, created["success"])
            assertEquals(
                root.resolve("notes/todo.txt").canonicalPath,
                created["absolute_path"]
            )

            val read = executor.execute(
                "workspace_read_file",
                mapOf("filename" to "notes/todo.txt")
            )
            assertEquals(true, read["success"])
            assertEquals("完成工具测试", read["content"])
            assertEquals(
                root.resolve("notes/todo.txt").canonicalPath,
                read["absolute_path"]
            )

            val escaped = executor.execute(
                "workspace_read_file",
                mapOf("path" to "../outside.txt")
            )
            assertEquals(false, escaped["success"])

            // 行范围与字符限制参数测试
            val multi = root.resolve("notes/multi.txt")
            multi.parentFile?.mkdirs()
            multi.writeText("line1\nline2\nline3\nline4\nline5\n", Charsets.UTF_8)

            // 只读第 2-4 行
            val range = executor.execute(
                "workspace_read_file",
                mapOf("path" to "notes/multi.txt", "start_line" to 2, "end_line" to 4)
            )
            assertEquals(true, range["success"])
            assertEquals("line2\nline3\nline4\n", range["content"])
            assertEquals(5, range["total_lines"])
            assertEquals(2, range["start_line"])
            assertEquals(4, range["end_line"])
            assertEquals(false, range["truncated"])

            // max_chars 截断
            val capped = executor.execute(
                "workspace_read_file",
                mapOf("path" to "notes/multi.txt", "max_chars" to 10)
            )
            assertEquals(true, capped["success"])
            assertEquals(10, (capped["content"] as String).length)
            assertEquals(true, capped["truncated"])
            assertEquals(35, capped["total_chars"])

            // max_chars=0 表示不限制
            val unlimited = executor.execute(
                "workspace_read_file",
                mapOf("path" to "notes/multi.txt", "max_chars" to 0)
            )
            assertEquals(true, unlimited["success"])
            assertEquals(false, unlimited["truncated"])
            assertEquals("line1\nline2\nline3\nline4\nline5\n", unlimited["content"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workspaceExtractEpubCreatesOrderedTxtAndReturnsCanonicalPath() {
        val root = Files.createTempDirectory("nekobot-agent-epub").toFile()
        try {
            val epub = root.resolve("novels/book.epub")
            epub.parentFile?.mkdirs()
            createTestEpub(epub)
            val executor = LocalAgentToolExecutor(
                sessionId = "session-epub",
                workspaceRoot = root,
                authorizationManager = LocalExecAuthorizationManager(100),
                onConfirmationRequired = {},
                thinkingHistoryProvider = { emptyList() }
            )

            val extracted = executor.execute(
                "workspace_extract_epub",
                mapOf("path" to "novels/book.epub")
            )

            assertEquals(true, extracted["success"])
            val output = root.resolve("novels/book.txt")
            assertEquals("novels/book.txt", extracted["path"])
            assertEquals(output.canonicalPath, extracted["absolute_path"])
            assertEquals(epub.canonicalPath, extracted["source_absolute_path"])
            assertEquals(2, extracted["chapter_count"])
            val text = output.readText(Charsets.UTF_8)
            assertTrue(text.indexOf("第一章") < text.indexOf("第二章"))
            assertTrue("你好 & 世界" in text)
            assertTrue("故事继续……" in text)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createTestEpub(file: java.io.File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            add(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent()
            )
            add(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <manifest>
                    <item id="second" href="Text/chapter%202.xhtml" media-type="application/xhtml+xml"/>
                    <item id="first" href="Text/chapter1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="first"/>
                    <itemref idref="second"/>
                  </spine>
                </package>
                """.trimIndent()
            )
            add(
                "OEBPS/Text/chapter1.xhtml",
                "<html><body><h1>第一章</h1><p>你好 &amp; 世界</p></body></html>"
            )
            add(
                "OEBPS/Text/chapter 2.xhtml",
                "<html><body><h1>第二章</h1><p>故事继续&#x2026;&#x2026;</p></body></html>"
            )
        }
    }
}
