package com.nekobot.app.data.local

import com.nekobot.app.data.model.GitDiffFile
import com.nekobot.app.data.model.GitDiffHunk
import com.nekobot.app.data.model.GitDiffLine
import com.nekobot.app.data.model.GitDiffSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * Git 差异引擎与模型测试（纯 JVM，无需 Android / git 二进制）。
 *
 * 端到端测试在临时目录手工构造一个最小的 git 仓库（loose object 存储），
 * 验证 summarize 对"新增/修改/删除"文件的摘要与 diff 内容生成。
 */
class WorkspaceGitDiffTest {

    @After
    fun tearDown() {
        root?.deleteRecursively()
    }

    private var root: File? = null

    private fun newTempDir(): File {
        val dir = Files.createTempDirectory("wgd").toFile()
        root = dir
        return dir
    }

    // ---------- 手工构造 git loose object 仓库 ----------

    private fun sha1(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-1")
        return d.digest(bytes).toHex()
    }

    private fun objectHash(type: String, content: ByteArray): String {
        val header = "$type ${content.size}\u0000".toByteArray(Charsets.US_ASCII)
        return sha1(header + content)
    }

    private fun zlibDeflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val bos = ByteArrayOutputStream()
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

    private fun writeLooseObject(gitDir: File, type: String, content: ByteArray): String {
        val sha = objectHash(type, content)
        val dir = File(gitDir, "objects/${sha.substring(0, 2)}")
        dir.mkdirs()
        File(dir, sha.substring(2)).writeBytes(zlibDeflate("$type ${content.size}\u0000".toByteArray(Charsets.US_ASCII) + content))
        return sha
    }

    private fun treeEntry(mode: String, name: String, sha: String): ByteArray =
        ("$mode $name").toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + hexDecode(sha)

