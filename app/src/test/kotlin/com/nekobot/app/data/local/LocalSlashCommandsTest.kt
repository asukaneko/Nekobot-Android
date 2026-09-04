package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.remote.RealtimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSlashCommandsTest {

    @Test
    fun skill_command_is_forwarded_to_agent_pipeline() {
        assertNull(LocalSlashCommands.parse("/skill writing-assistant draft a reply"))
    }

    @Test
    fun suggestions_match_primary_and_alias_names() {
        assertEquals("/local_status", LocalSlashCommands.suggestions("/stat").single().command)
        assertTrue(LocalSlashCommands.suggestions("/").isNotEmpty())
    }

    @Test
    fun suggestions_only_show_full_primary_commands() {
        val all = LocalSlashCommands.suggestions("/").map { it.command }
        assertTrue(all.contains("/findbook"))
        assertTrue(all.contains("/random_novel"))
        assertTrue(all.contains("/wenku8_login"))
        assertFalse(all.contains("/fb"))
        assertFalse(all.contains("/rn"))
        assertFalse(all.contains("/wenku_login"))
    }

    @Test
    fun alias_query_resolves_to_primary_command() {
        assertEquals("/findbook", LocalSlashCommands.suggestions("/fb").single().command)
        assertEquals("/random_novel", LocalSlashCommands.suggestions("/rn").single().command)
    }
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

        assertEquals(
            LocalCommandAction.WENKU8_LOGIN,
            LocalSlashCommands.parse("/wenku_login")?.action
        )
    }

    @Test
    fun jmDownloadAndRankingAreHandledNatively() {
        val jm = LocalSlashCommands.parse("/jm 123456")
        assertTrue(jm?.known == true)
        assertEquals(LocalCommandAction.JM_DOWNLOAD, jm?.action)
        assertEquals("123456", jm?.args)

        val rank = LocalSlashCommands.parse("/jmrank 周排行")
        assertEquals(LocalCommandAction.JM_RANK, rank?.action)

        val rankWithCount = LocalSlashCommands.parse("/jmrank 月排行 20")
        assertEquals(LocalCommandAction.JM_RANK, rankWithCount?.action)
        assertEquals("月排行 20", rankWithCount?.args)

        val search = LocalSlashCommands.parse("/jm_search 测试")
        assertEquals(LocalCommandAction.JM_SEARCH, search?.action)

        val searchWithCount = LocalSlashCommands.parse("/jm_search 测试 20")
        assertEquals(LocalCommandAction.JM_SEARCH, searchWithCount?.action)
        assertEquals("测试 20", searchWithCount?.args)
    }

    @Test
    fun unknownSlashCommandGetsExplicitResult() {
        val parsed = LocalSlashCommands.parse("/does_not_exist value")
        assertFalse(parsed?.known ?: true)
        assertEquals(LocalCommandAction.UNKNOWN, parsed?.action)
        assertTrue(LocalSlashCommands.unknownMessage(parsed!!.name).contains("/help"))
    }

    @Test
    fun goalCommandParsesWithAndWithoutArguments() {
        val set = LocalSlashCommands.parse("/goal 完成登录页重构并验证")
        assertEquals(LocalCommandAction.GOAL, set?.action)
        assertTrue(set?.known == true)
        assertEquals("完成登录页重构并验证", set?.args)

        val view = LocalSlashCommands.parse("/goal")
        assertEquals(LocalCommandAction.GOAL, view?.action)
        assertEquals("", view?.args)

        val done = LocalSlashCommands.parse("/goal done")
        assertEquals(LocalCommandAction.GOAL, done?.action)
        assertEquals("done", done?.args)
    }

    @Test
    fun specCommandParsesWorkflowSubcommands() {
        val set = LocalSlashCommands.parse("/spec 写一个 TODO 应用")
        assertEquals(LocalCommandAction.SPEC, set?.action)
        assertTrue(set?.known == true)
        assertEquals("写一个 TODO 应用", set?.args)

        val approve = LocalSlashCommands.parse("/spec approve")
        assertEquals(LocalCommandAction.SPEC, approve?.action)
        assertEquals("approve", approve?.args)

        val clear = LocalSlashCommands.parse("/spec clear")
        assertEquals(LocalCommandAction.SPEC, clear?.action)
    }

    @Test
    fun goalAndSpecAppearInSuggestionsAndHelp() {
        val all = LocalSlashCommands.suggestions("/").map { it.command }
        assertTrue(all.contains("/goal"))
        assertTrue(all.contains("/spec"))
        assertEquals("/goal", LocalSlashCommands.suggestions("/go").single().command)
        assertEquals("/spec", LocalSlashCommands.suggestions("/spec").first().command)

        val help = LocalSlashCommands.helpText()
        assertTrue(help.contains("/goal"))
        assertTrue(help.contains("/spec"))
        assertTrue(help.contains("/spec approve"))
    }

    @Test
    fun goalAndSpecAreReservedAliasesForPlugins() {
        val reserved = LocalSlashCommands.reservedCommandAliases()
        assertTrue("/goal" in reserved)
        assertTrue("/spec" in reserved)
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
    fun commandMessagesDoNotInflateSmartRoutingContext() {
        fun message(
            id: String,
            role: String,
            content: String,
            model: String? = null
        ) = LocalMessageEntity(
            id = id,
            sessionId = "session",
            role = role,
            content = content,
            timestamp = id,
            model = model,
            createdAt = id
        )

        val estimated = estimateLocalAiContextTokens(
            listOf(
                message("1", "user", "/h"),
                message("2", "assistant", "command help".repeat(100), LOCAL_COMMAND_MODEL),
                message("3", "user", "123456"),
                message("4", "assistant", "abc", "chat-model")
            )
        )

        assertEquals(3, estimated)
    }

    @Test
    fun commandReplyAlwaysEndsTheChatTurn() {
        val reply = Message(id = "reply", sessionId = "session", role = "assistant", content = "help")
        val events = localCommandCompletionEvents("session", reply)

        assertEquals(2, events.size)
        assertEquals(reply, (events[0] as RealtimeEvent.AiResponse).message)
        assertEquals("session", (events[1] as RealtimeEvent.StreamEnd).sessionId)
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
        assertTrue(help.contains("/jmrank [周排行|月排行] [数量]"))
        assertTrue(help.contains("/jm <漫画ID>"))
        assertTrue(help.contains("/jm_search <关键词或漫画ID> [数量]"))
        assertTrue(help.contains("/wenku8_login"))
        assertTrue(help.contains("/yolo"))
        assertFalse(help.contains("依赖 Python"))
    }
}
