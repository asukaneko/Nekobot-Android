package com.nekobot.app.data.local

import com.google.gson.JsonObject
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import java.io.File
import java.time.Instant

internal data class TransferMemorySelection(val accepted: Int, val skipped: Map<String, Int>)

/** 新版迁入保留所有历史行，current/voided决定是否参与回复，不再物理丢弃旧版本。 */
internal fun retainedCompanionMemoryIds(rows: Collection<JsonObject>): Set<String> =
    rows.map { requireNotNull(it.text("id")) }.toSet()

/** 只写新范围的独立正文；旧路径和旧 ID 留在备份，不参与运行时召回。 */
internal class CompanionTransferMemory(private val db: NekobotDatabase, private val sourceDb: NekobotDatabase = db) {
    fun exclusion(row: JsonObject, snapshot: TransferSnapshot): String? {
        if (row.text("target_id") != "local-user") return "无法确认属于当前用户"
        if (listOf("conversation_id", "source_session_id").mapNotNull { row.text(it)?.takeIf(String::isNotBlank) }.any { it !in snapshot.sessionIds }) return "属于其他会话"
        if (row.text("category") in setOf("recent_digest", "timeline", "life_sim")) return "旧摘要保存在迁入记录中供核对"
        if (row.text("content").isNullOrBlank()) return "无记忆正文"
        return null
    }

    fun selection(directory: File, snapshot: TransferSnapshot): TransferMemorySelection {
        val skipped = linkedMapOf<String, Int>()
        var accepted = 0
        for (row in transferRows(directory, "local_character_memories")) {
            val reason = exclusion(row, snapshot)
            if (reason == null) accepted++ else skipped[reason] = (skipped[reason] ?: 0) + 1
        }
        return TransferMemorySelection(accepted, skipped)
    }

