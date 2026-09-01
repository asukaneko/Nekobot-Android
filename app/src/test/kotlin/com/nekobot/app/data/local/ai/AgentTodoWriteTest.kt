package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.AgentTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AgentTodoWriteTest {

    private fun buildExecutor(
        onTodosUpdated: (List<AgentTodo>) -> Unit = {}
    ): LocalAgentToolExecutor = LocalAgentToolExecutor(
        sessionId = "session-todo-write",
        workspaceRoot = null,
        authorizationManager = LocalExecAuthorizationManager(100),
        onConfirmationRequired = {},
        thinkingHistoryProvider = { emptyList() },
        onTodosUpdated = onTodosUpdated
    )

    @Test
    fun todo_write_parsesAndNormalizesFullList() = runBlocking {
        val received = mutableListOf<List<AgentTodo>>()
        val executor = buildExecutor { received.add(it) }

        val result = executor.execute(
            "todo_write",
            mapOf(
                "todos" to listOf(
                    mapOf("content" to "阅读配置文件", "status" to "completed"),
                    mapOf("content" to "实现功能", "status" to "in_progress", "priority" to "high"),
                    mapOf("content" to "构建验证", "status" to "pending")
                )
            )
        )

        assertEquals(true, result["success"])
        assertEquals(3, result["total"])
        assertEquals(1, result["completed"])
        assertEquals(1, received.size)
        val todos = received.single()
        assertEquals(AgentTodo.STATUS_COMPLETED, todos[0].status)
        assertEquals(AgentTodo.STATUS_IN_PROGRESS, todos[1].status)
        assertEquals(AgentTodo.PRIORITY_HIGH, todos[1].priority)
        assertEquals(AgentTodo.STATUS_PENDING, todos[2].status)
    }

    @Test
    fun todo_write_normalizesInvalidStatusAndPriority() = runBlocking {
        val received = mutableListOf<List<AgentTodo>>()
        val executor = buildExecutor { received.add(it) }

        executor.execute(
            "todo_write",
            mapOf(
                "todos" to listOf(
                    mapOf("content" to "A", "status" to "finished", "priority" to "urgent"),
                    mapOf("content" to "B", "status" to "what", "priority" to "huh")
                )
            )
        )

        val todos = received.single()
        assertEquals(AgentTodo.STATUS_COMPLETED, todos[0].status)
        assertEquals(AgentTodo.PRIORITY_HIGH, todos[0].priority)
        assertEquals(AgentTodo.STATUS_PENDING, todos[1].status)
        assertEquals(AgentTodo.PRIORITY_MEDIUM, todos[1].priority)
    }

    @Test
    fun todo_write_dropsEntriesWithoutContent() = runBlocking {
        val received = mutableListOf<List<AgentTodo>>()
        val executor = buildExecutor { received.add(it) }

        val result = executor.execute(
            "todo_write",
            mapOf(
                "todos" to listOf(
                    mapOf("content" to "  有效任务  ", "status" to "pending"),
                    mapOf("status" to "pending"),
                    "not-a-map"
                )
            )
        )

        assertEquals(true, result["success"])
        assertEquals(1, received.single().size)
        assertEquals("有效任务", received.single().single().content)
    }

    @Test
    fun todo_write_rejectsMissingTodosAndEmitsNothing() = runBlocking {
        var called = false
        val executor = buildExecutor { called = true }

        val missing = executor.execute("todo_write", emptyMap())
        assertEquals(false, missing["success"])
        val wrongType = executor.execute("todo_write", mapOf("todos" to "nope"))
        assertEquals(false, wrongType["success"])
        assertTrue(called.not())
    }

    @Test
    fun todo_write_emptyListClearsTodos() = runBlocking {
        val received = mutableListOf<List<AgentTodo>>()
        val executor = buildExecutor { received.add(it) }

        val result = executor.execute("todo_write", mapOf("todos" to emptyList<Any>()))

        assertEquals(true, result["success"])
        assertEquals(0, result["total"])
        assertEquals(emptyList<AgentTodo>(), received.single())
    }

    @Test
    fun todo_write_isInExecutableWhitelist() {
        assertTrue("todo_write" in localExecutableToolIds)
        val functionNames = buildLocalAgentToolDefinitions()
            .mapNotNull { (it["function"] as? Map<String, Any>)?.get("name") }
        assertTrue("todo_write" in functionNames)
    }

    @Test
    fun agentTodo_jsonRoundTrip() {
        val todos = listOf(
            AgentTodo(id = "todo_1", content = "分析任务", status = AgentTodo.STATUS_COMPLETED),
            AgentTodo(
                id = "todo_2",
                content = "实现功能",
                status = AgentTodo.STATUS_IN_PROGRESS,
                priority = AgentTodo.PRIORITY_HIGH
            )
        )
        val encoded = AgentTodo.encodeList(todos)
        val decoded = AgentTodo.fromJsonList(encoded)
        assertEquals(todos, decoded)
    }

    @Test
    fun agentTodo_encodeEmptyListReturnsNullAndDecodeBlankReturnsEmpty() {
        assertNull(AgentTodo.encodeList(emptyList()))
        assertTrue(AgentTodo.fromJsonList(null).isEmpty())
        assertTrue(AgentTodo.fromJsonList("").isEmpty())
        assertTrue(AgentTodo.fromJsonList("not-json").isEmpty())
    }
}
