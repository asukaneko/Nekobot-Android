package com.nekobot.app.data.local

import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地耗时命令的持久化进度报告器。
 *
 * 同一条命令始终更新父用户消息上的同一张卡片；以百分比和时间双重节流，
 * 避免下载大量图片时为每一页都高频写入 Room。
 */
internal class LocalCommandProgressReporter(
    private val parentMessageId: String,
    private val onUpdate: suspend (ThinkingCard) -> Unit,
    private val cardId: String = "local-command-$parentMessageId",
    private val minUpdateIntervalMs: Long = 250L,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var lastProgress = -1
    private var lastUpdatedAt = Long.MIN_VALUE
    private var terminal = false

    suspend fun update(
        content: String,
        progress: Int,
        steps: List<ThinkingStep>,
        isComplete: Boolean = false,
        force: Boolean = false
    ) {
        mutex.withLock {
            if (terminal) return
            // 并发封面/章节任务可能乱序回调，进度只能前进不能倒退。
            val normalizedProgress = maxOf(lastProgress, progress.coerceIn(0, 100))
            val now = nowMillis()
            val shouldSkip = !force &&
                !isComplete &&
                normalizedProgress == lastProgress &&
                now - lastUpdatedAt < minUpdateIntervalMs
            if (shouldSkip) return

            onUpdate(
                ThinkingCard(
                    id = cardId,
                    content = content,
                    steps = steps,
                    progress = normalizedProgress,
                    isComplete = isComplete,
                    isAgent = false,
                    timestamp = LocalRepository.nowIsoStatic(),
                    parentMessageId = parentMessageId
                )
            )
            lastProgress = normalizedProgress
            lastUpdatedAt = now
            terminal = isComplete
        }
    }
}

internal fun progressBetween(
    start: Int,
    end: Int,
    completed: Int,
    total: Int
): Int {
    if (total <= 0) return start.coerceIn(0, 100)
    val lower = start.coerceIn(0, 100)
    val upper = end.coerceIn(lower, 100)
    val done = completed.coerceIn(0, total)
    return lower + (((upper - lower).toLong() * done) / total).toInt()
}
