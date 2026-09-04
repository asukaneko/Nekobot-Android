package com.nekobot.app.data.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Agent 文件更改 Git 摘要卡片数据模型（仅本地 Agent 管线生成）。
 *
 * 当 AI 通过 workspace_*_file / file_write / file_edit 等工具修改文件后，
 * 若会话工作区位于 Git 仓库内，则对比 HEAD 基线生成逐文件差异摘要，
 * 挂载到进度卡片的步骤上（[ThinkingStep.gitDiff]）用于聊天界面渲染。
 */

/** 单个文件的差异行（kind: ctx 上下文 / add 新增 / del 删除） */
data class GitDiffLine(
    val kind: String = KIND_CONTEXT,
    val text: String = ""
) {
    companion object {
        const val KIND_CONTEXT = "ctx"
        const val KIND_ADD = "add"
        const val KIND_DEL = "del"
    }
}

/** 一个差异块：`@@ -a,b +c,d @@` 头 + 块内行 */
data class GitDiffHunk(
    @SerializedName("header") val header: String = "",
    @SerializedName("lines") val lines: List<GitDiffLine> = emptyList()
)

/**
 * 单个文件相对 HEAD 的更改摘要。
 *
 * @param path 相对 Git 仓库根目录的路径
 * @param status added / modified / deleted
 * @param additions 新增行数（二进制/不可读基线时为 0）
 * @param deletions 删除行数
 * @param binary 二进制文件（按 NUL 字节检测），不生成文本差异
 * @param unavailable HEAD 基线内容不可读（如 pack 解析失败），仅显示更改状态
 * @param truncated 差异行数超过上限被截断
 * @param hunks 文本差异块（added/deleted 也用统一格式表达）
 */
data class GitDiffFile(
    @SerializedName("path") val path: String = "",
    @SerializedName("status") val status: String = STATUS_MODIFIED,
    @SerializedName("additions") val additions: Int = 0,
    @SerializedName("deletions") val deletions: Int = 0,
    @SerializedName("binary") val binary: Boolean = false,
    @SerializedName("unavailable") val unavailable: Boolean = false,
    @SerializedName("truncated") val truncated: Boolean = false,
    @SerializedName("hunks") val hunks: List<GitDiffHunk> = emptyList()
) {
    companion object {
        const val STATUS_ADDED = "added"
        const val STATUS_MODIFIED = "modified"
        const val STATUS_DELETED = "deleted"
    }
}

/**
 * 一轮对话内文件更改的 Git 摘要（相对 HEAD）。
 *
 * @param repoName Git 仓库根目录名
 * @param branch 当前分支名；detached HEAD 时为 null
 * @param files 更改文件列表（按路径排序，超过上限时截断）
 * @param filesTruncated 文件数超过上限被截断
 */
data class GitDiffSummary(
    @SerializedName("repo_name") val repoName: String = "",
    @SerializedName("branch") val branch: String? = null,
    @SerializedName("files") val files: List<GitDiffFile> = emptyList(),
    @SerializedName("files_truncated") val filesTruncated: Boolean = false
) {
    /** 新增行总数 */
    val totalAdditions: Int get() = files.sumOf { it.additions }

    /** 删除行总数 */
    val totalDeletions: Int get() = files.sumOf { it.deletions }

    /** 是否包含可展示的更改（文件列表非空） */
    val hasChanges: Boolean get() = files.isNotEmpty()

    companion object {
        private val gson = Gson()

        /** 序列化为 JSON；null 直接返回 null（用于 ThinkingStep 持久化）。 */
        fun encode(summary: GitDiffSummary?): String? = summary?.let { runCatching { gson.toJson(it) }.getOrNull() }

        /** 从 JSON 解析；失败返回 null（Gson 对未知字段宽容）。 */
        fun fromJson(raw: String?): GitDiffSummary? {
            if (raw.isNullOrBlank()) return null
            return runCatching { gson.fromJson(raw, GitDiffSummary::class.java) }.getOrNull()
        }
    }
}
