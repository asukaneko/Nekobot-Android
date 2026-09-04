package com.nekobot.app.data.local

import com.nekobot.app.data.model.GitDiffFile
import com.nekobot.app.data.model.GitDiffHunk
import com.nekobot.app.data.model.GitDiffLine
import com.nekobot.app.data.model.GitDiffSummary
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/**
 * 纯 Kotlin 实现的 Git 只读差异引擎（无外部 git 二进制依赖）。
 *
 * 场景：本地 Agent 在工作区编辑文件后，聊天界面需要展示"相对 HEAD 的 git diff 摘要卡片"。
 * Android 上没有 git 命令，因此这里直接按 Git 对象格式解析仓库：
 * - HEAD → commit → tree → path→blob-sha 映射（loose object + packfile 两种存储）
 * - 工作区文件与 HEAD blob 对比（SHA-1 快速判等 → 行级 Myers/LCS 差异 → unified hunks）
 *
 * 仅覆盖常见的代码仓库场景；对无法解析（如 alternates 仓库、超大数据）做安全降级，
 * 返回 unavailable / truncated 标记，绝不抛出异常导致工具链路中断。
 */
object WorkspaceGitDiff {

    // ---------- 上限与安全阀 ----------
    // GitRepository / TreeParser 等外层类也会读取这些上限，故声明为 internal 而非 private。
    internal const val MAX_FILES_PER_SUMMARY = 30
    internal const val MAX_TREE_ENTRIES = 60_000
    internal const val MAX_TREES = 10_000
    internal const val MAX_TREE_DEPTH = 40
    internal const val MAX_BLOB_BYTES = 32L * 1024 * 1024
    internal const val MAX_OBJECT_BYTES = 64L * 1024 * 1024
    internal const val MAX_FILE_LINES = 200_000
    internal const val MAX_DIFF_LINES_PER_FILE = 400
    internal const val MAX_HUNKS_PER_FILE = 16
    internal const val MAX_LINE_TEXT_CHARS = 300
    internal const val MAX_SUMMARY_DIFF_CHARS = 260_000
    internal const val MAX_LCS_CELLS = 1_000_000L
    internal const val FIND_GIT_ROOT_DEPTH = 40
    internal const val BINARY_SCAN_BYTES = 8192

    internal const val OBJ_COMMIT = 1
    internal const val OBJ_TREE = 2
    internal const val OBJ_BLOB = 3
    internal const val OBJ_TAG = 4
    internal const val OBJ_OFS_DELTA = 6
    internal const val OBJ_REF_DELTA = 7

    // ---------- 仓库实例缓存（按 git 根目录；对象/树缓存随 HEAD 变化自动失效） ----------
    private val repoCache = ConcurrentHashMap<String, GitRepository>()

    /** 获取（或缓存）一个 git 根目录对应的只读仓库视图。 */
    internal fun repositoryFor(gitRoot: File): GitRepository {
        val key = gitRoot.canonicalPath
        return repoCache.computeIfAbsent(key) {
            if (repoCache.size >= 6) repoCache.clear() // 简单容量保护
            GitRepository(gitRoot)
        }
    }

    /**
     * 汇总工作区内被修改文件的 Git 差异摘要。
     *
     * @param workspace 会话工作区根目录
     * @param changedPaths 相对 workspace 的改动路径（AI 本轮实际写入/删除的文件）
     * @return 非 git 仓库 / 无净变化时返回 null（调用方不渲染卡片）
     */
    fun summarize(workspace: File, changedPaths: Collection<String>): GitDiffSummary? {
        if (workspace == null) return null
        if (changedPaths.isEmpty()) return null
        return try {
            summarizeInternal(workspace, changedPaths)
        } catch (t: Throwable) {
            // 差异引擎只是增强展示，任何解析异常都不应中断 Agent 主链路
            com.nekobot.app.data.local.LocalLogger.w("WorkspaceGit", "git diff 摘要生成失败: ${t.message}")
            null
        }
    }

