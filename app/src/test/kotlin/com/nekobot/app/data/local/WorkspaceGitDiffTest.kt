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

    @Test
    fun absolutePathsOutsideWorkspaceProduceSummary() {
        // 模拟共享工作区：仓库位于会话工作区之外的独立根目录
        val sharedRoot = newTempDir()
        val ws = newTempDir()
        val gitDir = File(sharedRoot, ".git")
        initRepo(gitDir, mapOf("src/a.py" to "line1\nline2\n".toByteArray(Charsets.UTF_8)))

        // 工作区外的仓库文件被修改 / 新增（AI 编辑共享工作区文件）
        val target = File(sharedRoot, "src/a.py")
        target.writeText("line1 changed\nline2\n", Charsets.UTF_8)
        val added = File(sharedRoot, "src/b.py")
        added.writeText("brand new\n", Charsets.UTF_8)

        // changedPaths 传入真实绝对路径，workspace 参数指向会话工作区
        val summary = WorkspaceGitDiff.summarize(
            ws,
            listOf(target.canonicalPath, added.canonicalPath)
        )
        assertNotNull("绝对路径（共享工作区）应生成 git 摘要", summary)
        val s = summary!!
        val a = s.files.first { it.path == "src/a.py" }
        assertEquals(GitDiffFile.STATUS_MODIFIED, a.status)
        assertTrue("a.py 应有新增行", a.additions > 0)
        val b = s.files.first { it.path == "src/b.py" }
        assertEquals(GitDiffFile.STATUS_ADDED, b.status)
        assertTrue(b.hunks.flatMap { it.lines }.any { it.kind == GitDiffLine.KIND_ADD && it.text == "brand new" })
    }

    @Test
    fun mixedRelativeAndAbsolutePathsPickLargestRepo() {
        // 相对路径（会话工作区仓库）与绝对路径（共享工作区仓库）混用时，
        // 应正确解析各自仓库并按文件数取覆盖最多的仓库生成单张摘要
        val ws = newTempDir()
        val wsGit = File(ws, ".git")
        initRepo(wsGit, mapOf("keep.txt" to "x\n".toByteArray(Charsets.UTF_8)))
        File(ws, "keep.txt").writeText("x changed\n", Charsets.UTF_8)

        val sharedRoot = newTempDir()
        val sharedGit = File(sharedRoot, ".git")
        initRepo(sharedGit, mapOf("only.txt" to "y\n".toByteArray(Charsets.UTF_8)))
        File(sharedRoot, "only.txt").writeText("y changed\n", Charsets.UTF_8)

        // 相对 + 绝对混合，两者各 1 个变更文件 → 取 first（相对路径仓库）
        val summary = WorkspaceGitDiff.summarize(
            ws,
            listOf("keep.txt", File(sharedRoot, "only.txt").canonicalPath)
        )
        assertNotNull(summary)
        assertEquals("keep.txt", summary!!.files.single().path)
    }

    @Test
    fun repositoryScanFindsEveryChangeWithoutPathHints() {
        // 实时监控语义：不需要任何"AI 改了哪些文件"的输入，直接扫描仓库相对 HEAD 的全部更改
        val ws = newTempDir()
        val gitDir = File(ws, ".git")
        initRepo(
            gitDir,
            mapOf(
                "a.py" to "line1\nline2\n".toByteArray(Charsets.UTF_8),
                "gone.py" to "bye\n".toByteArray(Charsets.UTF_8)
            )
        )

        File(ws, "a.py").writeText("line1 changed\nline2\n", Charsets.UTF_8)
        File(ws, "added.py").writeText("brand new\n", Charsets.UTF_8)
        assertTrue(File(ws, "gone.py").delete())

        val summary = WorkspaceGitDiff.summarizeRepository(ws)
        assertNotNull("应扫描出仓库相对 HEAD 的全部更改", summary)
        val files = summary!!.files.associateBy { it.path }
        assertEquals(setOf("a.py", "added.py", "gone.py"), files.keys)
        assertEquals(GitDiffFile.STATUS_MODIFIED, files.getValue("a.py").status)
        assertEquals(GitDiffFile.STATUS_ADDED, files.getValue("added.py").status)
        assertEquals(GitDiffFile.STATUS_DELETED, files.getValue("gone.py").status)
        assertTrue(
            "不应把 git 内部文件当成工作区更改",
            files.keys.none { it.startsWith(".git/") || it == ".git" }
        )

        // 无任何更改时返回 null（调用方据此隐藏变更区域）
        File(ws, "a.py").writeText("line1\nline2\n", Charsets.UTF_8)
        File(ws, "added.py").delete()
        File(ws, "gone.py").writeText("bye\n", Charsets.UTF_8)
        assertNull("与 HEAD 一致时应返回 null", WorkspaceGitDiff.summarizeRepository(ws))
    }

    @Test
    fun repositoryScanSkipsBuildDirectories() {
        val ws = newTempDir()
        val gitDir = File(ws, ".git")
        initRepo(gitDir, mapOf("src/a.py" to "x\n".toByteArray(Charsets.UTF_8)))

        // 构建产物目录里的新文件不进入变更列表
        val buildDir = File(ws, "build/outputs")
        buildDir.mkdirs()
        File(buildDir, "app.apk").writeText("bin", Charsets.UTF_8)

        assertNull("只有构建产物变化时不应产生摘要", WorkspaceGitDiff.summarizeRepository(ws))
    }

    @Test
    fun summarizeRepositoriesFindsNestedRepositoryInsideWorkspace() {
        // 用户把项目放进工作区：工作区本身不是仓库，但子目录里是
        val ws = newTempDir()
        val project = File(ws, "project")
        initRepo(File(project, ".git"), mapOf("main.py" to "print(1)\n".toByteArray(Charsets.UTF_8)))
        File(project, "main.py").writeText("print(2)\n", Charsets.UTF_8)

        val summary = WorkspaceGitDiff.summarizeRepositories(listOf(ws))
        assertNotNull("应自动发现工作区内的嵌套仓库", summary)
        assertEquals("project", summary!!.repoName)
        assertEquals("main.py", summary.files.single().path)
    }

    @Test
    fun summarizeRepositoriesUsesHintPathToLocateExternalRepository() {
        // 工作区（App 私有目录）不是仓库：靠写入记录定位共享存储里的项目仓库
        val ws = newTempDir()
        val project = newTempDir()
        initRepo(File(project, ".git"), mapOf("book.md" to "v1\n".toByteArray(Charsets.UTF_8)))
        val edited = File(project, "book.md")
        edited.writeText("v2\n", Charsets.UTF_8)

        assertNull("无提示路径时工作区没有仓库", WorkspaceGitDiff.summarizeRepositories(listOf(ws)))

        val summary = WorkspaceGitDiff.summarizeRepositories(
            listOf(ws),
            listOf(edited.canonicalPath)
        )
        assertNotNull("提示路径所在仓库应被监控", summary)
        assertEquals("book.md", summary!!.files.single().path)
    }

    @Test
    fun scanRepositoryDistinguishesCleanFromChanged() {
        val ws = newTempDir()
        initRepo(File(ws, ".git"), mapOf("a.py" to "x\n".toByteArray(Charsets.UTF_8)))

        // 与 HEAD 完全一致：明确是「干净」，而不是「没扫出来」
        assertEquals(WorkspaceGitDiff.GitScanResult.Clean, WorkspaceGitDiff.scanRepository(ws))

        File(ws, "a.py").writeText("y\n", Charsets.UTF_8)
        val changed = WorkspaceGitDiff.scanRepository(ws)
        assertTrue("有改动时应返回 Changed", changed is WorkspaceGitDiff.GitScanResult.Changed)
        assertEquals("a.py", (changed as WorkspaceGitDiff.GitScanResult.Changed).summary.files.single().path)
    }

    @Test
    fun watchRepositoriesAppliesCustomRepositoryName() {
        // 仓库根就是工作区时，卡片上不显示 app 私有目录名（如 files），由调用方给出「工作区」
        val ws = newTempDir()
        initRepo(File(ws, ".git"), mapOf("a.py" to "x\n".toByteArray(Charsets.UTF_8)))
        File(ws, "a.py").writeText("y\n", Charsets.UTF_8)

        val result = WorkspaceGitDiff.watchRepositories(
            roots = listOf(ws),
            hintPaths = emptyList(),
            nameForRepo = { "工作区" }
        )
        val changed = result as? WorkspaceGitDiff.GitScanResult.Changed
            ?: throw AssertionError("应扫描出更改: $result")
        assertEquals("工作区", changed.summary.repoName)
    }

    /** 在既有仓库上追加一次提交（等价于 git add -A && git commit），返回新 commit sha。 */
    private fun commitWorktree(gitDir: File, files: Map<String, ByteArray>, parent: String): String {
        val treeContent = ByteArrayOutputStream()
        for (name in files.keys.sorted()) {
            val blobSha = writeLooseObject(gitDir, "blob", files.getValue(name))
            treeContent.write(treeEntry("100644", name, blobSha))
        }
        val treeSha = writeLooseObject(gitDir, "tree", treeContent.toByteArray())
        val commitBody = (
            "tree $treeSha\nparent $parent\nauthor Test <t@t> 1700000100 +0800\n" +
                "committer Test <t@t> 1700000100 +0800\n\nsecond\n"
            ).toByteArray(Charsets.UTF_8)
        val commitSha = writeLooseObject(gitDir, "commit", commitBody)
        File(gitDir, "refs/heads/main").writeText(commitSha + "\n", Charsets.UTF_8)
        return commitSha
    }

    @Test
    fun baselineFromRunStartKeepsCommittedChangesVisible() {
        // AI 中途把自己改的文件提交掉时，卡片不应该突然变空
        val ws = newTempDir()
        val gitDir = File(ws, ".git")
        val init = initRepo(gitDir, mapOf("a.py" to "x\n".toByteArray(Charsets.UTF_8)))
        val startSha = WorkspaceGitDiff.headShaOf(ws)
        assertEquals("应读到初始提交", init.second, startSha)

        File(ws, "b.py").writeText("new\n", Charsets.UTF_8)
        commitWorktree(
            gitDir,
            mapOf(
                "a.py" to "x\n".toByteArray(Charsets.UTF_8),
                "b.py" to "new\n".toByteArray(Charsets.UTF_8)
            ),
            requireNotNull(startSha)
        )

        // 与当前 HEAD 比较：仓库是干净的
        assertEquals(
            WorkspaceGitDiff.GitScanResult.Clean,
            WorkspaceGitDiff.scanRepository(ws)
        )
        // 与本轮开始时的 HEAD 比较：b.py 仍是新增，卡片保持显示
        val result = WorkspaceGitDiff.scanRepository(ws, WorkspaceGitDiff.GitBaseline.At(startSha))
        val changed = result as? WorkspaceGitDiff.GitScanResult.Changed
            ?: throw AssertionError("固定基线后应仍能看到本轮改动: $result")
        assertEquals("b.py", changed.summary.files.single().path)
        assertEquals(GitDiffFile.STATUS_ADDED, changed.summary.files.single().status)
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