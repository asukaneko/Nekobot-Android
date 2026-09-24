package com.nekobot.app.data.local.plugin

import org.junit.Assert.assertTrue
import org.junit.Test

/** api_version 兼容：接受 1..CURRENT，拒绝 0 与未来版本。 */
class PluginManifestApiVersionCompatTest {

    private fun manifest(apiVersion: Int, permissions: List<String> = emptyList()) = PluginManifest(
        apiVersion = apiVersion,
        id = "demo.version",
        name = "版本测试",
        version = "1.0.0",
        entry = "main.js",
        permissions = permissions,
        commands = listOf(PluginCommandManifest(name = "ping"))
    )

    private fun errorsFor(apiVersion: Int): List<String> =
        PluginManifestValidator.validate(manifest(apiVersion))

    @Test
    fun versionOneStillInstalls() {
        assertTrue(errorsFor(1).none { it.contains("API 版本") })
    }

    @Test
    fun currentVersionInstalls() {
        assertTrue(
            errorsFor(PluginManifestValidator.CURRENT_API_VERSION).none { it.contains("API 版本") }
        )
    }

    @Test
    fun zeroAndFutureVersionsAreRejected() {
        assertTrue(errorsFor(0).any { it.contains("API 版本") })
        assertTrue(errorsFor(PluginManifestValidator.CURRENT_API_VERSION + 1).any { it.contains("API 版本") })
    }

    @Test
    fun readOnlyPermissionsAreAccepted() {
        val errors = PluginManifestValidator.validate(
            manifest(
                2,
                listOf("characters.read", "worldbooks.read", "memory.read", "chat.write", "memory.write", "ai.call")
            )
        )
        assertTrue(errors.none { it.contains("未知插件权限") })
    }

    @Test
    fun dangerousPermissionsAreNotDefaultGranted() {
        val declared = listOf("storage", "chat.read", "network", "chat.write", "ai.call")
        val granted = PluginManifestValidator.defaultGrantedPermissions(declared)
        assertTrue("storage" in granted)
        assertTrue("chat.read" in granted)
        assertTrue("network" !in granted)
        assertTrue("chat.write" !in granted)
        assertTrue("ai.call" !in granted)
    }

    @Test
    fun workspacePermissionIsSupportedButDangerous() {
        val errors = PluginManifestValidator.validate(manifest(2, listOf("workspace")))
        assertTrue(errors.none { it.contains("未知插件权限") })
        assertTrue("workspace" in PluginManifestValidator.dangerousPermissions)
        assertTrue("workspace" !in PluginManifestValidator.defaultGrantedPermissions(listOf("workspace")))
    }

    @Test
    fun unknownPermissionIsRejected() {
        val errors = PluginManifestValidator.validate(manifest(2, listOf("camera")))
        assertTrue(errors.any { it.contains("未知插件权限") })
    }
}
