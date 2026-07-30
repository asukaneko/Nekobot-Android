package com.nekobot.app.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingShareParserTest {
    @Test
    fun `parses chat and session deep links`() {
        assertEquals("session-1", IncomingShareParser.parseDeepLinkSessionId("nekobot://chat/session-1"))
        assertEquals("会话 2", IncomingShareParser.parseDeepLinkSessionId("nekobot://session/%E4%BC%9A%E8%AF%9D%202"))
    }

    @Test
    fun `rejects unrelated links`() {
        assertNull(IncomingShareParser.parseDeepLinkSessionId("https://example.com/chat/1"))
        assertNull(IncomingShareParser.parseDeepLinkSessionId("nekobot://settings"))
    }
}
