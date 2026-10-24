package com.nekobot.app.data.local

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Public portable fields and device-only rollback references deliberately have different roles. */
internal object PortableImportSettingsPolicy {
    val fileDirectories = mapOf("custom_font_file" to "fonts", "custom_chat_background_file" to "chat_backgrounds")

    fun validate(before: JsonObject, incoming: JsonObject) {
        require(incoming.keySet().all { !it.startsWith('_') && before.has(it) }) { "应用设置包含当前版本不支持的字段" }
    }

    fun fileName(incoming: JsonObject, key: String): String? {
        val value = incoming[key]?.takeUnless(JsonElement::isJsonNull) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "设置中的文件名无效" }
        return value.asString.also {
            require(it.isNotBlank() && it !in setOf(".", "..") && !it.contains('/') && !it.contains('\\') &&
                !it.contains(':') && !it.contains('\u0000')) { "设置中的文件名无效" }
        }
    }

    fun apply(before: JsonObject, incoming: JsonObject, selected: Set<String>, restore: (JsonObject) -> Unit,
              font: (String?) -> Unit, background: (String?) -> Unit) {
        validate(before, incoming)
        require(selected.all(incoming::has)) { "设置选择已失效，请重新预览" }
        val merged = before.deepCopy()
        selected.forEach { merged.add(it, incoming[it]) }
        restore(merged)
        // The legacy full restore reconstructs paths from filenames. Preserve exact device URIs otherwise.
        if ("custom_font_file" !in selected) font(before["_font_path"]?.takeUnless(JsonElement::isJsonNull)?.asString)
        if ("custom_chat_background_file" !in selected) background(before["_background_path"]?.takeUnless(JsonElement::isJsonNull)?.asString)
    }
}
