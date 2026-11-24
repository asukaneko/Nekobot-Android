package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HostModelBackupAndroidTest {
    @Test fun mirrorsFollowAndSecretsStayOutOfRestoreAndExport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = UUID.randomUUID().toString()
        val store = Store(context, scope)
        val vault = ModelVault(context, scope, store)
        val key = "fixture-host-secret"
        val host = HostModelSnapshot("host-source","Fixture","https://example.test/v1","sample",
            key,"chat",true,16384,2048)
        val id = host.config().id
        var rows = listOf(host)
        val bridge = HostModelBridge { rows }
        val local = host.config().copy(id="private-local",contextTokens=32768)
        try {
            vault.save(local,"fixture-local-secret")
            HostModelSync.refresh(store,vault,bridge)
            assertEquals(id,vault.defaultId())
            val stamp = vault.recordStamp(id)
            val selection = vault.selectionStamp(ModelPurpose.CHAT)
            HostModelSync.refresh(store,vault,bridge)
            assertEquals(stamp,vault.recordStamp(id))
            assertEquals(selection,vault.selectionStamp(ModelPurpose.CHAT))
            for (withKeys in listOf(false,true)) {
                val (models, selections) = vault.backup(withKeys)
                assertEquals(listOf(local.id), models.map { it.config.id })
                assertEquals("", selections[ModelPurpose.CHAT])
                assertTrue(models.none { it.secret == key })
            }
            HostModelSync.select(store,vault,ModelPurpose.MEMORY,id)
            val pass = "synthetic-password"
            BackupRepository(store,vault).export(includeKeys=true,password=pass.toCharArray()).use { export ->
                val opened = BackupKeySeal.open(export.root,pass.toCharArray())
                assertFalse(opened.toString().contains(key))
                assertFalse(opened.toString().contains(id))
                assertTrue(opened.toString().contains("fixture-local-secret"))
                assertFalse(opened.toString().contains(FOLLOW_PREFIX))
            }
            val (saved, selections) = vault.backup(false)
            assertTrue(runCatching {
                vault.prepareRestore(listOf(BackupModel(host.config(), key)),selections,true)
            }.isFailure)
            val staged = vault.prepareRestore(saved,selections,false)
            try {
                assertEquals(listOf(local.id), staged.directory.listFiles().orEmpty().filter { it.extension=="enc" }.map { it.nameWithoutExtension })
            } finally { vault.abort(staged) }
            HostModelSync.select(store,vault,ModelPurpose.CHAT,local.id)
            rows = listOf(HostModelSnapshot("host-source","Renamed","https://example.test/v1","other",
                "rotated-fixture","chat",true,32768,2048))
            HostModelSync.refresh(store,vault,bridge)
            assertEquals(local.id,vault.defaultId())
            assertEquals("rotated-fixture",vault.secret(id))
            assertNotEquals(stamp,vault.recordStamp(id))
            HostModelSync.select(store,vault,ModelPurpose.CHAT,"")
            HostModelSync.refresh(store,vault,bridge)
            assertEquals("",vault.defaultId())
            HostModelSync.select(store,vault,ModelPurpose.CHAT,local.id)
            rows = emptyList()
            HostModelSync.refresh(store,vault,bridge)
            assertEquals(listOf(local),vault.list())
            assertEquals(local.id,vault.defaultId())
            assertEquals("",vault.purposeId(ModelPurpose.MEMORY))
        } finally {
            vault.list().forEach { vault.delete(it.id) }
            store.close()
            context.deleteDatabase(scope)
            context.noBackupFilesDir.resolve(scope).deleteRecursively()
        }
    }
}
