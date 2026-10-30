package com.nekobot.app.ui.screens.chat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * 待发送附件的暂存与持久化。
 *
 * 选择/分享进来的图片与文件只复制到应用私有目录（暂存），真正上传到工作区发生在点击发送时；
 * 记录按会话保存在 SharedPreferences 中，因此退出会话重进后候选附件依然保留。
 */
internal object PendingAttachmentStore {
    private const val PREFS_NAME = "pending_chat_attachments"
    private const val KEY_PREFIX = "session_"
    private const val STAGING_DIR = "pending_attachments"
    private const val SOURCE_PENDING = "pending"

    private val gson = Gson()

    private data class Entry(
        val name: String = "",
        val type: String = "",
        val path: String = "",
        val size: Long = 0L
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(sessionId: String) = KEY_PREFIX + sessionId

    /** 会话暂存目录：filesDir/pending_attachments/<session> */
    private fun sessionDir(context: Context, sessionId: String): File {
        val safeId = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.filesDir, STAGING_DIR), safeId).apply { mkdirs() }
    }

    private fun stagingRoot(context: Context): File = File(context.filesDir, STAGING_DIR)

    /** 读取会话的候选附件（已不存在的暂存文件会被丢弃） */
    fun load(context: Context, sessionId: String): List<Map<String, Any>> {
        val raw = runCatching { prefs(context).getString(key(sessionId), null) }.getOrNull() ?: return emptyList()
        val entries = runCatching {
            gson.fromJson<List<Entry>>(raw, object : TypeToken<List<Entry>>() {}.type)
        }.getOrNull().orEmpty()
        return entries.mapNotNull { entry ->
            if (entry.path.isBlank() || !File(entry.path).isFile) return@mapNotNull null
            buildMap<String, Any> {
                put("name", entry.name)
                put("type", entry.type)
                put("path", entry.path)
                put("size", entry.size)
                put("source", SOURCE_PENDING)
            }
        }
    }

    /** 保存会话的候选附件 */
    fun save(context: Context, sessionId: String, attachments: List<Map<String, Any>>) {
        val entries = attachments.mapNotNull { attachment ->
            val path = attachment["path"] as? String ?: return@mapNotNull null
            Entry(
                name = attachment["name"] as? String ?: File(path).name,
                type = attachment["type"] as? String ?: "",
                path = path,
                size = (attachment["size"] as? Number)?.toLong() ?: 0L
            )
        }
        runCatching {
            prefs(context).edit().putString(key(sessionId), gson.toJson(entries)).apply()
        }
    }

    /** 清空会话的候选附件记录 */
    fun clear(context: Context, sessionId: String) {
        runCatching { prefs(context).edit().remove(key(sessionId)).apply() }
    }

    /** 删除暂存文件（只允许删除暂存目录内的路径） */
    fun deleteStaged(context: Context, path: String?) {
        val file = path?.takeIf { it.isNotBlank() }?.let { File(it) } ?: return
        val root = stagingRoot(context).absolutePath + File.separator
        if (!file.absolutePath.startsWith(root)) return
        runCatching { file.delete() }
    }

    /** 复制字节到暂存目录，返回与上传后结构一致的附件描述 */
    fun stage(context: Context, sessionId: String, name: String, mime: String, bytes: ByteArray): Map<String, Any>? =
        stageInternal(context, sessionId, name, mime) { file -> file.writeBytes(bytes) }

    /** 把已有文件复制到暂存目录（用于系统分享进来的文件） */
    fun stageFromFile(context: Context, sessionId: String, source: File, name: String, mime: String): Map<String, Any>? =
        stageInternal(context, sessionId, name, mime) { file -> source.copyTo(file, overwrite = true) }

    private fun stageInternal(
        context: Context,
        sessionId: String,
        name: String,
        mime: String,
        write: (File) -> Unit
    ): Map<String, Any>? = runCatching {
        val displayName = name.takeIf { it.isNotBlank() } ?: "attachment"
        val safeName = displayName.take(80).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val dest = File(
            sessionDir(context, sessionId),
            "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_$safeName"
        )
        write(dest)
        buildMap<String, Any> {
            put("name", displayName)
            put("type", mime)
            put("path", dest.absolutePath)
            put("size", dest.length())
            put("source", SOURCE_PENDING)
        }
    }.getOrNull()
}
