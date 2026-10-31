package com.nekobot.app.data.local.db

import androidx.room.*

/** Database file, conversation and character jointly own a memory scope. No shared MemoryFS writes. */
@Entity(tableName = "companion_scopes", indices = [Index("sessionId")])
data class CompanionScopeEntity(
    @PrimaryKey val id: String,
    val profile: String,
    val sessionId: String,
    val characterId: String,
    val baselineRowId: Long,
    val archiveRowId: Long = 0,
    val archiveMessageId: String? = null,
    val historyAccess: Boolean = false,
    val revision: Long = 0,
    val error: String? = null,
    val updatedAt: String,
    /** null 沿用未设置前的行为，空串仅关键词，其他值固定到已有模型配置。 */
    val embeddingModelId: String? = null
)

/** 单会话记忆保留作废版本；待确认记录与当前事实分开提供。 */
@Entity(tableName = "companion_memories", indices = [Index("scopeId"), Index(value = ["scopeId", "factKey", "version"], unique = true),
    Index(value = ["scopeId", "pendingConfirm", "voided"], name = "index_companion_memories_scope_pending")])
data class CompanionMemoryEntity(
    @PrimaryKey val id: String,
    val scopeId: String,
    val factKey: String,
    val version: Int,
    val kind: String, // core / fact / task / birthday
    val subject: String,
    val content: String,
    val status: String, // active / completed / cancelled / conflict
    val eventDate: String? = null,
    val annualMonthDay: String? = null,
    val tagsJson: String = "[]",
    val current: Boolean = true,
    val valid: Boolean = true,
    val locked: Boolean = false,
    @ColumnInfo(defaultValue = "'user_profile'") val category: String = "user_profile",
    @ColumnInfo(defaultValue = "0") val alwaysProvide: Boolean = false,
    @ColumnInfo(defaultValue = "0") val manual: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    @ColumnInfo(defaultValue = "'native'") val provenance: String = "native",
    val expiresAt: String? = null,
    @ColumnInfo(defaultValue = "0") val coreExcluded: Boolean = false,
    @ColumnInfo(defaultValue = "0") val voided: Boolean = false,
    val voidedAt: String? = null,
    val voidedReason: String? = null,
    val supersededById: String? = null,
    @ColumnInfo(defaultValue = "0") val pendingConfirm: Boolean = false,
    val uncertainTargetId: String? = null,
    @ColumnInfo(defaultValue = "0") val uncertainInjectedTurns: Int = 0,
    val timeText: String? = null,
    val modelDate: String? = null,
    val dateType: String? = null,
    val dateSource: String? = null
)

@Entity(tableName = "companion_sources", primaryKeys = ["memoryId", "messageId"], indices = [Index("messageId")])
data class CompanionSourceEntity(
    val memoryId: String,
    val messageId: String,
    val fingerprint: String,
    val quote: String,
    val spokenDate: String?,
    val role: String,
    val messageSnapshot: String? = null
)

@Entity(tableName = "companion_applied_ops", primaryKeys = ["scopeId", "opKey"])
data class CompanionAppliedOpEntity(val scopeId: String, val opKey: String, val memoryId: String?, val createdAt: String)

@Entity(tableName = "companion_locked_reviews", indices = [
    Index(value = ["sessionId", "resolvedAt"], name = "index_companion_locked_reviews_pending")])
data class CompanionLockedReviewEntity(
    @PrimaryKey val id: String, val scopeId: String, val sessionId: String,
    val lockedMemoryId: String, val confirmedMemoryId: String, val createdAt: String,
    val resolvedAt: String? = null, val resolution: String? = null
)

/** A receipt is committed with all derived facts. Missing receipts are the persistent pending queue. */
@Entity(tableName = "companion_receipts", primaryKeys = ["scopeId", "messageId"], indices = [Index("messageId")])
data class CompanionReceiptEntity(val scopeId: String, val messageId: String, val fingerprint: String)

/** Retain only source IDs after forgetting, so a later raw-history search cannot resurrect it. */
@Entity(tableName = "companion_forgotten_sources", primaryKeys = ["scopeId", "messageId"])
data class CompanionForgottenSourceEntity(val scopeId: String, val messageId: String)