    private fun summarizeInternal(
        workspace: File,
        changedPaths: Collection<String>
    ): GitDiffSummary? {
        val wsRoot = runCatching { workspace.canonicalFile }.getOrNull() ?: workspace.absoluteFile

        // 1. 按文件所在 git 仓库分组（支持工作区嵌套仓库 / 工作区位于仓库子目录）
        val byRepo = LinkedHashMap<File, MutableList<File>>()
        for (relative in changedPaths) {
            val rel = relative.trim().replace('\\', '/').removePrefix("./")
            if (rel.isBlank()) continue
            // 忽略 .git 内部写入
            if (rel == ".git" || rel.startsWith(".git/")) continue
            val file = File(wsRoot, rel)
            val parent = file.parentFile ?: wsRoot
            val gitRoot = findGitRoot(parent, FIND_GIT_ROOT_DEPTH) ?: continue
            val canonicalRoot = runCatching { gitRoot.canonicalFile }.getOrElse { gitRoot.absoluteFile }
            val target = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
            // 文件本身不应落在 .git 目录内
            val gitDirPath = File(canonicalRoot, ".git").absolutePath
            if (target.absolutePath.startsWith(gitDirPath + File.separator)) continue
            byRepo.getOrPut(canonicalRoot) { mutableListOf() }.add(target)
        }
        if (byRepo.isEmpty()) return null

        // 2. 取覆盖改动文件最多的仓库生成单张摘要
        val mainEntry = byRepo.maxByOrNull { it.value.size } ?: return null
        val gitRoot = mainEntry.key
        val repo = repositoryFor(gitRoot)
        // HEAD 缺失（git init 后尚无提交）时以空树为基线：所有变更文件视为"新增"。
        val headSha = repo.headSha()
        val headTree = if (headSha != null) {
            repo.treeFiles(headSha) ?: return null
        } else {
            emptyMap()
        }

        // 3. 逐文件 diff（HEAD → 工作区）
        val diffs = mutableListOf<GitDiffFile>()
        var diffChars = 0
        var truncatedByBudget = false
        val paths = mainEntry.value.distinct().sortedBy { repoRelative(gitRoot, it) }
        for (file in paths) {
            if (diffs.size >= MAX_FILES_PER_SUMMARY) break
            val repoRel = repoRelative(gitRoot, file)
            val fileDiff = diffFile(repo, headTree, repoRel, file)
            if (fileDiff == null) continue
            if (truncatedByBudget) {
                // 全局字符预算已超：仅保留状态与计数，不再携带 hunks
                val slim = fileDiff.copy(hunks = emptyList(), truncated = true)
                diffs.add(slim)
                continue
            }
            val fileChars = fileDiff.hunks.sumOf { hunk ->
                hunk.lines.sumOf { 1 + it.text.length } + hunk.header.length
            }
            diffChars += fileChars
            if (diffChars > MAX_SUMMARY_DIFF_CHARS) {
                truncatedByBudget = true
                val slim = fileDiff.copy(hunks = emptyList(), truncated = true)
                diffs.add(slim)
            } else {
                diffs.add(fileDiff)
            }
        }
        if (diffs.isEmpty()) return null

        val filesTruncated = truncatedByBudget ||
            mainEntry.value.distinct().size > MAX_FILES_PER_SUMMARY
        return GitDiffSummary(
            repoName = gitRoot.name,
            branch = repo.branch(),
            files = diffs,
            filesTruncated = filesTruncated
        )
    }

    /** 将仓库内绝对路径转为相对仓库根的 "/" 分隔路径。 */
    private fun repoRelative(gitRoot: File, file: File): String =
        file.relativeTo(gitRoot).invariantSeparatorsPath

    /** 单个文件 HEAD→工作区差异；无净变化返回 null。 */
    private fun diffFile(
        repo: GitRepository,
        headTree: Map<String, String>,
        repoRel: String,
        file: File
    ): GitDiffFile? {
        val headSha = headTree[repoRel]
        val exists = file.isFile
        if (headSha == null) {
            // HEAD 中不存在 → 新增文件（或创建后又被删除 → 忽略）
            if (!exists) return null
            return diffNewFile(file, repoRel)
        }
        if (!exists) {
            // 已被删除
            val baseBytes = repo.readBlob(headSha)
            if (baseBytes == null) {
                return GitDiffFile(path = repoRel, status = GitDiffFile.STATUS_DELETED, unavailable = true)
            }
            if (looksBinary(baseBytes)) {
                return GitDiffFile(path = repoRel, status = GitDiffFile.STATUS_DELETED, binary = true)
            }
            val (hunks, truncated) = buildHunksFor(
                splitTextLines(decodeUtf8(baseBytes)),
                emptyList()
            )
            val additions = 0
            val deletions = hunks.sumOf { h -> h.lines.count { it.kind == GitDiffLine.KIND_DEL } }
            return GitDiffFile(
                path = repoRel, status = GitDiffFile.STATUS_DELETED,
                additions = additions, deletions = deletions,
                truncated = truncated, hunks = hunks
            )
        }

        // 文件仍在：先读工作区内容
        if (file.length() > MAX_BLOB_BYTES) return null
        val workingBytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        // SHA-1 判等：内容与 HEAD 一致（如整文件重写为相同内容）→ 无变化
        if (gitBlobSha(workingBytes) == headSha) return null

        val baseBytes = repo.readBlob(headSha)
        if (baseBytes == null) {
            return GitDiffFile(path = repoRel, status = GitDiffFile.STATUS_MODIFIED, unavailable = true)
        }
        if (looksBinary(baseBytes) || looksBinary(workingBytes)) {
            return GitDiffFile(path = repoRel, status = GitDiffFile.STATUS_MODIFIED, binary = true)
        }
        val (hunks, truncated) = buildHunksFor(
            splitTextLines(decodeUtf8(baseBytes)),
            splitTextLines(decodeUtf8(workingBytes))
        )
        val additions = hunks.sumOf { h -> h.lines.count { it.kind == GitDiffLine.KIND_ADD } }
        val deletions = hunks.sumOf { h -> h.lines.count { it.kind == GitDiffLine.KIND_DEL } }
        return GitDiffFile(
            path = repoRel, status = GitDiffFile.STATUS_MODIFIED,
            additions = additions, deletions = deletions,
            truncated = truncated, hunks = hunks
        )
    }

