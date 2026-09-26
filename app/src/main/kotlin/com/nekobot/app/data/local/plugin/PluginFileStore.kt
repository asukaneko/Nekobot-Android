package com.nekobot.app.data.local.plugin

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * 插件私有文件目录。
 *
 * 布局：`filesDir/plugin_files/<插件id>/`。插件页面的 `<input type="file">` 选择的文件
 * 会复制到这里；插件通过 `files.*` API 与页面内 `@files/` 虚拟资源路径访问。
 * 目录与插件包目录（`plugins/<插件id>`）分离：更新插件不会清空用户已上传的文件，
 * 卸载插件时随插件一起删除。
 */
class PluginFileStore internal constructor(
    private val root: File,
    private val contentResolver: ContentResolver?
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, ROOT_DIRECTORY_NAME),
        context.applicationContext.contentResolver
    )

    /** 纯 JVM 场景（单元测试）使用；没有 ContentResolver 时不能从 Uri 导入。 */
    constructor(root: File) : this(root, null)

    data class Entry(val name: String, val size: Long, val updatedAt: Long)

    /** 插件私有目录（不创建）；上传写入时才会按需创建。 */
    fun directory(pluginId: String): File {
        requireSafePluginId(pluginId)
        return File(root, pluginId)
    }

    /** 列出私有文件（按名称排序）；不存在时返回空列表。 */
    fun list(pluginId: String): List<Entry> {
        val dir = directory(pluginId)
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.map { Entry(it.name, it.length(), it.lastModified()) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.toList()
            .orEmpty()
    }

    /** 解析私有文件；文件名不安全或文件不存在时返回 null。 */
    fun resolve(pluginId: String, name: String): File? {
        val raw = name.trim()
        if (!isSafeFileName(raw)) return null
        val dir = directory(pluginId).canonicalFile
        val target = File(dir, raw).canonicalFile
        return target.takeIf { it.path.startsWith(dir.path + File.separator) && it.isFile }
    }

    fun delete(pluginId: String, name: String): Boolean =
        resolve(pluginId, name)?.delete() == true

    /** 卸载清理：整目录删除。 */
    fun clear(pluginId: String) {
        runCatching { directory(pluginId).deleteRecursively() }
    }

    /** 页面文件选择的复制入口：按显示的原始文件名复制到插件私有目录。 */
    fun importFromUri(pluginId: String, uri: Uri): Entry {
        val resolver = contentResolver
            ?: throw PluginApiException("当前环境不支持文件上传", "unavailable")
        val input = resolver.openInputStream(uri)
            ?: throw PluginApiException("无法读取所选文件", "invalid_argument")
        return input.use { saveStream(pluginId, displayName(uri), it) }
    }

    /**
     * 限量复制一个文件到插件私有目录。
     *
     * 超限报错码与工作区一致：`too_large`（单文件）、`too_many_files`（数量）、
     * `quota_exceeded`（总大小）。同名文件自动追加 `-1`、`-2` 后缀。
     */
    fun saveStream(pluginId: String, suggestedName: String, input: InputStream): Entry {
        val dir = directory(pluginId)
        val existing = dir.listFiles()?.filter { it.isFile }?.toList().orEmpty()
        if (existing.size >= MAX_FILES) {
            throw PluginApiException(
                "插件文件数量已达上限（$MAX_FILES 个），请先删除部分文件",
                "too_many_files"
            )
        }
        val used = existing.sumOf { it.length() }
        if (used >= MAX_TOTAL_BYTES) {
            throw PluginApiException("插件文件总量已达上限（${MAX_TOTAL_BYTES / MIB} MiB）", "quota_exceeded")
        }
        dir.mkdirs()
        val fileName = uniqueName(dir, sanitizeFileName(suggestedName))
        val temp = File.createTempFile("upload-", ".tmp", dir)
        val written = try {
            temp.outputStream().use { output ->
                copyLimited(input, output, MAX_FILE_BYTES, MAX_TOTAL_BYTES - used)
            }
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
        val target = File(dir, fileName)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw PluginApiException("保存文件失败", "io_error")
        }
        return Entry(target.name, written, target.lastModified())
    }

    private fun copyLimited(
        input: InputStream,
        output: OutputStream,
        fileLimit: Long,
        totalRemaining: Long
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            written += count
            if (written > fileLimit) {
                throw PluginApiException("文件超过大小限制（${MAX_FILE_BYTES / MIB} MiB）", "too_large")
            }
            if (written > totalRemaining) {
                throw PluginApiException(
                    "插件文件总量已达上限（${MAX_TOTAL_BYTES / MIB} MiB）",
                    "quota_exceeded"
                )
            }
            output.write(buffer, 0, count)
        }
        return written
    }

    /** 读取显示名；查不到时回退到 URI 末段。 */
    private fun displayName(uri: Uri): String {
        val resolver = contentResolver
        val queried = resolver?.let {
            runCatching {
                it.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        }
        return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
    }

    private fun requireSafePluginId(pluginId: String) {
        require(PluginManifestValidator.isSafeRelativePath(pluginId) && '/' !in pluginId) {
            "不安全的插件 id：$pluginId"
        }
    }

    companion object {
        const val ROOT_DIRECTORY_NAME = "plugin_files"

        /** 单个上传文件上限。 */
        const val MAX_FILE_BYTES = 16L * 1024 * 1024

        /** 每个插件最多保留的文件数。 */
        const val MAX_FILES = 100

        /** 每个插件私有文件总量上限。 */
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

        const val MAX_NAME_CHARS = 80
        private const val MIB = 1024L * 1024

        /** 私有文件名必须是单段安全名称（无路径分隔符/控制字符）。 */
        fun isSafeFileName(raw: String): Boolean {
            val name = raw.trim()
            if (name.isEmpty() || name.length > MAX_NAME_CHARS) return false
            if (name == "." || name == "..") return false
            if (name.any { it == '/' || it == '\\' || it.isISOControl() }) return false
            return true
        }

        /** 去掉路径与危险字符，得到可落盘的文件名；空名回退为 upload。 */
        fun sanitizeFileName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
                .trim { it == '.' || it.isWhitespace() }
                .take(MAX_NAME_CHARS)
            return cleaned.ifBlank { "upload" }
        }

        private fun uniqueName(dir: File, name: String): String {
            if (!File(dir, name).exists()) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val stem = name.substring(0, dot)
            val extension = name.substring(dot)
            var index = 1
            while (true) {
                val candidate = "$stem-$index$extension"
                if (!File(dir, candidate).exists()) return candidate
                index++
            }
        }
    }
}
