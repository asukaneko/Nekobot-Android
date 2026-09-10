package com.nekobot.app.data.local.ai

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 会话级“Agent 运行通知”队列。
 *
 * 后台子代理完成任务时，父会话可能仍在工具循环中，也可能已经空闲。两种情况都要让模型
 * 有机会知道任务已经结束（否则模型只能靠 subagent_get 轮询，或者干脆遗忘）：
 *
 * - 仍在循环中：通知在下一轮模型调用前作为消息注入（复用排队消息通道）；
 * - 已经空闲：通知留在队列里，等用户下一次发消息时注入。
 *
 * 只做内存队列并限制长度：通知是过程性信息，任务结果本身持久化在 [SubagentTaskStore]，
 * 进程重启后依然可以用 subagent_get 查询完整结果。
 */
internal object AgentNoticeBus {

    /** 单个会话最多保留的通知条数，避免长期空闲的会话无上限堆积。 */
    internal const val MAX_NOTICES_PER_SESSION = 20

    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    /** 发布一条通知；超过上限时丢弃最旧的通知。 */
    fun publish(sessionId: String, notice: String) {
        if (sessionId.isBlank() || notice.isBlank()) return
        val queue = queues.computeIfAbsent(sessionId) { ConcurrentLinkedQueue() }
        queue.add(notice)
        while (queue.size > MAX_NOTICES_PER_SESSION) {
            queue.poll()
        }
    }

    /** 取出并清空某会话的全部通知（取出即消费）。 */
    fun drain(sessionId: String): List<String> {
        val queue = queues[sessionId] ?: return emptyList()
        val drained = mutableListOf<String>()
        while (true) {
            val item = queue.poll() ?: break
            drained.add(item)
        }
        return drained
    }

    /** 清空某会话的通知（会话删除/测试用）。 */
    fun clear(sessionId: String) {
        queues.remove(sessionId)
    }
}

/**
 * 后台子代理并发闸门。
 *
 * 后台任务在独立协程里运行、不随父会话生成结束而结束；没有上限时模型一次委派十几条
 * 后台任务会把模型与网络配额同时打满，用户也看不到进度。超限时直接拒绝并给出可执行的
 * 建议（等已有任务完成，或改用前台执行）。
 */
internal object SubagentConcurrency {

    const val MAX_BACKGROUND_RUNS = 3

    private val permits = java.util.concurrent.Semaphore(MAX_BACKGROUND_RUNS)

    fun tryAcquire(): Boolean = permits.tryAcquire()

    fun release() {
        permits.release()
    }

    internal fun availablePermits(): Int = permits.availablePermits()
}

/** 工具上下文里传递“当前子代理任务 id”的键，用于按任务树计算嵌套深度。 */
internal const val SUBAGENT_TASK_CONTEXT_KEY = "_subagent_task_id"

/**
 * 后台子代理运行句柄表。
 *
 * 后台子代理是独立协程，光有任务记录（[SubagentTaskStore]）无法终止它；
 * 这里登记 taskId → Job，供 `subagent_kill` 精确取消单个任务（不影响其他子代理）。
 */
internal object SubagentRunRegistry {

    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun register(taskId: String, job: kotlinx.coroutines.Job) {
        jobs[taskId] = job
    }

    fun unregister(taskId: String) {
        jobs.remove(taskId)
    }

    fun isRunning(taskId: String): Boolean = jobs[taskId]?.isActive == true

    /** @return 是否确实取消了正在运行的任务。 */
    fun cancel(taskId: String): Boolean {
        val job = jobs.remove(taskId) ?: return false
        job.cancel()
        return true
    }
}
