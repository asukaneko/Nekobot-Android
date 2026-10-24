package com.nekobot.app.data.local.db

import androidx.room.RoomDatabase
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CompanionHistoryMigrationPathTest {
    @Test fun roomChoosesSafeUpgradeFromEverySupportedVersion() {
        val schemaRoot = listOf(File("schemas"), File("app/schemas")).first { it.isDirectory }
        val latest = requireNotNull(File(schemaRoot, "com.nekobot.app.data.local.db.NekobotDatabase")
            .listFiles()?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }?.maxOrNull())
        val migrations = NekobotDatabase.ALL_MIGRATIONS
        assertEquals(latest, migrations.maxOf { it.endVersion })
        assertEquals(migrations.size, migrations.map { it.startVersion to it.endVersion }.distinct().size)
        val container = RoomDatabase.MigrationContainer().apply { addMigrations(*migrations) }
        for (start in 1 until latest) {
            val path = requireNotNull(container.findMigrationPath(start, latest)) { "No path from $start" }
            assertEquals(start, path.first().startVersion)
            assertEquals(latest, path.last().endVersion)
            path.zipWithNext().forEach { (current, next) ->
                assertEquals(current.endVersion, next.startVersion)
            }
            if (start <= 60) {
                assertTrue(path.any { it.startVersion == 60 && it.endVersion == 64 })
                assertTrue(path.none { it.startVersion == 60 && it.endVersion == 61 })
            }
        }
        // Already-current databases do not rerun migration or reconstruct deleted records.
        assertTrue(requireNotNull(container.findMigrationPath(latest, latest)).isEmpty())
    }
}
