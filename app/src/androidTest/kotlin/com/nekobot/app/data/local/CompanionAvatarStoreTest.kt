package com.nekobot.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Synthetic standalone database/files only; never switches the user's active profile. */
class CompanionAvatarStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "avatar_test_${UUID.randomUUID()}.db"
    private val root = File(context.cacheDir, "avatar_test_${UUID.randomUUID()}")
    private lateinit var db: NekobotDatabase
    private fun open() = Room.databaseBuilder(context, NekobotDatabase::class.java, databaseName).build()
    @Before fun setup() = runBlocking {
        root.mkdirs()
        db = open()
        db.characterDao().upsert(LocalCharacterEntity(id = "role", name = "Synthetic role", avatar = "original.png",
            portrait = "portrait.png", personality = "preserve", chatModelQueue = "[\"model\"]", createdAt = "old", updatedAt = "old"))
        db.characterDao().upsert(LocalCharacterEntity(id = "other", name = "Other role", avatar = "other.png", createdAt = "old", updatedAt = "old"))
        db.sessionDao().upsert(LocalSessionEntity(id = "session", name = "Synthetic session", characterId = "role", sessionMode = "agent", companionMode = true,
            characterAvatar = "original.png", portrait = "session-portrait.png", systemPrompt = "keep prompt", createdAt = "old", updatedAt = "old"))
        db.sessionDao().upsert(LocalSessionEntity(id = "other-session", name = "Other", characterId = "other",
            characterAvatar = "other.png", createdAt = "old", updatedAt = "old"))
    }
    @After fun cleanup() {
        db.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }
    @Test fun repeatedSavesSurviveDatabaseReopenAndKeepPortraitAndOtherFields() = runBlocking {
        val before = requireNotNull(db.characterDao().getById("role"))
        val store = CompanionAvatarStore(db, root)
        val first = store.save("session", "role", png(Color.RED))
        val second = store.save("session", "role", png(Color.BLUE))
        assertNotEquals(first, second)
        assertTrue(File(requireNotNull(Uri.parse(first).path)).isFile)
        assertTrue(File(requireNotNull(Uri.parse(second).path)).isFile)
        db.close(); db = open()
        val after = requireNotNull(db.characterDao().getById("role"))
        assertEquals(before.copy(avatar = second, updatedAt = after.updatedAt), after)
        val session = requireNotNull(db.sessionDao().getById("session"))
        assertEquals(second, session.characterAvatar)
        assertEquals("session-portrait.png", session.portrait)
        assertEquals("keep prompt", session.systemPrompt)
        assertEquals("other.png", db.sessionDao().getById("other-session")?.characterAvatar)
    }
    @Test fun databaseFailureRollsBackAvatarAndDeletesOnlyNewFile() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER avatar_test_abort BEFORE UPDATE OF character_avatar ON local_sessions BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        val result = runCatching { CompanionAvatarStore(db, root).save("session", "role", png(Color.GREEN)) }
        assertTrue(result.isFailure)
        assertEquals("original.png", db.characterDao().getById("role")?.avatar)
        assertEquals("original.png", db.sessionDao().getById("session")?.characterAvatar)
        assertTrue(File(root, "portraits").listFiles().orEmpty().isEmpty())
    }
    @Test fun changedOwnerCannotWriteEitherCharacter() = runBlocking {
        val result = runCatching { CompanionAvatarStore(db, root).save("session", "other", png(Color.GREEN)) }
        assertTrue(result.isFailure)
        assertEquals("original.png", db.characterDao().getById("role")?.avatar)
        assertEquals("other.png", db.characterDao().getById("other")?.avatar)
        assertTrue(File(root, "portraits").listFiles().orEmpty().isEmpty())
    }
    @Test fun multipleCompanionWindowsAreRejectedWithoutWrites() = runBlocking {
        val original = requireNotNull(db.sessionDao().getById("session"))
        db.sessionDao().upsert(original.copy(id = "second-window"))
        assertTrue(runCatching { CompanionAvatarStore(db, root).save("session", "role", png(Color.GREEN)) }.isFailure)
        assertEquals("original.png", db.characterDao().getById("role")?.avatar)
        assertTrue(File(root, "portraits").listFiles().orEmpty().isEmpty())
    }
    @Test fun invalidImageCannotCreateAFileOrChangeTheDatabase() = runBlocking {
        assertTrue(runCatching { CompanionAvatarStore(db, root).save("session", "role", byteArrayOf(1, 2, 3)) }.isFailure)
        assertEquals("original.png", db.characterDao().getById("role")?.avatar)
        assertFalse(File(root, "portraits").exists())
    }
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
}
