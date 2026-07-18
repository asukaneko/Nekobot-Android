package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalWorkspaceStorageTest {

    @Test
    fun uiAndAgentResolveTheSameWorkspaceAndMigrateLegacyFiles() {
        val filesDir = Files.createTempDirectory("nekobot-workspace").toFile()
        try {
            val legacy = filesDir.resolve("agent_workspaces/session-1")
            legacy.mkdirs()
            legacy.resolve("old-agent.txt").writeText("旧 Agent 文件")

            val workspace = LocalWorkspaceStorage.resolve(filesDir, "session-1")

            assertEquals(
                filesDir.resolve("workspace/session-1").canonicalFile,
                workspace
            )
            assertEquals(
                "旧 Agent 文件",
                workspace?.resolve("old-agent.txt")?.readText()
            )

            workspace?.resolve("uploaded.txt")?.writeText("界面上传内容")
            val agentWorkspace = LocalWorkspaceStorage.resolve(filesDir, "session-1")
            assertEquals("界面上传内容", agentWorkspace?.resolve("uploaded.txt")?.readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun migrationNeverOverwritesFilesAlreadyUploadedInCanonicalWorkspace() {
        val filesDir = Files.createTempDirectory("nekobot-workspace-conflict").toFile()
        try {
            filesDir.resolve("workspace/session-1").apply {
                mkdirs()
                resolve("same.txt").writeText("界面版本")
            }
            filesDir.resolve("agent_workspaces/session-1").apply {
                mkdirs()
                resolve("same.txt").writeText("旧 Agent 版本")
            }

            val workspace = LocalWorkspaceStorage.resolve(filesDir, "session-1")

            assertEquals("界面版本", workspace?.resolve("same.txt")?.readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsSessionIdsThatEscapeWorkspaceRoot() {
        val filesDir = Files.createTempDirectory("nekobot-workspace-escape").toFile()
        try {
            assertNull(LocalWorkspaceStorage.resolve(filesDir, "../outside"))
            assertFalse(filesDir.resolve("outside").exists())
            assertTrue(LocalWorkspaceStorage.resolve(filesDir, "safe-session")?.isDirectory == true)
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
