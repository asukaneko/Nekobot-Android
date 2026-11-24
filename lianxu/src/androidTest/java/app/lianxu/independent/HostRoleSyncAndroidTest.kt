package app.lianxu.independent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class HostRoleSyncAndroidTest {
    private class Host(override val name: String) : HostRoleScope {
        val rows = mutableMapOf<String, RoleBasics>()
        var writes = 0
        var failDelete = false
        override suspend fun ids() = rows.keys.toList()
        override suspend fun get(id: String) = rows[id]
        override suspend fun write(id: String, data: RoleBasics, expected: RoleBasics?, avatarChanged: Boolean): RoleBasics {
            check(rows[id]?.digest == expected?.digest)
            val saved = data.copy(avatar = if (avatarChanged) data.avatar else expected?.avatar)
            rows[id] = saved
            writes++
            return saved
        }
        override suspend fun delete(id: String) {
            check(!failDelete)
            rows.remove(id)
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = UUID.randomUUID().toString()
        val store = Store(context, dbName)
        val vault = ModelVault(context, dbName, store)
        val backup = BackupRepository(store, vault)
        val host = Host("A.db")
        var active: Host? = host
        val sync = HostRoleSync(context, HostRoleBridge { active }, store)
        val hostId = "original-host"
        suspend fun addHost(avatar: ByteArray? = null): RoleLink {
            host.rows[hostId] = RoleBasics("小雨", "介绍", "设定", "你好", avatar)
            sync.sync()
            return requireNotNull(sync.hostLink(host.name, hostId))
        }
        fun close() {
            store.close()
            context.deleteDatabase(dbName)
            context.noBackupFilesDir.resolve(dbName).deleteRecursively()
        }
    }

    @Test fun bothSidesCreateAndEditOnlySharedFieldsAndSkipUnchangedWrites() = runBlocking {
        val f = Fixture()
        try {
            val bitmap = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            bitmap.recycle()
            f.store.addConfigured(Friend("native", "本地", "介绍", tone = 7),
                RolePrompts(characterDefinition = "本地设定", expression = "轻声", extra = "独有"))
            val link = f.addHost(png)
            val native = requireNotNull(f.sync.friendLink("native"))
            assertEquals("本地设定", f.host.rows.getValue(native.host).definition)
            assertEquals(listOf("你好"), f.store.messagesFor(link.friend).map { it.text })
            val assets = RoleAssets(ProcessingRepository(f.store))
            val photo = requireNotNull(assets.avatar(link.friend))
            assertEquals("image/jpeg", photo.mime)
            val decoded = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)
            assertEquals(512, decoded.width)
            decoded.recycle()
            // A real SQLite write on the no-change path fails the test even if the bytes match.
            val db = f.store.writableDatabase
            db.execSQL("CREATE TRIGGER reject_map BEFORE INSERT ON preferences BEGIN SELECT RAISE(ABORT,'unexpected pref write'); END")
            db.execSQL("CREATE TRIGGER reject_avatar BEFORE DELETE ON role_assets BEGIN SELECT RAISE(ABORT,'unexpected avatar rewrite'); END")
            val writes = f.host.writes
            f.sync.sync()
            assertEquals(writes, f.host.writes)
            db.execSQL("DROP TRIGGER reject_map")

            val changed = f.host.rows.getValue(native.host).copy(name = "新名字", opening = "新招呼")
            f.host.rows[native.host] = changed
            f.sync.sync(native.host, f.host.name)
            assertEquals(7, f.store.friends().single { it.id == "native" }.tone)
            val prompts = RolePromptStore.decode(f.store.preference(RolePromptStore.key("native"), ""))
            assertEquals("轻声", prompts.expression)
            assertEquals("独有", prompts.extra)
            assertEquals("新招呼", prompts.opening)

            f.host.rows[link.host] = f.host.rows.getValue(link.host).copy(name = "改名")
            f.sync.sync(link.host, f.host.name)
            assertArrayEquals(photo.bytes, requireNotNull(assets.avatar(link.friend)).bytes)
            f.store.saveConfigured(link.friend, "手动改名") { it.copy(characterDefinition = "新设定") }
            f.sync.sync()
            assertEquals("手动改名", f.host.rows.getValue(link.host).name)
            assertEquals("新设定", f.host.rows.getValue(link.host).definition)
            assertEquals(listOf("你好"), f.store.messagesFor(link.friend).map { it.text })
            db.execSQL("DROP TRIGGER reject_avatar")
            assets.put("native", RoleAssets.Asset("avatar.png", "avatar", "image/png", png))
            f.sync.sync()
            val outgoing = requireNotNull(f.host.rows.getValue(native.host).avatar)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(outgoing, 0, outgoing.size, bounds)
            assertEquals("image/jpeg", bounds.outMimeType)
            assertEquals(512, bounds.outWidth)
            f.backup.export().use { assertFalse(it.root.toString().contains(ROLE_MAP)) }
        } finally { f.close() }
    }

    @Test fun confirmedDeletesCascadeBothWaysAndRefusalsKeepBothRoles() = runBlocking {
        val f = Fixture()
        try {
            val link = f.addHost()
            val plan = f.backup.previewRoleDeletion(link.friend).copy(link = link)
            f.active = Host("B.db")
            f.sync.sync()
            assertTrue(f.active!!.rows.isEmpty())
            assertTrue(runCatching { f.sync.deleteFriend(plan) }.isFailure)
            f.active = null
            f.sync.sync()
            assertEquals(link.friend, f.store.friends().single().id)
            f.active = f.host
            var deletedWithoutApproval = false
            val denied = runCatching { f.sync.deleteHost(link.scope, link.host, null) { deletedWithoutApproval = true } }
            assertTrue(denied.exceptionOrNull() is IllegalStateException)
            assertFalse(deletedWithoutApproval)
            // Losing a Room row outside an authorized delete must not erase local history.
            f.host.rows.remove(link.host)
            f.sync.sync()
            assertTrue(link.host in f.host.rows)
            assertEquals(listOf("你好"), f.store.messagesFor(link.friend).map { it.text })
            val ticket = requireNotNull(f.store.memorySummary.begin(link.friend, ChatAttemptLease(), 300_000) {})
            try {
                assertTrue(runCatching { f.sync.deleteHost(link.scope, link.host, link) {} }.isFailure)
                f.sync.sync()
                assertTrue(link.host in f.host.rows)
                assertEquals(1, f.store.messagesFor(link.friend).size)
            } finally { f.store.memorySummary.finish(ticket) }
            f.sync.deleteFriend(plan)
            f.sync.sync()
            assertTrue(f.host.rows.isEmpty())
            assertTrue(f.store.friends().isEmpty())
            assertTrue(f.store.messagesFor(link.friend).isEmpty())

            val second = f.addHost()
            f.sync.deleteHost(second.scope, second.host, second) { error("linked") }
            f.sync.sync()
            assertTrue(f.host.rows.isEmpty())
            assertTrue(f.store.friends().isEmpty())
            assertNull(f.sync.hostLink(second.scope, second.host))
        } finally { f.close() }
    }

    @Test fun unfinishedDeleteSurvivesFullRestoreAndNeverResurrects() = runBlocking {
        val f = Fixture()
        try {
            val link = f.addHost()
            f.host.failDelete = true
            assertTrue(runCatching { f.sync.deleteHost(link.scope, link.host, link) {} }.isFailure)
            assertTrue(f.store.friends().isEmpty())
            assertTrue(link.host in f.host.rows)
            f.active = Host("B.db")
            f.store.addConfigured(Friend("other", "另一个", "简介"), RolePrompts(characterDefinition = "设定"))
            f.sync.sync()
            f.backup.export().use { export ->
                assertFalse(export.root.toString().contains(ROLE_MAP))
                val pack = BackupArchive.Package(export.root, export.files, "")
                val forged = pack.root.deepCopy()
                forged["preferences"].asJsonObject.addProperty(ROLE_MAP + "foreign", "{}")
                assertTrue(runCatching { f.backup.preview(pack.copy(root = forged), "REPLACE") }.isFailure)
                val plan = f.backup.preview(pack, "REPLACE")
                f.sync.duringRestore { f.backup.commit(plan, true) }
            }
            assertNull(f.sync.friendLink("other"))
            assertEquals(link, f.sync.hostLink(link.scope, link.host))
            f.host.failDelete = false
            f.active = f.host
            f.sync.sync()
            assertNull(f.sync.hostLink(link.scope, link.host))
            assertFalse(link.host in f.host.rows)
            assertTrue(f.store.friends().none { it.id == link.friend || it.name == "小雨" })
        } finally { f.close() }
    }
}
