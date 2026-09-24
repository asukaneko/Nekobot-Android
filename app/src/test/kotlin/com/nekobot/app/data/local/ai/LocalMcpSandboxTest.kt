package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalMcpSandboxTest {

    @Test
    fun `posix quoting keeps safe values and escapes special characters`() {
        assertEquals("npx", LocalMcpSandbox.localPosixQuote("npx"))
        assertEquals(
            "@modelcontextprotocol/server-filesystem",
            LocalMcpSandbox.localPosixQuote("@modelcontextprotocol/server-filesystem")
        )
        assertEquals("-y", LocalMcpSandbox.localPosixQuote("-y"))
        assertEquals("'/shared/my dir'", LocalMcpSandbox.localPosixQuote("/shared/my dir"))
        assertEquals("'it'\\''s'", LocalMcpSandbox.localPosixQuote("it's"))
        assertEquals("''", LocalMcpSandbox.localPosixQuote(""))
        assertEquals("'a;rm -rf /'", LocalMcpSandbox.localPosixQuote("a;rm -rf /"))
    }

    @Test
    fun `shell command joins command and args with quoting`() {
        assertEquals(
            "npx -y @modelcontextprotocol/server-filesystem '/shared/my dir'",
            LocalMcpSandbox.buildShellCommand(
                "npx",
                listOf("-y", "@modelcontextprotocol/server-filesystem", "/shared/my dir")
            )
        )
    }

    @Test
    fun `sandbox command runs through guest shell inside proot`() {
        val proot = File("native/libproot.so").absoluteFile
        val rootfs = File("data/alpine-rootfs").absoluteFile
        val workspace = File("data/mcp-workspace").absoluteFile
        val shared = File("data/workspace/shared").absoluteFile

        val command = buildLocalMcpSandboxCommand(
            proot = proot,
            rootfs = rootfs,
            workspace = workspace,
            command = "python3",
            args = listOf("/shared/mcp/server.py"),
            sharedWorkspace = shared,
        )

        assertEquals(proot.absolutePath, command.first())
        assertTrue(command.windowed(2).contains(listOf("-r", rootfs.absolutePath)))
        assertTrue(command.windowed(2).contains(listOf("-b", "${workspace.absolutePath}:/workspace")))
        assertTrue(command.windowed(2).contains(listOf("-b", "${shared.absolutePath}:/shared")))
        assertEquals(listOf("/bin/sh", "-c"), command.dropLast(1).takeLast(2))
        assertEquals("python3 /shared/mcp/server.py", command.last())
    }

    @Test
    fun `protected proot environment cannot be overridden by user env`() {
        val overrides = LocalMcpSandbox.sandboxEnvOverrides(
            mapOf(
                "API_KEY" to "secret",
                "LD_LIBRARY_PATH" to "/evil",
                "PROOT_TMP_DIR" to "/evil",
                "PROOT_LOADER" to "/evil",
                "PROOT_LOADER_32" to "/evil",
                " " to "blank",
            )
        )

        assertEquals(mapOf("API_KEY" to "secret"), overrides)
    }
}
