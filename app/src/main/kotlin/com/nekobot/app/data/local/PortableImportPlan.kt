package com.nekobot.app.data.local

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.security.MessageDigest

/** Thin adapters run these same planning/writing rules against Android SQLite or synthetic JDBC. */
internal interface PortableImportSql {
    fun query(sql: String, args: List<Any?> = emptyList()): List<JsonObject>
    fun execute(sql: String, args: List<Any?> = emptyList())
}

internal data class PortableIncomingRow(val table: String, val values: JsonObject)
internal typealias PortableRowVisitor = ((PortableIncomingRow) -> Unit) -> Unit

data class PortableImportChoices(
    val fields: Map<String, Set<String>> = emptyMap(),
    val files: Set<String> = emptySet(),
    val settings: Set<String> = emptySet(),
    val restoreCredentials: Boolean = false,
    val allowSameName: Boolean = false,
    val keepMemorySources: Set<String> = emptySet()
)

data class PortableImportFieldDifference(val column: String, val current: String, val incoming: String)
data class PortableImportRowDifference(
    val key: String, val table: String, val label: String,
    val fields: List<PortableImportFieldDifference>,
    val owner: PortableImportOwner = PortableImportOwner(),
    val memoryId: String? = null
)
data class PortableImportCounts(
    val added: Int = 0, val same: Int = 0, val different: Int = 0, val updated: Int = 0
) { val kept: Int get() = different - updated }

data class PortableImportRowPlan(
    val fingerprint: String,
    val counts: PortableImportCounts,
    val differences: List<PortableImportRowDifference>,
    val issues: List<String>,
    val sensitiveFieldsOmitted: Int,
    internal val localFiles: List<String> = emptyList(),
    val requiredTables: Set<String> = emptySet(),
    internal val skillNames: Set<String> = emptySet(),
    internal val newKeys: Set<String> = emptySet(),
    val sameNameCopies: Int = 0,
    internal val sourceMemoryStates: Map<String, JsonObject> = emptyMap(),
    val tableCounts: Map<String, PortableImportCounts> = emptyMap(),
    val sourceConflictKeys: Set<String> = emptySet(),
    val sourceConflictMemoryCount: Int = 0,
    val skippedSourceRows: Int = 0,
    val sourceFieldChoices: Map<String, Map<String, Set<String>>> = emptyMap(),
    val recordDifferenceKeys: Set<String> = emptySet()
) {
    /** A source conflict must not hide the file's text-only or other record repairs. */
    val sourceReviewKeys: Set<String> get() = recordDifferenceKeys.ifEmpty { sourceConflictKeys }
}

