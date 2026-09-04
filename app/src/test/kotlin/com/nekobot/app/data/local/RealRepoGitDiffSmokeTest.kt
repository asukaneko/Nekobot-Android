package com.nekobot.app.data.local

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 针对真实仓库的临时复现测试：在 D:\code\Nekobot-Android 内创建临时文件，
 * 验证 WorkspaceGitDiff.summarize 能产出摘要（loose + packfile 混合仓库）。
 * 该测试仅在本机有该仓库时有效，运行后立即删除临时文件。
 */
class RealRepoGitDiffSmokeTest {

    @Test
    fun summarizeRealRepoProducesSummary() {
        val repoRoot = File("D:/code/Nekobot-Android")
        if (!File(repoRoot, ".git").exists()) return // 本机无仓库则跳过

        val probe = File(repoRoot, "git_diff_probe_tmp.txt")
        try {
            probe.writeText("hello from probe\nline2\nline3\n")
            val summary = WorkspaceGitDiff.summarize(repoRoot, listOf("git_diff_probe_tmp.txt"))
            assertNotNull("真实仓库应能生成摘要（含 packfile 读取）", summary)
            assertTrue("应有文件变更", summary!!.files.isNotEmpty())
            val f = summary.files.first()
            assertTrue("新增文件 status=added: ${f.status}", f.status == com.nekobot.app.data.model.GitDiffFile.STATUS_ADDED)
        } finally {
            probe.delete()
        }
    }
}
