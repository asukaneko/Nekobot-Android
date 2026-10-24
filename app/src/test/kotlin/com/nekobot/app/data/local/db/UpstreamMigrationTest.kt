package com.nekobot.app.data.local.db

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/** Runs the production Kotlin migrations against real SQLite without building/installing an APK. */
@RunWith(Parameterized::class)
class UpstreamMigrationTest(private val version: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "v{0} to latest")
        fun versions() = (31..63).map { arrayOf(it) }
    }

    @Test fun upgradePreservesDataAndMatchesCurrentSchemaAfterReopen() {
        val file = File.createTempFile("nekobot-main-$version-", ".db")
        try {
            connect(file).use { connection ->
                val original = schema(version)
                createSchema(connection, original)
                insert(connection, original, "local_sessions", mapOf("id" to "session", "name" to "Original session"))
                insert(connection, original, "local_messages", mapOf("id" to "message", "session_id" to "session",
                    "role" to "user", "content" to "Original quote", "created_at" to "2026-01-02"))
                if (version >= 48) {
                    insert(connection, original, "local_experience_archives", mapOf("id" to "archive", "session_id" to "session",
                        "summary" to "Manually edited summary", "summary_edited" to 1, "tags_edited" to 1))
                }
                if (version >= 49) {
                    insert(connection, original, "local_subagent_tasks", mapOf("task_id" to "task", "session_id" to "session",
                        "result_text" to "Original task result"))
                }
                if (version >= 53) {
                    insert(connection, original, "companion_scopes", mapOf("id" to "scope", "sessionId" to "session"))
                    insert(connection, original, "companion_memories", mapOf("id" to "memory", "scopeId" to "scope",
                        "content" to "Preserved memory", "locked" to 1, "manual" to 1, "alwaysProvide" to 1))
                    insert(connection, original, "companion_sources", mapOf("memoryId" to "memory", "messageId" to "message",
                        "quote" to "Original quote"))
                }
                val database = sqliteBridge(connection)
                connection.autoCommit = false
                val path = RoomDatabase.MigrationContainer().apply {
                    addMigrations(*NekobotDatabase.ALL_MIGRATIONS)
                }.findMigrationPath(version, latestSchemaVersion())
                requireNotNull(path).forEach { it.migrate(database) }
                connection.commit()
                connection.autoCommit = true
                assertSchema(connection)
                assertEquals("Original quote", scalar(connection, "SELECT content FROM local_messages WHERE id='message'"))
                assertEquals("Original session", scalar(connection, "SELECT name FROM local_sessions WHERE id='session'"))
                assertEquals("0", scalar(connection, "SELECT companion_mode FROM local_sessions WHERE id='session'"))
                assertEquals(if (version >= 53) "1" else "0", scalar(connection, "SELECT COUNT(*) FROM companion_memories"))
                assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM companion_owners"))
                if (version >= 53) {
                    assertEquals("Preserved memory", scalar(connection, "SELECT content FROM companion_memories WHERE id='memory'"))
                    for (column in listOf("locked", "manual", "alwaysProvide")) {
                        assertEquals("1", scalar(connection, "SELECT $column FROM companion_memories WHERE id='memory'"))
                    }
                    assertEquals("Original quote", scalar(connection, "SELECT quote FROM companion_sources WHERE memoryId='memory'"))
                }
                if (version >= 48) {
                    assertEquals("Manually edited summary", scalar(connection, "SELECT summary FROM local_experience_archives WHERE id='archive'"))
                    assertEquals("1", scalar(connection, "SELECT summary_edited FROM local_experience_archives WHERE id='archive'"))
                }
                if (version >= 49) assertEquals("Original task result",
                    scalar(connection, "SELECT result_text FROM local_subagent_tasks WHERE task_id='task'"))
                // Exercise the newly installed message triggers, not only table existence.
                connection.createStatement().use { it.executeUpdate("UPDATE local_messages SET content='Updated quote' WHERE id='message'") }
                assertEquals("Updated quote", scalar(connection, "SELECT content FROM local_messages WHERE id='message'"))
                assertEquals("ok", scalar(connection, "PRAGMA integrity_check"))
                connection.createStatement().use { it.executeQuery("PRAGMA foreign_key_check").use { rows -> assertFalse(rows.next()) } }
            }
            connect(file).use { reopened ->
                assertEquals("Updated quote", scalar(reopened, "SELECT content FROM local_messages WHERE id='message'"))
                assertSchema(reopened)
            }
        } finally { file.delete() }
    }

    internal fun schema(version: Int): JsonObject {
        val directory = listOf(File("schemas"), File("app/schemas")).first { it.isDirectory }
        return JsonParser.parseString(File(directory, "com.nekobot.app.data.local.db.NekobotDatabase/$version.json").readText())
            .asJsonObject.getAsJsonObject("database")
    }

    /**
     * 期望结构跟随导出的最新 schema，不在测试里写死版本号。
     * 产品升版本并导出 schema 后，这里自动指向新结构；漏导出则会明确报错。
     */
    private fun latestSchemaVersion(): Int {
        val directory = listOf(File("schemas"), File("app/schemas")).firstOrNull { it.isDirectory }
            ?: error("找不到 schemas 导出目录，当前工作目录=${File(".").absolutePath}")
        val schemas = File(directory, "com.nekobot.app.data.local.db.NekobotDatabase")
        return schemas.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.maxOrNull()
            ?: error("$schemas 下没有可用的 schema 导出文件")
    }

    private fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").also {
        it.createStatement().use { statement -> statement.execute("PRAGMA foreign_keys=ON") }
    }

    internal fun createSchema(connection: Connection, schema: JsonObject) {
        connection.createStatement().use { statement ->
            schema.getAsJsonArray("entities").forEach { entry ->
                val entity = entry.asJsonObject
                statement.execute(entity["createSql"].asString.replace("\${TABLE_NAME}", entity["tableName"].asString))
                // Room 新导出格式省略空索引数组；非空索引仍逐个创建并核对。
                entity.getAsJsonArray("indices")?.forEach {
                    statement.execute(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", entity["tableName"].asString))
                }
            }
        }
    }

    internal fun insert(connection: Connection, schema: JsonObject, table: String, values: Map<String, Any>) {
        val fields = schema.getAsJsonArray("entities").single { it.asJsonObject["tableName"].asString == table }
            .asJsonObject.getAsJsonArray("fields").map { it.asJsonObject }
        val names = fields.joinToString(",") { "`${it["columnName"].asString}`" }
        connection.prepareStatement("INSERT INTO $table ($names) VALUES (${fields.joinToString(",") { "?" }})").use { statement ->
            fields.forEachIndexed { index, field ->
                val value = values[field["columnName"].asString] ?: if (field["notNull"]?.asBoolean != true) null
                    else if (field["affinity"].asString == "INTEGER") 0 else ""
                statement.setObject(index + 1, value)
            }
            statement.executeUpdate()
        }
    }

    internal fun scalar(connection: Connection, sql: String): String? = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> assertTrue(rows.next()); rows.getString(1) }
    }

    private fun rows(connection: Connection, sql: String): List<List<String?>> = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> buildList {
            while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) })
        } }
    }

    internal fun assertSchema(connection: Connection) {
        // Compare PRAGMA metadata with a freshly created current Room export. Triggers are
        // migration-only; defaults are checked whenever the Room entity declares one.
        // 期望结构取自最新导出，因此新增的表（如 portable_import_commits）也会被核对。
        val expected = schema(latestSchemaVersion())
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            createSchema(fresh, expected)
            expected.getAsJsonArray("entities").forEach { entry ->
                val table = entry.asJsonObject["tableName"].asString
                val expected = rows(fresh, "PRAGMA table_info(`$table`)").associateBy { it[1] }
                val actual = rows(connection, "PRAGMA table_info(`$table`)").associateBy { it[1] }
                assertEquals("$table columns", expected.keys, actual.keys)
                expected.forEach { (name, field) ->
                    val migrated = actual.getValue(name)
                    for (index in listOf(2, 3, 5)) assertEquals("$table.$name metadata[$index]", field[index], migrated[index])
                    if (field[4] != null) assertEquals("$table.$name default", field[4], migrated[4])
                }
                assertEquals("$table foreign keys", rows(fresh, "PRAGMA foreign_key_list(`$table`)"),
                    rows(connection, "PRAGMA foreign_key_list(`$table`)"))
                entry.asJsonObject.getAsJsonArray("indices")?.forEach { index ->
                    val name = index.asJsonObject["name"].asString
                    assertEquals("$name columns", rows(fresh, "PRAGMA index_info(`$name`)").map { it[0] to it[2] },
                        rows(connection, "PRAGMA index_info(`$name`)").map { it[0] to it[2] })
                    assertEquals("$name unique", rows(fresh, "PRAGMA index_list(`$table`)").single { it[1] == name }[2],
                        rows(connection, "PRAGMA index_list(`$table`)").single { it[1] == name }[2])
                }
            }
        }
    }

    /** Only adapts the SQLite transport. All migration SQL comes from production code. */
    internal fun sqliteBridge(connection: Connection): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)
    ) { _, method, args ->
        when (method.name) {
            "execSQL" -> { connection.createStatement().use { it.execute(args!![0] as String) }; null }
            "query" -> {
                val sql = args!![0] as String
                val data = rows(connection, sql)
                var position = -1
                Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, cursorMethod, cursorArgs ->
                    when (cursorMethod.name) {
                        "moveToFirst" -> { position = 0; data.isNotEmpty() }
                        "moveToNext" -> { position++; position < data.size }
                        "getString" -> data[position][cursorArgs!![0] as Int]
                        "getInt" -> data[position][cursorArgs!![0] as Int]!!.toInt()
                        "close" -> null
                        else -> error("Unsupported cursor call: ${cursorMethod.name}")
                    }
                }
            }
            else -> error("Unsupported database call: ${method.name}")
        }
    } as SupportSQLiteDatabase
}
