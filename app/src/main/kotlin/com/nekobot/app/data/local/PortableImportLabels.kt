package com.nekobot.app.data.local

import com.google.gson.JsonObject

/** Names are presentation metadata only; matching and writes continue to use stable IDs. */
data class PortableImportOwner(val character: String = "", val session: String = "", val shared: Boolean = false)

internal class PortableImportLabels(
    private val sql: PortableImportSql,
    private val incoming: Map<String, Map<String, JsonObject>> = emptyMap()
) {
    private val cache = mutableMapOf<Pair<String, String>, JsonObject?>()
    fun reset() { cache.clear() }
    private fun row(table: String, id: String?): JsonObject? {
        if (id.isNullOrBlank()) return null
        val key = table to id
        if (!cache.containsKey(key)) cache[key] = sql.query("SELECT * FROM `$table` WHERE id=? LIMIT 1", listOf(id)).firstOrNull()
            ?: incoming[table]?.get(id)
        return cache[key]
    }
    private fun JsonObject.text(vararg fields: String): String = fields.firstNotNullOfOrNull { field ->
        get(field)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
    }.orEmpty()
    fun owner(table: String, values: JsonObject): PortableImportOwner {
        val memory = if (values.text("scopeId").isBlank()) row("companion_memories", values.text("memoryId")) else null
        val scope = if (table == "companion_scopes") values else row("companion_scopes", values.text("scopeId").ifBlank { memory?.text("scopeId").orEmpty() })
        val sessionId = values.text("sessionId", "session_id").ifBlank { scope?.text("sessionId").orEmpty() }
        val session = if (table == "local_sessions") values else row("local_sessions", sessionId)
        val characterId = values.text("characterId", "character_id").ifBlank {
            scope?.text("characterId").orEmpty().ifBlank { session?.text("character_id").orEmpty() }
        }
        val character = if (table == "local_characters") values else row("local_characters", characterId)
        return PortableImportOwner(character?.text("name", "title").orEmpty().take(160), session?.text("title", "name").orEmpty().take(160))
    }
    fun workspace(root: String, relative: String): PortableImportOwner {
        if (root !in setOf("workspace", "agent_workspaces")) return PortableImportOwner()
        val id = relative.substringBefore('/')
        if (id == "shared") return PortableImportOwner(shared = true)
        val session = row("local_sessions", id) ?: return PortableImportOwner()
        return owner("local_sessions", session)
    }
    fun title(values: JsonObject, fallback: String): String = values.text("name", "title", "content", "factKey")
        .lineSequence().firstOrNull()?.trim()?.take(100)?.takeIf(String::isNotBlank) ?: fallback
}

/** Empty field selections and insertion order cannot turn an unchanged choice into a new request. */
internal fun PortableImportChoices.normalized(): PortableImportChoices = copy(
    fields = fields.filterValues { it.isNotEmpty() }.mapValues { it.value.toSet() },
    files = files.toSet(), settings = settings.toSet(), keepMemorySources = keepMemorySources.toSet()
)

/** A memory and its incoming evidence are one user decision; keeping never mixes new evidence into old text. */
internal fun PortableImportChoices.withRecordChoice(row: PortableImportRowDifference, useIncoming: Boolean,
    sourceFields: Map<String, Set<String>> = emptyMap()): PortableImportChoices {
    val columns = row.fields.mapTo(linkedSetOf()) { it.column }
    val next = if (useIncoming) fields[row.key].orEmpty() + columns else fields[row.key].orEmpty() - columns
    val sources = row.memoryId?.let { if (useIncoming) keepMemorySources - it else keepMemorySources + it } ?: keepMemorySources
    val selected = (fields + (row.key to next)).toMutableMap()
    sourceFields.forEach { (key, columns) -> selected[key] = if (useIncoming) selected[key].orEmpty() + columns else selected[key].orEmpty() - columns }
    return copy(fields = selected, keepMemorySources = sources).normalized()
}
