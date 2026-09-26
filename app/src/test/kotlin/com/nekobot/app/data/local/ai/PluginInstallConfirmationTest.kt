package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.plugin.PluginManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * 第三方插件安装确认：Agent 安装工作区 ZIP 时必须由用户勾选协议与权限，
 * 拒绝/取消/会话不匹配都不能放行。
 */
class PluginInstallConfirmationTest {

    private fun manifest() = PluginManifest(
        id = "demo.test",
        name = "测试插件",
        version = "1.0.0",
        permissions = listOf("storage", "network", "ai.call")
    )

    @Test
    fun `确认安装只授予用户勾选的权限`() = runBlocking {
        val manager = LocalPluginInstallConfirmationManager()
        val sessionId = "session-a"
        val captured = AtomicReference<PluginInstallConfirmationRequest?>()

        val decision = manager.requestConfirmation(sessionId, "plugins/demo.zip", manifest()) { request ->
            captured.set(request)
            manager.resolve(
                requestId = request.requestId,
                sessionId = sessionId,
                decision = PluginInstallDecision(approved = true, grantedPermissions = setOf("network"))
            )
        }

        val request = captured.get()!!
        assertEquals("plugins/demo.zip", request.sourceLabel)
        assertEquals(listOf("storage", "network", "ai.call"), request.declaredPermissions)
        assertEquals("危险权限默认不勾选", setOf("storage"), request.defaultGrantedPermissions)
        assertTrue(decision.approved)
        assertEquals(setOf("network"), decision.grantedPermissions)
    }

    @Test
    fun `拒绝安装不返回权限`() = runBlocking {
        val manager = LocalPluginInstallConfirmationManager()
        val sessionId = "session-a"

        val decision = manager.requestConfirmation(sessionId, "demo.zip", manifest()) { request ->
            manager.resolve(
                requestId = request.requestId,
                sessionId = sessionId,
                decision = PluginInstallDecision(approved = false)
            )
        }

        assertFalse(decision.approved)
        assertTrue(decision.grantedPermissions.isEmpty())
    }

    @Test
    fun `停止生成会取消待确认安装并视为拒绝`() = runBlocking {
        val manager = LocalPluginInstallConfirmationManager()
        val sessionId = "session-a"
        val requestId = AtomicReference<String>()

        val decision = manager.requestConfirmation(sessionId, "demo.zip", manifest()) { request ->
            requestId.set(request.requestId)
            manager.cancelSession(sessionId)
        }

        assertFalse(decision.approved)
        assertFalse(
            "已取消的请求不能再被提交",
            manager.resolve(requestId.get(), sessionId, PluginInstallDecision(approved = true))
        )
    }

    @Test
    fun `其他会话不能替用户确认`() = runBlocking {
        val manager = LocalPluginInstallConfirmationManager()
        val captured = AtomicReference<PluginInstallConfirmationRequest?>()

        val decision = manager.requestConfirmation("session-a", "demo.zip", manifest()) { request ->
            captured.set(request)
            val accepted = manager.resolve(
                requestId = request.requestId,
                sessionId = "session-b",
                decision = PluginInstallDecision(approved = true, grantedPermissions = setOf("ai.call"))
            )
            assertFalse(accepted)
            manager.resolve(
                requestId = request.requestId,
                sessionId = "session-a",
                decision = PluginInstallDecision(approved = true, grantedPermissions = emptySet())
            )
        }

        assertTrue(decision.approved)
        assertTrue("用户未勾选任何权限时保持空集合", decision.grantedPermissions.isEmpty())
    }
}
