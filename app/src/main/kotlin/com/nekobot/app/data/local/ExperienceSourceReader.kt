package com.nekobot.app.data.local

import androidx.room.withTransaction
import androidx.room.InvalidationTracker
import com.nekobot.app.data.local.db.LocalExperienceArchiveEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalMessageRow
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

data class ExperienceSourcePage(
    val archive: LocalExperienceArchiveEntity,
    val sessionName: String,
    val messages: List<LocalMessageEntity>,
    val sourceCount: Int,
    val availableCount: Int,
    val hasMore: Boolean,
    val assistantName: String? = null
)

data class ChatHistoryWindow(
    val sessionId: String,
    val sessionName: String,
    val anchorMessageId: String,
    val messages: List<LocalMessageEntity>,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val assistantName: String? = null,
    val archiveId: String? = null,
    val sourceMessageIds: Set<String> = emptySet()
)

data class ChatHistoryPage(
    val messages: List<LocalMessageEntity>, val hasMore: Boolean,
    val sourceMessageIds: Set<String> = emptySet()
)

/** Read-only UI access. Never invokes a model or changes the live chat runtime. */
class ExperienceSourceReader(private val db: NekobotDatabase) {
    /** Detect repository replacement even before its public revision notification is delivered. */
    fun sharesDatabase(other: ExperienceSourceReader): Boolean = db === other.db

    /** Observe invalidation signals only; never load an entire conversation to watch it. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : InvalidationTracker.Observer(
            "local_messages", "local_sessions", "local_experience_archives",
            "local_experience_sources", "local_characters"
        ) {
            override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
        }
        db.invalidationTracker.addObserver(observer)
        trySend(Unit)
        awaitClose { db.invalidationTracker.removeObserver(observer) }
    }.conflate().flowOn(Dispatchers.IO)

    suspend fun sourcePage(
        sessionId: String, archiveId: String, afterMessageId: String? = null,
        pageSize: Int = PAGE_SIZE
    ): ExperienceSourcePage? = db.withTransaction {
        val archive = db.experienceArchiveDao().getById(archiveId)
            ?.takeIf { it.sessionId == sessionId } ?: return@withTransaction null
        val session = db.sessionDao().getById(sessionId) ?: return@withTransaction null
        val sources = db.experienceArchiveDao()
        val cursor = if (afterMessageId == null) null else {
            // Soft-deleted cursors still have a stable position. Foreign or missing
            // cursors cannot restart the list or disclose a different conversation.
            val message = db.messageDao().getById(afterMessageId)
                ?.takeIf { it.sessionId == sessionId } ?: return@withTransaction null
            if (!sources.containsSource(archiveId, message.id)) return@withTransaction null
            db.messageDao().cursorOf(message.id) ?: return@withTransaction null
        }
        val limit = pageSize.coerceIn(1, PAGE_SIZE)
        val rows = sources.listSourceRows(sessionId, archiveId, cursor?.createdAt, cursor?.rowId, limit + 1)
        ExperienceSourcePage(
            archive = archive,
            sessionName = session.name,
            messages = rows.take(limit).map { it.message },
            sourceCount = sources.sourceCount(archiveId),
            availableCount = sources.availableSourceCount(sessionId, archiveId),
            hasMore = rows.size > limit,
            assistantName = assistantName(session)
        )
    }

    suspend fun historyWindow(sessionId: String, messageId: String, archiveId: String? = null): ChatHistoryWindow? = db.withTransaction {
        val session = db.sessionDao().getById(sessionId) ?: return@withTransaction null
        if (archiveId != null && db.experienceArchiveDao().getById(archiveId)?.sessionId != sessionId) return@withTransaction null
        val messages = db.messageDao()
        val anchor = messages.visibleAnchorRow(sessionId, messageId) ?: return@withTransaction null
        if (archiveId != null && !db.experienceArchiveDao().containsSource(archiveId, messageId)) return@withTransaction null
        val previous = messages.listVisibleBeforeAnchor(sessionId, anchor.message.createdAt, anchor.rowId, PAGE_SIZE + 1)
        val following = messages.listVisibleAfterAnchor(sessionId, anchor.message.createdAt, anchor.rowId, PAGE_SIZE + 1)
        val visible = previous.take(PAGE_SIZE).asReversed().map { it.message } + anchor.message +
            following.take(PAGE_SIZE).map { it.message }
        ChatHistoryWindow(
            sessionId = sessionId, sessionName = session.name, anchorMessageId = messageId,
            messages = visible,
            hasOlder = previous.size > PAGE_SIZE, hasNewer = following.size > PAGE_SIZE,
            assistantName = assistantName(session), archiveId = archiveId,
            sourceMessageIds = sourceIds(sessionId, archiveId, visible)
        )
    }

    suspend fun historyPage(sessionId: String, cursorId: String, older: Boolean, archiveId: String? = null): ChatHistoryPage? = db.withTransaction {
        if (archiveId != null && db.experienceArchiveDao().getById(archiveId)?.sessionId != sessionId) return@withTransaction null
        val messages = db.messageDao()
        val cursor = messages.getById(cursorId)?.takeIf { it.sessionId == sessionId }
            ?: return@withTransaction null
        val position = messages.cursorOf(cursor.id) ?: return@withTransaction null
        val rows = if (older) {
            messages.listVisibleBeforeAnchor(sessionId, position.createdAt, position.rowId, PAGE_SIZE + 1)
        } else {
            messages.listVisibleAfterAnchor(sessionId, position.createdAt, position.rowId, PAGE_SIZE + 1)
        }
        val page = rows.take(PAGE_SIZE).let { if (older) it.asReversed() else it }
        val visible = page.map { it.message }
        ChatHistoryPage(visible, rows.size > PAGE_SIZE, sourceIds(sessionId, archiveId, visible))
    }

    /** Keep loaded pages while replacing edited text and dropping deleted or unmapped originals. */
    suspend fun refreshSource(current: ExperienceSourcePage): ExperienceSourcePage? = db.withTransaction {
        val sessionId = current.archive.sessionId
        val archiveId = current.archive.id
        val archive = db.experienceArchiveDao().getById(archiveId)
            ?.takeIf { it.sessionId == sessionId } ?: return@withTransaction null
        val session = db.sessionDao().getById(sessionId) ?: return@withTransaction null
        val rows = visibleRows(sessionId, current.messages.map { it.id })
        val membership = sourceIds(sessionId, archiveId, rows.map { it.message })
        val visible = rows.filter { it.message.id in membership }
        // If every loaded original disappeared, reopen one bounded page so pagination has a cursor.
        if (visible.isEmpty()) return@withTransaction sourcePage(sessionId, archiveId)
        val tail = visible.lastOrNull()
        val sources = db.experienceArchiveDao()
        current.copy(
            archive = archive, sessionName = session.name, assistantName = assistantName(session),
            messages = visible.map { it.message }, sourceCount = sources.sourceCount(archiveId),
            availableCount = sources.availableSourceCount(sessionId, archiveId),
            hasMore = sources.listSourceRows(sessionId, archiveId, tail?.message?.createdAt, tail?.rowId, 1).isNotEmpty()
        )
    }

