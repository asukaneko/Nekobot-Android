package com.nekobot.app.data.local

import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 本地表情包图片的唯一目录入口（filesDir/stickers）。
 *
 * 仓库、归档恢复与 WebDAV 同步必须共用这里解析路径，避免目录穿越；
 * 磁盘文件名使用随机 id，防止同名表情互相覆盖。
 */
internal object LocalStickerStorage {
    const val DIR_NAME = "stickers"

    /** 支持的表情图片扩展名（与聊天内联图片解码能力保持一致）。 */
    private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

    /** 表情名称上限：过长的名称不便于在气泡中匹配，也容易误伤正文。 */
    const val MAX_NAME_LENGTH = StickerMarkers.MAX_NAME_LENGTH

    fun root(filesDir: File): File = File(filesDir, DIR_NAME).apply { mkdirs() }

    /** 在 stickers 目录内解析安全文件；越界或不存在返回 null。 */
    fun resolve(filesDir: File, fileName: String?): File? {
        val safeName = fileName?.trim().orEmpty()
        if (!SAFE_FILE_NAME.matches(safeName)) return null
        val canonicalRoot = root(filesDir).canonicalFile
        val target = runCatching { File(canonicalRoot, safeName).canonicalFile }.getOrNull() ?: return null
        if (target.path != canonicalRoot.path && !target.path.startsWith(canonicalRoot.path + File.separator)) {
            return null
        }
        return target
    }

    /** 写入一张表情图片，返回落盘文件；失败返回 null。 */
    fun write(filesDir: File, extension: String, bytes: ByteArray): File? = runCatching {
        val safeExtension = extension.lowercase(Locale.ROOT)
            .takeIf { it in SUPPORTED_EXTENSIONS } ?: "png"
        val fileName = "sticker_${UUID.randomUUID().toString().replace("-", "").take(20)}.$safeExtension"
        val target = resolve(filesDir, fileName) ?: return@runCatching null
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        target
    }.getOrNull()

    fun delete(filesDir: File, fileName: String?) {
        resolve(filesDir, fileName)?.let { file -> runCatching { file.delete() } }
    }

    /** 根据 MIME 或原文件名推断扩展名（不在白名单内回退 png）。 */
    fun extensionFor(mimeType: String?, sourceName: String?): String {
        val fromName = sourceName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in SUPPORTED_EXTENSIONS }
        if (fromName != null) return fromName
        return when (mimeType?.lowercase(Locale.ROOT)) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            else -> "png"
        }
    }

    /** 判断 MIME + 文件名是否是可作为表情导入的图片。 */
    fun isSupportedImage(mimeType: String?, fileName: String?): Boolean {
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        if (mime.startsWith("image/")) {
            // svg 属于矢量图，直接内联渲染存在脚本风险；不纳入表情包导入。
            return !mime.contains("svg")
        }
        val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return extension in SUPPORTED_EXTENSIONS
    }

    /** 从文件名推导默认表情名称（去掉扩展名，清理方括号与换行）。 */
    fun defaultNameFromFileName(fileName: String?): String {
        val base = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.', "")
            .orEmpty()
        return StickerMarkers.sanitizeName(base)
    }

    /** 将落盘文件转成可持久化的 file:// URI。 */
    fun toUri(file: File): String = Uri.fromFile(file).toString()

    /** 从持久化引用中解析出 stickers 目录内的文件。 */
    fun resolveReference(filesDir: File, reference: String?): File? {
        if (reference.isNullOrBlank()) return null
        val fileName = runCatching {
            Uri.parse(reference).path?.let(::File)?.name ?: File(reference).name
        }.getOrNull() ?: return null
        return resolve(filesDir, fileName)?.takeIf { it.isFile }
    }
}