    /** 新增文件：空 → 全量内容。 */
    private fun diffNewFile(file: File, repoRel: String): GitDiffFile? {
        if (file.length() > MAX_BLOB_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (looksBinary(bytes)) {
            return GitDiffFile(path = repoRel, status = GitDiffFile.STATUS_ADDED, binary = true)
        }
        val (hunks, truncated) = buildHunksFor(emptyList(), splitTextLines(decodeUtf8(bytes)))
        val additions = hunks.sumOf { h -> h.lines.count { it.kind == GitDiffLine.KIND_ADD } }
        return GitDiffFile(
            path = repoRel, status = GitDiffFile.STATUS_ADDED,
            additions = additions, deletions = 0,
            truncated = truncated, hunks = hunks
        )
    }

    // ---------- 文本处理 ----------

    private fun decodeUtf8(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            sb.append(decoder.decode(java.nio.ByteBuffer.wrap(bytes)))
        } catch (_: Exception) {
            sb.append(String(bytes, Charsets.UTF_8))
        }
        return sb.toString()
    }

    /** 按行拆分（去掉行尾换行符；保留空行）。超过行数上限返回 null。 */
    private fun splitTextLines(text: String): List<String>? {
        if (text.isEmpty()) return emptyList()
        var newline = text.indexOf('\n')
        if (newline < 0) return listOf(text)
        val lines = ArrayList<String>(text.length / 24 + 8)
        var start = 0
        while (newline >= 0) {
            lines.add(text.substring(start, newline).removeSuffix("\r"))
            start = newline + 1
            newline = text.indexOf('\n', start)
            if (lines.size > MAX_FILE_LINES) return null
        }
        if (start < text.length) lines.add(text.substring(start))
        return lines
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val scan = minOf(bytes.size, BINARY_SCAN_BYTES)
        for (i in 0 until scan) {
            if (bytes[i].toInt() == 0) return true
        }
        return false
    }

    // ---------- 行级差异（前缀/后缀裁剪 + LCS 方向表，超限退化为整块替换） ----------

    internal enum class DiffKind { EQUAL, DELETE, INSERT }

    /** 行级编辑脚本：EQUAL(aStart,bStart,count) / DELETE(aStart,count) / INSERT(bStart,count) */
    internal data class DiffOp(
        val kind: DiffKind,
        val count: Int,
        val aStart: Int,
        val bStart: Int
    )

    /** 计算 a→b 的最小行编辑脚本（a 为基线，b 为新内容）。 */
    internal fun computeLineDiff(a: List<String>, b: List<String>): List<DiffOp> {
        // 公共前缀 / 公共后缀裁剪
        var prefix = 0
        val maxPrefix = minOf(a.size, b.size)
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (
            suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++

        val ops = mutableListOf<DiffOp>()
        if (prefix > 0) ops += DiffOp(DiffKind.EQUAL, prefix, 0, 0)
        val midOps = computeMiddleDiff(
            a, b, prefix, a.size - suffix, prefix, b.size - suffix
        )
        ops += midOps
        if (suffix > 0) {
            ops += DiffOp(DiffKind.EQUAL, suffix, a.size - suffix, b.size - suffix)
        }
        return ops
    }

    /** 中段差异：a[aStart, aEnd) → b[bStart, bEnd)。 */
    private fun computeMiddleDiff(
        a: List<String>, b: List<String>,
        aStart: Int, aEnd: Int, bStart: Int, bEnd: Int
    ): List<DiffOp> {
        val n = aEnd - aStart
        val m = bEnd - bStart
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return listOf(DiffOp(DiffKind.INSERT, m, aStart, bStart))
        if (m == 0) return listOf(DiffOp(DiffKind.DELETE, n, aStart, bStart))

        val cells = n.toLong() * m.toLong()
        if (cells > MAX_LCS_CELLS) {
            // 超大中段：退化为整块替换（hunk 构建时受行数上限约束会被截断）
            val ops = mutableListOf<DiffOp>()
            if (n > 0) ops += DiffOp(DiffKind.DELETE, n, aStart, bStart)
            if (m > 0) ops += DiffOp(DiffKind.INSERT, m, aStart, bStart)
            return ops
        }

        // DP LCS 方向表（DIAG=相等/DEL=删 a[i]/INS=插 b[j]），滚动行 + 全量方向回溯
        val dir = ByteArray(n * m)
        val DIAG: Byte = 0
        val DEL: Byte = 1
        val INS: Byte = 2
        // dp[i][j]：a[i..] 与 b[j..] 的 LCS 长度
        var nextRow = IntArray(m + 1) // dp[i+1][*]
        for (i in n - 1 downTo 0) {
            val row = IntArray(m + 1)
            val ai = a[aStart + i]
            for (j in m - 1 downTo 0) {
                if (ai == b[bStart + j]) {
                    row[j] = nextRow[j + 1] + 1
                    dir[i * m + j] = DIAG
                } else if (nextRow[j] >= row[j + 1]) {
                    row[j] = nextRow[j]
                    dir[i * m + j] = DEL
                } else {
                    row[j] = row[j + 1]
                    dir[i * m + j] = INS
                }
            }
            nextRow = row
        }

        // 回溯路径 → 合并后的操作
        val raw = mutableListOf<DiffOp>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when (dir[i * m + j]) {
                DEL -> { raw += DiffOp(DiffKind.DELETE, 1, aStart + i, bStart + j); i++ }
                INS -> { raw += DiffOp(DiffKind.INSERT, 1, aStart + i, bStart + j); j++ }
                else -> { raw += DiffOp(DiffKind.EQUAL, 1, aStart + i, bStart + j); i++; j++ }
            }
        }
        while (i < n) { raw += DiffOp(DiffKind.DELETE, 1, aStart + i, bStart + j); i++ }
        while (j < m) { raw += DiffOp(DiffKind.INSERT, 1, aStart + i, bStart + j); j++ }

        return mergeOps(raw)
    }

