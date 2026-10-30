package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hook 事件目录的契约测试。
 *
 * 事件库同时驱动 HooksScreen 的事件选择器与本地管线的触发点，
 * 这里锁定"事件 id 唯一 / Agent 事件归类正确 / 仅 before_call 可拦截"三条规则。
 */
class HookEventCatalogTest {

    @Test
    fun `事件 id 唯一且非空`() {
        val ids = HookEventCatalog.events.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
        assertTrue(ids.all { it.contains('.') })
    }

    @Test
    fun `分组覆盖全部事件`() {
        val grouped = HookEventCatalog.grouped().flatMap { it.second }
        assertEquals(HookEventCatalog.events.size, grouped.size)
    }

    @Test
    fun `agent 事件归类正确`() {
        val agentEvents = HookEventCatalog.events.filter { it.group == HookEventGroup.AGENT }
        assertEquals(7, agentEvents.size)
        assertTrue(agentEvents.all { HookEventCatalog.isAgentEvent(it.id) })
    }

    @Test
    fun `只有工具调用前事件允许拦截`() {
        val blockable = HookEventCatalog.events.filter { it.blockable }.map { it.id }
        assertEquals(listOf("agent.tool.before_call"), blockable)
        assertTrue(HookEventCatalog.isBlockableEvent("agent.tool.before_call"))
        assertTrue(HookEventCatalog.isBlockableEvent("agent.*"))
        assertFalse(HookEventCatalog.isBlockableEvent("agent.tool.after_call"))
        assertFalse(HookEventCatalog.isBlockableEvent("character.before_turn.finished"))
    }

    @Test
    fun `通配符事件不命中具体定义`() {
        assertNotNull(HookEventCatalog.find("agent.session.end"))
        assertEquals(null, HookEventCatalog.find("agent.*"))
        assertEquals(null, HookEventCatalog.find("message.*"))
    }
}
