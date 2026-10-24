package com.nekobot.app.data.local

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.data.local.ai.CompanionImportPolicy
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy

/** A source must describe the version of the memory and message that will actually remain locally. */
internal class PortableMemorySourceIntegrity(private val sql: PortableImportSql) {
    val conflictingMemoryIds = linkedSetOf<String>()
    private val incomingMemories = linkedMapOf<String, JsonObject>()
    private val memories = linkedMapOf<String, JsonObject>()
    private val scopes = linkedMapOf<String, JsonObject>()
    private val sourceWrites = linkedMapOf<Pair<String, String>, JsonObject>()
    private val verifiedWrites = linkedSetOf<String>()
    private val operationWrites = mutableListOf<JsonObject>()

    fun record(table: String, incoming: JsonObject, effective: JsonObject, writes: Boolean) {
        when (table) {
            "companion_applied_ops" -> if (writes) operationWrites += effective
            "companion_memories" -> {
                val id = effective.text("id") ?: return
                incomingMemories[id] = incoming
                memories[id] = effective
                if (writes && effective.text("provenance") == CompanionImportPolicy.IMPORTED_VERIFIED &&
                    effective["manual"]?.asInt != 1) verifiedWrites += id
            }
            "companion_scopes" -> effective.text("id")?.let { scopes[it] = effective }
            "companion_sources" -> if (writes) {
                val memory = effective.text("memoryId") ?: return
                val message = effective.text("messageId") ?: return
                sourceWrites[memory to message] = effective
            }
        }
    }

    fun validate(rows: PortableRowVisitor, effectiveRow: (PortableIncomingRow) -> JsonObject,
                 observe: (String, JsonObject?) -> Unit, issues: MutableSet<String>,
                 requiredTables: MutableSet<String>, checkpoint: () -> Unit) {
        for (operation in operationWrites) {
            checkpoint()
            val scopeId = operation.text("scopeId")
            val memoryId = operation.text("memoryId") ?: continue // retract/exclusion records have no resulting memory.
            val memory = memories[memoryId] ?: find("companion_memories", memoryId)
            observe("memory-operation-target:$memoryId", memory)
            if (scopeId == null || memory?.text("scopeId") != scopeId)
                issues += "记忆操作进度与记忆不属于同一会话，请同时核对记忆及其归属后重新预览"
        }
        if (sourceWrites.isEmpty() && verifiedWrites.isEmpty()) return
        val sources = linkedMapOf<Pair<String, String>, JsonObject>()
        val affectedMemories = (sourceWrites.keys.map { it.first } + verifiedWrites).toSet()
        for (id in affectedMemories) {
            checkpoint()
            sql.query("SELECT * FROM companion_sources WHERE memoryId=?", listOf(id)).forEach {
                sources[id to requireNotNull(it.text("messageId"))] = it
            }
        }
        sources.putAll(sourceWrites)
        for (id in verifiedWrites) if (sources.keys.none { it.first == id }) {
            requiredTables += "companion_sources"
            issues += "已核验的迁入记忆缺少原话来源，请同时选择记忆来源，或保留原核验状态"
        }
        for (id in affectedMemories) {
            val memory = memories[id] ?: find("companion_memories", id)?.also { memories[id] = it }
            observe("memory-source-memory:$id", memory)
            val incoming = incomingMemories[id]
            if (listOf("content", "subject", "scopeId").any { column ->
                    incoming?.text(column) == null || memory?.text(column) != incoming.text(column)
                }) {
                conflictingMemoryIds += id
                issues += "记忆正文、主体或归属与新来源不属于同一版本，请同时选择对应记忆字段，或取消本次来源导入"
            }
        }
        val messageIds = sources.keys.mapTo(linkedSetOf()) { it.second }
        val messages = linkedMapOf<String, JsonObject>()
        val sessions = linkedMapOf<String, JsonObject>()
        // Read only referenced messages on this second streaming pass, not the entire chat in memory.
        rows { incoming ->
            checkpoint()
            if (incoming.table == "local_sessions") effectiveRow(incoming).let { row -> row.text("id")?.let { sessions[it] = row } }
            if (incoming.table == "local_messages" && incoming.values.text("id") in messageIds) {
                val row = effectiveRow(incoming)
                messages[requireNotNull(row.text("id"))] = row
            }
        }
        for (id in messageIds) {
            checkpoint()
            if (id !in messages) find("local_messages", id)?.let { messages[id] = it }
            observe("memory-source-message:$id", messages[id])
        }
        for ((key, source) in sources) {
            checkpoint()
            val memory = memories[key.first] ?: find("companion_memories", key.first)?.also { memories[key.first] = it }
            val scopeId = memory?.text("scopeId")
            val scope = scopeId?.let { scopes[it] ?: find("companion_scopes", it)?.also { row -> scopes[it] = row } }
            observe("memory-source-scope:$scopeId", scope)
            observe("memory-source:${key.first}:${key.second}", source)
            val message = messages[key.second]
            val quote = source.text("quote")
            val role = message?.text("role")
            val createdAt = message?.text("created_at")
            val content = message?.text("content")
            val sessionId = scope?.text("sessionId")
            val session = sessionId?.let { sessions[it] ?: find("local_sessions", it) }
            observe("memory-source-session:$sessionId", session)
            val snapshot = source.text("messageSnapshot")
            val v2 = session?.get("companion_mode")?.asInt == 1 && snapshot != null
            // 新版快照证明的是当时的完整原话，保留选择后允许消息被编辑或软删除。
            // 非单会话和没有快照的旧备份继续使用原有逐字来源校验。
            val evidence = if (v2) snapshot else content
            val valid = memory != null && scope != null && message != null && sessionId == message.text("session_id") &&
                (v2 || message["deleted"]?.asInt == 0) && role != null && role == source.text("role") &&
                createdAt != null && evidence != null && (v2 || (!quote.isNullOrBlank() && evidence.contains(quote))) &&
                CompanionMemoryPolicy.fingerprint(key.second, role, createdAt, message.text("timestamp"), evidence, false) == source.text("fingerprint")
            if (!valid) issues += "记忆原话来源与当前聊天不一致，请核对会话、原文和来源版本后重新预览"
        }
    }

    /** These are the user's effective flags, including kept local values, after every source is checked. */
    fun memoryStates(): Map<String, JsonObject> =
        (sourceWrites.keys.map { it.first } + verifiedWrites).distinct().mapNotNull { id ->
            memories[id]?.let { memory -> id to JsonObject().apply {
                listOf("valid", "current").filter(memory::has).forEach { add(it, memory[it]) }
            } }
        }.filter { it.second.size() > 0 }.toMap()

    private fun find(table: String, id: String): JsonObject? =
        sql.query("SELECT * FROM `${table}` WHERE id=? LIMIT 1", listOf(id)).firstOrNull()

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless(JsonElement::isJsonNull)?.asString
}
