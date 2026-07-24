package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSlashCommandsTest {
    @Test
    fun ignoresOrdinaryChatMessages() {
        assertNull(LocalSlashCommands.parse("你好"))
        assertNull(LocalSlashCommands.parse("请解释 /help 的含义"))
    }

    @Test
    fun parsesAliasesAndKeepsArguments() {
        val help = LocalSlashCommands.parse("  /H  ")
        assertEquals(LocalCommandAction.HELP, help?.action)
        assertEquals("/h", help?.name)

        val send = LocalSlashCommands.parse("/ws_send \"报告 2026.pdf\"")
        assertEquals(LocalCommandAction.WORKSPACE_SEND, send?.action)
        assertEquals("\"报告 2026.pdf\"", send?.args)

        val roll = LocalSlashCommands.parse("/rd 2d20+3")
        assertEquals(LocalCommandAction.ROLL, roll?.action)
        assertEquals("2d20+3", roll?.args)

        assertEquals(
            LocalCommandAction.EXPORT_CHAT,
            LocalSlashCommands.parse("/export")?.action
        )
    }

    @Test
    fun pythonCommandsAreInterceptedInsteadOfSentToAi() {
        val jm = LocalSlashCommands.parse("/jm 123456")
        assertTrue(jm?.known == true)
        assertEquals(LocalCommandAction.PYTHON_RUNTIME_REQUIRED, jm?.action)
        assertEquals("123456", jm?.args)

        val rank = LocalSlashCommands.parse("/jmrank 周排行")
        assertEquals(LocalCommandAction.PYTHON_RUNTIME_REQUIRED, rank?.action)
    }

    @Test
    fun unknownSlashCommandGetsExplicitResult() {
        val parsed = LocalSlashCommands.parse("/does_not_exist value")
        assertFalse(parsed?.known ?: true)
        assertEquals(LocalCommandAction.UNKNOWN, parsed?.action)
        assertTrue(LocalSlashCommands.unknownMessage(parsed!!.name).contains("/help"))
    }

    @Test
    fun commandInputAndResultAreExcludedFromAiContext() {
        assertTrue(isLocalCommandMessage("user", "/password 24", null))
        assertTrue(isLocalCommandMessage("human", "/does_not_exist", null))
        assertTrue(isLocalCommandMessage("assistant", "命令执行结果", LOCAL_COMMAND_MODEL))

        assertFalse(isLocalCommandMessage("user", "请解释 /help", null))
        assertFalse(isLocalCommandMessage("assistant", "普通模型回复", "local-model"))
    }

    @Test
    fun helpOnlyAdvertisesCommandsAvailableLocally() {
        val help = LocalSlashCommands.helpText()
        assertTrue(help.contains("/tts"))
        assertTrue(help.contains("/workspace"))
        assertTrue(help.contains("/roll"))
        assertTrue(help.contains("/calc"))
        assertTrue(help.contains("/export"))
        assertTrue(help.contains("/note"))
        assertTrue(help.contains("/yolo"))
        assertFalse(help.contains("/jm <漫画ID>"))
    }
}
