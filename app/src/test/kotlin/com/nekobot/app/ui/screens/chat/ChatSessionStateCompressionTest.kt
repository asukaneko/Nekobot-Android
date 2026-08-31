package com.nekobot.app.ui.screens.chat

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地后台手动压缩的 Job 安装/清理语义（[ChatSessionState]）。
 *
 * 覆盖：同一会话防重复压缩、活跃 Job 计入跨页面保留工作、
 * 仅 Job 自身可清理、已完成 Job 不阻塞新一轮压缩。
 */
class ChatSessionStateCompressionTest {

    @Test
    fun `install allows one active compression and rejects duplicates`() {
        val state = ChatSessionState("s1")
        val job1 = Job()
        assertTrue(state.installCompressionJob(job1))
        val job2 = Job()
        assertFalse(state.installCompressionJob(job2))
        job2.cancel()
    }

    @Test
    fun `active compression job counts as retained work`() {
        val state = ChatSessionState("s2")
        val job = Job()
        assertTrue(state.installCompressionJob(job))
        assertTrue(state.hasRetainedWork())
        assertTrue(state.clearCompressionJob(job))
        assertFalse(state.hasRetainedWork())
    }

    @Test
    fun `clear only accepts the installed job`() {
        val state = ChatSessionState("s3")
        val job = Job()
        assertTrue(state.installCompressionJob(job))
        val other = Job()
        assertFalse(state.clearCompressionJob(other))
        assertTrue(state.hasRetainedWork())
        assertTrue(state.clearCompressionJob(job))
        assertFalse(state.hasRetainedWork())
        other.cancel()
    }

    @Test
    fun `completed compression job can be replaced by a new one`() {
        val state = ChatSessionState("s4")
        val job = Job()
        assertTrue(state.installCompressionJob(job))
        job.complete()
        assertFalse(job.isActive)
        // 已完成的 Job 不再计入保留工作，且新一轮压缩可以重新安装。
        assertFalse(state.hasRetainedWork())
        val next = Job()
        assertTrue(state.installCompressionJob(next))
        next.cancel()
    }

    @Test
    fun `compression events accept emissions without subscribers`() {
        val state = ChatSessionState("s5")
        assertTrue(
            state.compressionEvents.tryEmit(
                ContextCompressionEvent(sessionId = "s5", compressed = true)
            )
        )
    }
}
