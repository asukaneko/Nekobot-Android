package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ModernChatInputTest {
    @Test
    fun estimateModernChatDraftTokens_handlesMixedText() {
        assertEquals(0, estimateModernChatDraftTokens(""))
        assertEquals(4, estimateModernChatDraftTokens("测试文本"))
        assertEquals(1, estimateModernChatDraftTokens("abcd"))
        assertEquals(3, estimateModernChatDraftTokens("测试abcd"))
    }
}
