package com.nekobot.app.ui.screens.statehistory

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class StateHistorySessionFilterTest {
    @Test
    fun `filters agent sessions regardless of session mode key casing`() {
        val sessions = JsonParser.parseString(
            """[
                {"id":"character","session_mode":"character"},
                {"id":"agent","session_mode":"AGENT"},
                {"id":"agent-camel","sessionMode":"agent"}
            ]"""
        ).asJsonArray.map { it.asJsonObject }

        assertEquals(listOf("character"), filterStateHistorySessions(sessions).map { it.get("id").asString })
    }

    @Test
    fun `keeps sessions without an agent mode`() {
        val sessions = JsonParser.parseString(
            """[{"id":"channel"},{"id":"character","session_mode":"character"}]"""
        ).asJsonArray.map { it.asJsonObject }

        assertEquals(2, filterStateHistorySessions(sessions).size)
    }
}
