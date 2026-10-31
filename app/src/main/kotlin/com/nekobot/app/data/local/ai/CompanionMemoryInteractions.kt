package com.nekobot.app.data.local.ai

import androidx.room.withTransaction
import com.nekobot.app.data.local.db.*
import java.time.Instant

/** 出处弹窗显示完整原话；删除或编辑后保留历史快照。 */
internal data class CompanionMemorySourceView(val source: CompanionSourceEntity, val content: String,
    val originalDeleted: Boolean, val snapshot: Boolean)
internal data class CompanionLockedReviewItem(val review: CompanionLockedReviewEntity,
    val locked: CompanionMemoryEntity, val confirmed: CompanionMemoryEntity)
internal class MemoryAttachmentDecisionRequired(val attached: List<CompanionMemoryEntity>) :
    IllegalStateException(FusedMemoryText.DECISION.value())

/** 用户看到的完整范围，提交事务内再次核对，防止确认后新增关联被静默处理。 */
data class CompanionSourceExpectation(val messageIds: List<String>, val memoryIds: Set<String>)

internal object CompanionMemoryInteractionPolicy {
    fun affected(history: List<LocalMessageEntity>, messageId: String, regenerate: Boolean): List<LocalMessageEntity> {
        val index = history.indexOfFirst { it.id == messageId }
        require(index >= 0)
        return if (regenerate) history.drop(index) else listOf(history[index])
    }
    fun obsolete(review: CompanionLockedReviewEntity, locked: CompanionMemoryEntity?, confirmed: CompanionMemoryEntity?): Boolean =
        locked == null || confirmed == null || locked.scopeId != review.scopeId || confirmed.scopeId != review.scopeId ||
            !locked.locked || !locked.current || !locked.valid || locked.voided ||
            !confirmed.valid || ((!confirmed.current || confirmed.voided) && !confirmedRetraction(review, confirmed))

    // retract确认本身没有新的有效版本，以同事务作废行展示撤回结果；后续再次变更仍使队列失效。
    private fun confirmedRetraction(review: CompanionLockedReviewEntity, confirmed: CompanionMemoryEntity): Boolean =
        confirmed.voided && confirmed.voidedReason == "retract" && !confirmed.pendingConfirm &&
            confirmed.voidedAt == review.createdAt && confirmed.updatedAt == review.createdAt

    fun source(source: CompanionSourceEntity, row: LocalMessageEntity?): CompanionMemorySourceView {
        val deleted = row == null || row.deleted
        val changed = row != null && CompanionMemoryPolicy.fingerprint(row) != source.fingerprint
        val snapshot = deleted || changed
        return CompanionMemorySourceView(source, if (snapshot) source.messageSnapshot ?: row?.takeUnless { changed }?.content ?: source.quote else row!!.content,
            deleted, snapshot)
    }
}

/** 界面确认前的仓库接口：任何选择与原消息变化都在同一个事务内提交。 */
internal class CompanionMemoryInteractions(private val db: NekobotDatabase) {
    private val dao = db.companionMemoryDao()
    private suspend fun scope(session: LocalSessionEntity) = CompanionMemoryStore(db).ensureScope(session).also {
        require(session.usesFusedCompanionMemory())
    }
    suspend fun attached(session: LocalSessionEntity, messageIds: List<String>): List<CompanionMemoryEntity> {
        val scope = scope(session)
        return messageIds.distinct().chunked(400).flatMap { dao.memoriesBySourceMessages(it) }
            .filter { it.scopeId == scope.id }.distinctBy { it.id }
    }
    /** 取消选择只记录操作，不调用mutate。 */
    suspend fun cancelMutation(session: LocalSessionEntity, messageIds: List<String>) {
        fusedMemoryLog("info", "messageId=${messageIds.firstOrNull()} attached=${attached(session, messageIds).size} choice=cancel")
    }
    suspend fun mutate(session: LocalSessionEntity, rows: List<LocalMessageEntity>, keepMemories: Boolean?,
        editedId: String? = null, expectation: CompanionSourceExpectation? = null, change: suspend () -> Unit) =
        CompanionSourceMutation(dao, { block -> db.withTransaction { block() } }, { db.messageDao().getById(it) })
            .mutate(scope(session), rows, keepMemories, editedId, expectation, change)

