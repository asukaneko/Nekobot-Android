package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.WorkspaceGitDiff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * 复现用户反馈：AI 用 file_write 在 git 追踪的工作区创建文件后，git 摘要卡片未显示。
 * 端到端：构造 git 仓库 → LocalAgentToolExecutor.file_write → currentGitDiffSummary()。
 */
class FileWriteGitCardReproTest {

    private var root: File? = null

    @After
    fun tearDown() {
        root?.deleteRecursively()
    }

    // ---------- 最小 git 仓库构造（loose objects） ----------

    private fun sha1(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-1")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater()
        try {
            d.setInput(bytes)
            d.finish()
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!d.finished()) {
                val n = d.deflate(buf)
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun writeObj(gitDir: File, type: String, content: ByteArray): String {
        val header = "$type ${content.size}\u0000".toByteArray(Charsets.US_ASCII)
        val sha = sha1(header + content)
        val dir = File(gitDir, "objects/${sha.substring(0, 2)}")
        dir.mkdirs()
        File(dir, sha.substring(2)).writeBytes(deflate(header + content))
        return sha
    }

    private fun treeEntry(mode: String, name: String, sha: String): ByteArray =
        ("$mode $name").toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
            ByteArray(20) { i -> Integer.parseInt(sha.substring(i * 2, i * 2 + 2), 16).toByte() }

    /** 初始化带一个提交的仓库，含现有文件 a.txt。 */
    private fun initRepoWithCommit(ws: File) {
        val gitDir = File(ws, ".git")
        File(gitDir, "objects/info").mkdirs()
        File(gitDir, "objects/pack").mkdirs()
        File(gitDir, "refs/heads").mkdirs()

        val blob = writeObj(gitDir, "blob", "hello\n".toByteArray(Charsets.UTF_8))
        val treeContent = ByteArrayOutputStream().apply {
            write(treeEntry("100644", "a.txt", blob))
        }.toByteArray()
        val tree = writeObj(gitDir, "tree", treeContent)
        val commitBody = "tree $tree\nauthor T <t@t> 1700000000 +0800\ncommitter T <t@t> 1700000000 +0800\n\ninit\n".toByteArray(Charsets.UTF_8)
        val commit = writeObj(gitDir, "commit", commitBody)

        File(gitDir, "refs/heads/main").writeText(commit + "\n", Charsets.UTF_8)
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n", Charsets.UTF_8)
        File(ws, "a.txt").writeText("hello\n", Charsets.UTF_8)
    }

    private fun buildExecutor(ws: File): LocalAgentToolExecutor = LocalAgentToolExecutor(
        sessionId = "session-repro",
        workspaceRoot = ws,
        authorizationManager = LocalExecAuthorizationManager(100),
        onConfirmationRequired = {},
        thinkingHistoryProvider = { emptyList() }
    )

    @Test
    fun fileWriteInGitRepoProducesSummary() = runBlocking {
        val ws = Files.createTempDirectory("fwr").toFile()
        root = ws
        initRepoWithCommit(ws)
        val executor = buildExecutor(ws)

        val result = executor.execute(
            "file_write",
            mapOf("path" to "new.txt", "content" to "brand new file\n")
        )
        assertEquals(true, result["success"])
        assertTrue("应记录变更路径", executor.currentChangedPaths().contains("new.txt"))

        val summary = executor.currentGitDiffSummary()
        assertNotNull("file_write 后应能生成 git 摘要", summary)
        assertEquals(1, summary!!.files.size)
        assertEquals("new.txt", summary.files[0].path)
        assertEquals(com.nekobot.app.data.model.GitDiffFile.STATUS_ADDED, summary.files[0].status)
    }

    @Test
    fun fileWriteOutsideGitRepoReturnsNull() = runBlocking {
        val ws = Files.createTempDirectory("fwr2").toFile()
        root = ws
        // 无 .git
        val executor = buildExecutor(ws)
        executor.execute("file_write", mapOf("path" to "x.txt", "content" to "x"))
        assertEquals(null, executor.currentGitDiffSummary())
    }

    /** 只有 .git 目录但没有任何提交（git init 后未 commit）→ HEAD 缺失，以空树为基线全部视为新增。 */
    @Test
    fun gitRepoWithoutCommitStillProducesAddedSummary() = runBlocking {
        val ws = Files.createTempDirectory("fwr3").toFile()
        root = ws
        // 空仓库：只有 .git，无 HEAD / refs / objects
        val gitDir = File(ws, ".git")
        File(gitDir, "objects/info").mkdirs()
        File(gitDir, "objects/pack").mkdirs()
        File(gitDir, "refs/heads").mkdirs()
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n", Charsets.UTF_8)

        val executor = buildExecutor(ws)
        val result = executor.execute("file_write", mapOf("path" to "a.txt", "content" to "x"))
        assertEquals(true, result["success"])

        // 无提交也应生成"新增"摘要，否则用户 git init 后看不到任何卡片
        val summary = executor.currentGitDiffSummary()
        assertNotNull("git init 后未提交也应生成新增摘要", summary)
        assertEquals("a.txt", summary!!.files.single().path)
        assertEquals(com.nekobot.app.data.model.GitDiffFile.STATUS_ADDED, summary.files[0].status)
    }

    /** 工作区是 git 仓库的子目录（设备上 filesDir/workspace/<sessionId> 是仓库子目录）。 */
    @Test
    fun workspaceInsideGitRepoSubdirProducesSummary() = runBlocking {
        val repoRoot = Files.createTempDirectory("fwr4").toFile()
        root = repoRoot
        initRepoWithCommit(repoRoot)
        // 工作区 = 仓库子目录 ws/session-1
        val ws = File(repoRoot, "session-1").apply { mkdirs() }
        // 仓库内已提交的 a.txt 保留在根；工作区在子目录
        val executor = buildExecutor(ws)

        val result = executor.execute("file_write", mapOf("path" to "new.txt", "content" to "brand new file\n"))
        assertEquals(true, result["success"])

        val summary = executor.currentGitDiffSummary()
        assertNotNull("子目录工作区也应生成摘要", summary)
        // diff 路径是相对 git 根目录的（引擎行为），工作区在仓库子目录 session-1 下
        assertEquals("session-1/new.txt", summary!!.files.single().path)
        assertEquals(com.nekobot.app.data.model.GitDiffFile.STATUS_ADDED, summary.files[0].status)
    }
}
