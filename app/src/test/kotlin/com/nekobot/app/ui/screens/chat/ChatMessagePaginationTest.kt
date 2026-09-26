package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天历史分页的纯逻辑测试：刷新合并时保留已加载的更早历史前缀。
 */
class ChatMessagePaginationTest {

    private fun message(id: String, role: String = "user", content: String = id) =
        Message(id = id, role = role, content = content, timestamp = "2026-01-01 00:00:00")

    @Test
    fun refreshKeepsOlderPrefixLoadedByPagination() {
        val all = (1..100).map { message("m$it") }
        // 首屏只加载最近一页（m81..m100），向上翻页加载过 m1..m80
        val current = all
        val fresh = all.takeLast(20)

        val prefix = olderMessagesPrefixOf(current, fresh)

        assertEquals(80, prefix.size)
        assertEquals("m1", prefix.first().id)
        assertEquals("m80", prefix.last().id)
    }

    @Test
    fun refreshWithoutPaginationHasNoPrefix() {
        val fresh = (1..20).map { message("m$it") }

        val prefix = olderMessagesPrefixOf(fresh, fresh)

        assertTrue(prefix.isEmpty())
    }

    @Test
    fun emptyFreshDropsPrefix() {
        val current = (1..20).map { message("m$it") }

        val prefix = olderMessagesPrefixOf(current, emptyList())

        assertTrue(prefix.isEmpty())
    }

    @Test
    fun missingAnchorFallsBackToFreshOnly() {
        val current = (1..20).map { message("m$it") }
        val fresh = (100..110).map { message("m$it") }

        val prefix = olderMessagesPrefixOf(current, fresh)

        assertTrue(prefix.isEmpty())
    }

    @Test
    fun prefixKeepsNewestFirstBoundaryWhenFreshIsSingleMessage() {
        val current = (1..5).map { message("m$it") }
        val fresh = listOf(message("m5"))

        val prefix = olderMessagesPrefixOf(current, fresh)

        assertEquals(listOf("m1", "m2", "m3", "m4"), prefix.map { it.id })
    }
}
