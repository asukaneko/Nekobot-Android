package com.nekobot.app.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FailoverModelsTest {

    private val gson = Gson()

    @Test
    fun remoteFailoverDetail_deserializesTokenUsageAliases() {
        val json = """
            {
              "model_id": "model-1",
              "name": "Remote model",
              "model": "gpt-test",
              "health": {
                "available": true,
                "daily_failures": 1,
                "consecutive_failures": 2
              },
              "token_limit_daily": 1000,
              "token_limit_weekly": 5000,
              "token_usage": {
                "today_total": 120,
                "weekly_total": 640
              }
            }
        """.trimIndent()

        val detail = gson.fromJson(json, FailoverModelDetail::class.java)

        assertNotNull(detail.usage)
        assertEquals(120L, detail.usage.dailyTokens)
        assertEquals(640L, detail.usage.weeklyTokens)
        assertEquals(1000L, detail.tokenLimitDaily)
        assertEquals(5000L, detail.tokenLimitWeekly)
    }

    @Test
    fun localFailoverDetail_keepsExistingUsageFieldNames() {
        val json = """
            {
              "model_id": "model-2",
              "usage": {
                "daily_tokens": 25,
                "weekly_tokens": 100,
                "daily_limit": 500,
                "weekly_limit": 2000
              }
            }
        """.trimIndent()

        val detail = gson.fromJson(json, FailoverModelDetail::class.java)

        assertEquals(25L, detail.usage.dailyTokens)
        assertEquals(100L, detail.usage.weeklyTokens)
        assertEquals(500L, detail.usage.dailyLimit)
        assertEquals(2000L, detail.usage.weeklyLimit)
    }
}
