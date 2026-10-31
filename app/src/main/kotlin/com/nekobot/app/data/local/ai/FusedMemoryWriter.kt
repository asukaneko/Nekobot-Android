package com.nekobot.app.data.local.ai

import androidx.room.withTransaction
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.*
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal data class FusedMemoryWriteResult(val memoryId: String? = null, val reason: String? = null,
    val skipped: Boolean = false, val uncertain: Boolean = false) {
    val success get() = reason == null
}

/** 总结记忆、转换和本轮工具共用的单条事务。回执由流完整结束后另写。 */
internal class FusedMemoryWriter(
    private val dao: CompanionMemoryDao,
    private val transaction: suspend (suspend () -> FusedMemoryWriteResult) -> FusedMemoryWriteResult,
    private val source: suspend (String) -> LocalMessageEntity?,
    private val ownsScope: suspend (CompanionScopeEntity) -> Boolean,
    private val log: (String, String) -> Unit = ::fusedMemoryLog
) {
    constructor(db: NekobotDatabase) : this(db.companionMemoryDao(), { block -> db.withTransaction { block() } },
        { db.messageDao().getById(it) }, { scope ->
            db.isOpen && db.dbName == scope.profile && db.companionMemoryDao().scope(scope.id) != null &&
                db.sessionDao().getById(scope.sessionId)?.let { it.usesFusedCompanionMemory() && it.characterId == scope.characterId } == true
        })

    suspend fun write(scope: CompanionScopeEntity, line: FusedMemoryLine, refs: FusedMemoryReferences,
        tool: Boolean = false, language: String = Locale.getDefault().toLanguageTag(), newMemoryExpiresAt: String? = null): FusedMemoryWriteResult {
        val addRef = if (line is FusedMemoryLine.Operation && line.value.op == "add" || line is FusedMemoryLine.Rejected && line.add)
            refs.reserveAdd() else null
        fun reject(reason: String, ref: String? = null): FusedMemoryWriteResult {
            log("warn", "dropped reason=$reason ref=${ref.orEmpty().replace(Regex("[\\r\\n]"), " ").take(80)}")
            return FusedMemoryWriteResult(reason = reason)
        }
        if (line is FusedMemoryLine.Rejected) return reject(line.reason, line.ref)
        if (line !is FusedMemoryLine.Operation) return reject("bad_format")
        val op = line.value
        // 工具边界必须在幂等命中前核对，不能用旧成功操作绕过本轮编号限制。
        if (tool && op.evidence != listOf("S1")) return reject("bad_format")
        if (tool) listOfNotNull(op.target, op.doubt).firstOrNull { !it.matches(Regex("M[1-9][0-9]*")) }
            ?.let { return reject("unknown_ref", it) }
        val result = transaction {
            if (!ownsScope(scope)) return@transaction reject("source_changed")
            val targetId = op.target?.let(refs::memoryId)
            val evidenceIds = op.evidence.mapNotNull { refs.sources[it]?.id }
            val evidence = op.evidence.mapNotNull { refs.sources[it] }.distinctBy { it.id }
            // 同一消息编辑后是新的证据版本；先核原话，再核操作回执。
            for (row in evidence) {
                val fresh = source(row.id)
                if (fresh == null || fresh.deleted || fresh.sessionId != scope.sessionId ||
                    CompanionMemoryPolicy.fingerprint(fresh) != CompanionMemoryPolicy.fingerprint(row)) {
                    return@transaction reject("source_changed", op.evidence.firstOrNull { refs.sources[it]?.id == row.id })
                }
            }
            val legacyKey = FusedMemoryValidation.opKey(scope.id, op, targetId, evidenceIds)
            val key = "v2:" + FusedMemoryValidation.opKey(scope.id, op, targetId,
                evidence.map { it.id + ":" + CompanionMemoryPolicy.fingerprint(it) })
            // 仅对合法映射计算幂等。重跑曾成功的操作允许目标已被那次操作作废。
            if (evidenceIds.size == op.evidence.size && (op.target == null || targetId != null)) {
                for (candidate in listOf(key, legacyKey)) dao.appliedOp(scope.id, candidate)?.let { applied ->
                    val resultRow = (applied.memoryId ?: targetId)?.let { dao.memory(it) }
                    val invalidated = resultRow?.voidedReason in setOf("source_edited", "source_deleted")
                    val sameSources = if (candidate == key) true else resultRow?.let { row ->
                        val saved = dao.sources(row.id).associateBy { it.messageId }
                        evidence.all { saved[it.id]?.fingerprint == CompanionMemoryPolicy.fingerprint(it) }
                    } == true
                    if (!invalidated && sameSources) {
                        log("debug", "idempotent_skip opKey=$candidate")
                        return@transaction FusedMemoryWriteResult(applied.memoryId, skipped = true)
                    }
                    // 原话退回同一内容也不能被已经连带作废的操作挡住。历史记忆和来源不动。
                    if (candidate == key && invalidated) dao.removeAppliedOp(scope.id, key)
                }
            }
            val targets = listOfNotNull(targetId, op.doubt?.let(refs::memoryId)).distinct()
                .mapNotNull { dao.memory(it)?.takeIf { row -> row.scopeId == scope.id } }.associateBy { it.id }
            val (valid, failure) = FusedMemoryValidation.validate(op, refs, targets, tool)
            if (failure != null) return@transaction reject(failure.reason, failure.ref)
            checkNotNull(valid)
            val now = Instant.now().toString()
            val old = valid.target
            val protected = old != null && (old.locked || old.manual)
            val confirming = old?.pendingConfirm == true && !protected
            val pending = protected || (old == null && ("待确认" in op.tags || valid.doubt != null))
            val target = if (protected) null else old
            val date = FusedMemoryDate.resolve(op, valid.sources)
            if (date.missingTimeEvidence) log("debug", "time_text_not_in_evidence")
            if (date.overridden) log("debug", "date_override program=${date.day} model=${op.modelDate}")
            val content = op.content.ifBlank {
                if (protected) protectedQuestion(old!!.content, language) else old?.content.orEmpty()
            }
            val status = when (op.op) { "cancel" -> "cancelled"; "complete" -> "completed"; else -> "active" }
            val same = !protected && !confirming && op.op == "update" && old != null &&
                FusedMemoryValidation.normalized(content) == FusedMemoryValidation.normalized(old.content)
            val memory: CompanionMemoryEntity?
            if (same) {
                // 同正文只补证据，不制造修订；显式kind仍可调整核心归属。
                memory = old!!.copy(kind = op.kind ?: old.kind)
                dao.saveMemory(memory)
            } else if (op.op == "retract" && !protected) {
                memory = null
                if (confirming) dao.saveMemory(old!!.copy(pendingConfirm = false,
                    tagsJson = MemoryTags.toJson(MemoryTags.fromJson(old.tagsJson).filterNot { it == "待确认" }), updatedAt = now))
                dao.voidMemory(old!!.id, "retract", now)
            } else {
                val factKey = target?.factKey ?: UUID.randomUUID().toString()
                val tags = if (confirming) op.tags.filterNot { it == "待确认" }
                    else if (pending) (op.tags + "待确认").distinct() else op.tags
                memory = CompanionMemoryEntity(
                    id = UUID.randomUUID().toString(), scopeId = scope.id, factKey = factKey,
                    version = if (target == null) 1 else (dao.versions(scope.id, factKey).maxOfOrNull { it.version } ?: 0) + 1,
                    kind = op.kind ?: target?.kind ?: if (valid.category == "episode") "episode" else "fact",
                    subject = target?.subject ?: "", content = content, status = if (pending) "active" else status,
                    category = valid.category, eventDate = date.day?.toString(),
                    annualMonthDay = if ((op.kind ?: target?.kind) == "birthday") CompanionMemoryPolicy.annualDay(op.timeText, date.day) else null,
                    tagsJson = MemoryTags.toJson(tags), pendingConfirm = pending,
                    uncertainTargetId = if (protected) old!!.id else valid.doubt?.id ?: target?.uncertainTargetId,
                    alwaysProvide = target?.alwaysProvide ?: false, coreExcluded = target?.coreExcluded ?: false,
                    createdAt = now, updatedAt = now, expiresAt = if (target != null) target.expiresAt else newMemoryExpiresAt,
                    timeText = op.timeText, modelDate = op.modelDate, dateType = op.dateType, dateSource = date.source)
                dao.saveMemory(memory)
                if (target != null) dao.voidMemory(target.id, op.op, now, memory.id)
            }
            if (memory != null) {
                val inherited = target?.let { dao.sources(it.id) }.orEmpty().map { it.copy(memoryId = memory.id) }
                val added = valid.sources.map { CompanionSourceEntity(memory.id, it.id, CompanionMemoryPolicy.fingerprint(it), "",
                    agentUtteranceDay(it)?.toString(), it.role, it.content) }
                dao.saveSources((inherited + added).associateBy { it.messageId }.values.toList())
            }
            if (confirming && old?.uncertainTargetId != null) {
                val original = dao.memory(old.uncertainTargetId)?.takeIf { it.scopeId == scope.id && it.current && it.valid && !it.voided }
                if (original?.locked == true) {
                    val review = CompanionLockedReviewEntity(UUID.randomUUID().toString(), scope.id, scope.sessionId,
                        original.id, memory?.id ?: old.id, now)
                    dao.insertLockedReview(review)
                    log("info", "locked_review_enqueued reviewId=${review.id} lockedMemoryId=${original.id} confirmedMemoryId=${review.confirmedMemoryId}")
                } else if (original != null) {
                    dao.voidMemory(original.id, "uncertain_confirmed", now, memory?.id)
                    log("info", "uncertain_confirmed uncertain=${old.id} target=${original.id}")
                }
            }
            dao.insertAppliedOp(CompanionAppliedOpEntity(scope.id, key, memory?.id, now))
            dao.bumpRevision(scope.id, now)
            if (protected) log("info", "reason=protected_to_uncertain target=${old!!.id} op=${op.op}")
            log("debug", "stored op=${op.op} target=${targetId.orEmpty()} category=${valid.category} dateSource=${date.source}")
            FusedMemoryWriteResult(memory?.id, uncertain = pending)
        }
        // 编号只有在事务成功提交后才有效。
        if (result.success && addRef != null) refs.bind(addRef, result.memoryId)
        return result
    }

    companion object {
        fun protectedQuestion(content: String, language: String): String = when {
            language.startsWith("zh-TW", true) || language.startsWith("zh-Hant", true) -> "我記得之前是「$content」，好像有變化了？"
            language.startsWith("en", true) -> "I remember it was ‘$content’. Has something changed?"
            language.startsWith("ja", true) -> "前は「$content」だったと覚えているけど、何か変わったのかな？"
            language.startsWith("ko", true) -> "전에는 ‘$content’라고 기억하는데, 뭔가 달라졌을까?"
            else -> "我记得之前是“$content”，好像有变化了？"
        }
    }
}

internal fun fusedMemoryLog(level: String, message: String) {
    when (level) {
        "warn" -> LocalLogger.w("CompanionMemory", message)
        "info" -> LocalLogger.i("CompanionMemory", message)
        else -> LocalLogger.d("CompanionMemory", message)
    }
}
