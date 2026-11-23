package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话工作区列表排序：按会话时间 / 按占用大小。 */
class SessionWorkspaceSortTest {

    private fun entry(
        id: String,
        sizeBytes: Long,
        lastModified: Long,
        exists: Boolean = true,
        legacy: Boolean = false
    ) = SessionWorkspaceEntry(
        sessionId = id,
        sessionName = if (exists) "chat-$id" else null,
        profileLabel = if (exists) "demo-profile" else null,
        legacy = legacy,
        fileCount = 1,
        sizeBytes = sizeBytes,
        lastModified = lastModified
    )

    @Test
    fun defaultOrderListsNewestWorkspaceFirst() {
        val entries = listOf(
            entry("older", sizeBytes = 10, lastModified = 1_000L),
            entry("newest", sizeBytes = 10, lastModified = 3_000L),
            entry("middle", sizeBytes = 10, lastModified = 2_000L)
        )

        val sorted = sortWorkspaceEntries(entries, WorkspaceSortOrder())

        assertEquals(listOf("newest", "middle", "older"), sorted.map { it.sessionId })
    }

    @Test
    fun sizeOrderListsLargestWorkspaceFirstAndFlipsToSmallest() {
        val entries = listOf(
            entry("medium", sizeBytes = 2_048, lastModified = 1_000L),
            entry("huge", sizeBytes = 9_000_000, lastModified = 1_000L),
            entry("tiny", sizeBytes = 12, lastModified = 1_000L)
        )
        val bySize = WorkspaceSortOrder(key = WorkspaceSortKey.SIZE)

        assertEquals(
            listOf("huge", "medium", "tiny"),
            sortWorkspaceEntries(entries, bySize).map { it.sessionId }
        )
        assertEquals(
            listOf("tiny", "medium", "huge"),
            sortWorkspaceEntries(entries, bySize.copy(descending = false)).map { it.sessionId }
        )
    }

    @Test
    fun selectingSameKeyFlipsDirectionAndOtherKeyResetsToDefault() {
        val timeDesc = WorkspaceSortOrder()
        val timeAsc = timeDesc.selecting(WorkspaceSortKey.SESSION_TIME)
        assertTrue(timeDesc.descending)
        assertFalse(timeAsc.descending)
        assertEquals(WorkspaceSortKey.SESSION_TIME, timeAsc.key)

        // 切到大小字段时回到默认方向（大到小）
        val bySize = timeAsc.selecting(WorkspaceSortKey.SIZE)
        assertEquals(WorkspaceSortKey.SIZE, bySize.key)
        assertTrue(bySize.descending)
    }

    @Test
    fun equalValuesFallBackToSessionIdSoOrderStaysStable() {
        val entries = listOf(
            entry("c", sizeBytes = 500, lastModified = 1_000L),
            entry("a", sizeBytes = 500, lastModified = 1_000L),
            entry("b", sizeBytes = 500, lastModified = 1_000L)
        )

        // 两个方向下同值条目都保持会话 id 升序，不会来回跳动
        assertEquals(
            listOf("a", "b", "c"),
            sortWorkspaceEntries(entries, WorkspaceSortOrder(key = WorkspaceSortKey.SIZE))
                .map { it.sessionId }
        )
        assertEquals(
            listOf("a", "b", "c"),
            sortWorkspaceEntries(
                entries,
                WorkspaceSortOrder(key = WorkspaceSortKey.SIZE, descending = false)
            ).map { it.sessionId }
        )
    }

    @Test
    fun orphanWorkspacesAreStillSortedInsideTheirGroup() {
        val entries = listOf(
            entry("alive-old", sizeBytes = 300, lastModified = 1_000L),
            entry("orphan-old", sizeBytes = 100, lastModified = 1_000L, exists = false),
            entry("orphan-new", sizeBytes = 800, lastModified = 5_000L, exists = false)
        )

        val sorted = sortWorkspaceEntries(entries, WorkspaceSortOrder(key = WorkspaceSortKey.SIZE))
        val orphans = sorted.filterNot { it.sessionExists }

        assertEquals(listOf("orphan-new", "orphan-old"), orphans.map { it.sessionId })
        assertEquals(listOf("alive-old"), sorted.filter { it.sessionExists }.map { it.sessionId })
    }
}
