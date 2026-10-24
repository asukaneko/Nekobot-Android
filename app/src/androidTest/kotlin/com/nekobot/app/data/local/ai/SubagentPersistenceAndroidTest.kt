package com.nekobot.app.data.local.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubagentPersistenceAndroidTest {
    @Test fun bindOnMainThreadRestoresInterruptedTasksWithoutRoomThreadViolation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "subagent-main-thread-restore.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, NekobotDatabase::class.java, name).build()
        var db = open()
        try {
            db.sessionDao().upsert(LocalSessionEntity("main-thread-session", "Synthetic", sessionMode = "agent",
                createdAt = "now", updatedAt = "now"))
            SubagentTaskStore.bind(db.localSubagentTaskDao())
            val task = SubagentTaskStore.register("main-thread-session", "root", "Test", "Synthetic only", 1, null)
            db.close()
            db = open()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                SubagentTaskStore.bind(db.localSubagentTaskDao())
            }
            assertEquals(SubagentTaskStatus.INTERRUPTED, requireNotNull(SubagentTaskStore.get(task.id)).status)
            assertEquals("interrupted", db.localSubagentTaskDao().get(task.id)!!.status)
        } finally {
            SubagentTaskStore.bind(NekobotDatabase.get(context, ServiceContainer.prefs.activeDbName).localSubagentTaskDao())
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun reopeningRestoresCheckpointsPauseResumeAndRetryLineage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "upstream-083-subagent-proof.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, NekobotDatabase::class.java, name).build()
        var db = open()
        try {
            db.sessionDao().upsert(LocalSessionEntity("s", "Synthetic", sessionMode = "agent", createdAt = "now", updatedAt = "now"))
            SubagentTaskStore.bind(db.localSubagentTaskDao())
            val task = SubagentTaskStore.register("s", "root", "Test", "Synthetic only", 1, null)
            SubagentTaskStore.appendConversationMessage(task.id, mapOf("role" to "assistant", "tool_calls" to listOf(
                mapOf("id" to "call", "type" to "function", "function" to mapOf("name" to "file_read", "arguments" to "{}"))
            )))
            SubagentTaskStore.markToolCallRunning(task.id, "call")
            db.close()
            db = open()
            SubagentTaskStore.bind(db.localSubagentTaskDao())
            val restored = requireNotNull(SubagentTaskStore.get(task.id))
            assertEquals(SubagentTaskStatus.INTERRUPTED, restored.status)
            assertEquals(1, restored.conversation.size)
            assertEquals("running", restored.toolCallStates["call"])
            assertEquals("running", db.localSubagentTaskDao().getLatestToolCall(task.id, "call")!!.status)
            val resumed = requireNotNull(SubagentTaskStore.beginResume(task.id))
            assertEquals(2, resumed.executionGeneration)
            assertNull(SubagentTaskStore.beginResume(task.id))
            val conversation = SubagentTaskStore.prepareConversationForResume(task.id)
            assertTrue(conversation.any { it["role"] == "tool" && it["tool_call_id"] == "call" })
            assertEquals("unknown", db.localSubagentTaskDao().getLatestToolCall(task.id, "call")!!.status)
            SubagentTaskStore.update(task.id, status = SubagentTaskStatus.SUCCEEDED, result = "complete")
            SubagentTaskStore.publishCompletionNotice(requireNotNull(SubagentTaskStore.get(task.id)), null)
            SubagentTaskStore.publishCompletionNotice(requireNotNull(SubagentTaskStore.get(task.id)), null)
            assertEquals(1, db.localSubagentTaskDao().listPendingNotices().size)
            val retry = SubagentTaskStore.register("s", "root", "Retry", "Synthetic only", 1, null, sourceTaskId = task.id)
            assertEquals(task.id, db.localSubagentTaskDao().get(retry.id)!!.sourceTaskId)
            db.sessionDao().deleteById("s")
            assertTrue(db.localSubagentTaskDao().listAll().isEmpty())
            assertTrue(db.localSubagentTaskDao().listPendingNotices().isEmpty())
        } finally {
            // Rebind before closing the isolated fixture; later app tests use the real test profile.
            SubagentTaskStore.bind(NekobotDatabase.get(context, ServiceContainer.prefs.activeDbName).localSubagentTaskDao())
            db.close()
            context.deleteDatabase(name)
        }
    }
}
