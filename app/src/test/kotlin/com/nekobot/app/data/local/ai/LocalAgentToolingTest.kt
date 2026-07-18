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

            val read = executor.execute(
                "workspace_read_file",
                mapOf("path" to "notes/todo.txt")
            )
            assertEquals(true, read["success"])
            assertEquals("完成工具测试", read["content"])

            val escaped = executor.execute(
                "workspace_read_file",
                mapOf("path" to "../outside.txt")
            )
            assertEquals(false, escaped["success"])
        } finally {
            root.deleteRecursively()
        }
    }
}
