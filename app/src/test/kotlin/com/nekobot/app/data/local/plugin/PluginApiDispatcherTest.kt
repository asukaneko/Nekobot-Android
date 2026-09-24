package com.nekobot.app.data.local.plugin

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分派器的纯逻辑：API 名称集合、能力协商与 limit 夹取。 */
class PluginApiDispatcherTest {

    @Test
    fun legacyAndPageApiNameSetsAreStable() {
        assertTrue("get_session" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("http_get" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("system.info" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("chat.messages.list" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("host.ui.render" !in PluginApiDispatcher.PAGE_API_NAMES)
    }

    @Test
    fun capabilitiesCoverImplementedReadOnlyApis() {
        val capabilities = PluginApiDispatcher.CAPABILITIES
        assertTrue("pages" in capabilities)
        assertTrue("chat.read" in capabilities)
        assertTrue("characters.read" in capabilities)
        assertTrue("worldbooks.read" in capabilities)
        assertTrue("memory.read" in capabilities)
        assertTrue("network" in capabilities)
        assertTrue("ai.call" in capabilities)
        assertTrue("ai_complete" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("ai.complete" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("chat.write" in capabilities)
        assertTrue("memory.write" in capabilities)
        assertTrue("characters.write" in capabilities)
        assertTrue("ui.render" in capabilities)
        assertTrue("append_message" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("chat_send" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("create_session" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("switch_session" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("ui_render" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("chat.send" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("chat.sessions.create" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("chat.sessions.switch" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("ui.render" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("characters.create" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("characters.update" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("memory_write" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("memory_append" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("memory_edit" in PluginApiDispatcher.LEGACY_API_NAMES)
        assertTrue("chat.messages.append" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("memory.write" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("memory.append" in PluginApiDispatcher.PAGE_API_NAMES)
        assertTrue("memory.edit" in PluginApiDispatcher.PAGE_API_NAMES)
    }

    @Test
    fun pageNavigationAndNativeDialogApisAreRegistered() {
        listOf("ui.openPage", "ui.alert", "ui.confirm", "ui.prompt", "ui.select").forEach { api ->
            assertTrue("$api 需要出现在能力协商清单", api in PluginApiDispatcher.CAPABILITIES)
            assertTrue("$api 需要页面运行时可用", api in PluginApiDispatcher.PAGE_API_NAMES)
        }
        assertTrue(PluginApiDispatcher.MAX_LAUNCH_ARGS_CHARS > 0)
        assertTrue(PluginApiDispatcher.MAX_DIALOG_MESSAGE_CHARS > 0)
        assertTrue(PluginApiDispatcher.MAX_DIALOG_TITLE_CHARS > 0)
        assertTrue(PluginApiDispatcher.MAX_DIALOG_DEFAULT_CHARS > 0)
    }

    @Test
    fun sessionInsightApisAreRegisteredForBothRuntimes() {
        val capabilities = PluginApiDispatcher.CAPABILITIES
        val insightApis = listOf(
            "chat.context",
            "chat.session.config",
            "chat.prompt.stack",
            "chat.tool.calls"
        )
        insightApis.forEach { api ->
            assertTrue("$api 需要出现在能力协商清单", api in capabilities)
            assertTrue("$api 需要命令运行时可用", api in PluginApiDispatcher.LEGACY_API_NAMES)
            assertTrue("$api 需要页面运行时可用", api in PluginApiDispatcher.PAGE_API_NAMES)
        }
    }

    @Test
    fun insightApiResponseLimitsStayBounded() {
        assertTrue(PluginApiDispatcher.MAX_PROMPT_STACK_CONTENT_CHARS > 0)
        assertTrue(PluginApiDispatcher.MAX_COMPOSED_PROMPT_CHARS < PluginApiDispatcher.MAX_PROMPT_STACK_CONTENT_CHARS)
        assertTrue(PluginApiDispatcher.TOOL_CALL_DEFAULT_LIMIT <= PluginApiDispatcher.TOOL_CALL_MAX_LIMIT)
        assertTrue(PluginApiDispatcher.MAX_TOOL_ARGUMENT_CHARS > 0)
        assertTrue(PluginApiDispatcher.MAX_TOOL_RESULT_CHARS > 0)
    }

    @Test
    fun chatWriteRolesAreRestricted() {
        assertEquals(setOf("user", "assistant"), PluginApiDispatcher.CHAT_WRITE_ROLES)
        assertTrue(PluginApiDispatcher.MAX_CHAT_WRITE_CHARS > 0)
        assertTrue(PluginApiDispatcher.MAX_MEMORY_WRITE_CHARS >= PluginApiDispatcher.MAX_MEMORY_CHARS)
    }

    @Test
    fun workspaceApisAreRegisteredForBothRuntimes() {
        listOf("workspace_save", "workspace_list", "workspace_read", "workspace_delete").forEach { api ->
            assertTrue("$api 需要命令运行时可用", api in PluginApiDispatcher.LEGACY_API_NAMES)
        }
        listOf("workspace.save", "workspace.list", "workspace.read", "workspace.delete").forEach { api ->
            assertTrue("$api 需要页面运行时可用", api in PluginApiDispatcher.PAGE_API_NAMES)
        }
        assertTrue("workspace" in PluginApiDispatcher.CAPABILITIES)
        assertTrue(PluginApiDispatcher.MAX_WORKSPACE_SAVE_CHARS > 0)
        assertTrue(PluginApiDispatcher.MAX_WORKSPACE_READ_BYTES > 0)
        assertTrue(PluginApiDispatcher.MAX_WORKSPACE_PATH_CHARS > 0)
    }

    @Test
    fun limitIsClampedToRange() {
        val payload = JsonObject()
        assertEquals(50, PluginApiDispatcher.resolveLimit(payload, 50, 200))
        payload.addProperty("limit", 9999)
        assertEquals(200, PluginApiDispatcher.resolveLimit(payload, 50, 200))
        payload.addProperty("limit", 0)
        assertEquals(1, PluginApiDispatcher.resolveLimit(payload, 50, 200))
        payload.addProperty("limit", "abc")
        assertEquals(50, PluginApiDispatcher.resolveLimit(payload, 50, 200))
    }

    @Test
    fun legacyMessageLimitsStayBackwardCompatible() {
        assertEquals(30, PluginApiDispatcher.LEGACY_MESSAGE_DEFAULT_LIMIT)
        assertEquals(100, PluginApiDispatcher.LEGACY_MESSAGE_MAX_LIMIT)
        assertEquals(50, PluginApiDispatcher.MESSAGE_DEFAULT_LIMIT)
        assertEquals(200, PluginApiDispatcher.MESSAGE_MAX_LIMIT)
    }
}
