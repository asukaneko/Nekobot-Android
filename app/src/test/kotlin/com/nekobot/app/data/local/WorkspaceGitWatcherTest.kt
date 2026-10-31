package com.nekobot.app.data.local

import com.nekobot.app.data.local.WorkspaceGitDiff.GitScanResult
import com.nekobot.app.data.model.GitDiffFile
import com.nekobot.app.data.model.GitDiffSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * git 仓库实时监控器测试：验证“只派发变化”“可被工具结束事件提前唤醒”
 * 以及“扫描失败不清空已有展示”。
 */
class WorkspaceGitWatcherTest {

    private val summary = GitDiffSummary(
        repoName = "repo",
        files = listOf(GitDiffFile(path = "a.kt"))
    )

    @Test
    fun dispatchesOnlyOnChangeAndFlushesWhenRepositoryBecomesClean() = runBlocking {
        val results = CopyOnWriteArrayList<GitDiffSummary?>()
        var scans = 0
        val watcher = WorkspaceGitWatcher(
            intervalMs = 50,
            scan = {
                scans++
                // 前三次扫描都有同一份更改，之后仓库变干净
                if (scans <= 3) GitScanResult.Changed(summary) else GitScanResult.Clean
            },
            onSummary = { results += it }
        )
        watcher.start()
        withTimeout(5_000) { while (scans < 6) delay(10) }
        watcher.stop()

        assertEquals("相同摘要不重复派发，变干净后派发一次 null", listOf(summary, null), results.toList())
    }

    @Test
    fun unavailableScanKeepsPreviousChangesOnCard() = runBlocking {
        // 读盘失败 / 目录被占用等瞬时问题不能让已经显示的变更区域消失
        val results = CopyOnWriteArrayList<GitDiffSummary?>()
        var scans = 0
        val watcher = WorkspaceGitWatcher(
            intervalMs = 40,
            scan = {
                scans++
                when (scans) {
                    1 -> GitScanResult.Changed(summary)
                    2, 3 -> GitScanResult.Unavailable
                    else -> GitScanResult.Clean
                }
            },
            onSummary = { results += it }
        )
        watcher.start()
        withTimeout(5_000) { while (scans < 6) delay(10) }
        watcher.stop()

        assertEquals(
            "Unavailable 不派发，只有真正的 Clean 才清空",
            listOf(summary, null),
            results.toList()
        )
    }

    @Test
    fun requestScanWakesUpBeforeIntervalElapses() = runBlocking {
        var scans = 0
        val watcher = WorkspaceGitWatcher(
            intervalMs = 30_000,
            scan = { scans++; GitScanResult.Unavailable },
            onSummary = {}
        )
        watcher.start()
        withTimeout(3_000) { while (scans < 1) delay(10) }
        watcher.requestScan()
        withTimeout(3_000) { while (scans < 2) delay(10) }
        watcher.stop()

        assertTrue("工具结束后应能立即补扫一次（实际 $scans 次）", scans >= 2)
    }
}
