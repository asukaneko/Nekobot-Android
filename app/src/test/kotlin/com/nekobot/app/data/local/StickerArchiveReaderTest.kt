package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StickerArchiveReaderTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `readZip 提取图片并按文件名生成名称`() {
        val zip = zipOf(
            "pack/开心.png" to byteArrayOf(1, 2, 3),
            "生气.jpg" to byteArrayOf(4, 5),
            "说明.txt" to "hello".toByteArray(),
            "folder/" to ByteArray(0)
        )

        val result = StickerArchiveReader.readZip(zip.inputStream())

        assertEquals(2, result.size)
        assertEquals("开心", result[0].name)
        assertEquals("生气", result[1].name)
        assertTrue(result[0].bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("zip", result[0].source)
    }

    @Test
    fun `readZip 跳过超大条目之外的普通条目仍可读取`() {
        val zip = zipOf(
            "big.png" to ByteArray((StickerArchiveReader.MAX_ENTRY_BYTES + 1).toInt()),
            "small.png" to byteArrayOf(9)
        )

        val result = StickerArchiveReader.readZip(zip.inputStream())

        assertEquals(1, result.size)
        assertEquals("small", result[0].name)
    }
}
