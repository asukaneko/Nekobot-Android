package com.nekobot.app.data.local

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 表情包压缩包读取器：从 ZIP 中提取图片条目，名称取图片文件名。
 *
 * 兼容常见导出结构（压缩包根目录或 `包名/图片.png` 子目录），目录名不参与命名；
 * 名称可在导入预览里再修改。
 */
object StickerArchiveReader {
    /** 单个表情文件上限：超过的条目跳过，避免异常大图拖垮导入。 */
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024

    /** 单次导入条目上限，防止恶意压缩包展开过多文件。 */
    const val MAX_ENTRIES = 1000

    /** 单次导入的图片总字节上限，避免一次性把整个压缩包读进内存。 */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** 从 ZIP 流中提取可用的表情条目；读取失败返回已解析部分。 */
    fun readZip(input: InputStream): List<StickerImport> {
        val result = mutableListOf<StickerImport>()
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                if (count > MAX_ENTRIES) break
                if (entry.isDirectory) continue
                val entryName = entry.name.replace('\\', '/').substringAfterLast('/')
                if (entryName.startsWith(".")) continue
                if (!LocalStickerStorage.isSupportedImage(null, entryName)) continue
                val bytes = readBounded(zip) ?: continue
                if (bytes.isEmpty()) continue
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) break
                result += StickerImport(
                    name = LocalStickerStorage.defaultNameFromFileName(entryName),
                    bytes = bytes,
                    mimeType = null,
                    sourceName = entryName,
                    source = "zip"
                )
            }
        }
        return result
    }

    /** 读取单个条目字节；超过上限返回 null（跳过该条目，不中断整包读取）。 */
    private fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
