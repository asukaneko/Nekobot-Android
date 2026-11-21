package com.nekobot.app.data.local.sessionimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 把用户选择的本地归档复制到缓存目录。
 *
 * `java.util.zip.ZipFile` 需要随机读取的文件句柄，而 SAF 返回的 `content://` 只能顺序读，
 * 因此先落到 cache 再解析；导入结束（或取消）后由调用方删除。
 */
object SessionArchiveStaging {

    /** 单个导入归档的体积上限（2 GiB）：导出包可能包含大量图片附件。 */
    const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024

    fun fromUri(context: Context, uri: Uri): File {
        val target = File.createTempFile("session-import-", ".zip", context.cacheDir)
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw SessionImportException.UnsupportedArchive("unknown", "unreadable uri")
            source.use { input ->
                target.outputStream().buffered().use { sink -> copyLimited(input, sink) }
            }
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    /** 读取所选文件的展示名；拿不到时回退为路径末段。 */
    fun displayName(context: Context, uri: Uri): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun copyLimited(source: InputStream, sink: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ARCHIVE_BYTES) throw SessionImportException.TooLarge()
            sink.write(buffer, 0, read)
        }
        if (total == 0L) throw SessionImportException.UnsupportedArchive("unknown", "empty file")
    }
}
