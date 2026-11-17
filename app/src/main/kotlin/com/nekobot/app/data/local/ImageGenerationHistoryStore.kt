package com.nekobot.app.data.local

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片生成实验室的一条历史记录。
 *
 * [fileName] 指向应用私有目录下的图片副本（不是 cacheDir），
 * 因此清理缓存后历史图片仍然可以查看。
 */
data class ImageHistoryEntry(
    val id: String,
    val prompt: String,
    val size: String,
    val modelName: String,
    val mimeType: String,
    val fileName: String,
    val createdAt: Long
)

/**
 * 图片生成实验室的历史记录存储。
 *
 * 元数据以 JSON 存进独立的 SharedPreferences，图片复制到 filesDir/image_history；
 * 只保留最近 [MAX_ENTRIES] 条，超出的最旧记录连同图片文件一起删除。
 */
class ImageGenerationHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val dir = File(appContext.filesDir, DIR_NAME)

    /** 读取历史（最新的在前），顺手清掉图片文件已丢失的记录。 */
    suspend fun list(): List<ImageHistoryEntry> = withContext(Dispatchers.IO) {
        val stored = decode(prefs.getString(KEY_ENTRIES, null))
        val existing = stored.filter { File(dir, it.fileName).isFile }
        if (existing.size != stored.size) persist(existing)
        existing
    }

    /** 把生成结果复制进历史目录；源文件不可读时返回 null，不影响本次生成结果展示。 */
    suspend fun record(
        sourceUri: String,
        prompt: String,
        size: String,
        mimeType: String,
        modelName: String
    ): ImageHistoryEntry? = withContext(Dispatchers.IO) {
        val source = sourceUri.toLocalFileOrNull()?.takeIf { it.isFile } ?: return@withContext null
        val id = java.util.UUID.randomUUID().toString()
        val target = File(dir, "$id.${extensionFor(mimeType)}")
        val copied = runCatching {
            dir.mkdirs()
            source.copyTo(target, overwrite = true)
        }.isSuccess
        if (!copied) return@withContext null
        val entry = ImageHistoryEntry(
            id = id,
            prompt = prompt,
            size = size,
            modelName = modelName,
            mimeType = mimeType,
            fileName = target.name,
            createdAt = System.currentTimeMillis()
        )
        val current = decode(prefs.getString(KEY_ENTRIES, null)).filter { File(dir, it.fileName).isFile }
        val (kept, removed) = trimHistory(listOf(entry) + current, MAX_ENTRIES)
        persist(kept)
        removed.forEach { File(dir, it.fileName).delete() }
        entry
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val stored = decode(prefs.getString(KEY_ENTRIES, null))
        stored.firstOrNull { it.id == id }?.let { File(dir, it.fileName).delete() }
        persist(stored.filterNot { it.id == id })
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        decode(prefs.getString(KEY_ENTRIES, null)).forEach { File(dir, it.fileName).delete() }
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    /** 历史图片的 file:// URI，可直接交给图片组件预览。 */
    fun uriOf(entry: ImageHistoryEntry): String = Uri.fromFile(File(dir, entry.fileName)).toString()

    private fun persist(entries: List<ImageHistoryEntry>) {
        prefs.edit().putString(KEY_ENTRIES, encode(entries)).apply()
    }

    companion object {
        /** 历史记录上限；超出后删除最旧的记录与其图片。 */
        const val MAX_ENTRIES = 100
        private const val PREF_NAME = "image_generation_history"
        private const val KEY_ENTRIES = "entries"
        private const val DIR_NAME = "image_history"
        private val gson = Gson()

        internal fun encode(entries: List<ImageHistoryEntry>): String = gson.toJson(entries)

        internal fun decode(raw: String?): List<ImageHistoryEntry> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                gson.fromJson<List<ImageHistoryEntry>>(raw, object : TypeToken<List<ImageHistoryEntry>>() {}.type)
            }.getOrNull().orEmpty().filter { it.id.isNotBlank() && it.fileName.isNotBlank() }
        }

        /** 按创建时间保留最新 [max] 条，返回（保留、被淘汰）。 */
        internal fun trimHistory(
            entries: List<ImageHistoryEntry>,
            max: Int
        ): Pair<List<ImageHistoryEntry>, List<ImageHistoryEntry>> {
            val sorted = entries.sortedByDescending { it.createdAt }
            return sorted.take(max.coerceAtLeast(0)) to sorted.drop(max.coerceAtLeast(0))
        }

        internal fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }

        private fun String.toLocalFileOrNull(): File? = runCatching {
            val uri = Uri.parse(this)
            when (uri.scheme) {
                null, "file" -> File(uri.path ?: return null)
                else -> null
            }
        }.getOrNull()
    }
}
