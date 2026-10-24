package com.nekobot.app.data.local.diagnostics

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Full text stays in the app's private no-backup directory. Export requires the existing
 * per-export includeText choice. Capture at send time, never reconstruct from today's settings.
 * Retention removes whole old attempts; it never silently cuts a prompt or a response.
 */
internal class DiagnosticTraceStore(private val directory: File, private val maxAttempts: Int = 30,
    private val maxBytes: Long = 256L * 1024 * 1024) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun append(operation: String, profile: String, session: String, turn: String, kind: String,
               data: JsonElement) = synchronized(lock) {
        require(operation.matches(Regex("[a-f0-9]{64}")))
        require(kind.matches(Regex("[a-z_]+")))
        directory.mkdirs()
        val folder = File(directory, operation)
        require(!Files.isSymbolicLink(folder.toPath()))
        folder.mkdirs()
        val index = entries(folder).maxOfOrNull { it.name.substringBefore('-').toInt() }?.plus(1) ?: 1
        val event = JsonObject().apply {
            addProperty("index", index); addProperty("kind", kind); addProperty("time", System.currentTimeMillis())
            addProperty("operation_ref", operation); addProperty("profile_ref", diagnosticProfileRef(profile))
            addProperty("session_ref", diagnosticRef(session)); addProperty("turn_ref", diagnosticRef(turn))
            add("data", data)
        }
        val target = File(folder, "%06d-%s.json.gz".format(java.util.Locale.ROOT, index, kind))
        val temp = File(folder, "${target.name}.tmp")
        try {
            GZIPOutputStream(temp.outputStream()).bufferedWriter(Charsets.UTF_8).use { gson.toJson(event, it) }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
        folder.setLastModified(System.currentTimeMillis())
        // Keep in-flight attempts and this attempt. Reclaim completed attempts as a unit.
        if (kind == "saved_reply") File(folder, "complete").writeText("complete")
        prune(operation)
    }

    fun read(operation: String, profile: String, session: String): List<JsonObject> = synchronized(lock) {
        require(operation.matches(Regex("[a-f0-9]{64}")))
        val folder = File(directory, operation)
        require(!Files.isSymbolicLink(folder.toPath()))
        entries(folder).map { file ->
            val event = GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).use {
                JsonParser.parseReader(it).asJsonObject
            }
            require(event["operation_ref"].asString == operation &&
                event["profile_ref"].asString == diagnosticProfileRef(profile) &&
                event["session_ref"].asString == diagnosticRef(session))
            event
        }
    }

    private fun entries(folder: File) = folder.listFiles().orEmpty().filter {
        it.name.matches(Regex("[0-9]+-[a-z_]+\\.json\\.gz")) && it.isFile && !Files.isSymbolicLink(it.toPath())
    }.sortedBy { it.name.substringBefore('-').toInt() }

    private fun prune(current: String) {
        val folders = directory.listFiles().orEmpty().filter {
            it.name.matches(Regex("[a-f0-9]{64}")) && it.isDirectory && !Files.isSymbolicLink(it.toPath())
        }.sortedByDescending { it.lastModified() }
        var bytes = folders.sumOf { folder -> folder.listFiles().orEmpty().sumOf { it.length() } }
        var count = folders.size
        for (folder in folders.asReversed()) {
            if (count <= maxAttempts && bytes <= maxBytes) break
            if (folder.name == current) continue
            // Interrupted process traces also age out, without being labelled complete.
            if (!File(folder, "complete").exists() && System.currentTimeMillis() - folder.lastModified() < 7L*24*60*60*1000) continue
            require(folder.canonicalFile.parentFile == directory.canonicalFile)
            val files = folder.listFiles().orEmpty()
            if (files.any { !it.isFile || Files.isSymbolicLink(it.toPath()) }) continue
            val size = files.sumOf { it.length() }
            files.forEach { check(it.delete()) }
            check(folder.delete())
            bytes -= size; count--
        }
    }

    companion object { private val lock = Any() }
}

/** Precise credential redaction; preserves prompt lengths, message IDs and token settings. */
internal object DiagnosticTraceText {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val secretKey = Regex("(?i)^(?:authorization|proxy.authorization|cookies?|set.cookie|api.?key|access.?token|refresh.?token|id.?token|password|passwd|client.?secret|secret|credentials?|token)$")

    fun clean(text: String, secrets: Collection<String> = emptyList()): String {
        var result = text
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { result = result.replace(it, "[凭据已脱敏]") }
        return result
            .replace(Regex("(?i)bearer\\s+[^\\s\"',;}]+"), "Bearer [凭据已脱敏]")
            .replace(Regex("(?i)\\bsk-[a-z0-9_-]+"), "[密钥已脱敏]")
            .replace(Regex("(?im)^\\s*(?:authorization|proxy-authorization|cookie|set-cookie)\\s*[:=].*$"), "[凭据行已脱敏]")
            .replace(Regex("(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|client[_-]?secret)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)"), "$1[凭据已脱敏]")
            .replace(Regex("(?i)([?&](?:key|api_key|token|access_token|signature|sig|password)=)[^&#\\s\"']+"), "$1[凭据已脱敏]")
            .replace(Regex("(?i)(https?://)[^/\\s:@]+:[^/\\s@]+@"), "$1[凭据已脱敏]@")
            .replace(Regex("data:[^\\s\"']+"), "[二进制附件已省略]")
    }