    /** 合并相邻同类型操作。 */
    private fun mergeOps(raw: List<DiffOp>): List<DiffOp> {
        if (raw.isEmpty()) return raw
        val out = mutableListOf<DiffOp>()
        var cur = raw[0]
        for (op in raw.drop(1)) {
            if (op.kind == cur.kind) {
                cur = when (op.kind) {
                    DiffKind.EQUAL -> DiffOp(cur.kind, cur.count + op.count, cur.aStart, cur.bStart)
                    DiffKind.DELETE -> DiffOp(cur.kind, cur.count + op.count, cur.aStart, cur.bStart)
                    DiffKind.INSERT -> DiffOp(cur.kind, cur.count + op.count, cur.aStart, cur.bStart)
                }
            } else {
                out += cur
                cur = op
            }
        }
        out += cur
        return out
    }

    // ---------- unified hunk 构建 ----------

    private data class Ev(val kind: String, val oldLine: Int, val newLine: Int, val text: String)

    /** 由编辑脚本生成统一差异块；返回 (hunks, truncated)。 */
    internal fun buildHunksFor(
        old: List<String>?,
        new: List<String>?,
        context: Int = 3,
        maxHunks: Int = MAX_HUNKS_PER_FILE,
        maxDiffLines: Int = MAX_DIFF_LINES_PER_FILE
    ): Pair<List<GitDiffHunk>, Boolean> {
        // 任一侧行数超限（为 null 时）：退化生成本侧整块标记，diff 内容交由行数上限截断
        if (old == null || new == null) {
            return emptyList<GitDiffHunk>() to true
        }
        val ops = computeLineDiff(old, new)
        return buildHunks(ops, old, new, context, maxHunks, maxDiffLines)
    }

    private fun buildHunks(
        ops: List<DiffOp>,
        old: List<String>,
        new: List<String>,
        context: Int,
        maxHunks: Int = MAX_HUNKS_PER_FILE,
        maxDiffLines: Int = MAX_DIFF_LINES_PER_FILE
    ): Pair<List<GitDiffHunk>, Boolean> {
        // 行事件流（携带 1-based 行号；add/del 对应侧为 0）
        val events = mutableListOf<Ev>()
        for (op in ops) {
            when (op.kind) {
                DiffKind.EQUAL -> for (t in 0 until op.count) {
                    events += Ev("ctx", op.aStart + t + 1, op.bStart + t + 1, bounded(old[op.aStart + t]))
                }
                DiffKind.DELETE -> for (t in 0 until op.count) {
                    events += Ev("del", op.aStart + t + 1, 0, bounded(old[op.aStart + t]))
                }
                DiffKind.INSERT -> for (t in 0 until op.count) {
                    events += Ev("add", 0, op.bStart + t + 1, bounded(new[op.bStart + t]))
                }
            }
        }
        // 变更分组
        val changeIdx = events.indices.filter { events[it].kind != "ctx" }
        if (changeIdx.isEmpty()) return emptyList<GitDiffHunk>() to false
        val groups = mutableListOf<Pair<Int, Int>>()
        var groupStart = -1
        var groupEnd = -1
        for (ci in changeIdx) {
            val s = (ci - context).coerceAtLeast(0)
            val e = (ci + 1 + context).coerceAtMost(events.size)
            if (groupStart < 0) {
                groupStart = s
                groupEnd = e
            } else if (s <= groupEnd) {
                groupEnd = maxOf(groupEnd, e)
            } else {
                groups += groupStart to groupEnd
                groupStart = s
                groupEnd = e
            }
        }
        if (groupStart >= 0) groups += groupStart to groupEnd

        val hunks = mutableListOf<GitDiffHunk>()
        var truncated = false
        var emittedLines = 0
        for ((start, end) in groups) {
            if (hunks.size >= maxHunks || emittedLines + (end - start) > maxDiffLines) {
                truncated = true
                break
            }
            // @@ -oldStart,oldCount +newStart,newCount @@
            var oldStart = 0
            var oldCount = 0
            var newStart = 0
            var newCount = 0
            for (k in start until end) {
                val ev = events[k]
                if (ev.oldLine > 0) {
                    if (oldStart == 0) oldStart = ev.oldLine
                    oldCount++
                }
                if (ev.newLine > 0) {
                    if (newStart == 0) newStart = ev.newLine
                    newCount++
                }
            }
            val header = buildString {
                append("@@ -")
                append(if (oldCount == 0) "0,0" else if (oldCount == 1) "$oldStart" else "$oldStart,$oldCount")
                append(" +")
                append(if (newCount == 0) "0,0" else if (newCount == 1) "$newStart" else "$newStart,$newCount")
                append(" @@")
            }
            val lines = (start until end).map { k ->
                val ev = events[k]
                val kind = when (ev.kind) {
                    "add" -> GitDiffLine.KIND_ADD
                    "del" -> GitDiffLine.KIND_DEL
                    else -> GitDiffLine.KIND_CONTEXT
                }
                GitDiffLine(kind = kind, text = ev.text)
            }
            hunks += GitDiffHunk(header = header, lines = lines)
            emittedLines += end - start
        }
        return hunks to truncated
    }

