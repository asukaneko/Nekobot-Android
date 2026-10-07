package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台 shell 任务注册表：启动、查询、终止与并发上限。
 */
class LocalShellJobsTest {

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `任务完成后保留输出与退出码`() {
        val sessionId = "shell-session-1"
        val job = LocalShellJobs.start(sessionId, "echo hi") {
            mapOf("success" to true, "output" to "hi\n", "exit_code" to 0)
        }

        waitUntil { LocalShellJobs.get(sessionId, job.id)?.status == LocalShellJobs.STATUS_SUCCEEDED }

        val finished = LocalShellJobs.get(sessionId, job.id)
        assertEquals("hi\n", finished?.output)
        assertEquals(0, finished?.exitCode)
        assertFalse(finished?.isRunning ?: true)
        LocalShellJobs.clear(sessionId)
    }

    @Test
    fun `失败的命令标记为 failed 并保留错误`() {
        val sessionId = "shell-session-2"
        val job = LocalShellJobs.start(sessionId, "false") {
            mapOf("success" to false, "output" to "", "error" to "exit code 1")
        }

        waitUntil { LocalShellJobs.get(sessionId, job.id)?.status == LocalShellJobs.STATUS_FAILED }

        val finished = LocalShellJobs.get(sessionId, job.id)
        assertEquals(LocalShellJobs.STATUS_FAILED, finished?.status)
        assertTrue(finished?.error?.contains("exit code 1") == true)
        LocalShellJobs.clear(sessionId)
    }

    @Test
    fun `终止运行中的任务后状态为 killed`() {
        val sessionId = "shell-session-3"
        val job = LocalShellJobs.start(sessionId, "sleep 100") {
            Thread.sleep(30_000)
            mapOf("success" to true, "output" to "never", "exit_code" to 0)
        }
        assertTrue(LocalShellJobs.runningCount(sessionId) >= 1)

        assertTrue(LocalShellJobs.kill(sessionId, job.id))

        assertEquals(LocalShellJobs.STATUS_KILLED, LocalShellJobs.get(sessionId, job.id)?.status)
        assertEquals(0, LocalShellJobs.runningCount(sessionId))
        LocalShellJobs.clear(sessionId)
    }

    @Test
    fun `任务按会话隔离`() {
        val job = LocalShellJobs.start("shell-session-a", "echo a") {
            mapOf("success" to true, "output" to "a", "exit_code" to 0)
        }
        waitUntil { LocalShellJobs.get("shell-session-a", job.id)?.isRunning == false }

        assertTrue("不同会话不应看到彼此的任务", LocalShellJobs.list("shell-session-b").isEmpty())
        assertTrue(LocalShellJobs.get("shell-session-b", job.id) == null)
        LocalShellJobs.clear("shell-session-a")
    }

    @Test
    fun `输出长度受上限约束`() {
        val sessionId = "shell-session-4"
        val huge = "x".repeat(LocalShellJobs.MAX_OUTPUT_CHARS * 2)
        val job = LocalShellJobs.start(sessionId, "huge") {
            mapOf("success" to true, "output" to huge, "exit_code" to 0)
        }

        waitUntil { LocalShellJobs.get(sessionId, job.id)?.isRunning == false }

        assertEquals(LocalShellJobs.MAX_OUTPUT_CHARS, LocalShellJobs.get(sessionId, job.id)?.output?.length)
        LocalShellJobs.clear(sessionId)
    }

    @Test
    fun `多个后台任务并行运行而不是排队`() {
        val sessionId = "shell-session-parallel"
        val allStarted = java.util.concurrent.CountDownLatch(3)
        val release = java.util.concurrent.CountDownLatch(1)
        val jobs = (1..3).map { index ->
            LocalShellJobs.start(sessionId, "job$index") {
                allStarted.countDown()
                // 三个任务必须同时在跑才能全部到达这里；任一排队就会等到超时
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                mapOf("success" to true, "output" to "done$index", "exit_code" to 0)
            }
        }

        assertTrue("三个后台任务应同时在运行", allStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        release.countDown()
        jobs.forEach { job -> waitUntil { LocalShellJobs.get(sessionId, job.id)?.isRunning == false } }
        jobs.forEach { job ->
            assertEquals(
                LocalShellJobs.STATUS_SUCCEEDED,
                LocalShellJobs.get(sessionId, job.id)?.status
            )
        }
        LocalShellJobs.clear(sessionId)
    }

    @Test
    fun `终止后运行体返回的结果不会把状态改回成功`() {
        val sessionId = "shell-session-killed-late"
        val started = java.util.concurrent.CountDownLatch(1)
        val job = LocalShellJobs.start(sessionId, "sleep 100") {
            started.countDown()
            Thread.sleep(400)
            mapOf("success" to true, "output" to "too late", "exit_code" to 0)
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))

        assertTrue(LocalShellJobs.kill(sessionId, job.id))
        Thread.sleep(800)

        assertEquals(LocalShellJobs.STATUS_KILLED, LocalShellJobs.get(sessionId, job.id)?.status)
        LocalShellJobs.clear(sessionId)
    }

    @Test
    fun `并发上限为三个`() {
        assertEquals(3, LocalShellJobs.MAX_JOBS_PER_SESSION)
    }
}
