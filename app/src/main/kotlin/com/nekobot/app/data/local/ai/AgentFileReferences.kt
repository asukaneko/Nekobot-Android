package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.StickerMarkers

/**
 * 将 Agent 工具发送的文件补充到最终助手消息中。
 *
 * 工具调用本身只返回文件路径，聊天 UI 需要 [File: path] 标记才能渲染文件卡片。
 * 保留模型已经生成的引用，并对同一文件去重，避免出现重复卡片。
 */
internal fun appendAgentFileReferences(
    content: String,
    references: Iterable<String>
): String {
    val normalizedReferences = references
        .map {
            it.trim().replace('\\', '/').let { value ->
                if (value.startsWith("shared:/") && !value.startsWith("shared://")) {
                    "shared://${value.removePrefix("shared:/")}"
                } else {
                    value
                }
            }
        }
        .filter { it.isNotBlank() }
        .distinct()
    if (normalizedReferences.isEmpty()) return content

    val missingReferences = normalizedReferences.filter { reference ->
        val escaped = Regex.escape(reference)
        !Regex("\\[(?:File|文件):\\s*$escaped\\s*\\]").containsMatchIn(content)
    }
    if (missingReferences.isEmpty()) return content

    val markers = missingReferences.joinToString("\n") { reference -> "[File: $reference]" }
    return if (content.isBlank()) markers else "$content\n\n$markers"
}

/**
 * 将 Agent 通过 send_sticker 发送的表情名称补进最终助手消息。
 *
 * 聊天界面把 `[名称]` 渲染成表情原图；模型已经写出同名标记时不重复追加。
 */
internal fun appendAgentStickerReferences(
    content: String,
    names: Iterable<String>
): String {
    val normalized = names
        .map { it.trim() }
        .filter { it.isNotBlank() && '[' !in it && ']' !in it }
        .distinct()
    if (normalized.isEmpty()) return content

    val missing = normalized.filterNot { name -> StickerMarkers.containsReference(content, name) }
    if (missing.isEmpty()) return content

    val markers = missing.joinToString(" ") { name -> "[$name]" }
    return if (content.isBlank()) markers else "$content\n$markers"
}