    /** Refresh the bounded browsing window without recentering on its original anchor. */
    suspend fun refreshHistory(current: ChatHistoryWindow): ChatHistoryWindow? = db.withTransaction {
        val session = db.sessionDao().getById(current.sessionId) ?: return@withTransaction null
        val messages = db.messageDao()
        if (messages.visibleAnchorRow(current.sessionId, current.anchorMessageId) == null) return@withTransaction null
        if (current.archiveId != null &&
            (db.experienceArchiveDao().getById(current.archiveId)?.sessionId != current.sessionId ||
                !db.experienceArchiveDao().containsSource(current.archiveId, current.anchorMessageId))) return@withTransaction null
        val rows = visibleRows(current.sessionId, current.messages.map { it.id })
        if (rows.isEmpty()) return@withTransaction historyWindow(current.sessionId, current.anchorMessageId, current.archiveId)
        val first = rows.first()
        val last = rows.last()
        val visible = rows.map { it.message }
        current.copy(
            sessionName = session.name, assistantName = assistantName(session), messages = visible,
            hasOlder = messages.listVisibleBeforeAnchor(current.sessionId, first.message.createdAt, first.rowId, 1).isNotEmpty(),
            hasNewer = messages.listVisibleAfterAnchor(current.sessionId, last.message.createdAt, last.rowId, 1).isNotEmpty(),
            sourceMessageIds = sourceIds(current.sessionId, current.archiveId, visible)
        )
    }

    /** Chunk displayed IDs below SQLite's bind limit and retain the database's stable ordering. */
    private suspend fun visibleRows(sessionId: String, ids: List<String>): List<LocalMessageRow> =
        ids.distinct().chunked(400).flatMap { db.messageDao().visibleRowsByIds(sessionId, it) }
            .sortedWith(compareBy({ it.message.createdAt }, { it.rowId }))

    private suspend fun assistantName(session: LocalSessionEntity): String? =
        session.characterName?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.characterId?.let { db.characterDao().getById(it)?.name?.trim()?.takeIf(String::isNotEmpty) }
            ?: session.senderName?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun sourceIds(sessionId: String, archiveId: String?, messages: List<LocalMessageEntity>): Set<String> =
        if (archiveId == null || messages.isEmpty()) emptySet()
        else messages.map { it.id }.chunked(400)
            .flatMap { db.experienceArchiveDao().sourceIdsAmong(sessionId, archiveId, it) }.toSet()

    companion object { const val PAGE_SIZE = 60 }
}
