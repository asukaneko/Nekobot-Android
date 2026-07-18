package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ThinkingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentProgressReporterTest {

    @Test
    fun progressLifecycleUpdatesOneCardAndCompletesEveryStep() {
        val updates = mutableListOf<ThinkingCard>()
        val reporter = LocalAgentProgressReporter(
            parentMessageId = "user-message-1",
            onUpdate = updates::add,
            cardId = "progress-card-1"
        )
        val context = PipelineContext(
            ChatRequest.forLocal(sessionId = "session-1", content = "检查项目")
        )

        reporter.onThinkingStart(context)
        reporter.onToolStart(
            context,
            toolName = "exec",
            arguments = mapOf("command" to "git status"),
            thinking = "先检查仓库状态"
        )
        reporter.onToolDone(
            context,
            toolName = "exec",
            result = mapOf("output" to "clean"),
            thinking = ""
        )
        reporter.onDone(context)

        assertTrue(updates.isNotEmpty())
        assertTrue(updates.all { it.id == "progress-card-1" })
        assertTrue(updates.all { it.parentMessageId == "user-message-1" })
        assertFalse(updates.first().isComplete)

        val completed = updates.last()
        assertTrue(completed.isComplete)
        assertTrue(completed.isAgent)
        assertEquals("处理完成", completed.content)
        assertTrue(completed.steps.isNotEmpty())
        assertTrue(completed.steps.all { it.status == "done" })
        assertEquals(1, completed.steps.count { it.type == "tool" })
        assertEquals(1, completed.steps.count { it.type == "done" })
    }
}