    fun json(value: Any?, secrets: Collection<String> = emptyList()): JsonElement {
        fun scrub(node: JsonElement): JsonElement = when {
            node.isJsonObject -> JsonObject().apply {
                node.asJsonObject.entrySet().forEach { (key, child) ->
                    if (secretKey.matches(key)) addProperty(key, "[凭据已脱敏]")
                    else if (key in setOf("b64_json", "base64", "image_bytes", "audio_bytes", "inlineData", "inline_data", "input_audio", "encrypted_content", "thinking_signature", "thinkingSignature", "signature")) {
                        addProperty(key, "[二进制或签名已省略]")
                    } else add(key, scrub(child))
                }
            }
            node.isJsonArray -> com.google.gson.JsonArray().apply { node.asJsonArray.forEach { add(scrub(it)) } }
            node.isJsonPrimitive && node.asJsonPrimitive.isString -> {
                val raw = node.asString
                val nested = if (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("["))
                    runCatching { JsonParser.parseString(raw) }.getOrNull() else null
                com.google.gson.JsonPrimitive(if (nested != null) {
                    val safe = scrub(nested)
                    if (safe == nested) raw else safe.toString()
                } else clean(raw, secrets))
            }
            else -> node
        }
        return scrub(if (value is JsonElement) value else gson.toJsonTree(value))
    }

    fun response(raw: String, secrets: Collection<String>): String {
        val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()
        if (parsed != null) return json(parsed, secrets).toString()
        // Rejoin each streaming text field before redaction: a credential may span chunks.
        // Only fields requiring redaction are coalesced, and their complete safe text is kept.
        data class Fragment(val text: String, val replace: (String) -> Unit)
        val groups = linkedMapOf<String, MutableList<Fragment>>()
        val lines = raw.lines()
        val events = lines.map { line ->
            val data = line.removePrefix("data:").trimStart()
            if (line.startsWith("data:") && data != "[DONE]") runCatching { JsonParser.parseString(data) }.getOrNull()
            else null
        }
        fun collect(node: JsonElement, path: String) {
            if (node.isJsonArray) node.asJsonArray.forEachIndexed { i, child -> collect(child, "$path[$i]") }
            if (node.isJsonObject) node.asJsonObject.entrySet().forEach { (key, child) ->
                if (child.isJsonPrimitive && child.asJsonPrimitive.isString &&
                    key in setOf("content", "text", "thinking", "reasoning_content", "arguments", "partial_json")) {
                    groups.getOrPut("$path.$key") { mutableListOf() }.add(Fragment(child.asString) { node.asJsonObject.addProperty(key, it) })
                } else collect(child, "$path.$key")
            }
        }
        events.filterNotNull().forEach { collect(it, "") }
        groups.values.forEach { fragments ->
            val full = fragments.joinToString("") { it.text }
            val nested = runCatching { JsonParser.parseString(full) }.getOrNull()
            val safe = if (nested?.isJsonObject == true || nested?.isJsonArray == true) {
                val cleaned = json(nested, secrets)
                if (cleaned == nested) full else cleaned.toString()
            } else clean(full, secrets)
            if (safe != full) {
                fragments.first().replace(safe)
                fragments.drop(1).forEach { it.replace("") }
            }
        }
        return lines.mapIndexed { i, line ->
            if (line.startsWith("data:")) {
                val event = events[i]
                if (event == null) clean(line, secrets) else "data: ${json(event, secrets)}"
            } else clean(line, secrets)
        }.joinToString("\n")
    }
}

internal class DiagnosticTraceSession(private val store: DiagnosticTraceStore?, attempt: String,
    private val profile: String, private val session: String, private val turn: String,
    private val onLoss: () -> Unit = {}) {
    val operation = diagnosticRef(attempt)
    fun record(kind: String, data: Any?, secrets: Collection<String> = emptyList()) {
        val target = store ?: return
        runCatching { target.append(operation, profile, session, turn, kind, DiagnosticTraceText.json(data, secrets)) }
            .onFailure { onLoss() }
    }
    fun context(phase: String) = DiagnosticTraceContext(this, phase)
}

internal class DiagnosticTraceContext(val session: DiagnosticTraceSession, val phase: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiagnosticTraceContext>
    fun request(metadata: Map<String, Any?>, payload: JsonElement, secrets: Collection<String>): DiagnosticHttpTrace {
        val id = UUID.randomUUID().toString()
        session.record("http_request", metadata + mapOf("request_id" to id, "phase" to phase, "payload" to payload), secrets)
        return DiagnosticHttpTrace(session, id, secrets)
    }
}

internal class DiagnosticHttpTrace(private val session: DiagnosticTraceSession, private val id: String,
    private val secrets: Collection<String>) {
    private var finished = false
    @Synchronized fun finish(status: Int?, body: String, complete: Boolean, error: String? = null) {
        if (finished) return
        finished = true
        session.record("http_response", mapOf("request_id" to id, "status" to status,
            "complete" to complete,
            "provider_end_marker" to (body.contains("data: [DONE]") ||
                Regex("\"type\"\\s*:\\s*\"(?:message_stop|response.completed)\"").containsMatchIn(body)),
            "body" to DiagnosticTraceText.response(body, secrets), "error" to error), secrets)
    }
}