    private fun hexDecode(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 构造一个包含单文件 blobs 的最小 git 仓库，返回 {gitDir, commitSha, treeSha}。 */
    private fun initRepo(
        gitDir: File,
        files: Map<String, ByteArray>
    ): Triple<File, String, String> {
        File(gitDir, "objects/info").mkdirs()
        File(gitDir, "objects/pack").mkdirs()
        File(gitDir, "refs/heads").mkdirs()

        val workspace = gitDir.parentFile
        val treeContent = ByteArrayOutputStream()
        val sortedNames = files.keys.sorted()
        for (name in sortedNames) {
            val blobSha = writeLooseObject(gitDir, "blob", files.getValue(name))
            treeContent.write(treeEntry("100644", name, blobSha))
            // 同步创建工作区文件（git 追踪的文件在工作区也存在）
            val target = File(workspace, name)
            target.parentFile?.mkdirs()
            target.writeBytes(files.getValue(name))
        }
        val treeSha = writeLooseObject(gitDir, "tree", treeContent.toByteArray())

        val commitBody = "tree $treeSha\nauthor Test <t@t> 1700000000 +0800\ncommitter Test <t@t> 1700000000 +0800\n\ninitial\n".toByteArray(Charsets.UTF_8)
        val commitSha = writeLooseObject(gitDir, "commit", commitBody)

        // 分支与 HEAD
        File(gitDir, "refs/heads/main").writeText(commitSha + "\n", Charsets.UTF_8)
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n", Charsets.UTF_8)

        return Triple(gitDir, commitSha, treeSha)
    }

    // ---------- 端到端：新增 / 修改 / 删除 ----------

    @Test
    fun newFileAndModifiedFileProduceDiffSummary() {
        val ws = newTempDir()
        val gitDir = File(ws, ".git")
        // HEAD 基线：a.py = "line1\nline2\nline3\n"，b.py 不存在
        initRepo(gitDir, mapOf("a.py" to "line1\nline2\nline3\n".toByteArray(Charsets.UTF_8)))

        // 工作区修改 a.py 并新增 b.py
        File(ws, "a.py").writeText("line1\nline2 changed\nline3\n", Charsets.UTF_8)
        File(ws, "b.py").writeText("brand new\ncontent\n", Charsets.UTF_8)

        val summary = WorkspaceGitDiff.summarize(ws, listOf("a.py", "b.py"))

        assertNotNull("应生成非空 git 摘要", summary)
        val s = summary!!
        assertEquals("main", s.branch)
        assertEquals(2, s.files.size)

        val a = s.files.first { it.path == "a.py" }
        assertEquals(GitDiffFile.STATUS_MODIFIED, a.status)
        assertTrue("a.py 应有删除行", a.deletions > 0)
        assertTrue("a.py 应有新增行", a.additions > 0)
        assertTrue("a.py 应有 diff hunks", a.hunks.isNotEmpty())
        // 新增行近 line2 changed
        assertTrue(a.hunks.flatMap { it.lines }.any { it.kind == GitDiffLine.KIND_ADD && it.text.contains("changed") })

        val b = s.files.first { it.path == "b.py" }
        assertEquals(GitDiffFile.STATUS_ADDED, b.status)
        assertTrue("b.py 应为新增", b.additions == 2)
        assertTrue(b.hunks.flatMap { it.lines }.any { it.kind == GitDiffLine.KIND_ADD && it.text == "brand new" })
    }

    @Test
    fun deletedFileProducesDeleteDiff() {
        val ws = newTempDir()
        val gitDir = File(ws, ".git")
        initRepo(gitDir, mapOf("gone.txt" to "hello\nworld\n".toByteArray(Charsets.UTF_8)))

        // 工作区删除 gone.txt
        assertTrue(File(ws, "gone.txt").delete())

        val summary = WorkspaceGitDiff.summarize(ws, listOf("gone.txt"))
        assertNotNull(summary)
        val f = summary!!.files.single { it.path == "gone.txt" }
        assertEquals(GitDiffFile.STATUS_DELETED, f.status)
        assertEquals(2, f.deletions)
        assertTrue(f.hunks.flatMap { it.lines }.any { it.kind == GitDiffLine.KIND_DEL && it.text == "hello" })
    }

    @Test
    fun noChangesOrNoGitRepoReturnsNull() {
        val ws = newTempDir()
        // 无 git 仓库：即使有 changedPaths 也返回 null
        File(ws, "a.txt").writeText("x", Charsets.UTF_8)
        assertNull("非 git 仓库应返回 null", WorkspaceGitDiff.summarize(ws, listOf("a.txt")))

        // 空 changedPaths 直接 null
        val gitDir = File(ws, ".git")
        initRepo(gitDir, mapOf("a.txt" to "x".toByteArray(Charsets.UTF_8)))
        assertNull("无变更应返回 null", WorkspaceGitDiff.summarize(ws, emptyList()))
    }

    // ---------- 纯逻辑单测 ----------

    @Test
    fun computeLineDiffHandlesInsertsDeletesAndAutoInternal() {
        val ops = WorkspaceGitDiff.computeLineDiff(
            listOf("a", "b", "c", "d"),
            listOf("a", "x", "c", "d", "e")
        )
        val opsText = ops.joinToString { "${it.kind}@${it.count}" }
        assertTrue("应包含多次操作: $opsText", ops.size >= 3)
        // 公共前缀相同 → 首个操作为 EQUAL
        assertEquals(WorkspaceGitDiff.DiffKind.EQUAL, ops.first().kind)
        // 末尾新增 e → 存在 INSERT
        assertTrue(ops.any { it.kind == WorkspaceGitDiff.DiffKind.INSERT })
        // 差分可重建新内容：按 op 重建 a→b
        val rebuiltOld = StringBuilder(); val rebuiltNew = StringBuilder()
        for (op in ops) {
            when (op.kind) {
                WorkspaceGitDiff.DiffKind.EQUAL -> {
                    for (t in 0 until op.count) rebuiltOld.append(listOf("a","b","c","d")[op.aStart + t]).append('|')
                    for (t in 0 until op.count) rebuiltNew.append(listOf("a","x","c","d","e")[op.bStart + t]).append('|')
                }
                WorkspaceGitDiff.DiffKind.DELETE -> for (t in 0 until op.count) rebuiltOld.append(listOf("a","b","c","d")[op.aStart + t]).append('|')
                WorkspaceGitDiff.DiffKind.INSERT -> for (t in 0 until op.count) rebuiltNew.append(listOf("a","x","c","d","e")[op.bStart + t]).append('|')
            }
        }
        assertEquals("a|b|c|d|", rebuiltOld.toString())
        assertEquals("a|x|c|d|e|", rebuiltNew.toString())
    }

    @Test
    fun buildHunksForProducesValidUnifiedHeaders() {
        val (hunks, truncated) = WorkspaceGitDiff.buildHunksFor(
            old = listOf("a", "b", "c", "d", "e"),
            new = listOf("a", "b", "x", "d", "e")
        )
        assertFalse(truncated)
        assertTrue(hunks.isNotEmpty())
        val h = hunks.first()
        assertTrue("unified header 格式: ${h.header}", h.header.startsWith("@@ -"))
        assertTrue("header 含新增侧: ${h.header}", h.header.contains(" +"))
        val add = h.lines.firstOrNull { it.kind == GitDiffLine.KIND_ADD }
        assertNotNull(add)
        assertEquals("x", add!!.text)
    }

    @Test
    fun buildHunksForHandlesEmptySides() {
        // 新增文件：old 为空
        val (hunks, truncated) = WorkspaceGitDiff.buildHunksFor(emptyList(), listOf("hello"))
        assertFalse(truncated)
        assertTrue(hunks.isNotEmpty())
        assertTrue(hunks.first().lines.all { it.kind == GitDiffLine.KIND_ADD })

        // 全空 → 无 hunk、不截断
        val (h2, t2) = WorkspaceGitDiff.buildHunksFor(emptyList(), emptyList())
        assertFalse(t2)
        assertTrue(h2.isEmpty())
    }

    // ---------- 模型编解码 ----------

    @Test
    fun gitDiffSummaryJsonRoundTrip() {
        val summary = GitDiffSummary(
            repoName = "repo",
            branch = "feature",
            files = listOf(
                GitDiffFile(
                    path = "src/a.kt", status = GitDiffFile.STATUS_ADDED, additions = 3, deletions = 0,
                    hunks = listOf(
                        GitDiffHunk(
                            header = "@@ -0,0 +1,3 @@",
                            lines = listOf(GitDiffLine(GitDiffLine.KIND_ADD, "fun main() {}"))
                        )
                    )
                )
            )
        )
        val json = GitDiffSummary.encode(summary)
        assertNotNull(json)
        val decoded = GitDiffSummary.fromJson(json)
        assertNotNull(decoded)
        assertEquals("repo", decoded!!.repoName)
        assertEquals("feature", decoded.branch)
        assertEquals(1, decoded.files.size)
        assertEquals(3, decoded.totalAdditions)
        assertEquals("src/a.kt", decoded.files[0].path)
        assertEquals("fun main() {}", decoded.files[0].hunks[0].lines[0].text)

        assertNull("null 应编码为 null", GitDiffSummary.encode(null))
        assertNull("非法 JSON 应解析为 null", GitDiffSummary.fromJson("not json"))
        assertNull("空串应解析为 null", GitDiffSummary.fromJson(""))
    }

    @Test
    fun gitBlobShaMatchesKnownValueAndToHex() {
        val bytes = "content".toByteArray(Charsets.UTF_8)
        val sha = WorkspaceGitDiff.gitBlobSha(bytes)
        // 手工计算 "blob 7\0content"
        val expected = sha1("blob 7\u0000content".toByteArray(Charsets.US_ASCII))
        assertEquals(expected, sha)
        assertArrayEquals(
            "1234abcd".toByteArray(Charsets.US_ASCII),
            byteArrayOf(0x12, 0x34, 0xab.toByte(), 0xcd.toByte()).toHex().toByteArray(Charsets.US_ASCII)
        )
    }
}