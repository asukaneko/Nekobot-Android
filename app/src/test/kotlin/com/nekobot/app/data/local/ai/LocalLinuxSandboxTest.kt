package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalLinuxSandboxTest {

    @Test
    fun `proot command mounts only the selected session workspace`() {
        val proot = File("native/libproot.so").absoluteFile
        val rootfs = File("data/alpine-rootfs").absoluteFile
        val workspace = File("data/workspace/session-a").absoluteFile
        val command = buildLocalProotCommand(
            proot = proot,
            rootfs = rootfs,
            workspace = workspace,
        )

        assertEquals(proot.absolutePath, command.first())
        assertTrue(command.windowed(2).contains(listOf("-r", rootfs.absolutePath)))
        assertTrue(
            command.windowed(2).contains(
                listOf("-b", "${workspace.absolutePath}:/workspace")
            )
        )
        assertTrue(command.windowed(2).contains(listOf("-w", "/workspace")))
        assertEquals("/bin/sh", command.last())
        assertFalse(command.joinToString(" ").contains("session-b"))
    }

    @Test
    fun `stream parser detects marker split across chunks`() {
        val collector = LocalLinuxCommandOutputCollector(
            markerPrefix = "__NEKOBOT_DONE_token_EXIT_",
            maxOutputChars = 20_000,
        )

        assertEquals(null, collector.accept("hello\n__NEKOBOT_DO"))
        assertEquals(7, collector.accept("NE_token_EXIT_7__\n"))
        assertEquals("hello\n", collector.renderOutput())
    }

    @Test
    fun `stream parser truncates output without losing completion`() {
        val collector = LocalLinuxCommandOutputCollector(
            markerPrefix = "__NEKOBOT_DONE_token_EXIT_",
            maxOutputChars = 5,
        )

        assertEquals(0, collector.accept("123456789__NEKOBOT_DONE_token_EXIT_0__\n"))
        assertEquals("12345\n[输出已截断，最多返回 5 个字符]", collector.renderOutput())
    }

    @Test
    fun `tar paths cannot escape extraction directory`() {
        val target = File("build/test-rootfs")

        assertThrows(IllegalArgumentException::class.java) {
            LocalSafeTarExtractor.resolveEntry(target, "../../outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalSafeTarExtractor.resolveEntry(target, "/absolute/path")
        }
        assertTrue(
            LocalSafeTarExtractor.resolveEntry(target, "./usr/bin/tool")
                .absolutePath
                .endsWith(File("build/test-rootfs/usr/bin/tool").path)
        )
    }
}