/** No deletion/REPLACE, no matching by display name, no network or activation side effects. */
internal class PortableImportPlanner(private val sql: PortableImportSql, private val profile: String,
    private val labels: PortableImportLabels = PortableImportLabels(sql)) {
    private data class Column(val name: String, val type: String, val required: Boolean, val default: String?, val pk: Int)
    private val schemas = mutableMapOf<String, List<Column>>()
    private val uniqueCache = mutableMapOf<String, List<List<String>>>()
    private val foreignCache = mutableMapOf<String, List<JsonObject>>()

    fun preview(rows: PortableRowVisitor, choices: PortableImportChoices, detailLimit: Int = 50,
                checkpoint: () -> Unit = {}): PortableImportRowPlan {
        labels.reset()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(choices.fields.toSortedMap().entries.joinToString { "${it.key}:${it.value.sorted()}" }.toByteArray())
        digest.update(choices.allowSameName.toString().toByteArray())
        digest.update(choices.keepMemorySources.sorted().joinToString("|").toByteArray())
        val seen = hashSetOf<String>()
        val supplied = hashSetOf<String>()
        val references = mutableListOf<Pair<String, Pair<String, JsonObject>>>()
        val uniqueRows = hashSetOf<String>()
        val characterNames = hashSetOf<String>()
        val issues = linkedSetOf<String>()
        val details = mutableListOf<PortableImportRowDifference>()
        val localFiles = linkedSetOf<String>()
        val requiredTables = linkedSetOf<String>()
        val skillNames = linkedSetOf<String>()
        val newKeys = hashSetOf<String>()
        val sessionWrites = mutableListOf<JsonObject>()
        val memoryWrites = mutableListOf<JsonObject>()
        val scopeOwners = hashSetOf<String>()
        val memorySources = PortableMemorySourceIntegrity(sql)
        val tableCounts = linkedMapOf<String, PortableImportCounts>()
        val memoryDifferenceKeys = mutableMapOf<String, String>()
        val recordDifferenceKeys = linkedSetOf<String>()
        val sourceFieldChoices = linkedMapOf<String, MutableMap<String, Set<String>>>()
        var added = 0; var same = 0; var different = 0; var updated = 0; var sensitive = 0
        var sameNameCopies = 0
        var skippedSourceRows = 0
        rows { incoming ->
            checkpoint()
            if (skipMemorySource(incoming, choices)) {
                val values = normalize(incoming).first
                val key = primaryKey(incoming.table, values)
                find(incoming.table, key)?.let { existing ->
                    val changes = differences(incoming.table, values, existing)
                    if (changes.isNotEmpty()) sourceFieldChoices.getOrPut(values["memoryId"].asString) { linkedMapOf() }[rowKey(incoming.table, key)] = changes.toSet()
                }
                skippedSourceRows++; return@rows
            }
            val (values, omitted) = normalize(incoming)
            sensitive += omitted
            val table = incoming.table
            val key = primaryKey(table, values)
            val token = rowKey(table, key)
            require(seen.add(token)) { "归档中同一稳定ID出现多次，请修正文件后重试" }
            supplied += token
            val existing = find(table, key)
            val previousCounts = PortableImportCounts(added, same, different, updated)
            digest.update((token + ":" + canonical(existing) + ":" + canonical(values) + "\n").toByteArray())
            if (existing == null) {
                newKeys += token
                if (table == "local_characters" && values.has("name")) {
                    val repeatedName = !characterNames.add(values["name"].asString.lowercase(java.util.Locale.ROOT))
                    val localName = sql.query("SELECT id FROM local_characters WHERE lower(name)=lower(?) LIMIT 1", listOf(values["name"].asString)).isNotEmpty()
                    if (repeatedName || localName) {
                        sameNameCopies++
                        if (!choices.allowSameName) issues += "发现同名但ID不同的角色；请明确允许新增独立副本，或取消该角色"
                    }
                }
                columns(table).filter { it.required && it.default == null && !values.has(it.name) }.forEach {
                    issues += "$table：新记录缺少 ${it.name}"
                }
                added++
            } else {
                // A stable ID must never acquire another memory owner, even through field overwrite.
                ownershipColumns(table).filter { values.has(it) && values[it] != existing[it] }.forEach {
                    issues += "$table：同一ID的归属与本地不同，请取消此项并核对来源；不会改绑角色或会话"
                }
                val changes = differences(table, values, existing)
                if (changes.isEmpty()) same++ else {
                    different++
                    recordDifferenceKeys += token
                    if (changes.any { it in choices.fields[token].orEmpty() }) updated++
                    if (table == "companion_memories") memoryDifferenceKeys[values["id"].asString] = token
                    if (table == "companion_sources") sourceFieldChoices.getOrPut(values["memoryId"].asString) { linkedMapOf() }[token] = changes.toSet()
                    if (details.size < detailLimit) details += PortableImportRowDifference(token, table,
                        labels.title(existing, labels.title(values, label(values, key))), changes.map {
                            val (current, incoming) = displayDifference(it, existing[it], values[it])
                            PortableImportFieldDifference(it, current, incoming)
                        }, labels.owner(table, existing), values["id"]?.asString?.takeIf { table == "companion_memories" })
                }
            }
            val counted = tableCounts[table] ?: PortableImportCounts()
            tableCounts[table] = PortableImportCounts(counted.added + added - previousCounts.added,
                counted.same + same - previousCounts.same, counted.different + different - previousCounts.different,
                counted.updated + updated - previousCounts.updated)
            // Validate only fields that would actually be written; keeping local must not manufacture dependencies.
            val written = if (existing == null) values else JsonObject().apply {
                key.entrySet().forEach { add(it.key, it.value) }
                differences(table, values, existing).filter { it in choices.fields[token].orEmpty() }.forEach { add(it, values[it]) }
            }
            if (table in setOf("companion_memories", "companion_sources", "companion_scopes", "companion_applied_ops")) {
                val effective = (existing?.deepCopy() ?: JsonObject()).apply { written.entrySet().forEach { add(it.key, it.value) } }
                memorySources.record(table, values, effective, existing == null || written.size() > key.size())
            }
            if (table == "local_sessions" && (existing == null || written.size() > key.size()))
                sessionWrites += (existing?.deepCopy() ?: JsonObject()).apply { written.entrySet().forEach { add(it.key, it.value) } }
            if (table == "companion_memories" && (existing == null || written.size() > key.size()))
                memoryWrites += (existing?.deepCopy() ?: JsonObject()).apply { written.entrySet().forEach { add(it.key, it.value) } }
            if (table == "companion_scopes" && existing == null && values.has("sessionId") && values.has("characterId")) {
                val identity = "${values["sessionId"]}|${values["characterId"]}"
                if (!scopeOwners.add(identity) || sql.query("SELECT id FROM companion_scopes WHERE sessionId=? AND characterId=? LIMIT 1",
                        listOf(bind(values["sessionId"]), bind(values["characterId"]))).isNotEmpty())
                    issues += "同一角色与会话已有不同ID的独立记忆归属，请取消此项并核对来源；不会创建第二份归属"
            }
            references(table, written).forEach { references += label(values, key) to it }
            if (table == "local_skills" && (existing == null || written.size() > key.size()))
                (written["name"] ?: existing?.get("name"))?.takeUnless(JsonElement::isJsonNull)?.asString?.let(skillNames::add)
            if (table in setOf("local_skills", "local_tools", "local_mcp_servers", "local_workflows", "local_tasks", "local_hooks") && written.has("name")) {
                val names = sql.query("SELECT * FROM ${q(table)} WHERE lower(name)=lower(?)", listOf(written["name"].asString))
                if (names.any { primaryKey(table, it) != key }) issues += "$table：同名配置的ID不同，不能自动覆盖或新增重名执行项"
            }
            mediaColumns(table).forEach { column -> written[column]?.takeUnless(JsonElement::isJsonNull)?.asString?.let { reference ->
                if (reference.startsWith("file:") || reference.startsWith('/')) localFiles += reference
            } }
            uniqueColumns(table).forEach { names ->
                if (names.all(written::has) && names.none { written[it].isJsonNull }) {
                    val uniqueKey = JsonObject().apply { names.forEach { add(it, written[it]) } }
                    val signature = table + ":" + canonical(uniqueKey)
                    if (!uniqueRows.add(signature)) issues += "$table：归档存在重复的唯一值"
                    val match = find(table, uniqueKey)
                    if (match != null && primaryKey(table, match) != key) issues += "$table：稳定ID不同但唯一值冲突，请保留当前或另行处理副本"
                }
            }
        }
        references.forEach { (label, ref) ->
            checkpoint()
            if (rowKey(ref.first, ref.second) !in supplied && find(ref.first, ref.second) == null) {
                requiredTables += ref.first
                if (issues.size < 50) issues += "$label：缺少 ${ref.first} 关联资料，请返回补选或取消此项"
            }
        }
        memorySources.validate(rows, { incoming ->
            val values = normalize(incoming).first
            val key = primaryKey(incoming.table, values)
            val existing = find(incoming.table, key)
            if (existing == null) values else existing.deepCopy().apply {
                differences(incoming.table, values, existing).filter { it in choices.fields[rowKey(incoming.table, key)].orEmpty() }
                    .forEach { add(it, values[it]) }
            }
        }, { key, value -> digest.update((key + ":" + canonical(value) + "\n").toByteArray()) }, issues, requiredTables, checkpoint)
        fun currentMemory(row: JsonObject) = row["current"]?.asInt == 1 && row["valid"]?.asInt == 1 &&
            row["status"]?.asString !in setOf("conflict", "locked_conflict")
        memoryWrites.filter { it.has("factKey") && it.has("scopeId") }
            .groupBy { it["scopeId"].asString to it["factKey"].asString }.forEach { (identity, writes) ->
                if (writes.none(::currentMemory)) return@forEach
                val effective = sql.query("SELECT * FROM companion_memories WHERE scopeId=? AND factKey=?", listOf(identity.first, identity.second))
                    .associateBy { it["id"].asString }.toMutableMap()
                writes.forEach { effective[it["id"].asString] = it }
                if (effective.values.count(::currentMemory) > 1)
                    issues += "记忆 ${writes.first()["factKey"].asString.take(60)} 有两个当前版本；请在差异中确认旧版本的当前标记，或取消记忆正文。不会自动删除或覆盖"
            }
        if (sessionWrites.isNotEmpty() && columns("local_sessions").any { it.name == "companion_mode" }) {
            val sessions = sql.query("SELECT * FROM local_sessions").associateBy { it["id"].asString }.toMutableMap()
            sessionWrites.forEach { sessions[it["id"].asString] = it }
            val owners = sql.query("SELECT * FROM companion_owners")
            fun characters(row: JsonObject): Set<String> = buildSet {
                row["character_id"]?.takeUnless(JsonElement::isJsonNull)?.asString?.takeIf(String::isNotBlank)?.let(::add)
                row["character_ids"]?.takeUnless(JsonElement::isJsonNull)?.asString?.let { raw ->
                    runCatching { com.google.gson.JsonParser.parseString(raw).asJsonArray.forEach { add(it.asString) } }
                }
            }
            sessionWrites.forEach { row ->
                val id = row["id"].asString
                val character = row["character_id"]?.takeUnless(JsonElement::isJsonNull)?.asString
                val companion = row["companion_mode"]?.asInt == 1
                if (companion && (character.isNullOrBlank() || row["session_mode"]?.asString?.lowercase() != "agent" ||
                            row["inherit_character"]?.asInt != 1 || row["long_conversation_enabled"]?.asInt != 1))
                    issues += "单会话角色的模式与角色绑定不完整，请补全资料或取消该会话"
                if (owners.any { it["characterId"].asString in characters(row) && (it["sessionId"].asString != id || !companion) } ||
                    companion && sessions.values.any { it["id"].asString != id && character in characters(it) })
                    issues += "角色已绑定其他会话，不能导入为第二个窗口；请取消该会话或通过专门迁入入口处理"
                val current = sql.query("SELECT * FROM local_sessions WHERE id=?", listOf(id)).firstOrNull()
                if (current != null && (current["companion_mode"] != row["companion_mode"] ||
                            current["companion_mode"]?.asInt == 1 && current["character_id"] != row["character_id"]))
                    issues += "已有单会话角色不能通过普通导入切换模式或更换绑定角色"
            }
        }
        return PortableImportRowPlan(hex(digest.digest()), PortableImportCounts(added, same, different, updated),
            details, issues.take(50), sensitive, localFiles.toList(), requiredTables, skillNames, newKeys, sameNameCopies,
            memorySources.memoryStates(), tableCounts,
            memorySources.conflictingMemoryIds.mapNotNullTo(linkedSetOf()) { memoryDifferenceKeys[it] },
            memorySources.conflictingMemoryIds.size, skippedSourceRows, sourceFieldChoices,
            recordDifferenceKeys)
    }

    /** Caller must hold one database transaction and compare preview fingerprint before this call. */
    fun apply(rows: PortableRowVisitor, choices: PortableImportChoices, checkpoint: () -> Unit = {},
              originallyNew: Set<String>? = null,
              sourceMemoryStates: Map<String, JsonObject>? = null,
              onWrite: (String, JsonObject, Boolean, Set<String>) -> Unit = { _, _, _, _ -> }): Int {
        val checkedPlan = if (originallyNew == null || sourceMemoryStates == null)
            preview(rows, choices, 0).also { require(it.issues.isEmpty()) { it.issues.first() } } else null
        val newKeys = originallyNew ?: requireNotNull(checkedPlan).newKeys
        val verifiedStates = sourceMemoryStates ?: requireNotNull(checkedPlan).sourceMemoryStates
        var changed = 0
        rows { incoming ->
            checkpoint()
            if (skipMemorySource(incoming, choices)) return@rows
            var normalized = normalize(incoming).first
            val table = incoming.table
            val key = primaryKey(table, normalized)
            val wasNew = rowKey(table, key) in newKeys
            if (wasNew) normalized = normalize(incoming, forceNew = true).first
            val existing = find(table, key)
            val values = if (existing == null || wasNew) normalized else JsonObject().apply {
                differences(table, normalized, existing).filter { it in choices.fields[rowKey(table, key)].orEmpty() }
                    .forEach { add(it, normalized[it]) }
            }
            if (existing != null && values.size() == 0) return@rows
            if (table in EXECUTABLE_TABLES) listOf("enabled", "active").filter { n -> columns(table).any { it.name == n } }
                .forEach { values.addProperty(it, 0) }
            if (existing == null) {
                val names = values.keySet().toList()
                sql.execute("INSERT INTO ${q(table)} (${names.joinToString { q(it) }}) VALUES (${names.joinToString { "?" }})", names.map { bind(values[it]) })
            } else {
                val names = values.keySet().toList()
                sql.execute("UPDATE ${q(table)} SET ${names.joinToString { "${q(it)}=?" }} WHERE ${where(key)}",
                    names.map { bind(values[it]) } + key.entrySet().map { bind(it.value) })
            }
            changed++
            val written = existing?.deepCopy() ?: JsonObject()
            values.entrySet().forEach { written.add(it.key, it.value) }
            onWrite(table, written, wasNew, values.keySet().toSet())
        }
        // Message triggers can invalidate a linked memory before its replacement source is written.
        // Restore only the already-reviewed state for proofs validated against the effective messages.
        for ((id, flags) in verifiedStates) {
            checkpoint()
            val key = JsonObject().apply { addProperty("id", id) }
            val existing = find("companion_memories", key) ?: continue
            val names = flags.keySet().filter { flags[it] != existing[it] }
            if (names.isEmpty()) continue
            sql.execute("UPDATE companion_memories SET ${names.joinToString { "${q(it)}=?" }} WHERE id=?",
                names.map { bind(flags[it]) } + id)
            val token = rowKey("companion_memories", key)
            names.forEach { existing.add(it, flags[it]) }
            onWrite("companion_memories", existing, token in newKeys, names.toSet())
        }
        // 队列随备份迁入；缺失的记忆不能阻止其余资料导入，也不能产生悬空弹窗。
        if (sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name='companion_locked_reviews'").isNotEmpty()) sql.execute("""
            UPDATE companion_locked_reviews SET resolvedAt=?,resolution='obsolete'
            WHERE resolvedAt IS NULL AND (
                NOT EXISTS(SELECT 1 FROM companion_memories m WHERE m.id=lockedMemoryId AND m.scopeId=companion_locked_reviews.scopeId)
                OR NOT EXISTS(SELECT 1 FROM companion_memories m WHERE m.id=confirmedMemoryId AND m.scopeId=companion_locked_reviews.scopeId))
        """.trimIndent(), listOf(java.time.Instant.now().toString()))
        return changed
    }

    private fun normalize(incoming: PortableIncomingRow, forceNew: Boolean = false): Pair<JsonObject, Int> {
        val table = incoming.table
        val schema = columns(table).associateBy { it.name }
        val result = JsonObject()
        var omitted = 0
        incoming.values.entrySet().forEach { (name, value) ->
            require(name in schema) { "$table：归档包含当前版本无法安全恢复的字段" }
            if (name in sensitiveColumns(table)) { if (!value.isJsonNull && value.toString() !in setOf("\"\"", "{}")) omitted++; return@forEach }
            if (table in EXECUTABLE_TABLES && name in setOf("enabled", "active")) return@forEach
            val col = schema.getValue(name)
            require(!value.isJsonNull || !col.required) { "$table：必填字段为空" }
            val normalized = when {
                value.isJsonNull -> JsonNull.INSTANCE
                col.type.equals("BLOB", true) -> { require(value.isJsonObject && value.asJsonObject.has("__base64_blob__")) { "无效二进制字段" }; value }
                col.type.contains("INT", true) -> JsonPrimitive(if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) if (value.asBoolean) 1L else 0L else {
                    require(value.isJsonPrimitive) { "无效整数字段" }
                    try { value.asBigDecimal.longValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("无效整数字段") }
                })
                col.type.contains("REAL", true) -> JsonPrimitive(value.asDouble.also { require(it.isFinite()) { "无效数值字段" } })
                else -> { require(value.isJsonPrimitive) { "无效文本字段" }; JsonPrimitive(value.asString) }
            }
            result.add(name, normalized)
        }
        val key = primaryKey(table, result)
        if (forceNew || find(table, key) == null) {
            sensitiveColumns(table).filter(schema::containsKey).forEach { name ->
                result.add(name, if (schema.getValue(name).required) JsonPrimitive("") else JsonNull.INSTANCE)
            }
            if (table in EXECUTABLE_TABLES) listOf("enabled", "active").filter(schema::containsKey).forEach { result.addProperty(it, 0) }
            if (table == "companion_scopes") {
                result.addProperty("profile", profile)
                result.addProperty("baselineRowId", 0)
                result.addProperty("archiveRowId", 0)
            }
            if (table == "companion_relationship_state") {
                result.addProperty("enabled", 0); result.addProperty("awaitingHistory", 1)
            }
            if (table == "companion_examples") { result.addProperty("state", "disabled"); result.add("noticeAfterId", JsonNull.INSTANCE) }
            if (table == "local_agent_runs" && schema.containsKey("status") && result["status"]?.asString in setOf("running", "pending", "queued")) result.addProperty("status", "paused")
            if (table == "local_subagent_tasks" && schema.containsKey("status") && result["status"]?.asString in setOf("running", "pending", "queued")) result.addProperty("status", "cancelled")
            if (table == "local_agent_notices" && schema.containsKey("consumed_by_run_id")) result.add("consumed_by_run_id", JsonNull.INSTANCE)
        }
        return result to omitted
    }

    private fun skipMemorySource(incoming: PortableIncomingRow, choices: PortableImportChoices): Boolean =
        incoming.table == "companion_sources" && incoming.values["memoryId"]?.asString in choices.keepMemorySources


    private fun columns(table: String): List<Column> = schemas.getOrPut(table) {
        sql.query("PRAGMA table_info(${q(table)})").map {
            Column(it["name"].asString, it["type"].asString, it["notnull"].asInt != 0,
                it["dflt_value"]?.takeUnless(JsonElement::isJsonNull)?.asString, it["pk"].asInt)
        }.also { require(it.isNotEmpty()) { "当前数据库缺少归档所需表" } }
    }
    private fun primaryKey(table: String, values: JsonObject): JsonObject = JsonObject().apply {
        val keys = columns(table).filter { it.pk > 0 }.sortedBy { it.pk }
        require(keys.isNotEmpty()) { "归档记录没有稳定主键" }
        keys.forEach { col ->
            val value = values[col.name]
            require(value != null && !value.isJsonNull && value.asString.isNotBlank()) { "$table：记录缺少稳定ID" }
            add(col.name, value)
        }
    }
    private fun find(table: String, key: JsonObject): JsonObject? = sql.query(
        "SELECT * FROM ${q(table)} WHERE ${where(key)} LIMIT 1", key.entrySet().map { bind(it.value) }).firstOrNull()
    private fun differences(table: String, values: JsonObject, existing: JsonObject): List<String> =
        values.keySet().filter { name -> columns(table).none { it.name == name && it.pk > 0 } &&
            name !in protectedColumns(table) && values[name] != existing[name] }

    private fun uniqueColumns(table: String): List<List<String>> = uniqueCache.getOrPut(table) { sql.query("PRAGMA index_list(${q(table)})")
        .filter { it["unique"].asInt != 0 && it["origin"]?.asString != "pk" }
        .map { index -> sql.query("PRAGMA index_info(${q(index["name"].asString)})").mapNotNull { it["name"]?.takeUnless(JsonElement::isJsonNull)?.asString } }
        .filter { it.isNotEmpty() } }

    private fun references(table: String, values: JsonObject): List<Pair<String, JsonObject>> {
        val result = mutableListOf<Pair<String, JsonObject>>()
        foreignCache.getOrPut(table) { sql.query("PRAGMA foreign_key_list(${q(table)})") }.groupBy { it["id"].asInt }.values.forEach { rows ->
            if (rows.all { values.has(it["from"].asString) && !values[it["from"].asString].isJsonNull }) {
                result += rows.first()["table"].asString to JsonObject().apply { rows.forEach { add(it["to"].asString, values[it["from"].asString]) } }
            }
        }
        val logical = when {
            table == "local_sessions" -> listOf("character_id" to "local_characters")
            table == "companion_sources" -> listOf("memoryId" to "companion_memories", "messageId" to "local_messages")
            table == "companion_locked_reviews" -> listOf("scopeId" to "companion_scopes", "sessionId" to "local_sessions")
            table == "companion_scopes" || table == "companion_owners" -> listOf("sessionId" to "local_sessions", "characterId" to "local_characters")
            table.startsWith("companion_") && table !in setOf("companion_relationship_state", "companion_relationship_events", "companion_relationship_assessments", "companion_examples") -> listOf("scopeId" to "companion_scopes", "memoryId" to "companion_memories")
            table == "local_character_memories" -> listOf("character_id" to "local_characters")
            else -> emptyList()
        }
        logical.forEach { (column, parent) -> values[column]?.takeUnless { it.isJsonNull || it.asString.isBlank() }?.let { result += parent to JsonObject().apply { add("id", it) } } }
        return result
    }

    companion object {
        val EXECUTABLE_TABLES = setOf("local_ai_models", "local_hooks", "local_tasks", "local_workflows", "local_skills", "local_tools", "local_mcp_servers", "local_api_keys", "local_oauth_accounts")
        private fun mediaColumns(table: String): Set<String> = when (table) {
            "local_sessions" -> setOf("portrait", "sender_avatar", "character_avatar")
            "local_characters" -> setOf("portrait", "avatar")
            "local_world_books" -> setOf("cover_url")
            "local_message_images" -> setOf("file_path", "reference_image_path")
            "local_messages" -> setOf("audio_url")
            "local_stickers" -> setOf("file_path")
            else -> emptySet()
        }
        fun sensitiveColumns(table: String): Set<String> = when (table) {
            "local_ai_models" -> setOf("api_key", "proxy_url", "tts_headers", "tts_body_template", "stt_headers")
            "local_mcp_servers" -> setOf("url", "headers_json", "args_json", "env_json")
            "local_api_keys" -> setOf("key")
            "local_oauth_accounts" -> setOf("encrypted_credentials")
            else -> emptySet()
        }
        private fun ownershipColumns(table: String): Set<String> = when (table) {
            "companion_scopes", "companion_relationship_state" -> setOf("sessionId", "characterId")
            "companion_owners" -> setOf("sessionId")
            "companion_memories", "companion_clarifications" -> setOf("scopeId")
            "companion_applied_ops" -> setOf("scopeId", "memoryId")
            "companion_locked_reviews" -> setOf("scopeId", "sessionId", "lockedMemoryId", "confirmedMemoryId")
            else -> emptySet()
        }
        fun protectedColumns(table: String): Set<String> = sensitiveColumns(table) + ownershipColumns(table) + when (table) {
            "companion_scopes" -> setOf("profile", "baselineRowId", "archiveRowId", "revision")
            "local_messages" -> setOf("deleted")
            "companion_examples" -> setOf("state", "noticeAfterId")
            "companion_relationship_state" -> setOf("enabled", "awaitingHistory")
            "local_agent_runs", "local_subagent_tasks" -> setOf("status")
            "local_agent_notices" -> setOf("consumed_by_run_id")
            in EXECUTABLE_TABLES -> setOf("enabled", "active")
            else -> emptySet()
        }
        fun q(name: String): String { require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))); return "`$name`" }
        fun bind(value: JsonElement?): Any? = when {
            value == null || value.isJsonNull -> null
            value.isJsonObject -> java.util.Base64.getDecoder().decode(value.asJsonObject["__base64_blob__"].asString)
            value.asJsonPrimitive.isNumber -> if (value.asString.contains('.') || value.asString.contains('e', true)) value.asDouble else value.asLong
            value.asJsonPrimitive.isBoolean -> if (value.asBoolean) 1 else 0
            else -> value.asString
        }
        private fun where(key: JsonObject) = key.keySet().joinToString(" AND ") { "${q(it)}=?" }
        private fun rowKey(table: String, key: JsonObject) = table + ":" + hash(canonical(key))
        private fun canonical(value: JsonObject?): String = value?.keySet()?.sorted()?.joinToString("|", "{", "}") { "$it:${value[it]}" } ?: "<absent>"
        private fun hash(value: String) = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
        private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }
        private fun label(values: JsonObject, key: JsonObject): String = listOf("name", "title", "factKey", "id").firstNotNullOfOrNull { values[it]?.takeUnless(JsonElement::isJsonNull)?.asString }?.take(80) ?: key.toString().take(80)
        private fun display(column: String, value: JsonElement?): String = when {
            column.contains("url", true) || column.contains("endpoint", true) -> "连接地址（已隐藏）"
            value == null || value.isJsonNull -> "未设置"
            value.isJsonObject -> "二进制内容"
            else -> value.asString.take(180)
        }
        private fun displayDifference(column: String, current: JsonElement?, incoming: JsonElement?): Pair<String, String> {
            val first = display(column, current)
            val second = display(column, incoming)
            // A long common prefix must not make two different values look identical.
            if (first != second || current == incoming || column.contains("url", true) || column.contains("endpoint", true)) return first to second
            if (current?.isJsonPrimitive != true || incoming?.isJsonPrimitive != true) return first to second
            val a = current.asString; val b = incoming.asString
            var at = 0
            while (at < minOf(a.length, b.length) && a[at] == b[at]) at++
            fun excerpt(text: String): String { val start = (at - 60).coerceAtLeast(0).coerceAtMost(text.length); return "…" + text.substring(start, minOf(text.length, at + 120)) + if (text.length > at + 120) "…" else "" }
            return excerpt(a) to excerpt(b)
        }
    }
}

/** Source invalidation is scoped to an archive actually written by this import, including same-session isolation. */
internal val portableExperienceInvalidationSql = """
    UPDATE local_experience_archives SET status='stale' WHERE id=? AND (
        NOT EXISTS (SELECT 1 FROM local_experience_sources source WHERE source.archive_id=local_experience_archives.id)
        OR EXISTS (SELECT 1 FROM local_experience_sources source LEFT JOIN local_messages message ON message.id=source.message_id
            WHERE source.archive_id=local_experience_archives.id AND
            (message.id IS NULL OR message.session_id!=local_experience_archives.session_id OR message.deleted!=0)))
""".trimIndent()

/** Blocks profile/repository replacement while a portable transaction owns the active connection. */
internal object PortableImportGate {
    @Volatile var profile: String? = null
    fun requireSwitchAllowed(target: String, owned: Boolean) {
        require(profile == null || owned && profile == target) { "导入正在处理，请先完成或取消导入" }
    }
}
