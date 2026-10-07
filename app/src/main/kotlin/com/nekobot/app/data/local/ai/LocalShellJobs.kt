package com.nekobot.app.data.local.ai

import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台 shell 任务注册表。
 *
 * `exec_command` 是同步的：构建、下载、批处理这类长命令会一直占住工具循环的一轮——
 * 用户看不到进度，模型也无法在等待期间做别的事，超时上限还容易被顶到。
 * 这里提供最小可用的后台执行：命令在独立协程里继续跑，结果累积到任务记录，
 * 模型用 `shell_job` 工具查询或终止；完成时通过 [AgentNoticeBus] 通知父会话。
 * 命令本身跑在各自的独立沙盒进程里（见 `executeDetached`），因此互不排队。
 *
 * 生命周期与进程一致（内存态）。后台命令的授权在启动前于前台完成，
 * 因此不存在"后台偷偷获得了新权限"的路径。
 */
internal object LocalShellJobs {

    /** 单个会话允许同时运行的后台命令数。 */
    internal const val MAX_JOBS_PER_SESSION = 3

    /** 单个任务保留的输出字符上限：与其它工具统一走「设置 → Agent 设置」，见 [AgentToolLimits]。 */
    internal val MAX_OUTPUT_CHARS: Int get() = AgentToolLimits.toolOutputChars()

    /** 保留的历史任务条数（每个会话），避免长期堆积。 */
    private const val MAX_JOBS_KEPT_PER_SESSION = 10

    internal data class ShellJob(
        val id: String,
        val sessionId: String,
        val command: String,
        val startedAt: Long,
        @Volatile var status: String = STATUS_RUNNING,
        @Volatile var output: String = "",
        @Volatile var exitCode: Int? = null,
        @Volatile var error: String? = null,
        @Volatile var finishedAt: Long? = null,
        /** 后台命令的沙盒进程句柄（独立 PRoot 进程）；终止任务时直接销毁它。 */
        @Volatile var process: Process? = null
    ) {
        val isRunning: Boolean get() = status == STATUS_RUNNING
    }

    internal const val STATUS_RUNNING = "running"
    internal const val STATUS_SUCCEEDED = "succeeded"
    internal const val STATUS_FAILED = "failed"
    internal const val STATUS_KILLED = "killed"

    private val jobs = ConcurrentHashMap<String, ShellJob>()
    private val handles = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    internal fun runningCount(sessionId: String): Int =
        jobs.values.count { it.sessionId == sessionId && it.isRunning }

    internal fun list(sessionId: String): List<ShellJob> =
        jobs.values.filter { it.sessionId == sessionId }.sortedBy { it.startedAt }

    internal fun get(sessionId: String, jobId: String): ShellJob? =
        jobs[jobId]?.takeIf { it.sessionId == sessionId }

    /**
     * 启动一个后台命令。[runner] 返回一次性命令结果（与前台 exec_command 相同结构），
     * 并可通过 [ShellJob.process] 登记自己的沙盒进程，供 [kill] 真正终止。
     */
    internal fun start(
        sessionId: String,
        command: String,
        runner: suspend (ShellJob) -> Map<String, Any>
    ): ShellJob {
        val job = ShellJob(
            id = UUID.randomUUID().toString().take(8),
            sessionId = sessionId,
            command = command,
            startedAt = System.currentTimeMillis()
        )
        jobs[job.id] = job
        prune(sessionId)
        // 与后台子代理一致：独立协程 + SupervisorJob，父会话结束不影响已启动的命令。
        // 后台命令可能超出父会话的生成周期，用独立槽位保活前台服务，避免进程被回收。
        com.nekobot.app.ServiceContainer.appContext?.let { context ->
            runCatching { com.nekobot.app.service.AgentForegroundService.acquireBackgroundTask(context, sessionId) }
        }
        val handle = kotlinx.coroutines.GlobalScope.launch(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
        ) {
            try {
                val result = runner(job)
                recordResult(job, result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                finish(job, STATUS_KILLED, error = "已终止")
                throw e
            } catch (e: Exception) {
                finish(job, STATUS_FAILED, error = e.message ?: "命令执行失败")
            } finally {
                com.nekobot.app.ServiceContainer.appContext?.let { context ->
                    runCatching {
                        com.nekobot.app.service.AgentForegroundService.releaseBackgroundTask(context, sessionId)
                    }
                }
            }
        }
        handles[job.id] = handle
        handle.invokeOnCompletion { handles.remove(job.id) }
        return job
    }

    /**
     * 终止后台命令：销毁它的沙盒进程并取消协程。@return 是否确实终止了正在运行的任务。
     */
    internal fun kill(sessionId: String, jobId: String): Boolean {
        val job = get(sessionId, jobId) ?: return false
        if (!job.isRunning) return false
        // 先把状态落成 killed：进程销毁后运行体会返回结果，避免它把状态改回 failed/succeeded。
        finish(job, STATUS_KILLED, error = "已终止")
        val process = job.process
        job.process = null
        val handle = handles.remove(jobId)
        if (process != null) {
            runCatching { process.destroy() }
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
        handle?.cancel()
        return true
    }

    private fun recordResult(job: ShellJob, result: Map<String, Any>) {
        val succeeded = result["success"] == true
        val output = buildString {
            (result["output"] as? String)?.let { append(it) }
            (result["error"] as? String)?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
        }
        val exitCode = (result["exit_code"] as? Number)?.toInt()
        if (job.status == STATUS_KILLED) {
            // 已被用户终止：保留 killed 状态与已产出的输出，不再发"已完成"通知。
            finish(job, STATUS_KILLED, output = output, exitCode = exitCode, error = job.error ?: "已终止")
            return
        }
        finish(
            job = job,
            status = if (succeeded) STATUS_SUCCEEDED else STATUS_FAILED,
            output = output,
            exitCode = exitCode,
            error = (result["error"] as? String)
        )
        val summary = if (succeeded) "执行成功" else "执行失败"
        AgentNoticeBus.publish(
            job.sessionId,
            "[系统通知] 后台命令已结束（job_id=${job.id}，$summary）：${job.command.take(120)}\n" +
                "输出摘要：\n${output.take(1_500).ifBlank { "（无输出）" }}\n" +
                "如需完整输出可用 shell_job(action=get, job_id=${job.id}) 读取，不要重复执行同一命令。"
        )
    }

    private fun finish(
        job: ShellJob,
        status: String,
        output: String? = null,
        exitCode: Int? = null,
        error: String? = null
    ) {
        if (output != null) {
            job.output = output.take(MAX_OUTPUT_CHARS)
        }
        job.exitCode = exitCode
        job.error = error
        job.status = status
        job.finishedAt = System.currentTimeMillis()
    }

    /** 每个会话只保留最近 [MAX_JOBS_KEPT_PER_SESSION] 条已完成任务。 */
    private fun prune(sessionId: String) {
        val finished = list(sessionId).filter { !it.isRunning }
        if (finished.size <= MAX_JOBS_KEPT_PER_SESSION) return
        finished.take(finished.size - MAX_JOBS_KEPT_PER_SESSION).forEach { jobs.remove(it.id) }
    }

    /** 会话删除/测试清理。 */
    internal fun clear(sessionId: String) {
        list(sessionId).filter { it.isRunning }.forEach { kill(sessionId, it.id) }
        jobs.values.filter { it.sessionId == sessionId }.forEach { jobs.remove(it.id) }
    }
}
