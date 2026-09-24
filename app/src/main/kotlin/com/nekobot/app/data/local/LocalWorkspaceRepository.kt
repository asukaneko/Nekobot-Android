package com.nekobot.app.data.local

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.data.local.plugin.PluginManifestValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 本地会话工作区与共享工作区的文件边界。
 *
 * 所有路径在访问前都会转为 canonical path，并限制在对应工作区根目录内，
 * 避免仓库主类同时承担文件系统校验和业务编排职责。
 */
internal class LocalWorkspaceRepository(
    private val context: Context?
) {
    suspend fun listSessionFiles(sessionId: String, path: String?): JsonElement = withContext(Dispatchers.IO) {
        val root = sessionRoot(sessionId)?.canonicalFile ?: return@withContext JsonArray()
        listFiles(root, path)
    }

    suspend fun uploadSessionFile(sessionId: String, bytes: ByteArray, fileName: String): JsonElement =
        withContext(Dispatchers.IO) {
            val root = sessionRoot(sessionId) ?: return@withContext unavailable("工作区目录不可用")
            writeFile(root, bytes, fileName)
        }

    suspend fun deleteSessionFile(sessionId: String, path: String): JsonElement = withContext(Dispatchers.IO) {
        val root = sessionRoot(sessionId)?.canonicalFile ?: return@withContext unavailable("工作区目录不可用")
        deleteFile(root, path)
    }

    suspend fun downloadSessionFile(sessionId: String, path: String): File? = withContext(Dispatchers.IO) {
        val root = sessionRoot(sessionId)?.canonicalFile ?: return@withContext null
        resolve(root, path, allowRoot = false)?.takeIf { it.exists() && it.isFile }
    }

    suspend fun listSharedFiles(path: String?): JsonElement = withContext(Dispatchers.IO) {
        val root = sharedRoot()?.canonicalFile ?: return@withContext JsonArray()
        listFiles(root, path)
    }

    suspend fun uploadSharedFile(bytes: ByteArray, fileName: String): JsonElement = withContext(Dispatchers.IO) {
        val root = sharedRoot() ?: return@withContext unavailable("共享工作区目录不可用")
        writeFile(root, bytes, fileName)
    }

    suspend fun deleteSharedFile(path: String): JsonElement = withContext(Dispatchers.IO) {
        val root = sharedRoot()?.canonicalFile ?: return@withContext unavailable("共享工作区目录不可用")
        deleteFile(root, path)
    }

    suspend fun downloadSharedFile(path: String): File? = withContext(Dispatchers.IO) {
        val root = sharedRoot()?.canonicalFile ?: return@withContext null
        resolve(root, path, allowRoot = false)?.takeIf { it.exists() && it.isFile }
    }

    suspend fun moveToShared(sessionId: String, path: String): JsonElement = withContext(Dispatchers.IO) {
        val sourceRoot = sessionRoot(sessionId)?.canonicalFile
        val targetRoot = sharedRoot()?.canonicalFile
        if (sourceRoot == null || targetRoot == null) return@withContext unavailable("目录不可用")
        move(sourceRoot, targetRoot, path).apply {
            if (get("success")?.asBoolean == true) addProperty("session_id", sessionId)
        }
    }

    suspend fun moveSessionFile(sessionId: String, path: String, targetPath: String): JsonElement =
        withContext(Dispatchers.IO) {
            val root = sessionRoot(sessionId)?.canonicalFile
                ?: return@withContext unavailable("工作区目录不可用")
            move(root, root, path, targetPath)
        }

    suspend fun moveSharedFile(path: String, targetPath: String): JsonElement = withContext(Dispatchers.IO) {
        val root = sharedRoot()?.canonicalFile ?: return@withContext unavailable("共享工作区目录不可用")
        move(root, root, path, targetPath)
    }

    suspend fun moveSharedToSession(path: String, sessionId: String): JsonElement = withContext(Dispatchers.IO) {
        val sourceRoot = sharedRoot()?.canonicalFile
        val targetRoot = sessionRoot(sessionId)?.canonicalFile
        if (sourceRoot == null || targetRoot == null) return@withContext unavailable("目录不可用")
        move(sourceRoot, targetRoot, path).apply {
            if (get("success")?.asBoolean == true) addProperty("session_id", sessionId)
        }
    }

    suspend fun createSharedFolder(path: String): JsonElement = withContext(Dispatchers.IO) {
        val root = sharedRoot()?.canonicalFile ?: return@withContext unavailable("共享工作区目录不可用")
        createFolder(root, path)
    }

    suspend fun createSessionFolder(sessionId: String, path: String): JsonElement = withContext(Dispatchers.IO) {
        val root = sessionRoot(sessionId)?.canonicalFile ?: return@withContext unavailable("工作区目录不可用")
        createFolder(root, path)
    }

    // ==================== 插件专属文件夹（会话 / 共享） ====================

    /**
     * 插件生成内容的落盘位置：会话工作区 `plugins/<插件id>/`；
     * [sessionId] 为空（非会话入口）时使用共享工作区 `plugins/<插件id>/`。
     *
     * 插件只能读写自己的专属文件夹，所有路径都会做 canonical 越界校验。
     */
    suspend fun savePluginFile(
        sessionId: String?,
        pluginId: String,
        relativePath: String,
        content: String
    ): JsonElement = withContext(Dispatchers.IO) {
        val (root, scope) = pluginRoot(sessionId)
            ?: return@withContext unavailable("工作区目录不可用", "workspace_unavailable")
        val folder = pluginFolder(root, pluginId)
            ?: return@withContext unavailable("插件目录无效", "invalid_argument")
        val normalized = normalize(relativePath)
        if (normalized.isBlank()) return@withContext unavailable("文件路径不能为空", "invalid_argument")
        val target = resolve(folder, normalized, allowRoot = false)
            ?: return@withContext unavailable("文件路径无效", "invalid_argument")
        if (target.isDirectory) return@withContext unavailable("目标是文件夹：$normalized", "invalid_argument")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PLUGIN_FILE_BYTES) {
            return@withContext unavailable("单个文件最多 ${MAX_PLUGIN_FILE_BYTES / 1024} KiB", "too_large")
        }
        quotaViolation(folder, target, bytes.size.toLong())?.let { return@withContext it }
        val written = runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }.isSuccess
        if (!written) return@withContext unavailable("写入文件失败", "write_failed")
        JsonObject().apply {
            addProperty("success", true)
            addProperty("scope", scope)
            addProperty("path", normalized)
            addProperty("name", target.name)
            addProperty("size", bytes.size)
            addProperty("mime_type", mimeType(target.name))
        }
    }

    suspend fun listPluginFiles(sessionId: String?, pluginId: String, path: String?): JsonElement =
        withContext(Dispatchers.IO) {
            val (root, scope) = pluginRoot(sessionId)
                ?: return@withContext JsonObject().apply {
                    addProperty("success", false)
                    addProperty("message", "工作区目录不可用")
                    addProperty("code", "workspace_unavailable")
                }
            val folder = pluginFolder(root, pluginId)
                ?: return@withContext unavailable("插件目录无效", "invalid_argument")
            JsonObject().apply {
                addProperty("success", true)
                addProperty("scope", scope)
                addProperty("path", normalize(path))
                add("files", listFiles(folder, path))
            }
        }

    suspend fun readPluginFile(
        sessionId: String?,
        pluginId: String,
        relativePath: String,
        maxBytes: Long
    ): JsonElement = withContext(Dispatchers.IO) {
        val (root, scope) = pluginRoot(sessionId)
            ?: return@withContext unavailable("工作区目录不可用", "workspace_unavailable")
        val folder = pluginFolder(root, pluginId)
            ?: return@withContext unavailable("插件目录无效", "invalid_argument")
        val normalized = normalize(relativePath)
        val file = resolve(folder, normalized, allowRoot = false)?.takeIf { it.isFile }
            ?: return@withContext unavailable("文件不存在：$normalized", "not_found")
        if (file.length() > MAX_PLUGIN_FILE_BYTES) {
            return@withContext unavailable("文件过大（上限 ${MAX_PLUGIN_FILE_BYTES / 1024} KiB）", "too_large")
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return@withContext unavailable("读取文件失败", "read_failed")
        }
        val truncated = bytes.size > maxBytes
        val content = if (truncated) {
            bytes.copyOf(maxBytes.toInt().coerceAtLeast(0)).toString(Charsets.UTF_8)
        } else {
            bytes.toString(Charsets.UTF_8)
        }
        JsonObject().apply {
            addProperty("success", true)
            addProperty("scope", scope)
            addProperty("path", normalized)
            addProperty("size", bytes.size)
            addProperty("truncated", truncated)
            addProperty("content", content)
        }
    }

    suspend fun deletePluginFile(sessionId: String?, pluginId: String, relativePath: String): JsonElement =
        withContext(Dispatchers.IO) {
            val (root, _) = pluginRoot(sessionId)
                ?: return@withContext unavailable("工作区目录不可用", "workspace_unavailable")
            val folder = pluginFolder(root, pluginId)
                ?: return@withContext unavailable("插件目录无效", "invalid_argument")
            val normalized = normalize(relativePath)
            if (normalized.isBlank()) return@withContext unavailable("文件路径不能为空", "invalid_argument")
            val target = resolve(folder, normalized, allowRoot = false)
                ?: return@withContext unavailable("文件路径无效", "invalid_argument")
            if (!target.exists()) return@withContext unavailable("文件不存在：$normalized", "not_found")
            val deleted = target.deleteRecursively()
            JsonObject().apply {
                addProperty("success", deleted)
                addProperty("path", normalized)
                if (!deleted) addProperty("message", "删除失败")
            }
        }

    private fun pluginRoot(sessionId: String?): Pair<File, String>? {
        val safeSessionId = sessionId?.trim().orEmpty()
        return if (safeSessionId.isNotEmpty()) {
            sessionRoot(safeSessionId)?.let { it to "session" }
        } else {
            sharedRoot()?.let { it to "shared" }
        }
    }

    /** 插件专属文件夹（`plugins/<插件id>`），越界或 id 不安全时返回 null。 */
    private fun pluginFolder(root: File, pluginId: String): File? {
        if (!PluginManifestValidator.isSafeRelativePath(pluginId)) return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val folder = runCatching { File(canonicalRoot, "$PLUGIN_DIR_NAME/$pluginId").canonicalFile }.getOrNull()
            ?: return null
        return folder.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    /** 插件文件夹数量与总大小配额；违规时返回失败对象。 */
    private fun quotaViolation(folder: File, target: File, addedBytes: Long): JsonObject? {
        if (!folder.isDirectory) return null
        val existing = folder.walkTopDown().filter(File::isFile).toList()
        val replaced = target.takeIf(File::isFile)
        val fileCount = existing.size - (if (replaced != null) 1 else 0) + 1
        val totalBytes = existing.sumOf(File::length) - (replaced?.length() ?: 0L) + addedBytes
        return when {
            fileCount > MAX_PLUGIN_FILES ->
                unavailable("插件文件数量超过 $MAX_PLUGIN_FILES", "too_many_files")
            totalBytes > MAX_PLUGIN_TOTAL_BYTES ->
                unavailable("插件文件夹总大小超过 ${MAX_PLUGIN_TOTAL_BYTES / (1024 * 1024)} MiB", "quota_exceeded")
            else -> null
        }
    }

    private fun sessionRoot(sessionId: String): File? =
        context?.filesDir?.let { LocalWorkspaceStorage.resolve(it, sessionId) }

    private fun sharedRoot(): File? =
        context?.filesDir?.let(LocalWorkspaceStorage::resolveShared)

    private fun listFiles(root: File, path: String?): JsonArray {
        val target = resolve(root, path, allowRoot = true)?.takeIf(File::isDirectory) ?: return JsonArray()
        return JsonArray().also { result ->
            target.listFiles()
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                ?.forEach { file ->
                    result.add(JsonObject().apply {
                        addProperty("name", file.name)
                        addProperty("type", if (file.isDirectory) "directory" else "file")
                        addProperty("size", file.length())
                        addProperty("path", file.relativeTo(root).invariantSeparatorsPath)
                        addProperty("mime_type", mimeType(file.name))
                    })
                }
        }
    }

    private fun writeFile(root: File, bytes: ByteArray, originalName: String): JsonObject {
        val safeName = originalName.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { UUID.randomUUID().toString() }
        val file = resolve(root.canonicalFile, safeName, allowRoot = false)
            ?: return unavailable("文件路径无效")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return JsonObject().apply {
            addProperty("success", true)
            addProperty("name", safeName)
            addProperty("size", bytes.size)
            addProperty("path", safeName)
            addProperty("mime_type", mimeType(safeName))
        }
    }

    private fun deleteFile(root: File, path: String): JsonObject {
        val file = resolve(root, path, allowRoot = false) ?: return unavailable("文件路径无效")
        return JsonObject().apply {
            addProperty("success", file.exists() && file.deleteRecursively())
            addProperty("filename", path)
        }
    }

    private fun move(sourceRoot: File, targetRoot: File, path: String, targetPath: String = ""): JsonObject {
        val source = resolve(sourceRoot, path, allowRoot = false)
            ?.takeIf(File::exists)
            ?: return unavailable("源文件不存在")
        val targetDirectory = resolve(targetRoot, targetPath, allowRoot = true)
            ?.takeIf(File::isDirectory)
            ?: return unavailable("目标文件夹不存在")
        if (targetDirectory == source || targetDirectory.path.startsWith(source.path + File.separator)) {
            return unavailable("不能移动到自身或子文件夹")
        }
        val target = File(targetDirectory, source.name).canonicalFile
        if (!target.path.startsWith(targetRoot.path + File.separator)) return unavailable("目标路径无效")
        source.copyRecursively(target, overwrite = true)
        val removed = source.deleteRecursively()
        return JsonObject().apply {
            addProperty("success", removed)
            addProperty("filename", source.name)
        }
    }

    private fun createFolder(root: File, path: String): JsonObject {
        val normalized = normalize(path)
        if (normalized.isBlank()) return unavailable("文件夹名称不能为空")
        val folder = resolve(root, normalized, allowRoot = false) ?: return unavailable("路径无效")
        val created = folder.exists() && folder.isDirectory || folder.mkdirs()
        return JsonObject().apply {
            addProperty("success", created)
            addProperty("path", normalized)
        }
    }

    private fun resolve(root: File, relativePath: String?, allowRoot: Boolean): File? {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val normalized = normalize(relativePath)
        if (normalized.isBlank()) return canonicalRoot.takeIf { allowRoot }
        val target = runCatching { File(canonicalRoot, normalized).canonicalFile }.getOrNull() ?: return null
        val inside = target.path.startsWith(canonicalRoot.path + File.separator)
        return target.takeIf { inside }
    }

    private fun normalize(path: String?): String = path.orEmpty().trim().replace('\\', '/').trim('/')

    private fun unavailable(message: String): JsonObject = JsonObject().apply {
        addProperty("success", false)
        addProperty("message", message)
    }

    private fun unavailable(message: String, code: String): JsonObject = unavailable(message).apply {
        addProperty("code", code)
    }

    private companion object {
        /** 插件专属文件夹的固定目录名：`workspace/<会话或 shared>/plugins/<插件id>/`。 */
        const val PLUGIN_DIR_NAME = "plugins"

        /** 单个插件文件上限（UTF-8 字节）。 */
        const val MAX_PLUGIN_FILE_BYTES = 2L * 1024 * 1024

        /** 单个插件的文件数量与总大小配额，避免插件把工作区写满。 */
        const val MAX_PLUGIN_FILES = 500
        const val MAX_PLUGIN_TOTAL_BYTES = 32L * 1024 * 1024
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
