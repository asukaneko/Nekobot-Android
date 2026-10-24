package com.nekobot.app.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NekobotDatabaseMigrationChainTest {
    @Test
    fun migrationChain_isContiguousThroughCurrentVersion() {
        // Keep verifying every original adjacent edge; shortcuts are checked by Room path tests.
        val migrations = NekobotDatabase.ALL_MIGRATIONS
            .filter { it.endVersion == it.startVersion + 1 }.sortedBy { it.startVersion }

        assertTrue(migrations.isNotEmpty())
        assertEquals(1, migrations.first().startVersion)
        migrations.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endVersion, next.startVersion)
        }
        // 版本号不在测试里写死：以已导出的最新 schema 为准。
        // 升版本却漏接迁移、或加了迁移却忘记导出 schema，都会在这里失败。
        assertEquals(
            "迁移链终点与最新导出的 schema 不一致",
            latestExportedSchemaVersion(),
            migrations.last().endVersion
        )
    }

    private fun latestExportedSchemaVersion(): Int {
        val directory = listOf(File("schemas"), File("app/schemas")).firstOrNull { it.isDirectory }
            ?: error("找不到 schemas 导出目录，当前工作目录=${File(".").absolutePath}")
        val schemas = File(directory, "com.nekobot.app.data.local.db.NekobotDatabase")
        return schemas.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.maxOrNull()
            ?: error("$schemas 下没有可用的 schema 导出文件")
    }
}