@Entity(tableName = "companion_pending", primaryKeys = ["scopeId", "messageId"])
data class CompanionPendingEntity(val scopeId: String, val messageId: String)

/** Rebuildable indexes. The model identity and source digest prevent mixed vector spaces. */
@Entity(tableName = "companion_vectors", primaryKeys = ["scopeId", "itemType", "itemId", "modelKey"])
data class CompanionVectorEntity(
    val scopeId: String, val itemType: String, val itemId: String, val modelKey: String,
    val fingerprint: String, val vectorJson: String, val updatedAt: String
)

/** One open clarification per canonical fact; original messages remain the audit trail. */
@Entity(tableName = "companion_clarifications", primaryKeys = ["scopeId", "factKey"])
data class CompanionClarificationEntity(
    val scopeId: String, val factKey: String, val memoryId: String, val question: String,
    val questionMessageId: String? = null, val createdAt: String, val updatedAt: String
)

/** A single joined scan avoids one database round trip per historical utterance. */
data class CompanionSourceValidation(
    val memoryId: String, val messageId: String, val fingerprint: String, val quote: String,
    val sourceRole: String, val storedId: String?, val sessionId: String?, val role: String?,
    val content: String?, val createdAt: String?, val timestamp: String?, val deleted: Boolean?
)

