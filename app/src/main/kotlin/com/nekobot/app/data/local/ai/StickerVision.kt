package com.nekobot.app.data.local.ai

import android.util.Base64
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalStickerStorage
import com.nekobot.app.data.local.StickerMarkers
import java.io.File
import java.util.Locale

/** 用户消息中最多附加的表情原图数量：避免一次把上下文刷满 base64。 */
private const val MAX_USER_STICKER_IMAGES = 4

/** 单张表情图片转 data URI 的大小上限：超出时跳过，不阻塞对话。 */
private const val MAX_STICKER_IMAGE_BYTES = 8L * 1024 * 1024

/** 表情图片文件 → base64 data URI；文件不存在或过大时返回 null。 */
internal fun stickerFileToDataUri(file: File): String? = runCatching {
    if (!file.isFile || file.length() > MAX_STICKER_IMAGE_BYTES) return null
    val mime = when (file.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }
    val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    "data:$mime;base64,$base64"
}.getOrNull()

/** 按表情名解析落盘文件；表情不存在或文件已丢失时返回 null。 */
internal suspend fun findStickerFileByName(name: String): File? {
    val filesDir = ServiceContainer.appContext?.filesDir ?: return null
    val sticker = runCatching { ServiceContainer.unified.findStickerByName(name) }.getOrNull() ?: return null
    return LocalStickerStorage.resolveReference(filesDir, sticker.filePath)
}

/**
 * 解析用户消息中引用的表情原图（data URI，按引用顺序去重）。
 *
 * 仅当当前对话模型支持视觉时由管线调用：把用户发的 `[名称]` 还原成真实表情画面，
 * 名称匹配不到表情或图片文件丢失时静默跳过，保持原有纯文本行为。
 */
internal suspend fun resolveUserStickerImages(
    content: String,
    existing: Collection<String> = emptyList()
): List<String> {
    if ('[' !in content) return emptyList()
    val names = StickerMarkers.candidateNames(content).take(MAX_USER_STICKER_IMAGES)
    if (names.isEmpty()) return emptyList()
    val resolved = mutableListOf<String>()
    for (name in names) {
        val file = findStickerFileByName(name) ?: continue
        val dataUri = stickerFileToDataUri(file) ?: continue
        if (dataUri in existing || dataUri in resolved) continue
        resolved += dataUri
    }
    return resolved
}
