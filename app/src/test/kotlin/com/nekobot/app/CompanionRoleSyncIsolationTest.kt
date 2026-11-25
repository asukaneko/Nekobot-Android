package com.nekobot.app

import com.nekobot.app.data.local.LocalLogger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CompanionRoleSyncIsolationTest {
    @Test fun hostCanReadAndReturnItsSavedRowAfterBridgeFailure() {
        val rows = mutableListOf("existing")
        fun list(): List<String> {
            syncCompanionRoles { error("list sync failed") }
            return rows.toList()
        }
        fun save(row: String): String {
            rows.add(row)
            syncCompanionRoles { error("save sync failed") }
            return row
        }
        assertEquals("saved", save("saved"))
        assertEquals(listOf("existing", "saved"), list())
        val warnings = LocalLogger.listLogs().filter { it.tag == "HostRoleSync" && it.level == LocalLogger.LEVEL_WARNING }
        assertTrue(warnings.any { "list sync failed" in it.message })
        assertTrue(warnings.any { "save sync failed" in it.message })
    }

    @Test fun cancellationPropagatesThroughTheBridgeBoundary() {
        val cancelled = CancellationException("cancel")
        assertSame(cancelled, runCatching { syncCompanionRoles { throw cancelled } }.exceptionOrNull())
    }
}
