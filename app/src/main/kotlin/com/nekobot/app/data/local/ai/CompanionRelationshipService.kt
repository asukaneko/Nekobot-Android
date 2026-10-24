package com.nekobot.app.data.local.ai

import com.nekobot.app.ServiceContainer

import com.nekobot.app.R

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.net.SocketTimeoutException

internal data class CompanionRelationshipView(
    val state: CompanionRelationshipEntity,
    val descriptions: Map<String, String>,
    val events: List<CompanionRelationshipEventEntity>,
    val pending: Int,
    val busy: Boolean,
    val sourceWarning: String? = null,
    val shortDescriptions: Map<String, String> = emptyMap(),
    val feelingsHaveSources: Boolean = false,
    val narrative: RelationshipNarrative? = null,
    val legacy: RelationshipLegacySnapshot? = null,
    val validRelationshipKeys: Set<String> = emptySet()
)

/** 独立后台作业：聊天不等待；配置版本与来源核验共同决定结果是否还能写入。 */
internal class CompanionRelationshipService(
    private val db: NekobotDatabase,
    private val client: LocalAiClient,
    private val onUsage: (String, LocalAiModelEntity, LocalAiResult) -> Unit
) {
    private val dao = db.companionRelationshipDao()
    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val running = active.asStateFlow()
    fun observe(id: String) = dao.observe(id)
    private val tags = ConcurrentHashMap<String, String>()
    private val interruptedSessions = ConcurrentHashMap.newKeySet<String>()
    /** 扫描上次被打断、可以继续的评估批次：应用切后台被冻结/回收时状态里会留下“未完成”标记。 */
    private suspend fun recoverableSessions(): List<String> = lock.withLock {
        dao.statesWithNotice().mapNotNull { state ->
            if (CompanionRelationshipRecovery.interrupted(state.notice)) {
                dao.save(CompanionRelationshipRecovery.clearInterruptedNotice(state))
                interruptedSessions.add(state.sessionId)
                state.sessionId
            } else state.sessionId.takeIf { CompanionRelationshipRecovery.retryable(state.notice) }
        }
    }
    private val startupRecovery = scope.async(start = CoroutineStart.LAZY) {
        recoverableSessions().forEach { afterChat(it, afterEvaluation = true) }
    }
    init { startupRecovery.start() }

    /**
     * 应用回到前台后继续被打断的评估。
     *
     * 关系评估在应用级作用域上运行，但切到后台后没有前台服务保活：系统可能冻结甚至回收进程，
     * 正在请求的批次会中断，状态里留下 IN_FLIGHT／RETRY_IN_FLIGHT 标记。
     * 回到前台立即续跑，不必等下一轮聊天，也不用重新进入关系状态页触发恢复。
     */
    fun resumeAfterForeground() {
        scope.launch {
            runCatching {
                startupRecovery.await()
                recoverableSessions().forEach { afterChat(it, afterEvaluation = true) }
            }
        }
    }
    private suspend fun session(id: String) = requireNotNull(db.sessionDao().getById(id)?.takeIf { it.companionMode && it.characterId != null }) { ServiceContainer.getString(R.string.single_role_error_not_companion) }
    private suspend fun rows(id: String): List<LocalMessageEntity> {
        val s = session(id)
        return CompanionMemoryStore(db).permittedHistory(s, db.messageDao().listBySession(id)).filter { !it.deleted }
    }
    private suspend fun ensure(id: String): CompanionRelationshipEntity {
        val s = session(id)
        dao.state(id)?.let {
            require(it.characterId == s.characterId) { ServiceContainer.getString(R.string.single_role_error_relationship_owner_changed) }
            return reviewHistoryScope(freezeLegacy(it, existing = true))
        }
        val old = db.relationshipDao().get(s.characterId!!, sessionRelationshipTargetId(id))
        val initialOnly = old?.let { runCatching { JsonParser.parseString(it.dataJson).asJsonObject["companion_initial_only"]?.asBoolean == true }.getOrDefault(false) } == true
        val baseline = old?.let { CompanionRelationshipPolicy.values(it.dataJson) } ?: initialValues(s.characterId, id)
        val oldState = db.characterStateDao().get(s.characterId, id)?.let { runCatching { JsonParser.parseString(it.dataJson).asJsonObject }.getOrNull() }
        // 已知初始值没有评估进度；真实旧积累继续保留，来源不明的初始值交玩家确认。
        val legacy = old != null && !initialOnly
        val cursor = if (legacy) CompanionRelationshipPolicy.turns(rows(id)).lastOrNull()?.endId else null
        val created = CompanionRelationshipEntity(id, s.characterId, cursorId = cursor, baselineJson = gson.toJson(baseline),
            legacy = legacy, mood = oldState?.get("mood")?.asString ?: "尚未评估", energy = "尚未评估",
            historyScopeVersion = if (legacy) 0 else 1)
        dao.save(created)
        return reviewHistoryScope(freezeLegacy(created, existing = false))
    }
    private suspend fun freezeLegacy(state: CompanionRelationshipEntity, existing: Boolean): CompanionRelationshipEntity {
        if (state.legacySnapshotJson != null) return state
        val byId = rows(state.sessionId).associateBy { it.id }
        val events = dao.currentEvents(state.sessionId).filter { valid(refs(it.sourcesJson), byId) }
        val totals = CompanionRelationshipPolicy.totals(CompanionRelationshipPolicy.values(state.baselineJson),
            events.map { CompanionRelationshipPolicy.values(it.deltasJson) })
        val feelingSources = refs(state.feelingSources)
        val snapshot = RelationshipLegacySnapshot(if (existing || state.legacy) "旧版记录" else "初始设定",
            if (existing) CompanionRelationshipPolicy.dimensions.mapValues { (key, _) -> CompanionRelationshipPolicy.describe(key, totals.getValue(key)) } else emptyMap(),
            if (existing) CompanionRelationshipPolicy.dimensions.mapValues { (key, _) -> CompanionRelationshipPolicy.shortDescription(key, totals.getValue(key)) } else emptyMap(),
            totals, state.mood, state.energy, state.moodIntensity, state.feelingReason, feelingSources,
            byId.values.lastOrNull { row -> feelingSources.any { it.id == row.id } }?.createdAt, state.updatedAt)
        return state.copy(legacySnapshotJson = gson.toJson(snapshot)).also { dao.save(it) }
    }
    private suspend fun initialValues(characterId: String, sessionId: String): Map<String, Int> = CompanionRelationshipPolicy.values(
        relationshipStateFromInitial(characterId, sessionRelationshipTargetId(sessionId),
            LocalProfileRepository(db.characterDao()).getById(characterId)?.initialState.orEmpty(), "").toJson())

    private suspend fun reviewHistoryScope(state: CompanionRelationshipEntity): CompanionRelationshipEntity {
        if (state.historyScopeVersion >= 1) return state
        val candidate = CompanionRelationshipPolicy.needsHistoryReview(state,
            hasEvents = dao.currentEvents(state.sessionId).isNotEmpty(),
            hasSnapshots = db.stateSnapshotDao().listBySession(state.sessionId).isNotEmpty(),
            initialValues = initialValues(state.characterId, state.sessionId))
        // 不移动旧游标，不重置基线，不静默重评；一次性标记也保留后续明确跳过的决定。
        return state.copy(historyScopeVersion = 1, historyReviewRequired = candidate,
            awaitingHistory = state.awaitingHistory || candidate, version = state.version + 1).also { dao.save(it) }
    }
    private fun refs(json: String): List<RelationshipSource> = gson.fromJson(json, object : TypeToken<List<RelationshipSource>>() {}.type)
    private fun valid(refs: List<RelationshipSource>, rows: Map<String, LocalMessageEntity>) = CompanionRelationshipNarrative.validSources(refs, rows)
    private suspend fun referencedRows(id: String, ids: List<String>): List<LocalMessageEntity> {
        val selected = ids.distinct().chunked(800).flatMap { dao.sources(id, it) }
        return CompanionMemoryStore(db).permittedHistory(session(id), selected)
    }
    private suspend fun viewUnlocked(id: String): CompanionRelationshipView {
        var state = ensure(id)
        val all = rows(id)
        val byId = all.associateBy { it.id }
        val events = dao.currentEvents(id)
        val validEvents = events.filter { valid(refs(it.sourcesJson), byId) }
        val narrative = CompanionRelationshipNarrative.read(state.narrativeJson)
        val legacy = CompanionRelationshipNarrative.legacy(state.legacySnapshotJson)
        val validKeys = narrative?.relationships.orEmpty().filterValues { field -> field != null && CompanionRelationshipNarrative.valid(field, byId, events) }.keys
        val count = runCatching { CompanionRelationshipPolicy.pending(CompanionRelationshipPolicy.turns(all), if (state.historyReviewRequired) null else state.cursorId).size }
        val backfillSourcesValid = narrative?.relationships?.values?.all { field ->
            field != null && CompanionRelationshipNarrative.validSources(field.sources, byId)
        } == true
        if (backfillSourcesValid &&
            CompanionRelationshipPolicy.backfillComplete(state, narrative, count.getOrNull())) {
            state = state.copy(awaitingHistory = false, version = state.version + 1)
            dao.save(state)
        }
        val feelingRefs = if (narrative != null) narrative.feelings?.sources.orEmpty() else refs(state.feelingSources)
        val feelingChanged = feelingRefs.isNotEmpty() && !valid(feelingRefs, byId)
        val staleNarrative = narrative?.relationships.orEmpty().any { (key, field) -> field != null && key !in validKeys }
        val warning = if (validEvents.size != events.size || feelingChanged || staleNarrative || state.narrativeJson != null && narrative == null)
            ServiceContainer.getString(R.string.single_role_warning_relationship_sources) else count.exceptionOrNull()?.message
        val descriptions = CompanionRelationshipPolicy.dimensions.mapValues { (key, _) ->
            if (narrative != null) narrative.relationships[key]?.description ?: legacy?.descriptions?.get(key)?.takeUnless { key in narrative.withdrawnKeys } ?: "尚未评估"
            else legacy?.descriptions?.get(key) ?: "尚未评估"
        }
        return CompanionRelationshipView(state, descriptions,
            events, count.getOrDefault(0), jobs[id]?.isActive == true, warning,
            if (narrative != null) descriptions else legacy?.shortDescriptions.orEmpty(),
            valid(feelingRefs, byId), narrative, legacy, validKeys)
    }
    suspend fun view(id: String) = withContext(Dispatchers.IO) { lock.withLock { viewUnlocked(id) } }

    suspend fun configure(id: String, enabled: Boolean, automatic: Boolean, modelId: String?, interval: Int) = withContext(Dispatchers.IO) {
        startupRecovery.await()
        require(interval in 1..100) { ServiceContainer.getString(R.string.single_role_error_interval) }
        if (enabled) require(db.aiModelDao().getById(modelId.orEmpty())?.let { it.enabled && it.purpose in setOf("chat", "compression") } == true) { ServiceContainer.getString(R.string.single_role_error_choose_assessment_model) }
        lock.withLock {
            val old = ensure(id)
            interruptedSessions.remove(id)
            val count = runCatching { CompanionRelationshipPolicy.pending(CompanionRelationshipPolicy.turns(rows(id)), old.cursorId).size }.getOrDefault(1)
            tags[id]?.let(client::cancelRequests); jobs.remove(id)?.cancel(); active.update { it - id }
            dao.save(old.copy(version = old.version + 1, enabled = enabled, automatic = automatic, interval = interval, modelId = modelId,
                awaitingHistory = old.awaitingHistory || (enabled && (!old.enabled || (automatic && !old.automatic)) && count > 0), notice = null))
        }
    }

    /** 不补评明确推进到此刻，后续新聊天才累计；不改旧事件、不清空聊天。 */
    suspend fun skipPending(id: String) = withContext(Dispatchers.IO) {
        startupRecovery.await()
        lock.withLock {
            val old = ensure(id)
            interruptedSessions.remove(id)
            tags[id]?.let(client::cancelRequests); jobs.remove(id)?.cancel(); active.update { it - id }
            dao.save(old.copy(cursorId = CompanionRelationshipPolicy.turns(rows(id)).lastOrNull()?.endId,
                version = old.version + 1, awaitingHistory = false, historyReviewRequired = false, notice = null))
        }
    }

    fun afterChat(id: String, afterEvaluation: Boolean = false) { scope.launch {
        runCatching {
            startupRecovery.await()
            val ready = lock.withLock {
                val state = ensure(id)
                val pending = CompanionRelationshipPolicy.pending(CompanionRelationshipPolicy.turns(rows(id)), state.cursorId).size
                if (pending == 0) interruptedSessions.remove(id)
                jobs[id]?.isActive != true && CompanionRelationshipRecovery.automaticReady(state,
                    pending,
                    interrupted = id in interruptedSessions, afterEvaluation = afterEvaluation)
            }
            if (ready) start(id, false)
        }
    } }

    /** 手动补评以点击时的消息为上限；不会把后续源源不断的新聊天一并追到后台。 */
    suspend fun start(id: String, manual: Boolean = true, correctionKey: String? = null, claim: String? = null) = withContext(Dispatchers.IO) {
        startupRecovery.await()
        lock.withLock {
            val state = ensure(id)
            require(state.enabled) { ServiceContainer.getString(R.string.single_role_error_enable_relationship) }
            require(jobs[id]?.isActive != true) { ServiceContainer.getString(R.string.single_role_error_assessment_running) }
            if (!manual) require(CompanionRelationshipRecovery.automaticReady(state,
                CompanionRelationshipPolicy.pending(CompanionRelationshipPolicy.turns(rows(id)), state.cursorId).size,
                interrupted = id in interruptedSessions))
            val recovering = !manual && (id in interruptedSessions || CompanionRelationshipRecovery.retryable(state.notice))
            interruptedSessions.remove(id)
            if (state.historyReviewRequired) {
                require(manual && correctionKey == null) { ServiceContainer.getString(R.string.single_role_error_confirm_history) }
                // 仅“补评这些对话”的明确操作才会回到历史开头；原游标在确认之前保持原样。
                dao.save(state.copy(cursorId = null, legacy = false, historyReviewRequired = false,
                    awaitingHistory = true, version = state.version + 1, notice = null))
            }
            val boundary = CompanionRelationshipPolicy.turns(rows(id)).lastOrNull()?.endId
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    evaluate(id, boundary, correctionKey, claim, recovering)
                } catch (_: java.io.InterruptedIOException) {
                    // 超时已保存原因，本批保持待评；本次作业不再追加请求。
                }
                finally {
                    if (jobs.remove(id, currentCoroutineContext()[Job])) {
                        active.update { it - id }
                        if (db.isOpen) afterChat(id, afterEvaluation = true)
                    }
                }
            }
            jobs[id] = job
            active.update { it + id }
            job.start()
        }
    }

    private suspend fun evaluate(id: String, boundary: String?, correctionKey: String?, claim: String?, recovering: Boolean) {
        var expected: CompanionRelationshipEntity? = null
        var requestTag: String? = null
        val evaluationStarted = System.nanoTime()
        try {
            val input = lock.withLock {
                val state = ensure(id)
                expected = state
                require(state.enabled)
                val model = requireNotNull(db.aiModelDao().getById(state.modelId.orEmpty())?.takeIf {
                    it.enabled && it.purpose in setOf("chat", "compression")
                }) { ServiceContainer.getString(R.string.single_role_error_assessment_model_unavailable) }
                val all = rows(id)
                val events = dao.currentEvents(id)
                val turns = CompanionRelationshipPolicy.turns(all)
                val available = CompanionRelationshipPolicy.throughBoundary(turns, boundary)
                val pending = if (correctionKey == null) CompanionRelationshipPolicy.pending(available, state.cursorId) else emptyList()
                if (correctionKey == null && pending.isEmpty()) {
                    dao.save(state.copy(awaitingHistory = false, notice = null)); return
                }
                val batch = pending
                val target = correctionKey?.let { key -> requireNotNull(events.firstOrNull { it.eventKey == key }) { ServiceContainer.getString(R.string.single_role_error_correction_gone) } }
                val evidence = if (target != null) {
                    val sourceIds = refs(target.sourcesJson).map { it.id }.toSet()
                    (all.filter { it.id in sourceIds } + available.takeLast(10).flatMap { it.rows }).distinctBy { it.id }
                } else batch.flatMap { it.rows }
                // 合并旧备份可恢复较早的状态版本；历史记录保留，后续评估编号不能与其碰撞。
                val inFlight = state.copy(version = maxOf(state.version, dao.latestRevision(id) ?: 0) + 1,
                    notice = if (recovering) CompanionRelationshipRecovery.RETRY_IN_FLIGHT else CompanionRelationshipRecovery.IN_FLIGHT)
                dao.save(inFlight); expected = inFlight
                EvaluationInput(inFlight, model, all, events, batch, evidence, target)
            }
            val byId = input.all.associateBy { it.id }
            val previous = CompanionRelationshipNarrative.read(input.state.narrativeJson)
            require(input.state.narrativeJson == null || previous != null) { ServiceContainer.getString(R.string.single_role_error_relationship_unreadable) }
            val legacy = CompanionRelationshipNarrative.legacy(input.state.legacySnapshotJson)
            val character = db.characterDao().getById(input.state.characterId)
            val background = listOfNotNull(character?.basicInfo, character?.description, character?.personality).joinToString("\n").take(4000)
            val initialScores = initialValues(input.state.characterId, id)
            val payload = gson.toJson(mapOf(
                "角色" to character?.name, "角色背景参考" to background,
                "上一版六维" to CompanionRelationshipScores.previous(previous, legacy),
                "本次必须初始化的维度" to if (input.target == null) CompanionRelationshipScores.initializationKeys(previous) else emptySet(),
                "角色卡初始关系参考" to initialScores,
                "上次感受_只是历史观察" to previous?.feelings?.let {
                    mapOf("mood" to it.mood, "intensity" to it.intensity, "energy" to it.energy, "observed_at" to it.observedAt)
                },
                "最近聊天" to input.evidence.map { mapOf("role" to it.role, "content" to it.content, "time" to it.createdAt) },
                "纠错目标" to input.target?.let { mapOf("summary" to it.summary, "受影响项目" to CompanionRelationshipNarrative.affected(it)) },
                "玩家提出的待核对说法" to claim?.take(2000)))
            val messages = listOf(mapOf("role" to "system", "content" to RULES), mapOf("role" to "user", "content" to payload))
            val outputBudget = CompanionRelationshipRequest.outputBudget(input.model)
            val s = session(id)
            val prefs = com.nekobot.app.ServiceContainer.prefs
            val requestExtra = CompanionRelationshipRequest.extra(input.model,
                prefs.reasoningEffortFor(db.dbName, s.id, s.companionMode, s.sessionMode)) {
                prefs.customReasoningFor(db.dbName, s.id, s.companionMode, s.sessionMode)
            }
            require(estimateLocalTextTokens(RULES + payload) + outputBudget < (input.model.maxContextLength ?: 32000)) { ServiceContainer.getString(R.string.single_role_error_assessment_context) }
            requestTag = "relationship-${UUID.randomUUID()}"
            tags[id] = requestTag!!
            val requestStarted = System.nanoTime()
            LocalLogger.i("Relationship", "开始整批评估：tag=$requestTag，轮数=${input.batch.size}，原话条数=${input.evidence.size}，原话字符=${input.evidence.sumOf { it.content.length }}，背景字符=${background.length}，材料字符=${payload.length}，材料字节=${payload.toByteArray(Charsets.UTF_8).size}，估算输入Token=${estimateLocalTextTokens(RULES + payload)}，输出上限=$outputBudget，旧事件输入=${if (input.target == null) 0 else 1}")
            // 关系评估独立等待 300 秒，不修改模型的全局设置。
            val result = runRelationshipEvaluation {
                client.chatOnce(input.model, messages, extra = requestExtra, requestTag = requestTag)
            }
            val finish = result.finishReason.takeIf { it in setOf("stop", "length", "max_tokens", "max_output_tokens", "content_filter", "tool_calls") } ?: "other"
            LocalLogger.i("Relationship", "评估请求完成：耗时毫秒=${(System.nanoTime() - requestStarted) / 1_000_000}，结束原因=$finish，思考字符数=${result.thinkingContent.length}，输入Token=${result.usage["prompt"] ?: 0}，输出Token=${result.usage["completion"] ?: 0}")
            val usage = resolveLocalTokenUsage(result.usage, messages, result.content)
            onUsage(id, input.model, result.copy(usage = result.usage + mapOf("prompt" to usage.inputTokens,
                "completion" to usage.outputTokens, "estimated" to if (usage.estimated) 1 else 0)))
            CompanionRelationshipRequest.requireComplete(result)
            require(result.error == null && result.content.isNotBlank()) { ServiceContainer.getString(R.string.single_role_error_assessment_empty) }
            val json = CompanionRelationshipResponse.json(result.content)
            val now = System.currentTimeMillis()
            val corrected = if (input.target != null) {
                require(json["verdict"]?.asString in setOf("corrected", "unchanged")) { ServiceContainer.getString(R.string.single_role_error_correction_verdict) }
                json["verdict"].asString == "corrected"
            } else false
            val allowedKeys = input.target?.let { if (corrected) CompanionRelationshipNarrative.affected(it) else emptySet() }
            val missingScores = linkedSetOf<String>()
            val narrative = CompanionRelationshipScores.parse(json, previous, legacy,
                input.batch.lastOrNull()?.rows.orEmpty(), now, allowedKeys, initialScores, missingScores::add)
            if (missingScores.isNotEmpty()) LocalLogger.w("Relationship",
                "评估漏分已逐项处理：项目=${missingScores.joinToString()}，已有分数保留，缺分按角色卡起点初始化")
            // 不再要求模型编写事件和引用；旧事件仅在明确纠错时追加版本，原记录不覆盖。
            val effects = input.target?.takeIf { corrected }?.let { target ->
                listOf(target.copy(id = UUID.randomUUID().toString(), revision = input.state.version + 1,
                    corrected = true, createdAt = now, impactsJson = gson.toJson(allowedKeys.orEmpty().associateWith {
                        narrative.relationships[it]?.description.orEmpty()
                    }), sourcesJson = gson.toJson(input.evidence.map { RelationshipSource(it.id, CompanionRelationshipPolicy.hash(it)) })))
            }.orEmpty()
            val narrativeJson = gson.toJson(narrative)
            lock.withLock {
                db.withTransaction {
                    val current = dao.state(id)
                    require(current?.enabled == true && current.version == input.state.version && session(id).characterId == input.state.characterId) { ServiceContainer.getString(R.string.single_role_error_settings_changed) }
                    require(db.aiModelDao().getById(input.model.id) == input.model) { ServiceContainer.getString(R.string.single_role_error_assessment_config_changed) }
                    val latest = rows(id).associateBy { it.id }
                    require(input.evidence.all { old -> latest[old.id]?.let { CompanionRelationshipPolicy.hash(it) == CompanionRelationshipPolicy.hash(old) } == true }) { ServiceContainer.getString(R.string.single_role_error_quotes_changed) }
                    require(dao.currentEvents(id) == input.events) { ServiceContainer.getString(R.string.single_role_error_relationship_updated) }
                    // 生成期间先前关系的依据也可能被改动；不得只核对新消息。
                    require(CompanionRelationshipNarrative.allSources(narrative).all { ref ->
                        latest[ref.id]?.let { CompanionRelationshipPolicy.hash(it) == ref.hash } == true ||
                            byId[ref.id]?.let { CompanionRelationshipPolicy.hash(it) == ref.hash } != true
                    }) { ServiceContainer.getString(R.string.single_role_error_relationship_sources_changed) }
                    // 旧版当前文字也留一份原样历史，兼容没有历史快照的旧导入资料。
                    current.narrativeJson?.takeIf { previous?.format == CompanionRelationshipNarrative.FORMAT }?.let { oldJson ->
                        if (dao.assessments(id).none { it.revision == current.version || it.narrativeJson == oldJson }) {
                            dao.appendAssessment(CompanionRelationshipAssessmentEntity(UUID.randomUUID().toString(), id,
                                current.version, oldJson, current.updatedAt))
                        }
                    }
                    dao.append(effects)
                    dao.appendAssessment(CompanionRelationshipAssessmentEntity(UUID.randomUUID().toString(), id,
                        current.version + 1, narrativeJson, now, correctionKey))
                    dao.save(current.copy(version = current.version + 1, awaitingHistory = false,
                        cursorId = if (correctionKey == null) input.batch.last().endId else current.cursorId,
                        narrativeJson = narrativeJson, updatedAt = now, notice = null,
                        correctionResult = if (correctionKey == null) current.correctionResult else if (effects.isEmpty())
                            ServiceContainer.getString(R.string.single_role_correction_unchanged) else ServiceContainer.getString(R.string.single_role_correction_applied)))
                }
            }
            LocalLogger.i("Relationship", "评估保存完成：tag=$requestTag，六维已保存，纠错事件=${effects.size}，总耗时毫秒=${(System.nanoTime() - evaluationStarted) / 1_000_000}")
        } catch (e: CancellationException) {
            LocalLogger.w("Relationship", "评估中断：tag=$requestTag，异常=${e.javaClass.simpleName}，总耗时毫秒=${(System.nanoTime() - evaluationStarted) / 1_000_000}")
            saveError(id, expected, if (e is TimeoutCancellationException) {
                CompanionRelationshipRecovery.TIMEOUT
            } else if (recovering) CompanionRelationshipRecovery.RETRY_IN_FLIGHT else CompanionRelationshipRecovery.IN_FLIGHT)
            throw e
        } catch (e: Exception) {
            LocalLogger.w("Relationship", "评估失败：tag=$requestTag，异常=${e.javaClass.simpleName}，总耗时毫秒=${(System.nanoTime() - evaluationStarted) / 1_000_000}")
            if (e is java.io.InterruptedIOException) {
                saveError(id, expected, CompanionRelationshipRecovery.TIMEOUT)
                throw e
            }
            val safe = if (e is IllegalArgumentException || e is IllegalStateException) e.message?.takeIf { it.length <= 150 && !it.contains("http", true) } else null
            saveError(id, expected, safe ?: ServiceContainer.getString(R.string.single_role_error_assessment_failed))
        } finally {
            requestTag?.let { client.cancelRequests(it); tags.remove(id, it) }
        }
    }
    private suspend fun saveError(id: String, expected: CompanionRelationshipEntity?, text: String) = withContext(NonCancellable + Dispatchers.IO) {
        if (db.isOpen && expected != null) lock.withLock { dao.state(id)?.takeIf { it.version == expected.version }?.let { dao.save(it.copy(notice = text)) } }
    }
    suspend fun sourceQuotes(id: String, json: String): List<LocalMessageEntity> = withContext(Dispatchers.IO) {
        referencedRows(id, refs(json).map { it.id })
    }
    suspend fun eventHistory(id: String, key: String) = withContext(Dispatchers.IO) { session(id); dao.history(id, key) }
    suspend fun assessmentHistory(id: String) = withContext(Dispatchers.IO) { session(id); dao.assessments(id) }
    /** 同一份已保存文字供页面和聊天使用；没有新评估时才显式引用旧版文字。 */
    suspend fun context(id: String): Pair<String, String> = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = dao.state(id)?.takeIf { it.enabled } ?: return@withLock ("" to "relationship-score-v2:off")
            require(saved.characterId == session(id).characterId)
            val state = freezeLegacy(saved, existing = true)
            val narrative = CompanionRelationshipNarrative.read(state.narrativeJson)
            if (state.narrativeJson != null && narrative == null) return@withLock ("" to "relationship-score-v2:invalid")
            val legacy = CompanionRelationshipNarrative.legacy(state.legacySnapshotJson)
            val events = dao.currentEvents(id)
            val sourceRefs = CompanionRelationshipNarrative.allSources(narrative) + legacy?.sources.orEmpty()
            val sources = referencedRows(id, sourceRefs.map { it.id }).associateBy { it.id }
            val text = CompanionRelationshipPrompt.build(narrative, legacy, sources, events)
            text to ("relationship-score-v2:" + CompanionMemoryPolicy.digest(text))
        }
    }

    private data class EvaluationInput(val state: CompanionRelationshipEntity, val model: LocalAiModelEntity,
        val all: List<LocalMessageEntity>, val events: List<CompanionRelationshipEventEntity>, val batch: List<RelationshipTurn>,
        val evidence: List<LocalMessageEntity>, val target: CompanionRelationshipEventEntity?)

    companion object {
        val RULES: String get() = CompanionRelationshipScores.RULES
    }
}
