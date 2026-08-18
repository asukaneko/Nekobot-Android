package com.nekobot.app.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NekobotDatabaseLatestMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun cleanDatabase() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migration30To31_preservesRowsAndAddsRoutingSchema() {
        open(version = 30, onCreate = { db ->
            db.execSQL(
                "CREATE TABLE local_knowledge_chunks " +
                    "(id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE local_messages " +
                    "(id TEXT NOT NULL PRIMARY KEY, content TEXT)"
            )
            db.execSQL(
                "INSERT INTO local_knowledge_chunks(id, content) VALUES ('chunk-1', '正文')"
            )
            db.execSQL(
                "INSERT INTO local_messages(id, content) VALUES ('message-1', '回复')"
            )
        }).close()

        val migrated = open(
            version = 31,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(30, oldVersion)
                assertEquals(31, newVersion)
                NekobotDatabase.MIGRATION_30_31.migrate(db)
            }
        )
        val db = migrated.writableDatabase

        assertTrue(columnNames(db, "local_knowledge_chunks").containsAll(listOf("char_offset", "char_end")))
        assertTrue(columnNames(db, "local_messages").containsAll(listOf("knowledge_citations", "routing_decision_id")))
        assertTrue(tableExists(db, "routing_decision_logs"))
        db.query("SELECT content FROM local_messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("回复", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun migration32To33_createsMessageImageStorage() {
        open(version = 32).close()

        val migrated = open(
            version = 33,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(32, oldVersion)
                assertEquals(33, newVersion)
                NekobotDatabase.MIGRATION_32_33.migrate(db)
            }
        )
        val db = migrated.writableDatabase

        assertTrue(tableExists(db, "local_message_images"))
        assertTrue(
            columnNames(db, "local_message_images").containsAll(
                listOf("session_id", "message_id", "prompt", "status", "file_path", "updated_at")
            )
        )
        migrated.close()
    }

    @Test
    fun migration33To34_addsReferenceImageColumns() {
        open(version = 33, onCreate = { db ->
            db.execSQL(
                "CREATE TABLE local_message_images " +
                    "(id TEXT NOT NULL PRIMARY KEY, session_id TEXT NOT NULL, message_id TEXT NOT NULL, " +
                    "prompt TEXT NOT NULL, status TEXT NOT NULL, file_name TEXT, file_path TEXT, " +
                    "mime_type TEXT, model_id TEXT, model_name TEXT, error_message TEXT, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)"
            )
        }).close()

        val migrated = open(
            version = 34,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(33, oldVersion)
                assertEquals(34, newVersion)
                NekobotDatabase.MIGRATION_33_34.migrate(db)
            }
        )
        val db = migrated.writableDatabase
        assertTrue(
            columnNames(db, "local_message_images").containsAll(
                listOf("reference_image_path", "reference_image_mime_type")
            )
        )
        migrated.close()
    }

    @Test
    fun migration34To35_addsMessageAudioUpdateTime() {
        open(version = 34, onCreate = { db ->
            db.execSQL(
                "CREATE TABLE local_messages " +
                    "(id TEXT NOT NULL PRIMARY KEY, audio_url TEXT, created_at TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO local_messages(id, audio_url, created_at) " +
                    "VALUES ('message-1', 'file:///data/user/0/test/files/tts/reply.mp3', '2026-08-18T00:00:00Z')"
            )
        }).close()

        val migrated = open(
            version = 35,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(34, oldVersion)
                assertEquals(35, newVersion)
                NekobotDatabase.MIGRATION_34_35.migrate(db)
            }
        )
        val db = migrated.writableDatabase
        assertTrue(columnNames(db, "local_messages").contains("audio_updated_at"))
        db.query("SELECT audio_url, audio_updated_at FROM local_messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("file:///data/user/0/test/files/tts/reply.mp3", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.close()
    }

    private fun open(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    onUpgrade(db, oldVersion, newVersion)
            })
            .build()
    ).also { it.writableDatabase }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private companion object {
        const val TEST_DB = "migration-30-31-test.db"
    }
}