    private fun bounded(text: String): String =
        if (text.length <= MAX_LINE_TEXT_CHARS) text else text.take(MAX_LINE_TEXT_CHARS)

    // ---------- Git 路径查找 ----------

    /** 从 [startDir] 向上查找最近的 git 仓库根（含 .git 目录或 worktree 指针文件）。 */
    internal fun findGitRoot(startDir: File, maxDepth: Int = FIND_GIT_ROOT_DEPTH): File? {
        var dir: File? = startDir
        var depth = 0
        while (dir != null && depth < maxDepth) {
            val gitEntry = File(dir, ".git")
            if (gitEntry.isDirectory || gitEntry.isFile) return dir
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /** 计算 git blob 对象的 SHA-1（"blob <len>\0" + content）。 */
    internal fun gitBlobSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        return digest.digest(bytes).toHex()
    }
}

private val GIT_HEX = "0123456789abcdef".toCharArray()

/** 字节转小写十六进制字符串（顶层函数，供 GitRepository / TreeParser 复用）。 */
internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(GIT_HEX[v ushr 4]).append(GIT_HEX[v and 0x0f])
    }
    return sb.toString()
}

/** 判断字符是否为十六进制数字。 */
internal fun Char.isHexChar(): Boolean =
    (this in '0'..'9') || (this in 'a'..'f') || (this in 'A'..'F')

internal fun String.isHexString(): Boolean =
    length == 40 && all(Char::isHexChar)

/**
 * 单个 git 仓库根目录的只读视图：HEAD/分支解析、tree 展开、对象读取（loose + pack）。
 * 内部缓存随 HEAD 变化自动失效。
 */
internal class GitRepository(private val gitRoot: File) {

