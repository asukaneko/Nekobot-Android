package com.nekobot.app

import app.lianxu.independent.RoleTextEdits
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class NekobotRoleScopeAndroidTest {
    @Test fun roomBridgeUpdatesOnlyBasicFieldsAndSessionAvatar() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "test-role-" + UUID.randomUUID()).apply { mkdirs() }
        val paths = object : ContextWrapper(context) {
            override fun getFilesDir() = root
        }
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        try {
            var active = true
            val scope = NekobotRoleScope(db, paths, "fixture.db") { active }
            val original = LocalCharacterEntity(
                id = "c", name = "小云", createdAt = "1", updatedAt = "1",
                personality = "安静", scenario = "雨天", greeting = "你好",
                tags = "[]", basicInfo = "资料", rules = "规则", state = "{}", responseFormat = "格式",
                chatModelQueue = "[]"
            )
            val dao = db.characterDao()
            dao.upsert(original)
            db.sessionDao().upsert(LocalSessionEntity(id = "s", name = "窗口", characterId = "c", createdAt = "1", updatedAt = "1"))
            val initial = requireNotNull(scope.get("c"))
            assertEquals("安静", initial.bio)
            assertEquals("安静", initial.definition)
            assertEquals("你好", initial.opening)
            scope.write("c", initial.copy(name = "改名"), initial, false, RoleTextEdits(true, false, false, false))
            val renamed = requireNotNull(dao.getById("c"))
            assertNull(renamed.description)
            assertNull(renamed.systemPrompt)
            assertNull(renamed.firstMessage)
            val afterRename = requireNotNull(scope.get("c"))
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val image = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
            bitmap.recycle()
            val new = initial.copy(name = "新名字", bio = "新简介", definition = "新设定", opening = "新招呼", avatar = image)
            val saved = scope.write("c", new, afterRename, true, RoleTextEdits(true, true, true, true))
            val entity = requireNotNull(dao.getById("c"))
            assertEquals(original.copy(name = new.name, description = new.bio, systemPrompt = new.definition,
                firstMessage = new.opening, avatar = entity.avatar, updatedAt = entity.updatedAt), entity)
            assertEquals(entity.avatar, db.sessionDao().getById("s")?.characterAvatar)
            val photo = File(requireNotNull(Uri.parse(entity.avatar).path))
            assertArrayEquals(image, photo.readBytes())
            assertTrue(photo.setLastModified(1500000000000L))
            val modified = photo.lastModified()
            scope.write("c", saved.copy(name = "再改名"), saved, false, RoleTextEdits(true, false, false, false))
            assertEquals(modified, photo.lastModified())
            active = false
            assertTrue(runCatching { scope.delete("c") }.isFailure)
            assertNotNull(dao.getById("c"))
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }
}
