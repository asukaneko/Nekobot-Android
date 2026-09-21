package com.nekobot.app.data.local.ai

import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * 命令授权记忆粒度：普通命令按命令名，高危多用途命令按“命令名 + 子命令”，
 * 工具类操作按“工具名 + 参数摘要”。
 */
class LocalExecAuthorizationFingerprintTest {

    @Test
    fun `普通命令只按命令名记忆`() {
        assertEquals(setOf("ls"), extractLocalAuthorizationFingerprints("ls -la /sdcard", "ls"))
        assertEquals(setOf("cat"), extractLocalAuthorizationFingerprints("cat notes.txt", "cat"))
    }

    @Test
    fun `高危命令按子命令区分`() {
        assertEquals(setOf("git status"), extractLocalAuthorizationFingerprints("git status", "git"))
        assertEquals(setOf("npm install"), extractLocalAuthorizationFingerprints("npm install --save x", "npm"))
        assertNotEquals(
            extractLocalAuthorizationFingerprints("git status", "git"),
            extractLocalAuthorizationFingerprints("git push origin main", "git")
        )
    }

    @Test
    fun `命令链中的每条命令分别记忆`() {
        val fingerprints = extractLocalAuthorizationFingerprints("git status && git push origin main", "git")

        assertEquals(setOf("git status", "git push"), fingerprints)
    }

    @Test
    fun `引号内的运算符不参与切分`() {
        val fingerprints = extractLocalAuthorizationFingerprints("""echo "a && b" """.trim(), "echo")

        assertEquals(setOf("echo"), fingerprints)
    }

    @Test
    fun `非 Shell 标签沿用调用方给出的主命令`() {
        assertEquals(
            setOf("agent_memory_update"),
            extractLocalAuthorizationFingerprints("模式: append", "agent_memory_update")
        )
    }

    @Test
    fun `始终允许某条命令不会放行同命令的其他子命令`() = runBlocking {
        val manager = LocalExecAuthorizationManager(authorizationTimeoutMs = 5_000L)
        val sessionId = "session-a"

        val first = manager.requestAuthorization(sessionId, "git status", "git") { request ->
            manager.resolve(request.requestId, sessionId, ExecAuthorization.Always)
        }
        assertEquals(ExecAuthorization.Always, first)

        var secondPopup = false
        val second = manager.requestAuthorization(sessionId, "git status", "git") { secondPopup = true }
        assertEquals("已授权命令应直接放行", ExecAuthorization.Always, second)
        assertFalse("已授权命令不应再次弹窗", secondPopup)

        var pushPopup = false
        val push = manager.requestAuthorization(sessionId, "git push origin main", "git") { request ->
            pushPopup = true
            manager.resolve(request.requestId, sessionId, ExecAuthorization.Reject)
        }
        assertTrue("git push 必须重新弹窗确认", pushPopup)
        assertEquals(ExecAuthorization.Reject, push)
    }

    @Test
    fun `持久化的授权指纹在重启后立即生效`() = runBlocking {
        val persisted = mapOf("session-b" to setOf("git status"))
        val manager = LocalExecAuthorizationManager(
            authorizationTimeoutMs = 5_000L,
            loadPersistedRules = { sessionId -> persisted[sessionId] },
            savePersistedRules = { _, _ -> }
        )

        var popup = false
        val decision = manager.requestAuthorization("session-b", "git status", "git") { popup = true }

        assertEquals(ExecAuthorization.Always, decision)
        assertFalse("持久化指纹命中时不应弹窗", popup)
    }

    @Test
    fun `始终允许会写入持久化存储`() = runBlocking {
        val saved = mutableMapOf<String, Set<String>>()
        val manager = LocalExecAuthorizationManager(
            authorizationTimeoutMs = 5_000L,
            loadPersistedRules = { sessionId -> saved[sessionId] },
            savePersistedRules = { sessionId, rules -> saved[sessionId] = rules }
        )
        val sessionId = "session-c"

        manager.requestAuthorization(sessionId, "git status", "git") { request ->
            manager.resolve(request.requestId, sessionId, ExecAuthorization.Always)
        }

        assertTrue("始终允许应持久化指纹", saved[sessionId]?.contains("git status") == true)
    }

    @Test
    fun `不可记忆的命令覆盖解释器与安装类`() {
        assertFalse(isMemorizableCommand("python3 train.py"))
        assertFalse(isMemorizableCommand("""sh -c 'echo hi'"""))
        assertFalse(isMemorizableCommand("npm install --save lodash"))
        assertFalse(isMemorizableCommand("pip install requests"))
        assertFalse(isMemorizableCommand("git status && apk add curl"))
        assertTrue(isMemorizableCommand("git status"))
        assertTrue(isMemorizableCommand("npm test"))
        assertTrue(isMemorizableCommand("npm run build"))
    }

