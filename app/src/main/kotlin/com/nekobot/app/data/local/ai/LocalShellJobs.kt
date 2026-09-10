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
 *
 * 生命周期与进程一致（内存态）。后台命令的授权在启动前于前台完成，
 * 因此不存在"后台偷偷获得了新权限"的路径。
 */
internal object LocalShellJobs {

    /** 单个会话允许同时运行的后台命令数。 */
    internal const val MAX_JOBS_PER_SESSION = 3

    /** 单个任务保留的输出字符上限。 */
    internal const val MAX_OUTPUT_CHARS = 20_000

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
        @Volatile var finishedAt: Long? = null
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
     * 启动一个后台命令。[runner] 返回一次性命令结果（与前台 exec_command 相同结构）。
     */
    internal fun start(
        sessionId: String,
        command: String,
        runner: suspend () -> Map<String, Any>
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
        val handle = kotlinx.coroutines.GlobalScope.launch(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
        ) {
            try {
                val result = runner()
                recordResult(job, result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                finish(job, STATUS_KILLED, error = "已终止")
                throw e
            } catch (e: Exception) {
                finish(job, STATUS_FAILED, error = e.message ?: "命令执行失败")
            }
        }
        handles[job.id] = handle
        handle.invokeOnCompletion { handles.remove(job.id) }
        return job
    }

    /** 终止后台命令。@return 是否确实终止了正在运行的任务。 */
    internal fun kill(sessionId: String, jobId: String): Boolean {
        val job = get(sessionId, jobId) ?: return false
        if (!job.isRunning) return false
        val handle = handles.remove(jobId)
        if (handle == null) {
            finish(job, STATUS_KILLED, error = "已终止")
            return false
        }
        handle.cancel()
        finish(job, STATUS_KILLED, error = "已终止")
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
        finish(
            job = job,
            status = if (succeeded) STATUS_SUCCEEDED else STATUS_FAILED,
            output = output,
            exitCode = (result["exit_code"] as? Number)?.toInt(),
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