    suspend fun copy(directory: File, snapshot: TransferSnapshot, target: LocalSessionEntity,
        id: (String, String) -> String, rewrite: (JsonObject, String) -> JsonObject) {
        val dao = db.companionMemoryDao()
        val store = CompanionMemoryStore(db)
        val scope = store.ensureScope(target)
        val sql = db.openHelper.writableDatabase
        val memoryRows = transferRows(directory, "companion_memories").associateBy { requireNotNull(it.text("id")) }
        val sourceRows = transferRows(directory, "companion_sources").groupBy { requireNotNull(it.text("memoryId")) }
        val retainedIds = retainedCompanionMemoryIds(memoryRows.values)
        for ((oldId, original) in memoryRows.filterKeys { it in retainedIds }) {
            val row = rewrite(original, snapshot.sourceId)
            val copiedId = id("memory", oldId)
            val oldScopeId = requireNotNull(row.text("scopeId"))
            row.addProperty("id", copiedId); row.addProperty("scopeId", scope.id)
            row.addProperty("factKey", "${id("scope", oldScopeId)}:${row.text("factKey")}")
            for (column in listOf("supersededById", "uncertainTargetId")) row.text(column)?.let { row.addProperty(column, id("memory", it)) }
            var evidenceValid = row.flag("manual") || row.text("provenance") == CompanionImportPolicy.IMPORTED_UNVERIFIED
            val sources = sourceRows[oldId].orEmpty()
            if (sources.isNotEmpty()) evidenceValid = true
            val newSources = mutableListOf<CompanionSourceEntity>()
            for (source in sources) {
                val originalMessage = source.text("messageId")?.let { sourceDb.messageDao().getById(it) }
                val newMessage = source.text("messageId")?.let { db.messageDao().getById(id("message", it)) }
                val rewritten = rewrite(source, originalMessage?.sessionId ?: snapshot.sourceId)
                val quote = rewritten.text("quote").orEmpty()
                val originalSnapshot = source.text("messageSnapshot")
                val copiedSnapshot = rewritten.text("messageSnapshot")
                val evidence = originalSnapshot ?: originalMessage?.content
                if (originalMessage == null || newMessage == null || originalMessage.sessionId !in snapshot.sessionIds ||
                    originalMessage.role != source.text("role") || evidence == null ||
                    CompanionMemoryPolicy.fingerprint(originalMessage.copy(content = evidence, deleted = false)) != source.text("fingerprint") ||
                    (originalSnapshot == null && (originalMessage.deleted || quote.isBlank() || !newMessage.content.contains(quote)))) {
                    evidenceValid = false
                    continue
                }
                val snapshotBody = copiedSnapshot ?: newMessage.content
                newSources += CompanionSourceEntity(copiedId, newMessage.id,
                    CompanionMemoryPolicy.fingerprint(newMessage.copy(content = snapshotBody, deleted = false)),
                    quote, source.text("spokenDate"), newMessage.role, snapshotBody)
            }
            if (!evidenceValid && !row.flag("manual")) row.addProperty("valid", 0)
            sql.insertTransfer("companion_memories", row)
            dao.saveSources(newSources)
        }
        for (row in transferRows(directory, "local_character_memories")) {
            if (exclusion(row, snapshot) != null) continue
            val oldId = requireNotNull(row.text("id"))
            val value = rewrite(row, row.text("source_session_id") ?: row.text("conversation_id") ?: snapshot.sourceId)
            dao.saveMemory(CompanionMemoryEntity(
                id = id("legacy-memory", oldId), scopeId = scope.id, factKey = id("legacy-key", oldId),
                version = row.text("version")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                kind = if (row.text("category") == "important_event") "episode" else "fact",
                subject = if (row.text("category") == "character_persona") snapshot.characterName else "玩家",
                content = requireNotNull(value.text("content")), status = "active",
                category = when (row.text("category")) { "character_persona" -> "character_profile"; "important_event" -> "episode"; else -> "user_profile" },
                tagsJson = row.text("tags_json") ?: "[]", createdAt = requireNotNull(row.text("created_at")),
                updatedAt = row.text("updated_at") ?: requireNotNull(row.text("created_at")),
                provenance = CompanionImportPolicy.IMPORTED_UNVERIFIED, expiresAt = row.text("expires_at")
            ))
        }
        for (row in transferRows(directory, "companion_forgotten_sources")) {
            val messageId = id("message", requireNotNull(row.text("messageId")))
            if (db.messageDao().getById(messageId) != null) dao.saveForgotten(listOf(CompanionForgottenSourceEntity(scope.id, messageId)))
        }
        for (row in transferRows(directory, "companion_clarifications")) {
            if (row.text("memoryId") !in retainedIds) continue
            val oldMemory = memoryRows[row.text("memoryId")] ?: continue
            row.addProperty("scopeId", scope.id)
            row.addProperty("factKey", "${id("scope", requireNotNull(oldMemory.text("scopeId")))}:${oldMemory.text("factKey")}")
            row.addProperty("memoryId", id("memory", requireNotNull(row.text("memoryId"))))
            row.text("questionMessageId")?.let { row.addProperty("questionMessageId", id("message", it)) }
            sql.insertTransfer("companion_clarifications", row)
        }
        for (row in transferRows(directory, "companion_locked_reviews")) {
            if (row.text("lockedMemoryId") !in retainedIds || row.text("confirmedMemoryId") !in retainedIds) {
                row.addProperty("resolvedAt", Instant.now().toString()); row.addProperty("resolution", "obsolete")
            }
            row.addProperty("id", id("locked-review", requireNotNull(row.text("id"))))
            row.addProperty("scopeId", scope.id); row.addProperty("sessionId", target.id)
            for (column in listOf("lockedMemoryId", "confirmedMemoryId")) row.addProperty(column, id("memory", requireNotNull(row.text(column))))
            sql.insertTransfer("companion_locked_reviews", row)
        }
        // 这是迁入基线，不是模型抽取回执。历史可以检索，新消息仍由插入触发器入队。
        sql.execSQL("DELETE FROM companion_pending WHERE scopeId=?", arrayOf(scope.id))
        dao.saveScope(scope.copy(baselineRowId = dao.lastRowId(target.id), archiveRowId = 0,
            archiveMessageId = null, historyAccess = true, updatedAt = Instant.now().toString()))
    }
}