    @Test
    fun `解释器命令不能始终允许但YOLO可以放行`() = runBlocking {
        val manager = LocalExecAuthorizationManager(authorizationTimeoutMs = 5_000L)
        val sessionId = "session-interpreter"

        val captured = AtomicReference<ExecConfirmationRequest>()
        manager.requestAuthorization(sessionId, "python3 train.py", "python3") { request ->
            captured.set(request)
            manager.resolve(request.requestId, sessionId, ExecAuthorization.Always)
        }

        assertFalse("解释器命令不应提供「始终允许」", captured.get().memorizable)
        assertTrue("解释器命令不应写入授权记忆", manager.allowedKeys(sessionId).isEmpty())

        manager.enableYolo(sessionId)
        var yoloPopup = false
        val yolo = manager.requestAuthorization(sessionId, "python3 train.py", "python3") { yoloPopup = true }
        assertEquals(ExecAuthorization.Once, yolo)
        assertFalse("YOLO 应放行解释器命令", yoloPopup)
    }

    @Test
    fun `安装类命令不能始终允许`() = runBlocking {
        val manager = LocalExecAuthorizationManager(authorizationTimeoutMs = 5_000L)
        val sessionId = "session-install"

        val captured = AtomicReference<ExecConfirmationRequest>()
        manager.requestAuthorization(sessionId, "npm install lodash", "npm") { request ->
            captured.set(request)
            manager.resolve(request.requestId, sessionId, ExecAuthorization.Always)
        }

        assertFalse("安装类命令不应提供「始终允许」", captured.get().memorizable)
        assertTrue("安装类命令不应写入授权记忆", manager.allowedKeys(sessionId).isEmpty())

        val testRequest = AtomicReference<ExecConfirmationRequest>()
        manager.requestAuthorization(sessionId, "npm test", "npm") { request ->
            testRequest.set(request)
            manager.resolve(request.requestId, sessionId, ExecAuthorization.Always)
        }
        assertTrue("npm test 仍可「始终允许」", testRequest.get().memorizable)
        assertTrue("npm test 应写入授权记忆", manager.allowedKeys(sessionId).contains("npm test"))
    }

    @Test
    fun `读取界面类操作连YOLO也不能跳过`() = runBlocking {
        val manager = LocalExecAuthorizationManager(authorizationTimeoutMs = 5_000L)
        val sessionId = "session-ui"
        manager.enableYolo(sessionId)

        var popup = false
        val decision = manager.requestAuthorization(
            sessionId = sessionId,
            command = "android_ui_tree",
            mainCommand = "android_ui_tree",
            memorizable = false
        ) { request ->
            popup = true
            manager.resolve(request.requestId, sessionId, ExecAuthorization.Once)
        }

        assertTrue("界面读取即使开启 YOLO 也必须确认", popup)
        assertEquals(ExecAuthorization.Once, decision)
    }

    @Test
    fun `工具类操作按参数摘要记忆且不串用`() = runBlocking {
        val manager = LocalExecAuthorizationManager(authorizationTimeoutMs = 5_000L)
        val sessionId = "session-d"

        val first = manager.requestToolAuthorization(
            sessionId = sessionId,
            toolName = "workspace_delete_file",
            fingerprintArgument = "/workspace/notes.md",
            message = "删除文件",
            onRequest = { request -> manager.resolve(request.requestId, sessionId, ExecAuthorization.Always) }
        )
        assertTrue(first)

        var samePopup = false
        val second = manager.requestToolAuthorization(
            sessionId = sessionId,
            toolName = "workspace_delete_file",
            fingerprintArgument = "/workspace/notes.md",
            message = "删除文件",
            onRequest = { samePopup = true }
        )
        assertTrue(second)
        assertFalse("同一参数应复用授权", samePopup)

        var otherPopup = false
        val other = manager.requestToolAuthorization(
            sessionId = sessionId,
            toolName = "workspace_delete_file",
            fingerprintArgument = "/workspace/other.md",
            message = "删除文件",
            onRequest = { request ->
                otherPopup = true
                manager.resolve(request.requestId, sessionId, ExecAuthorization.Reject)
            }
        )
        assertTrue("不同参数必须重新确认", otherPopup)
        assertFalse(other)
    }
}
