package com.nekobot.app.data.local

import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCommandProgressReporterTest {

    @Test
    fun updatesOneCardWithMonotonicDebouncedProgress() = runBlocking {
        var clock = 1_000L
        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalCommandProgressReporter(
            parentMessageId = "message-1",
            onUpdate = updates::add,
            cardId = "command-card-1",
            minUpdateIntervalMs = 250L,
            nowMillis = { clock }
        )
        val running = listOf(
            ThinkingStep(type = "image", name = "下载图片", status = "running")
        )

        reporter.update("下载中", 20, running, force = true)
        reporter.update("下载中", 20, running)
        reporter.update("乱序回调", 10, running)
        clock += 300L
        reporter.update("仍在下载", 20, running)
        reporter.update(
            content = "下载完成",
            progress = 100,
            steps = listOf(ThinkingStep(type = "done", name = "完成", status = "done")),
            isComplete = true,
            force = true
        )
        reporter.update("不应再更新", 80, running, force = true)

        assertEquals(3, updates.size)
        assertTrue(updates.all { it.id == "command-card-1" })
        assertTrue(updates.all { it.parentMessageId == "message-1" })
        assertTrue(updates.all { !it.isAgent })
        assertEquals(listOf(20, 20, 100), updates.map { it.progress })
        assertFalse(updates.first().isComplete)
        assertTrue(updates.last().isComplete)
    }

    @Test
    fun progressBetweenClampsAndInterpolates() {
        assertEquals(18, progressBetween(18, 96, 0, 200))
        assertEquals(57, progressBetween(18, 96, 100, 200))
        assertEquals(96, progressBetween(18, 96, 250, 200))
        assertEquals(12, progressBetween(12, 90, 4, 0))
    }
}