    suspend fun sources(session: LocalSessionEntity, memoryId: String): List<CompanionMemorySourceView> {
        val scope = scope(session)
        require(dao.memory(memoryId)?.scopeId == scope.id)
        return dao.sources(memoryId).map { CompanionMemoryInteractionPolicy.source(it, db.messageDao().getById(it.messageId)) }
    }

    suspend fun pendingReviews(session: LocalSessionEntity): List<CompanionLockedReviewItem> = db.withTransaction {
        val scope = scope(session)
        dao.pendingLockedReviews(session.id).mapNotNull { review ->
            val original = dao.memory(review.lockedMemoryId)
            val confirmed = dao.memory(review.confirmedMemoryId)
            if (review.scopeId != scope.id || CompanionMemoryInteractionPolicy.obsolete(review, original, confirmed)) {
                dao.resolveLockedReview(review.id, "obsolete", Instant.now().toString())
                fusedMemoryLog("info", "reviewId=${review.id} resolution=obsolete")
                null
            } else CompanionLockedReviewItem(review, original!!, confirmed!!)
        }
    }

    suspend fun resolveReview(session: LocalSessionEntity, id: String, resolution: String, editedContent: String? = null) = db.withTransaction {
        require(resolution in setOf("edit", "delete", "keep"))
        val item = pendingReviews(session).firstOrNull { it.review.id == id } ?: return@withTransaction
        CompanionMemoryRevisionActions.resolve(dao, item, resolution, editedContent) { original, content ->
            CompanionMemoryStore(db).editFused(session, original.factKey, content, original.category,
                MemoryTags.fromJson(original.tagsJson), CompanionCoreMemory.contains(original), true)
        }
    }
}

/** 调用者提供事务，修订、恢复和队列解决共用可测试的数据库操作。 */
internal object CompanionMemoryRevisionActions {
    suspend fun delete(dao: CompanionMemoryDao, scopeId: String, factKey: String) {
        val now = Instant.now().toString()
        for (row in dao.versions(scopeId, factKey).filter { it.current && !it.voided }) dao.voidMemory(row.id, "user", now)
        dao.bumpRevision(scopeId, now)
    }
    suspend fun resolve(dao: CompanionMemoryDao, item: CompanionLockedReviewItem, resolution: String,
        editedContent: String?, edit: suspend (CompanionMemoryEntity, String) -> Unit) {
        require(resolution in setOf("edit", "delete", "keep"))
        val now = Instant.now().toString()
        when (resolution) {
            "edit" -> { require(!editedContent.isNullOrBlank()); edit(item.locked, editedContent) }
            "delete" -> { dao.voidMemory(item.locked.id, "review_deleted", now); dao.bumpRevision(item.review.scopeId, now) }
            "keep" -> Unit
        }
        dao.resolveLockedReview(item.review.id, resolution, now)
        fusedMemoryLog("info", "reviewId=${item.review.id} lockedMemoryId=${item.locked.id} confirmedMemoryId=${item.confirmed.id} resolution=$resolution")
    }
    suspend fun restore(dao: CompanionMemoryDao, scopeId: String, memoryId: String) {
        val row = requireNotNull(dao.memory(memoryId)?.takeIf { it.scopeId == scopeId && it.voided && it.content.isNotBlank() })
        val now = Instant.now().toString()
        for (current in dao.versions(scopeId, row.factKey).filter { it.current && !it.voided }) dao.voidMemory(current.id, "user", now, row.id)
        dao.saveMemory(row.copy(voided = false, voidedAt = null, voidedReason = null, supersededById = null,
            current = true, valid = true, updatedAt = now))
        dao.bumpRevision(scopeId, now)
    }
}

