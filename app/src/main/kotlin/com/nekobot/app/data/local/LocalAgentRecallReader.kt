package com.nekobot.app.data.local

import com.nekobot.app.data.local.ai.AgentHistoryMessage
import com.nekobot.app.data.local.ai.AgentRecallCandidate
import com.nekobot.app.data.local.ai.AgentRecallReader
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import com.nekobot.app.data.local.ai.CompanionHybridSearch
import com.nekobot.app.data.local.ai.FusedCompanionTools
import com.nekobot.app.data.local.ai.CompanionPromptLanguage
import com.nekobot.app.data.local.ai.CompanionReferences
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.ai.usesCompanionMemory
import com.nekobot.app.data.local.db.NekobotDatabase

/** All reads are scoped to the current database, inherited character and conversation. */
internal class LocalAgentRecallReader(private val db: NekobotDatabase) : AgentRecallReader {
    private val sessions = db.sessionDao()
    private val experiences = db.experienceArchiveDao()
    private val messages = db.messageDao()

    override suspend fun isAvailable(sessionId: String): Boolean =
        sessions.getById(sessionId)?.let { session ->
            session.usesCompanionMemory()
        } == true

    override suspend fun searchRecall(
        sessionId: String,
        terms: List<String>,
        limit: Int
    ): List<AgentRecallCandidate> {
        val session = sessions.getById(sessionId)?.takeIf {
            it.usesCompanionMemory()
        } ?: return emptyList()
        val bounded = limit.coerceIn(1, 48)
        val queries = terms.asSequence().map(String::trim).filter { it.length >= 2 }
            .distinct().take(12).toList()
        val candidates = linkedMapOf<String, AgentRecallCandidate>()
        val store = CompanionMemoryStore(db)
        val scope = store.ensureScope(session)
        val forgotten = db.companionMemoryDao().forgotten(scope.id).toSet()
        val candidatesToValidate = if (session.companionMode) CompanionReferences.current(db.companionMemoryDao().memories(scope.id)) else null
        val facts = store.validatedMemories(scope, candidatesToValidate)
        for (query in queries) {
            if (session.usesCompanionMemory()) {
                facts.filter { CompanionMemoryPolicy.score(query, it.content + it.factKey + it.tagsJson + it.eventDate) > 0.04f }
                    .forEach { memory -> candidates["memory:${memory.id}"] = memoryCandidate(memory, sessionId, session.companionMode) }
            }
            experiences.searchReadyBySession(sessionId, query, bounded)
                .filter { it.characterId == session.characterId && experiences.sourceMessageIds(it.id).none { id -> id in forgotten } }
                .forEach { archive ->
                    candidates["episode:${archive.id}"] = AgentRecallCandidate(
                        id = archive.id,
                        sourceType = "episode",
                        title = MemoryTags.fromJson(archive.tagsJson).joinToString(" · ")
                            .ifBlank { "${archive.sourceStartedAt}—${archive.sourceEndedAt}" },
                        content = archive.summary,
                        tags = MemoryTags.fromJson(archive.tagsJson),
                        sourceSessionId = sessionId,
                        startMessageId = archive.startMessageId,
                        endMessageId = archive.endMessageId,
                        startedAt = archive.sourceStartedAt,
                        endedAt = archive.sourceEndedAt
                    )
                }
        }
        // 单会话角色开启向量时，按意思相近补充记忆；不可用时只用上面的关键词结果。
        if (session.companionMode && facts.isNotEmpty()) {
            val pool = facts.filter { it.current }
            val scores = CompanionHybridSearch(db).cachedScores(scope, terms.firstOrNull().orEmpty(), pool.map(store::searchItem))
            for (memory in pool) {
                val score = scores["fact:${memory.id}"]?.takeIf { it >= FusedCompanionTools.VECTOR_MIN_SCORE } ?: continue
                val key = "memory:${memory.id}"
                candidates[key] = (candidates[key] ?: memoryCandidate(memory, sessionId, true)).copy(vectorScore = score)
            }
        }
        return candidates.values.take(96)
    }

    private suspend fun memoryCandidate(memory: CompanionMemoryEntity, sessionId: String, companionMode: Boolean): AgentRecallCandidate {
        val sources = db.companionMemoryDao().sources(memory.id)
        return AgentRecallCandidate(
            id = memory.id,
            sourceType = "companion_memory",
            title = "${memory.factKey} · v${memory.version} · ${if (memory.current) "当前" else "历史"}",
            content = if (companionMode) CompanionPromptLanguage.memory(memory) else
                "${memory.content} [状态:${memory.status}; ${CompanionMemoryPolicy.temporalHint(memory.eventDate, java.time.LocalDate.now())}]",
            tags = MemoryTags.fromJson(memory.tagsJson),
            sourceSessionId = sessionId,
            startMessageId = sources.firstOrNull()?.messageId,
            endMessageId = sources.lastOrNull()?.messageId,
            startedAt = sources.firstOrNull()?.spokenDate,
            endedAt = sources.lastOrNull()?.spokenDate,
            current = memory.current
        )
    }

    override suspend fun searchHistory(
        sessionId: String,
        terms: List<String>,
        limit: Int
    ): List<AgentHistoryMessage> {
        if (!isAvailable(sessionId)) return emptyList()
        val bounded = limit.coerceIn(1, 48)
        val found = linkedMapOf<String, AgentHistoryMessage>()
        val session = sessions.getById(sessionId) ?: return emptyList()
        for (day in terms.flatMap(CompanionMemoryPolicy::dateTerms).distinct()) {
            CompanionMemoryStore(db).permittedHistory(session, db.companionMemoryDao().historyOnDay(sessionId, day))
                .take(bounded).forEach { found[it.id] = it.toRecallMessage() }
        }
        for (query in terms.asSequence().map(String::trim).filter { it.length >= 2 }
            .distinct().take(12)) {
            CompanionMemoryStore(db).permittedHistory(session, messages.searchVisibleFinalBySession(sessionId, query, bounded)).forEach { message ->
                found[message.id] = message.toRecallMessage()
            }
        }
        return found.values.take(48)
    }

    override suspend fun readHistory(
        sessionId: String,
        anchorMessageId: String,
        before: Int,
        after: Int
    ): List<AgentHistoryMessage> {
        if (!isAvailable(sessionId)) return emptyList()
        val session = sessions.getById(sessionId) ?: return emptyList()
        return CompanionMemoryStore(db).permittedHistory(session, messages.readVisibleWindow(
            sessionId = sessionId,
            anchorId = anchorMessageId,
            before = before.coerceIn(0, 4),
            after = after.coerceIn(0, 4)
        )).map { it.toRecallMessage() }
    }

    private fun com.nekobot.app.data.local.db.LocalMessageEntity.toRecallMessage() =
        AgentHistoryMessage(id = id, role = role, content = content,
            timestamp = com.nekobot.app.data.local.ai.agentUtteranceDay(this)?.toString().orEmpty())
}