    private val gitDir: File? = resolveGitDir()
    private val objectCache = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 96
    }
    private val packIndexes = ConcurrentHashMap<String, Map<String, Long>>()
    private val treeCache = ConcurrentHashMap<String, Map<String, String>?>()
    private var cachedHeadSha: String? = null
    private var cachedBranch: String? = null

    /** 解析 .git 实际目录（支持 worktree/submodule 的 gitdir 指针文件）。 */
    private fun resolveGitDir(): File? {
        return try {
            val entry = File(gitRoot, ".git")
            when {
                entry.isDirectory -> entry
                entry.isFile -> {
                    val pointer = entry.readText(Charsets.UTF_8).trim()
                    if (pointer.startsWith("gitdir:")) {
                        val target = pointer.removePrefix("gitdir:").trim()
                        val resolved = if (File(target).isAbsolute) File(target) else File(entry.parentFile, target)
                        resolved.canonicalFile
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isAvailable(): Boolean = gitDir != null

    /** 当前 HEAD 提交 sha（无提交返回 null）。 */
    fun headSha(): String? {
        val dir = gitDir ?: return null
        return try {
            val headText = File(dir, "HEAD").takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim().orEmpty()
            val sha: String?
            val branch: String?
            if (headText.startsWith("ref: ")) {
                val ref = headText.removePrefix("ref: ").trim()
                branch = ref.removePrefix("refs/heads/").takeIf { it != ref }
                sha = resolveRef(dir, ref)
            } else {
                branch = null
                sha = headText.takeIf { it.isHexString() }
            }
            if (sha != cachedHeadSha) {
                cachedHeadSha = sha
                cachedBranch = branch
                // HEAD 变化 → 树/对象缓存整体失效
                treeCache.clear()
            }
            sha
        } catch (_: Exception) {
            null
        }
    }

    fun branch(): String? {
        headSha() // 触发解析
        return cachedBranch
    }

    private fun resolveRef(dir: File, ref: String): String? {
        val refFile = File(dir, ref)
        if (refFile.isFile) {
            val value = refFile.readText(Charsets.UTF_8).trim()
            if (value.length == 40) return value.trim()
        }
        // packed-refs 兜底
        val packed = File(dir, "packed-refs")
        if (packed.isFile) {
            try {
                packed.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("^")) {
                            continue
                        }
                        val space = trimmed.indexOf(' ')
                        if (space == 40 && trimmed.substring(space + 1) == ref) {
                            return trimmed.substring(0, 40)
                        }
                    }
                }
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    /**
     * 展开某次提交的完整 path→blob-sha 映射。
     * 仅包含普通 blob（跳过 gitlink 子模块）；超限时返回 null（调用方做安全降级）。
     */
    fun treeFiles(commitSha: String): Map<String, String>? {
        treeCache[commitSha]?.let { return it }
        val commit = readObject(commitSha) ?: return null.also { treeCache[commitSha] = null }
        if (commit.type != "commit") return null.also { treeCache[commitSha] = null }
        // commit 正文为 ASCII 文本；其 header 已在读出时剥离，这里直接找 "tree <sha>" 行
        val treeLine = String(commit.content, Charsets.US_ASCII).split("\n")
        val treeSha = treeLine.firstOrNull { it.startsWith("tree ") }?.removePrefix("tree ")?.trim()
        if (treeSha.isNullOrBlank()) return null.also { treeCache[commitSha] = null }

        val out = LinkedHashMap<String, String>()
        var entries = 0
        var trees = 0
        val complete = walkTree(treeSha, "", out, 0) { entries++; trees++; entries > WorkspaceGitDiff.MAX_TREE_ENTRIES || trees > WorkspaceGitDiff.MAX_TREES }
        if (!complete) return null.also { treeCache[commitSha] = null }
        treeCache[commitSha] = out
        return out
    }

    /** 递归展开 tree；[onVisit] 用于统计并可能终止。返回是否完整遍历。 */
    private fun walkTree(
        treeSha: String,
        prefix: String,
        out: MutableMap<String, String>,
        depth: Int,
        onVisit: () -> Unit
    ): Boolean {
        if (depth > WorkspaceGitDiff.MAX_TREE_DEPTH) return false
        val content = readObject(treeSha) ?: return false
        if (content.type != "tree") return false
        // 读出对象时已剥离 "<type> <size>\0" 头，content 即 tree 原始字节
        val parser = TreeParser(content.content, 0)
        var complete = true
        loop@ while (true) {
            val mode = parser.readMode() ?: break
            val name = parser.readName() ?: break
            val sha = parser.readShaHex() ?: break
            onVisit()
            val child = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                mode.startsWith("04") -> { // 子目录
                    if (!walkTree(sha, child, out, depth + 1, onVisit)) {
                        complete = false
                        break@loop
                    }
                }
                mode.startsWith("16") -> { /* gitlink 子模块：跳过 */ }
                mode.startsWith("12") -> { // 符号链接：当作 blob 参与 diff
                    out[child] = sha
                }
                else -> out[child] = sha
            }
            if (parser.finished()) break
        }
        return complete
    }

    // ---------- 对象读取 ----------

    private class ObjectData(val type: String, val content: ByteArray)

    /** 按 sha 读取对象内容（loose → pack 顺序；自动解析 delta）。 */
    fun readBlob(sha: String): ByteArray? {
        val data = readObject(sha) ?: return null
        return if (data.type == "blob") data.content else null
    }

    private fun readObject(sha: String): ObjectData? {
        if (sha.length != 40) return null
        objectCache[sha]?.let { raw ->
            val header = decodeHeaderContent(raw)
            return ObjectData(header.second, header.first)
        }
        val dir = gitDir ?: return null
        // 1) loose object
        val loose = File(dir, "objects/${sha.substring(0, 2)}/${sha.substring(2)}")
        if (loose.isFile) {
            val raw = runCatching { inflateAll(loose.readBytes()) }.getOrNull() ?: return null
            if (raw.size > WorkspaceGitDiff.MAX_OBJECT_BYTES.toInt()) return null
            val header = decodeHeaderContent(raw)
            if (header.second == "blob" || header.second == "commit" || header.second == "tree" || header.second == "tag") {
                objectCache[sha] = raw
                return ObjectData(header.second, header.first)
            }
            return null
        }
        // 2) packfile
        val packDir = File(dir, "objects/pack")
        if (!packDir.isDirectory) return null
        val packFiles = packDir.listFiles { f -> f.isFile && f.extension == "pack" }?.sortedBy { it.name }.orEmpty()
        if (packFiles.isEmpty()) return null
        for (pack in packFiles) {
            val index = packIndex(pack) ?: continue
            val offset = index[sha] ?: continue
            val resolved = resolvePackObjectAt(pack, offset, 0) ?: return null
            if (resolved.size > WorkspaceGitDiff.MAX_OBJECT_BYTES.toInt()) return null
            objectCache[sha] = resolved
            val header = decodeHeaderContent(resolved)
            return ObjectData(header.second, header.first)
        }
        return null
    }

    /** 读取 pack 索引（*.idx），返回 sha→offset 映射。 */
    private fun packIndex(pack: File): Map<String, Long>? {
        val idxFile = File(pack.parentFile, pack.name.removeSuffix(".pack") + ".idx")
        val key = idxFile.absolutePath
        packIndexes[key]?.let { return it }
        val map = runCatching { parsePackIndex(idxFile) }.getOrNull() ?: return null
        packIndexes[key] = map
        return map
    }

    private fun parsePackIndex(idxFile: File): Map<String, Long>? {
        if (!idxFile.isFile) return null
        val bytes = idxFile.readBytes()
        if (bytes.size < 8) return null
        var pos = 0
        var isV2 = false
        if (
            bytes[0] == 0xff.toByte() && bytes[1] == 0x74.toByte() &&
            bytes[2] == 0x4f.toByte() && bytes[3] == 0x63.toByte()
        ) {
            val version = readBeInt(bytes, 4)
            if (version != 2) return null
            isV2 = true
            pos = 8
        }
        if (bytes.size - pos < 256 * 4 + 4) return null
        val fanout = IntArray(257)
        for (i in 1..256) {
            fanout[i] = readBeInt(bytes, pos + (i - 1) * 4)
        }
        val count = fanout[256]
        if (count <= 0) return emptyMap()
        if (isV2) {
            // sha 表
            val shaTable = pos + 256 * 4
            if (shaTable + count * 20L > bytes.size) return null
            // crc 表
            val crcTable = shaTable + count * 20
            // offset 表
            val offsetTable = crcTable + count * 4
            if (offsetTable + count * 4L > bytes.size) return null
            val largeOffsetTable = offsetTable + count * 4
            val map = HashMap<String, Long>(count * 2)
            for (i in 0 until count) {
                val sha = bytes.copyOfRange(shaTable + i * 20, shaTable + i * 20 + 20).toHex()
                val off = readBeInt(bytes, offsetTable + i * 4)
                val offset: Long = if (off and 0x80000000.toInt() != 0) {
                    val largeIndex = off and 0x7fffffff
                    if (largeOffsetTable + (largeIndex + 1) * 8L > bytes.size) return null
                    readBeLong(bytes, largeOffsetTable + largeIndex * 8)
                } else {
                    off.toLong() and 0xffffffffL
                }
                map[sha] = offset
            }
            return map
        } else {
            // v1：fanout 后为 count × (offset(4) + sha(20))
            var p = pos + 256 * 4
            if (p + count * 24L > bytes.size) return null
            val map = HashMap<String, Long>(count * 2)
            for (i in 0 until count) {
                val offset = readBeInt(bytes, p).toLong() and 0xffffffffL
                val sha = bytes.copyOfRange(p + 4, p + 24).toHex()
                map[sha] = offset
                p += 24
            }
            return map
        }
    }

    /**
     * 读取并解析 pack 对象（含 delta 链）。返回最终内容字节。
     */
    private fun resolvePackObjectAt(pack: File, offset: Long, depth: Int): ByteArray? {
        if (depth > 64) return null
        return try {
            RandomAccessFile(pack, "r").use { raf ->
                raf.seek(offset)
                val first = raf.readUnsignedByte()
                var type = (first ushr 4) and 0x7
                var size = first and 0x0f
                var shift = 4
                var b = first
                while (b and 0x80 != 0) {
                    b = raf.readUnsignedByte()
                    size = size or ((b and 0x7f) shl shift)
                    shift += 7
                }
                when (type) {
                    WorkspaceGitDiff.OBJ_OFS_DELTA -> {
                        // 负偏移 varint
                        var neg = 0L
                        var negShift = 0
                        var cb: Int
                        do {
                            cb = raf.readUnsignedByte()
                            neg = neg or ((cb and 0x7f).toLong() shl negShift)
                            negShift += 7
                        } while (cb and 0x80 != 0)
                        val baseOffset = offset - neg
                        val delta = inflateFrom(raf, size) ?: return null
                        val base = resolvePackObjectAt(pack, baseOffset, depth + 1) ?: return null
                        applyDelta(base, delta)
                    }
                    WorkspaceGitDiff.OBJ_REF_DELTA -> {
                        val baseShaBytes = ByteArray(20)
                        raf.readFully(baseShaBytes)
                        val baseSha = baseShaBytes.toHex()
                        val delta = inflateFrom(raf, size) ?: return null
                        val base = readObject(baseSha)?.content ?: return null
                        applyDelta(base, delta)
                    }
                    else -> inflateFrom(raf, size)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 应用 git delta 指令流。 */
    private fun applyDelta(base: ByteArray, delta: ByteArray): ByteArray? {
        if (delta.size < 2) return null
        return try {
            var pos = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = delta[pos++].toInt() and 0xff
                    result = result or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }
            readVarint() // base size（校验用）
            val resultSize = readVarint()
            if (resultSize > WorkspaceGitDiff.MAX_OBJECT_BYTES) return null
            val out = ByteArrayOutputStream(resultSize.coerceAtMost(1 shl 22).toInt())
            while (pos < delta.size) {
                val op = delta[pos++].toInt() and 0xff
                if (op and 0x80 != 0) {
                    var copyOffset = 0L
                    var copySize = 0L
                    if (op and 0x01 != 0) copyOffset = (delta[pos++].toInt() and 0xff).toLong()
                    if (op and 0x02 != 0) copyOffset = copyOffset or (((delta[pos++].toInt() and 0xff).toLong()) shl 8)
                    if (op and 0x04 != 0) copyOffset = copyOffset or (((delta[pos++].toInt() and 0xff).toLong()) shl 16)
                    if (op and 0x08 != 0) copyOffset = copyOffset or (((delta[pos++].toInt() and 0xff).toLong()) shl 24)
                    if (op and 0x10 != 0) copySize = (delta[pos++].toInt() and 0xff).toLong()
                    if (op and 0x20 != 0) copySize = copySize or (((delta[pos++].toInt() and 0xff).toLong()) shl 8)
                    if (op and 0x40 != 0) copySize = copySize or (((delta[pos++].toInt() and 0xff).toLong()) shl 16)
                    if (copySize == 0L) copySize = 0x10000L
                    if (copyOffset + copySize > base.size) return null
                    if (copySize <= Int.MAX_VALUE) {
                        out.write(base, copyOffset.toInt(), copySize.toInt())
                    } else {
                        return null
                    }
                } else if (op > 0) {
                    if (pos + op > delta.size) return null
                    out.write(delta, pos, op)
                    pos += op
                } else {
                    return null
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /** 从 RAF 当前位置以 zlib 流方式 inflate 出 [expected] 字节。 */
    private fun inflateFrom(raf: RandomAccessFile, expected: Int): ByteArray? {
        if (expected > WorkspaceGitDiff.MAX_OBJECT_BYTES.toInt()) return null
        return try {
            val inflater = Inflater()
            val out = ByteArrayOutputStream(minOf(expected.coerceAtLeast(256), 1 shl 20))
            val buf = ByteArray(64 * 1024)
            var inflated = 0
            while (inflated < expected) {
                if (inflater.needsInput()) {
                    val n = raf.read(buf)
                    if (n < 0) { inflater.end(); return null }
                    inflater.setInput(buf, 0, n)
                }
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.finished()) break
                    // needsInput 时继续读；否则为数据异常
                    if (!inflater.needsInput()) { inflater.end(); return null }
                } else {
                    out.write(buf, 0, n)
                    inflated += n
                }
            }
            inflater.end()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 字节解析工具 ----------

    /** 解压 loose object / 缓存对象，返回完整原始字节。 */
    private fun inflateAll(bytes: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater()
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(minOf(bytes.size * 3 + 64, 1 shl 20))
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                if (inflater.needsInput()) { inflater.end(); return null }
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (!inflater.needsInput() && !inflater.finished()) { inflater.end(); return null }
                } else {
                    out.write(buf, 0, n)
                }
                if (out.size() > WorkspaceGitDiff.MAX_OBJECT_BYTES) { inflater.end(); return null }
            }
            inflater.end()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /** 解析 "<type> <size>\0" 头，返回 (内容, 类型, 内容起始偏移)。 */
    private fun decodeHeaderContent(raw: ByteArray): Triple<ByteArray, String, Int> {
        var i = 0
        while (i < raw.size && raw[i].toInt() != 0) i++
        val header = String(raw, 0, i, Charsets.US_ASCII)
        val space = header.indexOf(' ')
        val type = if (space > 0) header.substring(0, space) else header
        return Triple(raw.copyOfRange(i + 1, raw.size), type, i + 1)
    }

    private fun readBeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun readBeLong(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xff)
        }
        return result
    }

    /** tree 条目顺序解析器：mode(ascii) 空格 name\0 sha(20B)。 */
    private class TreeParser(private val bytes: ByteArray, private var pos: Int) {
        fun readMode(): String? {
            if (pos >= bytes.size) return null
            val start = pos
            while (pos < bytes.size && bytes[pos].toInt() != 0x20.toByte().toInt()) pos++
            if (pos >= bytes.size) { pos = start; return null }
            val mode = String(bytes, start, pos - start, Charsets.US_ASCII)
            pos++ // 跳过空格
            return mode
        }

        fun readName(): String? {
            val start = pos
            while (pos < bytes.size && bytes[pos].toInt() != 0) pos++
            if (pos >= bytes.size) return null
            val name = String(bytes, start, pos - start, Charsets.UTF_8)
            pos++ // 跳过 NUL
            return name
        }

        fun readShaHex(): String? {
            if (pos + 20 > bytes.size) return null
            val sha = bytes.copyOfRange(pos, pos + 20).toHex()
            pos += 20
            return sha
        }

        fun finished(): Boolean = pos >= bytes.size
    }
}
