package com.nekobot.app.data.local

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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

    private fun move(sourceRoot: File, targetRoot: File, path: String): JsonObject {
        val source = resolve(sourceRoot, path, allowRoot = false)
            ?.takeIf(File::exists)
            ?: return unavailable("源文件不存在")
        val target = resolve(targetRoot, source.name, allowRoot = false)
            ?: return unavailable("目标路径无效")
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
