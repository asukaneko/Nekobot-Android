package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQuickActionsTest {

    @Test
    fun `normalize 过滤未知 id 并去重保持顺序`() {
        val result = ChatQuickAction.normalize(
            listOf("unknown", ChatQuickAction.SEARCH, ChatQuickAction.STICKER, ChatQuickAction.SEARCH)
        )

        assertEquals(listOf(ChatQuickAction.SEARCH, ChatQuickAction.STICKER), result)
    }

    @Test
    fun `默认按钮都在目录中且包含表情包`() {
        assertTrue(ChatQuickAction.defaultIds.all { it in ChatQuickAction.allIds })
        assertTrue(ChatQuickAction.STICKER in ChatQuickAction.defaultIds)
    }

    @Test
    fun `目录中的每个按钮都有名称资源`() {
        ChatQuickAction.allIds.forEach { id ->
            assertNotEquals("按钮 $id 缺少名称资源", 0, ChatQuickAction.labelResOf(id))
        }
    }

    @Test
    fun `normalize 允许清空工具栏`() {
        assertEquals(emptyList<String>(), ChatQuickAction.normalize(emptyList()))
    }

    @Test
    fun `move 支持前移后移并保持其余顺序`() {
        val ids = listOf(
            ChatQuickAction.STICKER,
            ChatQuickAction.IMAGE,
            ChatQuickAction.FILE,
            ChatQuickAction.SEARCH
        )

        assertEquals(
            listOf(
                ChatQuickAction.IMAGE,
                ChatQuickAction.STICKER,
                ChatQuickAction.FILE,
                ChatQuickAction.SEARCH
            ),
            ChatQuickAction.move(ids, from = 0, to = 1)
        )
        assertEquals(
            listOf(
                ChatQuickAction.STICKER,
                ChatQuickAction.IMAGE,
                ChatQuickAction.SEARCH,
                ChatQuickAction.FILE
            ),
            ChatQuickAction.move(ids, from = 2, to = 3)
        )
    }

    @Test
    fun `move 越界收敛且非法下标原样返回`() {
        val ids = listOf(ChatQuickAction.STICKER, ChatQuickAction.IMAGE)

        assertEquals(
            listOf(ChatQuickAction.IMAGE, ChatQuickAction.STICKER),
            ChatQuickAction.move(ids, from = 0, to = 99)
        )
        assertEquals(ids, ChatQuickAction.move(ids, from = 5, to = 0))
        assertEquals(ids, ChatQuickAction.move(ids, from = 1, to = 1))
    }
}
