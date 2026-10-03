package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.ExperienceArchiveDao
import com.nekobot.app.data.local.db.LocalExperienceArchiveEntity
import com.nekobot.app.data.local.db.LocalExperienceSourceEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalExperienceArchiverTest {
    private fun message(id: String, role: String, content: String = id, date: String = "2026-09-27T10:00:00Z") =
        ExperienceSourceMessage(id, role, content, date)

    @Test
    fun keepsCompleteTurnsAndLeavesUnfinishedTailForNextRun() {
        val split = ExperienceSegmenter.split(
            listOf(message("u1", "user"), message("a1", "assistant"), message("u2", "user"))
        )
        assertEquals(listOf("u1", "a1"), split.segments.single().messages.map { it.id })
        assertEquals(listOf("u2"), split.unprocessed.map { it.id })
    }

    @Test
    fun duplicateTimestampsUseInputOrderAndSegmentCapPreservesRemainder() {
        val input = listOf(
            message("u1", "user"), message("a1", "assistant"),
            message("u2", "user"), message("a2", "assistant"),
            message("u3", "user"), message("a3", "assistant")
        )
        val split = ExperienceSegmenter.split(input, maxTurnsPerSegment = 1, maxSegments = 2)
        assertEquals(listOf("u1", "a1"), split.segments[0].messages.map { it.id })
        assertEquals(listOf("u2", "a2"), split.segments[1].messages.map { it.id })
        assertEquals(listOf("u3", "a3"), split.unprocessed.map { it.id })
    }

    @Test
    fun longTurnRemainsWholeAndFingerprintChangesWhenOriginalTextChanges() {
        val original = ExperienceSegmenter.split(
            listOf(message("u1", "user", "甲".repeat(1000)), message("a1", "assistant", "乙")),
            maxCharsPerSegment = 10
        ).segments.single()
        assertEquals(2, original.messages.size)
        val edited = original.copy(messages = original.messages.map {
            if (it.id == "u1") it.copy(content = "甲".repeat(999) + "丙") else it
        })
        assertNotEquals(ExperienceSegmenter.fingerprint(original), ExperienceSegmenter.fingerprint(edited))
    }

    @Test
    fun standaloneAssistantOpeningIsArchivedWithoutDroppingOriginal() {
        val split = ExperienceSegmenter.split(
            listOf(message("greeting", "assistant"), message("u1", "user"), message("a1", "assistant"))
        )
        assertEquals(listOf("greeting"), split.segments[0].messages.map { it.id })
        assertEquals(listOf("u1", "a1"), split.segments[1].messages.map { it.id })
        assertEquals(1, split.segments[0].turnCount)
        assertTrue(split.unprocessed.isEmpty())
    }

    @Test
    fun parsesSummaryAndTagsFromOneModelOutput() {
        val result = ExperienceSummaryParser.parse("""```json
            {"summary":"中秋一起挑了月饼，礼物细节需核对。","tags":[" 中秋 ","月饼","月饼"]}
            ```""".trimIndent())
        assertEquals("中秋一起挑了月饼，礼物细节需核对。", result.summary)
        assertEquals(listOf("中秋", "月饼"), result.tags)
        assertTrue(ExperienceSummaryParser.parse("""{"summary":"聊了近况","tags":[]}""").tags.isEmpty())
    }

    /**
     * 压缩（聊天内自动）与用户补建会各自 new 一个归档器；同会话必须跨实例串行，
     * 否则会重复调用模型并撞上来源范围的唯一索引。
     */
    @Test
    fun concurrentRunsForOneSessionSerializeAcrossInstances() = runBlocking {
        val sessionId = "shared-mutex-session"
        val generateCount = AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val generate: suspend (String, String) -> String = { _, _ ->
            if (generateCount.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            """{"summary":"本段经历","tags":[]}"""
        }
        val dao = FakeExperienceArchiveDao()
        val first = IncrementalExperienceArchiver(dao, generate)
        val second = IncrementalExperienceArchiver(dao, generate)

        val firstRun = launch(Dispatchers.Default) {
            first.archiveNewOldMessages(sessionId, turn(sessionId, "u1", "a1"), "")
        }
        firstStarted.await()
        val secondRun = launch(Dispatchers.Default) {
            second.archiveNewOldMessages(sessionId, turn(sessionId, "u2", "a2"), "")
        }
        val overlapped = withTimeoutOrNull(300) {
            while (generateCount.get() < 2) delay(5)
            true
        } ?: false
        assertFalse("同一会话的归档必须串行执行，不能并发调用模型", overlapped)

        releaseFirst.complete(Unit)
        firstRun.join()
        secondRun.join()
        assertEquals(2, generateCount.get())
    }

    private fun turn(sessionId: String, userId: String, assistantId: String) = listOf(
        localMessage(sessionId, userId, "user"),
        localMessage(sessionId, assistantId, "assistant")
    )

    private fun localMessage(sessionId: String, id: String, role: String) = LocalMessageEntity(
        id = id, sessionId = sessionId, role = role, content = "内容-$id",
        timestamp = "2026-09-27T10:00:00Z", createdAt = "2026-09-27T10:00:00Z"
    )

    private class FakeExperienceArchiveDao : ExperienceArchiveDao {
        override suspend fun getById(id: String): LocalExperienceArchiveEntity? = null

        override suspend fun findByRange(
            sessionId: String,
            startMessageId: String,
            endMessageId: String
        ): LocalExperienceArchiveEntity? = null

        override suspend fun listBySession(
            sessionId: String,
            limit: Int,
            offset: Int
        ): List<LocalExperienceArchiveEntity> = emptyList()

        override suspend fun searchReadyBySession(
            sessionId: String,
            query: String,
            limit: Int
        ): List<LocalExperienceArchiveEntity> = emptyList()

        override suspend fun upsert(archive: LocalExperienceArchiveEntity) = Unit

        override suspend fun deleteSources(archiveId: String) = Unit

        override suspend fun insertSources(sources: List<LocalExperienceSourceEntity>) = Unit

        override suspend fun sourceMessageIds(archiveId: String): List<String> = emptyList()

        override suspend fun sourceCount(archiveId: String): Int = 0

        override suspend fun containsSource(archiveId: String, messageId: String): Boolean = false

        override suspend fun sourceIdsAmong(sessionId: String, archiveId: String, messageIds: List<String>): List<String> = emptyList()

        override suspend fun availableSourceCount(sessionId: String, archiveId: String): Int = 0

        override suspend fun listSourceRows(
            sessionId: String, archiveId: String, afterCreatedAt: String?, afterRowId: Long?, limit: Int
        ): List<com.nekobot.app.data.local.db.LocalMessageRow> = emptyList()

        override suspend fun updateUserEdits(id: String, summary: String, tagsJson: String, updatedAt: String) = Unit

        override suspend fun clearManualProtectionForExplicitRebuild(id: String) = Unit

        override suspend fun markStaleByMessage(sessionId: String, messageId: String) = Unit

        override suspend fun deleteBySession(sessionId: String) = Unit

        override suspend fun deleteById(id: String) = Unit

        override suspend fun saveGenerated(
            archive: LocalExperienceArchiveEntity,
            sourceMessageIds: List<String>
        ): LocalExperienceArchiveEntity = archive
    }
}
