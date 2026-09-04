package com.nekobot.app.data.local.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 验证 LocalAgentToolExecutor 对文件变更工具的路径追踪，以及 git 摘要生成。
 */
class LocalAgentGitTrackingTest {

    private var root: File? = null

    @After
    fun tearDown() {
        root?.deleteRecursively()
    }

    private fun buildExecutor(workspace: File): LocalAgentToolExecutor = LocalAgentToolExecutor(
        sessionId = "session-git-tracking",
        workspaceRoot = workspace,
        authorizationManager = LocalExecAuthorizationManager(100),
        onConfirmationRequired = {},
        thinkingHistoryProvider = { emptyList() }
    )

    @Test
    fun fileToolsTrackChangedRelativePaths() = runBlocking {
        val ws = Files.createTempDirectory("wgt").toFile()
        root = ws
        val executor = buildExecutor(ws)

        val created = executor.execute("workspace_create_file", mapOf("path" to "src/main.kt", "content" to "fun main() {}"))
        assertEquals(true, created["success"])
        val edited = executor.execute("workspace_edit_file", mapOf("path" to "src/main.kt", "old_string" to "fun", "new_string" to "inline fun"))
        assertEquals(true, edited["success"])

        // linux 风格别名
        val linux = executor.execute("file_write", mapOf("path" to "notes.txt", "content" to "hi"))
        assertEquals(true, linux["success"])

        val changes = executor.currentChangedPaths()
        assertEquals(setOf("src/main.kt", "notes.txt"), changes)

        // 删除后路径仍在追踪（用于 stat 删除差异）
        val del = executor.execute("workspace_delete_file", mapOf("path" to "notes.txt"))
        assertEquals(true, del["success"])
        assertTrue(executor.currentChangedPaths().contains("notes.txt"))
    }

    @Test
    fun noChangedPathsWhenOnlyWritingAndReading() = runBlocking {
        val ws = Files.createTempDirectory("wgt").toFile()
        root = ws
        val executor = buildExecutor(ws)

        // 只写再删，路径应保持为空（没有发生"成功变更"）
        executor.execute("workspace_create_file", mapOf("path" to "a.txt", "content" to "x"))
        assertFalse(executor.currentChangedPaths().isEmpty())

        val read = executor.execute("workspace_read_file", mapOf("path" to "a.txt"))
        assertEquals(true, read["success"])
        // 读操作不增加路径
        assertEquals(setOf("a.txt"), executor.currentChangedPaths())
    }

    @Test
    fun gitSummaryIsNullWithoutGitRepo() = runBlocking {
        val ws = Files.createTempDirectory("wgt").toFile()
        root = ws
        val executor = buildExecutor(ws)
        executor.execute("workspace_create_file", mapOf("path" to "a.txt", "content" to "x"))
        // 无 .git 目录 → null（不渲染卡片，不抛异常）
        assertNull(executor.currentGitDiffSummary())
    }

    @Test
    fun execSnapshotTracksCreatedModifiedAndDeletedFiles() {
        val ws = Files.createTempDirectory("wgt").toFile()
        root = ws
        val executor = buildExecutor(ws)
        // exec 执行前工作区已有两个文件
        File(ws, "pre.txt").writeText("before")
        File(ws, "gone.txt").writeText("old")

        // 等价于 exec 执行前的快照
        val before = executor.snapshotWorkspaceFiles()
        assertTrue(before.containsKey("pre.txt"))
        assertTrue(before.containsKey("gone.txt"))

        // 模拟 exec 内的三类变更：
        // 1. 新增 created.txt
        File(ws, "created.txt").writeText("new")
        // 2. 修改 pre.txt（内容变长）
        File(ws, "pre.txt").writeText("before changed content")
        // 3. 删除 gone.txt
        assertTrue(File(ws, "gone.txt").delete())

        // recordWorkspaceChanges 内部会重新拍"执行后"快照并对比
        executor.recordWorkspaceChanges(before)

        val changed = executor.currentChangedPaths()
        assertTrue("新增文件应被追踪", changed.contains("created.txt"))
        assertTrue("修改文件应被追踪", changed.contains("pre.txt"))
        assertTrue("删除文件应被追踪", changed.contains("gone.txt"))
        assertTrue("无关文件不应被追踪", !changed.contains("unrelated.txt"))
    }
}