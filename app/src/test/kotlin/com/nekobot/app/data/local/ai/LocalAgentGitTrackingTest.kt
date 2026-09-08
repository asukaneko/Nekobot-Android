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
import com.nekobot.app.data.local.toHex

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

    @Test
    fun sharedWorkspaceChangesTrackedAndSummarized() = runBlocking {
        val ws = Files.createTempDirectory("wgt").toFile()
        val shared = Files.createTempDirectory("wgt-shared").toFile()
        root = ws
        val executor = LocalAgentToolExecutor(
            sessionId = "session-git-shared",
            workspaceRoot = ws,
            sharedWorkspaceRoot = shared,
            authorizationManager = LocalExecAuthorizationManager(100),
            onConfirmationRequired = {},
            thinkingHistoryProvider = { emptyList() }
        )
        // 共享工作区预置一个 git 仓库（HEAD 含 main.py）
        initGitRepo(File(shared, ".git"), mapOf("main.py" to "print(1)\n".toByteArray(Charsets.UTF_8)))

        // AI 通过 shared:// 前缀编辑共享工作区文件
        val edited = executor.execute(
            "workspace_edit_file",
            mapOf(
                "path" to "shared://main.py",
                "old_string" to "print(1)",
                "new_string" to "print(2)"
            )
        )
        assertEquals(true, edited["success"])
        assertTrue("shared:// 变更应被追踪", executor.currentChangedPaths().contains("shared://main.py"))

        // 快照覆盖共享工作区（shared:// 前缀）
        val snap = executor.snapshotWorkspaceFiles()
        assertTrue("快照应包含共享工作区文件", snap.containsKey("shared://main.py"))

        // 共享工作区仓库上的变更应能生成 git 摘要（回归：此前只查当前工作区返回 null）
        val summary = executor.currentGitDiffSummary()
        assertNotNull("共享工作区变更应生成 git 摘要", summary)
        val f = summary!!.files.first { it.path == "main.py" }
        assertEquals(com.nekobot.app.data.model.GitDiffFile.STATUS_MODIFIED, f.status)
    }

    // ---------- 手工构造 git loose object 仓库（shared 场景测试用） ----------

    private fun sha1(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-1")
        return d.digest(bytes).toHex()
    }

    private fun zlibDeflate(bytes: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater()
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun hexDecode(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun writeLooseObject(gitDir: File, type: String, content: ByteArray): String {
        val header = "$type ${content.size}\u0000".toByteArray(Charsets.US_ASCII)
        val sha = sha1(header + content)
        val dir = File(gitDir, "objects/${sha.substring(0, 2)}")
        dir.mkdirs()
        File(dir, sha.substring(2)).writeBytes(zlibDeflate(header + content))
        return sha
    }

    private fun initGitRepo(gitDir: File, files: Map<String, ByteArray>) {
        File(gitDir, "objects/info").mkdirs()
        File(gitDir, "objects/pack").mkdirs()
        File(gitDir, "refs/heads").mkdirs()

        val workspace = gitDir.parentFile
        val treeContent = java.io.ByteArrayOutputStream()
        for (name in files.keys.sorted()) {
            val content = files.getValue(name)
            val blobSha = writeLooseObject(gitDir, "blob", content)
            treeContent.write(
                ("100644 $name").toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + hexDecode(blobSha)
            )
            val target = File(workspace, name)
            target.parentFile?.mkdirs()
            target.writeBytes(content)
        }
        val treeSha = writeLooseObject(gitDir, "tree", treeContent.toByteArray())
        val commitBody = ("tree $treeSha\nauthor Test <t@t> 1700000000 +0800\n" +
            "committer Test <t@t> 1700000000 +0800\n\ninitial\n").toByteArray(Charsets.UTF_8)
        val commitSha = writeLooseObject(gitDir, "commit", commitBody)
        File(gitDir, "refs/heads/main").writeText(commitSha + "\n", Charsets.UTF_8)
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n", Charsets.UTF_8)
    }
}