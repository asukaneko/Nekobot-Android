package com.nekobot.app.data.local.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompanionPromptMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun upgrade62To63OnlyAddsNullableSettingsAndKeepsHistory() {
        val name = "companion-prompt-upgrade"
        val old = helper.createDatabase(name, 62)
        val entities = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.nekobot.app.data.local.db.NekobotDatabase/62.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject["database"].asJsonObject["entities"].asJsonArray
            }
        fun insert(table: String, row: Any) {
            val fields = entities.single { it.asJsonObject["tableName"].asString == table }.asJsonObject["fields"].asJsonArray
            val source = Gson().toJsonTree(row).asJsonObject
            val values = ContentValues()
            fields.forEach { entry ->
                val field = entry.asJsonObject
                val value = source[field["fieldPath"].asString]
                val column = field["columnName"].asString
                when {
                    value == null || value.isJsonNull -> values.putNull(column)
                    value.asJsonPrimitive.isBoolean -> values.put(column, if (value.asBoolean) 1 else 0)
                    value.asJsonPrimitive.isNumber -> values.put(column, value.asLong)
                    else -> values.put(column, value.asString)
                }
            }
            old.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
        }
        insert("local_sessions", LocalSessionEntity("s", "虚构", characterId = "c", companionMode = true,
            sessionMode = "agent", createdAt = "before", updatedAt = "before"))
        insert("local_messages", LocalMessageEntity("m", "s", "user", "不能删除的原话", timestamp = "before", createdAt = "before"))
        old.close()
        val migrated = helper.runMigrationsAndValidate(name, 63, true, *NekobotDatabase.ALL_MIGRATIONS)
        migrated.query("SELECT companion_speaking_prompt, companion_thinking_prompt, companion_thinking_enabled FROM local_sessions WHERE id='s'").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
        }
        migrated.query("SELECT content FROM local_messages WHERE id='m'").use {
            assertTrue(it.moveToFirst()); assertEquals("不能删除的原话", it.getString(0))
        }
        migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        migrated.close()
    }
}
