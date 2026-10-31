package com.nekobot.app.data.local.diagnostics

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Diagnostic records contain only bounded, allowlisted metadata, never message bodies. */
internal data class DiagnosticEvent(val time: Long, val kind: String, val values: Map<String, String>)
internal data class DiagnosticOperation(
    val id: String, val profile: String, val session: String, val turn: String,
    val type: String, val createdAt: Long, val events: List<DiagnosticEvent> = emptyList(), val dropped: Int = 0
)

internal fun diagnosticRef(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
/** Match NekobotDatabase.get: preferences store a profile name, Room owns a .db filename. */
internal fun diagnosticProfileRef(profile: String): String = diagnosticRef(if (profile.endsWith(".db")) profile else "$profile.db")

internal object DiagnosticMetadata {
    private val numeric = setOf("rounds", "max_rounds", "tokens", "max_tokens", "per_call", "limit", "room",
        "max_context", "output_reserve", "returned", "candidates", "duplicates", "charged", "query_chars",
        "tools", "input_tokens", "output_tokens", "duration_ms", "variant", "new_rows", "updated_rows",
        "kept", "same", "files", "categories", "selected_fields", "selected_records", "source_choices", "history_chars", "http_status")
    private val flags = setOf("exhausted", "must_answer", "truncated", "recovery_pending", "committed", "cancelled")
    private val refs = setOf("reply_ref", "body_ref", "history_ref", "model_ref", "source_ref", "context_ref", "question_ref")
    private val symbols = mapOf(
        "status" to setOf("found", "empty", "truncated", "quota_exhausted", "error", "started", "completed", "cancelled", "pending"),
        "reason" to setOf("none", "rounds", "tokens", "context", "unprepared", "interrupted", "exception", "refresh", "unknown"),
        "tool" to setOf("agent_recall_search", "agent_history_search", "agent_history_read", "companion_memory_read", "companion_memory_search", "other"),
        "stage" to setOf("inspect", "commit", "model", "recall", "save"),
        "finish_reason" to setOf("stop", "tool_calls", "length", "content_filter", "cancelled", "error"))
    fun clean(values: Map<String, Any?>): Map<String, String> = buildMap {
        values.forEach { (key, value) ->
            when {
                key in numeric && value is Number && value.toLong() >= 0 -> put(key, value.toLong().toString())
                key in flags && value is Boolean -> put(key, value.toString())
                key in refs && value is String && value.matches(Regex("[a-f0-9]{64}")) -> put(key, value)
                value is String && value in symbols[key].orEmpty() -> put(key, value)
            }
        }
    }
}

/** One file per attempt; regeneration never replaces another attempt's evidence. No schema change. */
internal class ProblemDiagnosticStore(private val directory: File, private val maxRecords: Int = 200,
    private val maxBytes: Long = 10L * 1024 * 1024, private val maxEvents: Int = 160) {
    private val gson = Gson()
    fun append(id: String, profile: String, session: String, turn: String, type: String, kind: String,
               values: Map<String, Any?>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        require(type in setOf("reply", "import"))
        require(kind in setOf("recall_prepare", "recall_result", "recall_context", "model_start", "model_result", "reply_saved",
            "import_start", "import_result", "import_end"))
        directory.mkdirs()
        val file = File(directory, diagnosticRef(id) + ".json")
        val previous = read(file)
        val p = diagnosticProfileRef(profile); val s = diagnosticRef(session); val t = diagnosticRef(turn)
        require(previous == null || (previous.profile == p && previous.session == s && previous.turn == t && previous.type == type))
        val old = previous ?: DiagnosticOperation(diagnosticRef(id), p, s, t, type, now)
        val event = DiagnosticEvent(now, kind, DiagnosticMetadata.clean(values))
        val events = old.events + event
        val kept = if (events.size <= maxEvents) events else listOf(events.first()) + events.takeLast(maxEvents - 1)
        val next = old.copy(events = kept, dropped = old.dropped + events.size - kept.size)
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(gson.toJson(next), Charsets.UTF_8)
        try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { temp.delete() }
        prune()
    }
    fun list(profile: String): List<DiagnosticOperation> = synchronized(lock) {
        files().mapNotNull(::read).filter { it.profile == diagnosticProfileRef(profile) }
            .sortedByDescending { it.events.lastOrNull()?.time ?: it.createdAt }
    }
    private fun files() = directory.listFiles().orEmpty().filter {
        it.name.matches(Regex("[a-f0-9]{64}\\.json")) && it.isFile && !Files.isSymbolicLink(it.toPath())
    }
    private fun read(file: File): DiagnosticOperation? = runCatching {
        if (!file.isFile || file.length() > 256 * 1024 || Files.isSymbolicLink(file.toPath())) return@runCatching null
        val op = gson.fromJson(file.readText(Charsets.UTF_8), DiagnosticOperation::class.java)
        require(op.type in setOf("reply", "import") && listOf(op.id,op.profile,op.session,op.turn).all { it.matches(Regex("[a-f0-9]{64}")) })
        op.copy(events = op.events.take(maxEvents).map { event ->
            event.copy(values = DiagnosticMetadata.clean(event.values.mapValues { (_,value) ->
                value.toLongOrNull() ?: when(value) { "true" -> true; "false" -> false; else -> value }
            }))
        })
    }.getOrNull()
    private fun prune() {
        val ordered = files().sortedByDescending { it.lastModified() }
        var bytes = 0L
        ordered.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= maxRecords || bytes > maxBytes) file.delete()
        }
    }
    companion object { private val lock = Any() }
}
