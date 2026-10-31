package com.nekobot.app.data.local

import com.nekobot.app.R
import com.nekobot.app.data.local.WorkspaceGitDiff.GitScanResult
import com.nekobot.app.data.model.GitDiffSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * git 仓库实时监控器。
 *
 * 按固定间隔执行 [scan]，与上一次结果不同就把最新摘要推给 [onSummary]——
 * 也就是说：只要仓库工作区相对 HEAD 的更改发生了变化（AI 写入、用户在别的窗口修改、
 * 沙盒命令改动、提交后清空…），进度卡片就会刷新，而不是只在 AI 调用了写文件工具时才刷新。
 *
 * 扫描结果为 [GitScanResult.Unavailable] 时**不派发**：读盘失败、目录被占用等瞬时问题
 * 不应该让已经显示出来的"文件变更"区域消失。
 *
 * 生命周期由调用方控制（本地 Agent 一轮运行创建一个，运行结束时 [stop]）。
 */
internal class WorkspaceGitWatcher(
    private val intervalMs: Long,
    private val scan: () -> GitScanResult,
    private val onSummary: (GitDiffSummary?) -> Unit,
    /** 最长存活时间：调用方忘记 stop（异常中断）时也会自行退出，避免后台一直轮询。 */
    private val maxLifetimeMs: Long = DEFAULT_MAX_LIFETIME_MS
) {
    private companion object {
        const val TAG = "WorkspaceGitWatch"

        /** 单轮 Agent 运行远短于此，超时视为调用方未收尾。 */
        const val DEFAULT_MAX_LIFETIME_MS = 30L * 60L * 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 唤醒信号（合并连发）：工具刚结束时立刻补扫一次，不必等下一个轮询周期。 */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    private var loopJob: Job? = null

    /** 上一次派发的结果；用 [hasDispatched] 区分"还没有结果"与"结果为 null"。 */
    private var lastSummary: GitDiffSummary? = null
    private var hasDispatched = false

    @Volatile
    private var closed = false

    /** 启动监控（幂等）。启动后立即扫描一次，随后按间隔轮询。 */
    fun start() {
        if (closed || loopJob != null) return
        loopJob = scope.launch { loop() }
    }

    /** 请求立即补扫一次；多次请求会合并。 */
    fun requestScan() {
        if (closed) return
        wakeups.trySend(Unit)
    }

    /**
     * 停止监控。返回后不会再派发任何结果，调用方可在之后做一次同步收尾扫描，
     * 保证最终卡片状态不被旧的轮询结果覆盖。
     */
    fun stop() {
        if (closed) return
        closed = true
        loopJob?.cancel()
        loopJob = null
        wakeups.close()
        scope.cancel()
    }

    private suspend fun loop() {
        val startedAt = System.nanoTime()
        while (currentCoroutineContext().isActive) {
            dispatch(scanOnce())
            if ((System.nanoTime() - startedAt) / 1_000_000L >= maxLifetimeMs) {
                stop()
                return
            }
            // 等待下一个周期，期间允许被 requestScan 提前唤醒
            withTimeoutOrNull(intervalMs) { wakeups.receive() }
        }
    }

    private fun scanOnce(): GitScanResult = try {
        scan()
    } catch (t: Throwable) {
        // 监控只是展示增强：任何异常都不应影响 Agent 主链路
        LocalLogger.w(TAG, R.string.log_git_watch_scan_failed, t.message)
        GitScanResult.Unavailable
    }

    private fun dispatch(result: GitScanResult) {
        if (closed) return
        when (result) {
            // 扫描失败/不可信：保留上一次展示，绝不清空
            is GitScanResult.Unavailable -> return
            is GitScanResult.Changed -> dispatchSummary(result.summary)
            // 仓库确实回到干净状态（例如 AI 提交了改动）
            is GitScanResult.Clean -> dispatchSummary(null)
        }
    }

    private fun dispatchSummary(summary: GitDiffSummary?) {
        if (closed) return
        val normalized = summary?.takeIf { it.hasChanges }
        if (hasDispatched && normalized == lastSummary) return
        hasDispatched = true
        lastSummary = normalized
        onSummary(normalized)
    }
}
