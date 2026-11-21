package com.nekobot.app.data.local.sessionimport

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionImportPayloadTest {

    @Test
    fun `payload is the structure the existing import pipeline accepts`() {
        val session = JsonObject().apply { addProperty("name", "合成会话") }
        val payload = SessionImportPayload.build(listOf(session), exportedAt = 1_000L)

        assertEquals(SessionImportPayload.TYPE, payload["type"].asString)
        assertEquals(SessionImportPayload.VERSION, payload["version"].asInt)
        assertEquals(1_000L, payload["exported_at"].asLong)
        assertEquals(1, payload.getAsJsonArray("sessions").size())
    }

    @Test
    fun `format registry ids stay unique and can be looked up`() {
        val ids = SessionImportFormat.formats.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.contains("nekobot"))
        SessionImportFormat.formats.forEach { format ->
            assertEquals(format, SessionImportFormat.fromId(format.id))
        }
    }
}
