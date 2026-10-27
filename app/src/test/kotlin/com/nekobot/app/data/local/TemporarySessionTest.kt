package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.model.Session
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporarySessionTest {

    @Test
    fun identifiesTemporarySessionIdsByPrefix() {
        val id = TemporarySession.newId()
        assertTrue(id.startsWith(TemporarySession.ID_PREFIX))
        assertTrue(TemporarySession.isTemporary(id))
        assertFalse(TemporarySession.isTemporary("2c1f6f5e-1d33-4a3d-9d0b-6b4f2c9a1e77"))
        assertFalse(TemporarySession.isTemporary(null as String?))
    }

    @Test
    fun resolvesSessionModelsAndEntities() {
        val temporaryId = TemporarySession.newId()
        assertTrue(TemporarySession.isTemporary(Session(id = temporaryId)))
        assertTrue(
            TemporarySession.isTemporary(
                LocalSessionEntity(
                    id = temporaryId,
                    name = "临时会话",
                    createdAt = "2026-01-01T00:00:00",
                    updatedAt = "2026-01-01T00:00:00"
                )
            )
        )
        assertFalse(TemporarySession.isTemporary(Session(id = "session-1")))
    }
}
