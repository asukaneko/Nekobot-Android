package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLoggerTest {
    @Test fun quotedJsonCredentialsAreRemovedBeforeLogTruncation() {
        val secret = "SYNTHETIC_CREDENTIAL_ONLY"
        for (key in listOf("api_key", "apiKey", "API-KEY", "Authorization", "access_token", "password", "client_secret", "\\u0061pi_key")) {
            val value = "HTTP 401: {\"$key\":\"$secret with spaces and \\\"quoted\\\" suffix\",\"status\":401}"
            val redacted = redactForLocalLog(value)
            assertFalse(key, redacted.contains(secret))
            assertFalse(key, redacted.contains("suffix"))
            assertTrue(redacted.contains("\"status\":401"))
        }
        for (value in listOf("{\"api_key\":\"$secret", "{\"token\":{\"nested\":\"$secret\"}}",
            com.google.gson.Gson().toJson("{\"api_key\":\"$secret\"}"))) {
            assertFalse(redactForLocalLog(value).contains(secret))
        }
        assertEquals("{\"count\":2,\"message\":\"normal\"}", redactForLocalLog("{\"count\":2,\"message\":\"normal\"}"))
    }

    @Test
    fun redactForLocalLog_redacts_credentials_and_query_parameters() {
        val message = "Authorization: Bearer secret-token token=another-secret https://api.example.com/v1/chat?key=private"

        assertEquals(
            "Authorization=<redacted> token=<redacted> https://api.example.com/v1/chat?<redacted>",
            redactForLocalLog(message)
        )
    }
}
