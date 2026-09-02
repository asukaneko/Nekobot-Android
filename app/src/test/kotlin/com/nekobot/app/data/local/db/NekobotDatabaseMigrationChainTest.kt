package com.nekobot.app.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NekobotDatabaseMigrationChainTest {
    @Test
    fun migrationChain_isContiguousThroughCurrentVersion() {
        val migrations = NekobotDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }

        assertTrue(migrations.isNotEmpty())
        assertEquals(1, migrations.first().startVersion)
        migrations.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endVersion, next.startVersion)
        }
        assertEquals(38, migrations.last().endVersion)
    }
}
