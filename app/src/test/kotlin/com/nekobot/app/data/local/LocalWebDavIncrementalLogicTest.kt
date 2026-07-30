package com.nekobot.app.data.local

import com.google.gson.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebDavIncrementalLogicTest {
    @Test
    fun detectsLocalChangesAgainstBaseline() {
        val value = JsonObject().apply { addProperty("name", "新会话") }
        val record = LocalWebDavIncrementalLogic.record("session", "s1", "2026-07-30T10:00:00Z", value)
        val current = LocalWebDavIncrementalLogic.indexOf(record, "")

        assertTrue(LocalWebDavIncrementalLogic.changed(current, null))
        assertFalse(LocalWebDavIncrementalLogic.changed(current, current))
        assertTrue(
            LocalWebDavIncrementalLogic.changed(
                current.copy(hash = "new"),
                current
            )
        )
    }

    @Test
    fun latestTimestampWinsConflict() {
        assertTrue(
            LocalWebDavIncrementalLogic.localWins(
                "2026-07-30T11:00:00Z",
                "2026-07-30T10:00:00Z"
            )
        )
        assertFalse(
            LocalWebDavIncrementalLogic.localWins(
                "2026-07-30T09:00:00Z",
                "2026-07-30T10:00:00Z"
            )
        )
    }
}
