package com.nekobot.app.data.local.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 权限校验：声明 ∧ 授权；没有记录时按存量插件放行。 */
class PluginGrantsTest {

    private fun plugin(permissions: List<String>) = InstalledPlugin(
        id = "demo.grants",
        name = "权限测试",
        version = "1.0.0",
        author = "",
        description = "",
        entry = "main.js",
        permissions = permissions,
        commands = emptyList(),
        enabled = true,
        installedAt = 0L
    )

    @Test
    fun undeclaredPermissionFails() {
        val error = assertThrows(PluginApiException::class.java) {
            checkPluginPermission(plugin(emptyList()), "chat.read", emptySet())
        }
        assertEquals("permission_not_declared", error.code)
    }

    @Test
    fun declaredButNotGrantedFails() {
        val error = assertThrows(PluginApiException::class.java) {
            checkPluginPermission(plugin(listOf("network")), "network", emptySet())
        }
        assertEquals("permission_denied", error.code)
    }

    @Test
    fun declaredAndGrantedPasses() {
        checkPluginPermission(plugin(listOf("network")), "network", setOf("network"))
    }

    @Test
    fun legacyPluginWithoutRecordIsAllowed() {
        // granted = null 表示没有授权记录（升级前安装的存量插件）
        checkPluginPermission(plugin(listOf("chat.read")), "chat.read", null)
    }

    @Test
    fun revokedPermissionFailsImmediately() {
        val error = assertThrows(PluginApiException::class.java) {
            checkPluginPermission(plugin(listOf("storage")), "storage", emptySet())
        }
        assertEquals("permission_denied", error.code)
    }

}
