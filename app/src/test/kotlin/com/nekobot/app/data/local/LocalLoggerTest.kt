package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLoggerTest {

    @Test
    fun redactForLocalLog_redacts_credentials_and_query_parameters() {
        val message = "Authorization: Bearer secret-token token=another-secret https://api.example.com/v1/chat?key=private"

        assertEquals(
            "Authorization=<redacted> token=<redacted> https://api.example.com/v1/chat?<redacted>",
            redactForLocalLog(message)
        )
    }
}
