package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.AgentTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentTodoStoreTest {

    @Test
    fun parseAgentTodoWrites_acceptsValidListAndNormalizesFields() {
        val parsed = parseAgentTodoWrites(
            listOf(
                mapOf("content" to "检索资料", "status" to "in_progress", "priority" to "high"),
                mapOf("content" to "汇总结论")
            )
        )
        val todos = (parsed as AgentTodoWriteResult.Ok).todos
        assertEquals(2, todos.size)
        assertEquals(AgentTodo.STATUS_IN_PROGRESS, todos[0].status)
        assertEquals(AgentTodo.PRIORITY_HIGH, todos[0].priority)
        assertEquals(AgentTodo.STATUS_PENDING, todos[1].status)
    }

    @Test
    fun parseAgentTodoWrites_rejectsMissingAndNonListArguments() {
        assertTrue(parseAgentTodoWrites(null) is AgentTodoWriteResult.Invalid)
        assertTrue(parseAgentTodoWrites("nope") is AgentTodoWriteResult.Invalid)
    }

    @Test
    fun parseAgentTodoWrites_emptyListMeansClear() {
        val parsed = parseAgentTodoWrites(emptyList<Any>())
        assertEquals(0, (parsed as AgentTodoWriteResult.Ok).todos.size)
    }

    @Test
    fun formatAgentTodoRead_reportsEmptyStateAndList() {
        val empty = formatAgentTodoRead(emptyList(), scopeLabel = "当前子代理")
        assertEquals(true, empty["success"])
        assertEquals(0, empty["count"])
        assertTrue((empty["content"] as String).startsWith("当前子代理"))

        val nonEmpty = formatAgentTodoRead(
            listOf(
                AgentTodo(id = "todo_1", content = "任务A", status = AgentTodo.STATUS_COMPLETED),
                AgentTodo(id = "todo_2", content = "任务B", status = AgentTodo.STATUS_PENDING)
            ),
            scopeLabel = "当前子代理"
        )
        assertEquals(2, nonEmpty["count"])
        val content = nonEmpty["content"] as String
        assertTrue(content.contains("共 2 条"))
        assertTrue(content.contains("[x] 任务A"))
        assertTrue(content.contains("[ ] 任务B"))
    }

    @Test
    fun subagentTodoStore_isolatesListsByTaskId() {
        val first = listOf(AgentTodo(content = "子代理1的任务"))
        val second = listOf(AgentTodo(content = "子代理2的任务"))
        SubagentTodoStore.set("task_a", first)
        SubagentTodoStore.set("task_b", second)

        assertEquals(first, SubagentTodoStore.get("task_a"))
        assertEquals(second, SubagentTodoStore.get("task_b"))
        assertEquals(emptyList<AgentTodo>(), SubagentTodoStore.get("task_missing"))

        SubagentTodoStore.clearTasks(setOf("task_a"))
        assertEquals(emptyList<AgentTodo>(), SubagentTodoStore.get("task_a"))
        assertEquals(second, SubagentTodoStore.get("task_b"))
        SubagentTodoStore.clearTasks(setOf("task_b"))
    }

    @Test
    fun subagentTaskStore_clearSessionAlsoClearsTodoLists() {
        val task = SubagentTaskStore.register(
            sessionId = "session-subagent-todo",
            parentRunId = "run-1",
            description = "测试任务",
            prompt = "p",
            depth = 2,
            parentTaskId = null
        )
        SubagentTodoStore.set(task.id, listOf(AgentTodo(content = "待办")))

        SubagentTaskStore.clearSession("session-subagent-todo")

        assertEquals(null, SubagentTaskStore.get(task.id))
        assertEquals(emptyList<AgentTodo>(), SubagentTodoStore.get(task.id))
    }

    @Test
    fun interruptedTask_keepsOfflineTodosAndIgnoresLateCompletion() {
        val task = SubagentTaskStore.register(
            sessionId = "session-subagent-interrupted",
            parentRunId = "run-2",
            description = "中断任务",
            prompt = "p",
            depth = 2,
            parentTaskId = null,
            runInBackground = true,
            parentMessageId = "message-2"
        )
        val todos = listOf(AgentTodo(content = "未完成步骤"))
        SubagentTodoStore.set(task.id, todos)
        SubagentTaskStore.update(task.id, status = SubagentTaskStatus.INTERRUPTED)

        SubagentTaskStore.update(task.id, status = SubagentTaskStatus.SUCCEEDED, result = "迟到结果")

        val saved = SubagentTaskStore.get(task.id)!!
        assertEquals(SubagentTaskStatus.INTERRUPTED, saved.status)
        assertEquals(todos, saved.todos)
        assertEquals("message-2", saved.parentMessageId)
        assertTrue(saved.runInBackground)
        SubagentTaskStore.clearSession("session-subagent-interrupted")
    }
}