/** 真实生产来源变更事务，传输接口可替换以验证取消、回滚及选择分支。 */
internal class CompanionSourceMutation(private val dao: CompanionMemoryDao,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
    private val read: suspend (String) -> LocalMessageEntity?) {
    suspend fun mutate(scope: CompanionScopeEntity, rows: List<LocalMessageEntity>, keepMemories: Boolean?,
        editedId: String? = null, expectation: CompanionSourceExpectation? = null, change: suspend () -> Unit) = transaction {
        require(rows.all { it.sessionId == scope.sessionId && read(it.id)?.let { fresh ->
            CompanionMemoryPolicy.fingerprint(fresh) == CompanionMemoryPolicy.fingerprint(it)
        } == true }) { FusedMemoryText.SOURCE_CHANGED.value() }
        val memories = rows.map { it.id }.chunked(400).flatMap { dao.memoriesBySourceMessages(it) }.filter { it.scopeId == scope.id }.distinctBy { it.id }
        if (expectation != null && (rows.map { it.id } != expectation.messageIds ||
                memories.map { it.id }.toSet() != expectation.memoryIds)) throw MemoryAttachmentDecisionRequired(memories)
        if (memories.isNotEmpty() && keepMemories == null) throw MemoryAttachmentDecisionRequired(memories)
        val byId = rows.associateBy { it.id }
        val now = Instant.now().toString()
        for (memory in memories) {
            // 即使一起作废，也保留原话快照供修订历史查看。
            dao.saveSources(dao.sources(memory.id).map { source -> byId[source.messageId]?.let {
                source.copy(messageSnapshot = source.messageSnapshot ?: it.content)
            } ?: source })
            if (keepMemories == false) dao.voidMemory(memory.id, if (editedId == null) "source_deleted" else "source_edited", now)
        }
        change()
        if (editedId != null) {
            val updated = requireNotNull(read(editedId))
            if (keepMemories == true) dao.saveReceipts(listOf(CompanionReceiptEntity(scope.id, editedId, CompanionMemoryPolicy.fingerprint(updated))))
            else dao.removeReceipts(scope.id, listOf(editedId))
        }
        dao.bumpRevision(scope.id, now)
        fusedMemoryLog("info", "messageId=${rows.firstOrNull()?.id} attached=${memories.size} choice=${if (keepMemories == true) "keep" else "delete"}")
    }
}

/** 前台会话、生成结束及消息落库后才取下一条；关闭后等下一次回复边界。 */
internal class CompanionLockedReviewQueue {
    data class State(val sessionId: String?, val foreground: Boolean, val generating: Boolean,
        val summarizing: Boolean, val replySaved: Boolean, val generationFinished: Boolean, val boundary: String)
    private val dismissed = mutableMapOf<String, String>()
    private val shown = mutableMapOf<String, String>()
    @Synchronized fun next(state: State, reviews: List<CompanionLockedReviewEntity>): CompanionLockedReviewEntity? {
        if (!state.foreground || state.generating || state.summarizing || !state.replySaved || !state.generationFinished) return null
        return reviews.filter { it.sessionId == state.sessionId && it.resolvedAt == null }
            .minWithOrNull(compareBy<CompanionLockedReviewEntity> { it.createdAt }.thenBy { it.id })
            ?.takeUnless { dismissed[it.id] == state.boundary }
            ?.also { review -> if (shown[review.id] != state.boundary) {
                shown[review.id] = state.boundary
                fusedMemoryLog("info", "locked_review_ready reviewId=${review.id} lockedMemoryId=${review.lockedMemoryId} confirmedMemoryId=${review.confirmedMemoryId}")
            } }
    }
    @Synchronized fun dismiss(id: String, boundary: String) {
        dismissed[id] = boundary
        fusedMemoryLog("info", "reviewId=$id resolution=dismissed")
    }
}
