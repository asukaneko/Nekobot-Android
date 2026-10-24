package com.nekobot.app.data.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.ZoneId

class ImportedFavoriteSnapshotsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val input = """[{"personaName":"原角色","favoriteTime":1760000000000,"messageTime":1759999000000,"messageContent":"[转发消息]","forwardedContent":"第一行\n第二行，原文不变。"}]"""

    @Test fun originalFieldsAndFullForwardedContentRemainVerbatim() {
        val result = parseImportedFavoriteSnapshots(input)
        assertFalse(result.unreadable)
        val snapshot = result.items.single()
        assertEquals("原角色", snapshot.personaName)
        assertEquals("1760000000000", snapshot.favoriteTime)
        assertEquals("1759999000000", snapshot.messageTime)
        assertEquals("[转发消息]", snapshot.messageContent)
        assertEquals("第一行\n第二行，原文不变。", snapshot.forwardedContent)
    }

    @Test fun malformedAndWrongShapeDoNotProducePartialOrInventedRecords() {
        listOf("[", "{}", "[{\"messageContent\":true}]", "[{\"forwardedContent\":{}}]",
            "[{}]", "[null]", input + " trailing").forEach {
            val result = parseImportedFavoriteSnapshots(it)
            assertTrue(it, result.unreadable)
            assertTrue(it, result.items.isEmpty())
        }
    }

    @Test fun optionalMetadataIsNotFilledWithACharacterOrMessageIdentity() {
        val snapshot = parseImportedFavoriteSnapshots("""[{"messageContent":"正文"}]""").items.single()
        assertEquals("", snapshot.personaName)
        assertEquals("", snapshot.favoriteTime)
        assertEquals("", snapshot.messageTime)
        assertEquals("", snapshot.forwardedContent)
    }

    private fun fixture(session: String = "owned-session"): Pair<File, File> {
        val files = temporary.newFolder()
        val target = File(files, "workspace/$session/未匹配收藏.json")
        target.parentFile!!.mkdirs()
        target.writeText(input)
        return files to target
    }

    @Test fun readsOnlyTheExactOwnedSessionAndDoesNotCreateMissingDirectories() {
        val (files, target) = fixture()
        assertEquals(1, readImportedFavoriteSnapshots(files, "owned-session") { true }.items.size)
        assertTrue(readImportedFavoriteSnapshots(files, "other-session") { true }.items.isEmpty())
        assertFalse(File(files, "workspace/other-session").exists())
        assertEquals(input, target.readText())
    }

    @Test fun missingSessionOrDeniedProfileCannotExposeAnExistingSnapshot() {
        val (files, _) = fixture()
        assertTrue(readImportedFavoriteSnapshots(files, "owned-session") { false }.items.isEmpty())
        listOf("../owned-session", "shared", "/owned-session", "owned-session/..", "owned-session\\..").forEach {
            assertTrue(readImportedFavoriteSnapshots(files, it) { true }.items.isEmpty())
        }
    }

    @Test fun activeOwnershipLostDuringReadDiscardsTheResult() {
        val (files, _) = fixture()
        var checks = 0
        val result = readImportedFavoriteSnapshots(files, "owned-session") { ++checks == 1 }
        assertEquals(2, checks)
        assertTrue(result.items.isEmpty())
        assertFalse(result.unreadable)
    }

    @Test fun profileRepositoryRevisionAndModeMustAllMatchTheCapturedOwner() {
        val repository = Any()
        val owner = ImportedFavoriteSnapshotOwner("profile-a", 7L, repository)
        assertTrue(owner.permits(true, "profile-a", 7L, repository))
        assertFalse(owner.permits(false, "profile-a", 7L, repository))
        assertFalse(owner.permits(true, "profile-b", 7L, repository))
        assertFalse(owner.permits(true, "profile-a", 8L, repository))
        assertFalse(owner.permits(true, "profile-a", 7L, Any()))
    }

    @Test fun oversizedAndInvalidUtf8FilesFailWithoutPartialContent() {
        val (files, target) = fixture()
        RandomAccessFile(target, "rw").use { it.setLength(MAX_IMPORTED_FAVORITES_BYTES.toLong() + 1) }
        assertTrue(readImportedFavoriteSnapshots(files, "owned-session") { true }.unreadable)
        target.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(readImportedFavoriteSnapshots(files, "owned-session") { true }.unreadable)
    }

    @Test fun timestampsUseOriginalMillisecondsAndKeepUnknownFormats() {
        assertEquals("2025-10-09 08:53:20", importedSnapshotTimestamp("1760000000000", ZoneId.of("UTC")))
        assertEquals("原时间", importedSnapshotTimestamp("原时间", ZoneId.of("UTC")))
        assertEquals("", importedSnapshotTimestamp("", ZoneId.of("UTC")))
    }
}
