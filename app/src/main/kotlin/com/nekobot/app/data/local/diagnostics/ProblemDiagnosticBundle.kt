package com.nekobot.app.data.local.diagnostics

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Only explicitly selected text reaches this redactor. Basic mode does not accept raw bodies. */
internal object DiagnosticText {
    fun clean(raw: String): String = raw.take(120_000)
        .replace(Regex("(?im)^.*(?:authorization|set-cookie|cookie)\\s*[:=].*$"), "[凭据行已省略]")
        .replace(Regex("(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|token)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)"), "$1[已省略]")
        .replace(Regex("(?i)bearer\\s+[^\\s\"',;}]+"), "Bearer [已省略]")
        .replace(Regex("(?i)\\bsk-[a-z0-9_-]+"), "[密钥已省略]")
        .replace(Regex("(?i)https?://[^\\s<>\"']+"), "[网址已省略]")
        .replace(Regex("(?i)[a-z]:[\\\\/][^\\s\"'<>]+|/(?:data|storage|sdcard|home|Users)/[^\\s\"'<>]+"), "[路径已省略]")
        .replace(Regex("[A-Za-z0-9_+/=-]{40,}"), "[长标识已省略]")
        .replace(Regex("data:[^\\s]+"), "[附件已省略]")
        .let { if (raw.length > 120_000) it + "\n[正文达到上限，余下未附带]" else it }
    /** Strip credential-bearing JSON subtrees before formatting optional tool material. */
    fun json(raw: String): String = runCatching {
        fun scrub(value: JsonElement, depth: Int): JsonElement {
            require(depth < 24)
            return when {
                value.isJsonObject -> com.google.gson.JsonObject().apply {
                    value.asJsonObject.entrySet().forEach { (key, child) ->
                        if (!key.contains(Regex("(?i)key|token|secret|password|cookie|authorization|credential|image|audio|base64"))) add(key, scrub(child, depth+1))
                    }
                }
                value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(scrub(it, depth+1)) } }
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    val text = value.asString
                    val nested = if (text.trimStart().startsWith("{") || text.trimStart().startsWith("["))
                        runCatching { JsonParser.parseString(text) }.getOrNull() else null
                    com.google.gson.JsonPrimitive(if (nested == null) clean(text) else scrub(nested,depth+1).toString())
                }
                else -> value
            }
        }
        val value = scrub(JsonParser.parseString(raw.take(256_000)),0)
        clean(GsonBuilder().setPrettyPrinting().create().toJson(value))
    }.getOrElse { "[结构无法完整读取，未附带原始工具正文]" }
}

internal data class ProblemDiagnosticBundle(val files: Map<String, String>, val missing: List<String>) {
    fun zip(): ByteArray {
        require(files.keys.all { it in setOf("问题说明.txt", "诊断记录.json", "运行日志.log", "本次资料.txt", "完整请求与返回.json", "完整提示词.txt") })
        // Full requests can legitimately exceed 2 MiB. Do not silently omit or trim text.
        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes, Charsets.UTF_8).use { zip -> files.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            } }
            bytes.toByteArray()
        }
    }
}
