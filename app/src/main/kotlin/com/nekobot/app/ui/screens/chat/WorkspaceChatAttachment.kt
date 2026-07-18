package com.nekobot.app.ui.screens.chat

import com.google.gson.JsonElement

/**
 * 将工作区上传结果转换为聊天附件。
 *
 * 远程接口返回 `filename/path/mime_type`，本地实现返回 `name/path/mime_type`；
 * 这里统一两种格式，并保留远程服务端路径供 WebCallbacks 解析图片。
 */
internal fun buildWorkspaceChatAttachment(
    uploadResult: JsonElement?,
    sessionId: String,
    originalName: String,
    fallbackMime: String,
    localPath: String? = null
): Map<String, Any> {
    val result = uploadResult
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
    val uploadedName = result.stringValue("filename", "name")
        ?.takeIf { it.isNotBlank() }
        ?: originalName
    val mime = result.stringValue("mime_type", "type")
        ?.takeIf { it.isNotBlank() }
        ?: fallbackMime
    val serverPath = result.stringValue("path")
        ?.takeIf { it.isNotBlank() }
    val serverUrl = result.stringValue("url")
        ?.takeIf { it.isNotBlank() }
    val attachmentPath = localPath
        ?: serverPath
        ?: "/api/sessions/$sessionId/workspace/files/$uploadedName"

    return buildMap {
        put("name", uploadedName)
        put("type", mime)
        put("path", attachmentPath)
        put("url", serverUrl ?: attachmentPath)
        put("source", if (localPath != null) "local" else "web")
        result.longValue("size")?.let { put("size", it) }
    }
}

private fun com.google.gson.JsonObject?.stringValue(vararg keys: String): String? {
    val source = this ?: return null
    for (key in keys) {
        val value = source.get(key)?.takeUnless { it.isJsonNull } ?: continue
        val text = runCatching { value.asString }.getOrNull()
        if (text != null) return text
    }
    return null
}

private fun com.google.gson.JsonObject?.longValue(key: String): Long? {
    val value = this?.get(key)?.takeUnless { it.isJsonNull } ?: return null
    return runCatching { value.asLong }.getOrNull()
}
