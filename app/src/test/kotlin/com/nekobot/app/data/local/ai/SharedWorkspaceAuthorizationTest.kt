package com.nekobot.app.data.local.ai

import com.nekobot.app.data.remote.ExecAuthorization
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedWorkspaceAuthorizationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rejectionBlocksAliasesDownloadsAndEpubBeforeAnySideEffect() = runBlocking {
        val workspace = temporary.newFolder()
        val shared = temporary.newFolder()
        val target = shared.resolve("demo.txt").apply { writeText("original") }
        val manager = LocalExecAuthorizationManager(1_000)
        var prompts = 0
        val executor = LocalAgentToolExecutor("demo-session", workspace, manager, { request ->
            prompts++
            manager.resolve(request.requestId, "demo-session", ExecAuthorization.Reject)
        }, { emptyList() }, sharedWorkspaceRoot = shared, globalAgentMemoryStore = null)
        val calls = listOf(
            "file_write" to mapOf("path" to "", "filename" to "shared://demo.txt", "content" to "replacement"),
            "file_edit" to mapOf("file_path" to "shared://demo.txt", "old_string" to "original", "new_string" to "replacement"),
            "workspace_create_file" to mapOf("filename" to "shared://demo.txt", "content" to "replacement"),
            "workspace_edit_file" to mapOf("path" to "shared://demo.txt", "old_string" to "original", "new_string" to "replacement"),
            "workspace_delete_file" to mapOf("file_path" to "shared://demo.txt"),
            "download_file" to mapOf("url" to "https://example.com/demo.txt", "save_path" to "shared://demo.txt"),
            "workspace_extract_epub" to mapOf("path" to "demo.epub", "output_path" to "shared://demo.txt"),
            "workspace_extract_epub" to mapOf("filename" to "shared://demo.epub")
        )
        calls.forEachIndexed { index, (name, args) ->
            val result = executor.execute(name, args)
            assertEquals(false, result["success"])
            assertEquals(index + 1, prompts)
            assertEquals("original", target.readText())
            assertEquals(listOf("demo.txt"), shared.listFiles()!!.map { it.name })
        }
    }

    @Test fun oneTimePermissionAllowsExactlyTheRequestedWrite() = runBlocking {
        val workspace = temporary.newFolder()
        val shared = temporary.newFolder()
        val manager = LocalExecAuthorizationManager(1_000)
        var prompts = 0
        val executor = LocalAgentToolExecutor("demo-session", workspace, manager, { request ->
            prompts++
            manager.resolve(request.requestId, "demo-session", ExecAuthorization.Once)
        }, { emptyList() }, sharedWorkspaceRoot = shared, globalAgentMemoryStore = null)
        val arguments = mapOf("path" to "", "filename" to "shared://demo.txt", "content" to "demo")
        assertEquals(true, executor.execute("file_write", arguments)["success"])
        assertEquals(1, prompts)
        assertEquals("demo", shared.resolve("demo.txt").readText())
        assertEquals(true, executor.execute("file_write", arguments)["success"])
        assertEquals(2, prompts)
    }
}
