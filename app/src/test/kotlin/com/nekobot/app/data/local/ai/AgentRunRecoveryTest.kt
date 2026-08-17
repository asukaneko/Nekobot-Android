package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAgentRunEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryTest {

    @Test
    fun checkpointIsWrittenOnlyAfterWholeToolBatchCompletes() = runBlocking {
        var modelCalls = 0
        val checkpoints = mutableListOf<List<Map<String, Any>>>()
        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "执行任务")),
            modelCall = { _, _ ->
                if (modelCalls++ == 0) {
                    mapOf(
                        "content" to "",
                        "tool_calls" to listOf(
                            mapOf("id" to "tool-1", "name" to "first", "arguments" to emptyMap<String, Any>()),
                            mapOf("id" to "tool-2", "name" to "second", "arguments" to emptyMap<String, Any>())
                        )
                    )
                } else {
                    mapOf("content" to "完成", "finish_reason" to "stop")
                }
            },
            toolExecutor = { call, _, _, _ ->
                mapOf("success" to true, "name" to (call["name"] as? String).orEmpty())
            },
            hooks = ToolLoopHooks(
                onCheckpoint = { _, messages -> checkpoints += messages }
            )
        )

        assertEquals("完成", result.finalContent)
        assertEquals(1, checkpoints.size)
        val checkpoint = checkpoints.single()
        assertEquals(2, checkpoint.count { it["role"] == "tool" })
        assertEquals("tool-2", checkpoint.last()["tool_call_id"])
    }

    @Test
    fun liveRunIsHiddenButProcessLossBecomesRecoverable() {
        val run = runEntity(stage = AgentRunStage.THINKING, checkpoint = "[]", completed = 1)

        assertNull(run.toRecoveryState(hasLiveGeneration = true))
        val recovered = run.toRecoveryState(hasLiveGeneration = false)

        assertNotNull(recovered)
        assertEquals("interrupted", recovered?.status)
        assertTrue(recovered?.canContinueFromCheckpoint == true)
        assertFalse(recovered?.mayHaveUncommittedToolEffect == true)
    }

    @Test
    fun toolStageWarnsAboutUncommittedSideEffect() {
        val recovered = runEntity(
            stage = AgentRunStage.TOOL,
            checkpoint = null,
            completed = 0,
            lastTool = "workspace_edit_file"
        ).toRecoveryState(hasLiveGeneration = false)

        assertTrue(recovered?.mayHaveUncommittedToolEffect == true)
        assertEquals("workspace_edit_file", recovered?.lastToolName)
        assertFalse(recovered?.canContinueFromCheckpoint == true)
    }

    private fun runEntity(
        stage: String,
        checkpoint: String?,
        completed: Int,
        lastTool: String? = null
    ) = LocalAgentRunEntity(
        sessionId = "session",
        runId = "run",
        userMessageId = "user",
        prompt = "任务",
        attachmentsJson = null,
        status = AgentRunStatus.RUNNING,
        stage = stage,
        checkpointHistory = checkpoint,
        completedToolCalls = completed,
        lastToolName = lastTool,
        lastError = null,
        assistantMessageId = null,
        createdAt = "2026-08-03T00:00:00Z",
        updatedAt = "2026-08-03T00:00:00Z"
    )
}
