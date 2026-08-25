package com.nekobot.app.data.local.ai

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