@Dao
interface CompanionMemoryDao {
    @Query("SELECT * FROM companion_memories WHERE id=:id")
    suspend fun memory(id: String): CompanionMemoryEntity?
    @Query("SELECT * FROM companion_applied_ops WHERE scopeId=:scopeId AND opKey=:key")
    suspend fun appliedOp(scopeId: String, key: String): CompanionAppliedOpEntity?
    @Query("DELETE FROM companion_applied_ops WHERE scopeId=:scopeId AND opKey=:key")
    suspend fun removeAppliedOp(scopeId: String, key: String)
    @Query("SELECT opKey FROM companion_applied_ops WHERE scopeId=:scopeId AND substr(opKey,1,length(:prefix))=:prefix")
    suspend fun appliedOpKeys(scopeId: String, prefix: String): List<String>
    @Query("SELECT memoryId FROM companion_applied_ops WHERE scopeId=:scopeId AND opKey LIKE :prefix || '%' AND memoryId IS NOT NULL")
    suspend fun injectedMemoryIds(scopeId: String, prefix: String): List<String>
    @Insert suspend fun insertAppliedOp(row: CompanionAppliedOpEntity)
    @Insert suspend fun insertLockedReview(row: CompanionLockedReviewEntity)
    @Query("SELECT * FROM companion_locked_reviews WHERE id=:id")
    suspend fun lockedReview(id: String): CompanionLockedReviewEntity?
    @Query("SELECT * FROM companion_locked_reviews WHERE sessionId=:sessionId AND resolvedAt IS NULL ORDER BY createdAt,id")
    suspend fun pendingLockedReviews(sessionId: String): List<CompanionLockedReviewEntity>
    @Query("UPDATE companion_locked_reviews SET resolvedAt=:at,resolution=:resolution WHERE id=:id AND resolvedAt IS NULL")
    suspend fun resolveLockedReview(id: String, resolution: String, at: String)
    @Query("UPDATE companion_memories SET current=0,voided=1,voidedAt=:at,voidedReason=:reason,supersededById=:supersededBy,updatedAt=:at WHERE id=:id")
    suspend fun voidMemory(id: String, reason: String, at: String, supersededBy: String? = null)
    @Query("SELECT * FROM companion_memories WHERE scopeId=:scopeId AND valid=1 AND current=1 AND voided=0 AND pendingConfirm=1 ORDER BY createdAt DESC,id")
    suspend fun pendingConfirmMemories(scopeId: String): List<CompanionMemoryEntity>
    @Query("SELECT DISTINCT m.* FROM companion_memories m JOIN companion_sources s ON s.memoryId=m.id WHERE s.messageId IN (:ids) AND m.valid=1 AND m.current=1 AND m.voided=0 ORDER BY m.createdAt,m.id")
    suspend fun memoriesBySourceMessages(ids: List<String>): List<CompanionMemoryEntity>
    @Query("UPDATE companion_memories SET uncertainInjectedTurns=uncertainInjectedTurns+1 WHERE id IN (:ids) AND pendingConfirm=1 AND voided=0")
    suspend fun incrementUncertainInjected(ids: List<String>)
    @Query("SELECT * FROM companion_receipts WHERE scopeId=:scopeId")
    suspend fun receipts(scopeId: String): List<CompanionReceiptEntity>
    @Query("DELETE FROM companion_receipts WHERE scopeId=:scopeId AND messageId IN (:ids)")
    suspend fun removeReceipts(scopeId: String, ids: List<String>)
    @Query("UPDATE companion_scopes SET revision=revision+1,updatedAt=:at WHERE id=:scopeId")
    suspend fun bumpRevision(scopeId: String, at: String)
    @Query("""SELECT x.memoryId, x.messageId, x.fingerprint, x.quote, x.role AS sourceRole,
        r.id AS storedId, r.session_id AS sessionId, r.role, r.content,
        r.created_at AS createdAt, r.timestamp, r.deleted
        FROM companion_sources x JOIN companion_memories m ON m.id=x.memoryId
        LEFT JOIN local_messages r ON r.id=x.messageId
        WHERE m.scopeId=:scopeId AND m.valid=1 AND m.manual=0 AND m.id IN (:ids)""")
    suspend fun sourceValidationRows(scopeId: String, ids: List<String>): List<CompanionSourceValidation>
    @Query("SELECT messageId FROM companion_sources WHERE memoryId=:memoryId ORDER BY spokenDate,messageId LIMIT 3")
    suspend fun sourceAnchorIds(memoryId: String): List<String>
    @Query("SELECT * FROM companion_context_materials WHERE scopeId = :scopeId ORDER BY sequence")
    suspend fun contextMaterials(scopeId: String): List<CompanionContextMaterialEntity>
    @Query("SELECT * FROM companion_context_materials WHERE messageId = :messageId AND scopeId IN (SELECT id FROM companion_scopes WHERE profile = :profile AND sessionId = :sessionId) LIMIT 1")
    suspend fun diagnosticContext(profile: String, sessionId: String, messageId: String): CompanionContextMaterialEntity?
    @Upsert suspend fun saveContextMaterial(row: CompanionContextMaterialEntity)
    @Query("DELETE FROM companion_context_materials WHERE scopeId = :scopeId")
    suspend fun eraseContextMaterials(scopeId: String)
    @Query("SELECT * FROM companion_owners WHERE characterId = :characterId")
    suspend fun owner(characterId: String): CompanionOwnerEntity?
    @Query("SELECT * FROM companion_vectors WHERE scopeId = :scopeId AND modelKey = :modelKey")
    suspend fun vectors(scopeId: String, modelKey: String): List<CompanionVectorEntity>
    @Upsert suspend fun saveVectors(rows: List<CompanionVectorEntity>)
    @Query("DELETE FROM companion_vectors WHERE scopeId = :scopeId AND (modelKey != :modelKey OR itemType || ':' || itemId NOT IN (:ids))")
    suspend fun pruneVectors(scopeId: String, modelKey: String, ids: List<String>)
    @Query("DELETE FROM companion_vectors WHERE scopeId = :scopeId AND modelKey = :modelKey AND itemType || ':' || itemId IN (:ids)")
    suspend fun eraseVectorItems(scopeId: String, modelKey: String, ids: List<String>)
    @Query("DELETE FROM companion_vectors WHERE scopeId = :scopeId")
    suspend fun eraseVectors(scopeId: String)
    @Query("SELECT * FROM companion_clarifications WHERE scopeId = :scopeId")
    suspend fun clarifications(scopeId: String): List<CompanionClarificationEntity>
    @Upsert suspend fun saveClarification(row: CompanionClarificationEntity)
    @Query("DELETE FROM companion_clarifications WHERE scopeId = :scopeId AND factKey = :key")
    suspend fun closeClarification(scopeId: String, key: String)
    @Query("DELETE FROM companion_sources WHERE memoryId = :id")
    suspend fun eraseMemorySources(id: String)
    @Query("DELETE FROM companion_memories WHERE scopeId = :scopeId AND id = :id AND locked = 0")
    suspend fun eraseReplacedMemory(scopeId: String, id: String)
    @Query("DELETE FROM companion_vectors WHERE scopeId = :scopeId AND itemType IN ('memory','fact') AND itemId = :id")
    suspend fun eraseMemoryVectors(scopeId: String, id: String)
    @Query("DELETE FROM companion_memories WHERE id = :id AND current = 0 AND status = 'conflict'")
    suspend fun eraseConflict(id: String)
    @Query("SELECT * FROM companion_scopes WHERE id = :id")
    suspend fun scope(id: String): CompanionScopeEntity?
    @Query("SELECT * FROM companion_scopes WHERE profile = :profile AND sessionId = :sessionId AND characterId = :characterId LIMIT 1")
    suspend fun scopeByOwner(profile: String, sessionId: String, characterId: String): CompanionScopeEntity?
    @Upsert suspend fun saveScope(scope: CompanionScopeEntity)
    @Query("SELECT COALESCE(MAX(rowid), 0) FROM local_messages WHERE session_id = :sessionId")
    suspend fun lastRowId(sessionId: String): Long
    @Query("SELECT COUNT(*) FROM companion_scopes WHERE sessionId = :sessionId")
    suspend fun scopeCount(sessionId: String): Int
    @Query("SELECT messageId FROM companion_pending WHERE scopeId = :scopeId")
    suspend fun ownedMessages(scopeId: String): List<String>
    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId AND deleted = 0 AND role IN ('user','assistant') AND substr(created_at, 1, 10) = :day ORDER BY created_at, rowid LIMIT :limit")
    suspend fun historyOnDay(sessionId: String, day: String, limit: Int = 512): List<LocalMessageEntity>
    @Query("SELECT m.* FROM local_messages m JOIN companion_pending p ON p.messageId = m.id JOIN companion_scopes s ON s.id = p.scopeId AND s.sessionId = m.session_id WHERE s.id = :scopeId AND m.deleted = 0 AND m.role IN ('user','assistant') AND NOT EXISTS(SELECT 1 FROM companion_receipts r WHERE r.scopeId = :scopeId AND r.messageId = m.id) ORDER BY m.created_at, m.rowid LIMIT :limit")
    suspend fun pending(scopeId: String, limit: Int = 512): List<LocalMessageEntity>
    @Query("SELECT m.* FROM local_messages m JOIN companion_scopes s ON s.sessionId = m.session_id JOIN companion_pending p ON p.scopeId = s.id AND p.messageId = m.id WHERE s.id = :scopeId AND m.rowid > s.baselineRowId AND m.deleted = 0 AND m.role IN ('user','assistant') AND NOT EXISTS (SELECT 1 FROM local_experience_sources x JOIN local_experience_archives a ON a.id = x.archive_id WHERE x.message_id = m.id AND a.session_id = s.sessionId AND a.character_id = s.characterId AND a.status = 'ready') ORDER BY m.created_at, m.rowid LIMIT :limit")
    suspend fun unarchived(scopeId: String, limit: Int = 512): List<LocalMessageEntity>
    @Query("SELECT * FROM companion_memories WHERE scopeId = :scopeId AND valid = 1 ORDER BY factKey, version DESC")
    suspend fun memories(scopeId: String): List<CompanionMemoryEntity>
    @Query("SELECT DISTINCT s.memoryId FROM companion_sources s JOIN companion_memories m ON m.id=s.memoryId WHERE m.scopeId=:scopeId")
    suspend fun memoriesWithSources(scopeId: String): List<String>
    @Query("SELECT * FROM companion_memories WHERE scopeId=:scopeId AND provenance='conversion_reference' ORDER BY factKey")
    suspend fun conversionReferences(scopeId: String): List<CompanionMemoryEntity>
    @Query("SELECT * FROM companion_memories WHERE scopeId = :scopeId AND factKey = :key ORDER BY version DESC")
    suspend fun versions(scopeId: String, key: String): List<CompanionMemoryEntity>
    @Query("SELECT * FROM companion_memories WHERE scopeId = :scopeId AND (valid = 1 OR voided = 1) ORDER BY updatedAt DESC, factKey, version DESC")
    suspend fun revisionHistory(scopeId: String): List<CompanionMemoryEntity>
    @Query("SELECT * FROM companion_sources WHERE memoryId = :id ORDER BY spokenDate, (SELECT rowid FROM local_messages WHERE id = messageId)")
    suspend fun sources(id: String): List<CompanionSourceEntity>
    @Upsert suspend fun saveMemory(memory: CompanionMemoryEntity)
    @Upsert suspend fun saveSources(sources: List<CompanionSourceEntity>)
    @Upsert suspend fun saveReceipts(receipts: List<CompanionReceiptEntity>)
    @Upsert suspend fun saveForgotten(sources: List<CompanionForgottenSourceEntity>)
    @Upsert suspend fun savePending(rows: List<CompanionPendingEntity>)
    @Query("INSERT OR IGNORE INTO companion_pending(scopeId,messageId) SELECT :scopeId,id FROM local_messages WHERE session_id = :sessionId AND rowid > :baselineRowId AND deleted = 0 AND role IN ('user','assistant')")
    suspend fun seedPending(scopeId: String, sessionId: String, baselineRowId: Long)
    @Query("SELECT messageId FROM companion_forgotten_sources WHERE scopeId = :scopeId")
    suspend fun forgotten(scopeId: String): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM companion_memories WHERE scopeId = :scopeId AND factKey = :key AND content = '')")
    suspend fun isForgotten(scopeId: String, key: String): Boolean
    @Query("UPDATE companion_memories SET current = 0 WHERE scopeId = :scopeId AND factKey = :key")
    suspend fun retire(scopeId: String, key: String)
    @Query("UPDATE companion_memories SET locked = :locked WHERE scopeId = :scopeId AND factKey = :key")
    suspend fun lock(scopeId: String, key: String, locked: Boolean)
    @Query("DELETE FROM companion_sources WHERE memoryId IN (SELECT id FROM companion_memories WHERE scopeId = :scopeId AND factKey = :key)")
    suspend fun eraseSources(scopeId: String, key: String)
    @Query("UPDATE companion_memories SET content = '', tagsJson = '[]', subject = '', eventDate = NULL, annualMonthDay = NULL, valid = 0, current = 0 WHERE scopeId = :scopeId AND factKey = :key")
    suspend fun forget(scopeId: String, key: String)
    @Query("DELETE FROM companion_receipts WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = :sessionId)")
    suspend fun deleteReceipts(sessionId: String)
    @Query("DELETE FROM companion_sources WHERE memoryId IN (SELECT id FROM companion_memories WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = :sessionId))")
    suspend fun deleteSources(sessionId: String)
    @Query("DELETE FROM companion_memories WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = :sessionId)")
    suspend fun deleteMemories(sessionId: String)
    @Query("DELETE FROM companion_scopes WHERE sessionId = :sessionId")
    suspend fun deleteScopes(sessionId: String)
}

/** Ownership survives deleting a window; re-entry requires an explicit external conversion. */
@Entity(tableName = "companion_owners", indices = [Index(value = ["sessionId"], unique = true)])
data class CompanionOwnerEntity(@PrimaryKey val characterId: String, val sessionId: String)

/** Rebuildable request material; never treated as a player's utterance or extraction source. */
@Entity(tableName = "companion_context_materials", primaryKeys = ["scopeId", "messageId"])
data class CompanionContextMaterialEntity(
    val scopeId: String, val messageId: String, val sequence: Long,
    val revision: Long, val configuration: String, val startMessageId: String,
    val content: String, val timeKey: String, val createdAt: Long, val retrieval: String,
    val userPayload: String = ""
)
