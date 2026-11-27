package app.lianxu.independent

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ChatMediaSaveTest {
    @get:org.junit.Rule val temp = org.junit.rules.TemporaryFolder()

    @Test fun storagePermissionAppliesOnlyBeforeScopedStorage() {
        assertTrue(ChatMediaSave.needsLegacyPermission(26))
        assertTrue(ChatMediaSave.needsLegacyPermission(28))
        assertFalse(ChatMediaSave.needsLegacyPermission(29))
        assertFalse(ChatMediaSave.needsLegacyPermission(35))
    }

    @Test fun legacyAlbumReceivesOriginalBytes() {
        val directory = java.io.File(temp.root, "Pictures/Lianxu")
        val bytes = ByteArray(150_001) { it.toByte() }
        val file = ChatMediaSave.saveToDirectory(directory, "original.jpg", bytes::inputStream)
        assertEquals(java.io.File(directory, "original.jpg"), file)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun failedReadRemovesPartialVideo() {
        val directory = temp.newFolder("Movies", "Lianxu")
        val broken = object : java.io.InputStream() {
            var started = false
            override fun read(): Int = error("expected buffered copy")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (started) throw java.io.IOException("broken stream")
                started = true
                bytes[offset] = 42
                return 1
            }
        }
        assertThrows(java.io.IOException::class.java) {
            ChatMediaSave.saveToDirectory(directory, "partial.mp4") { broken }
        }
        assertArrayEquals(emptyArray<String>(), directory.list())
    }

    @Test fun namesAndFoldersMatchTheOriginalMimeType() {
        for((mime,extension) in mapOf("image/jpeg" to "jpg","image/png" to "png","video/mp4" to "mp4","video/quicktime" to "mov")) {
            assertEquals("Lianxu-fixture.$extension",ChatMediaSave.mediaName(mime,"fixture"))
            assertEquals(if(mime.startsWith("video/")) "Movies/Lianxu" else "Pictures/Lianxu",ChatMediaSave.folder(mime))
        }
    }
    @Test fun copiesOriginalBytesAcrossMultipleBuffers() {
        val original=ByteArray(150_001) { (it%203).toByte() }
        val saved=ByteArrayOutputStream()
        assertEquals(original.size.toLong(),ChatMediaSave.copy(original.inputStream(),saved))
        assertArrayEquals(original,saved.toByteArray())
    }
}
