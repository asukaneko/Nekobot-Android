package com.nekobot.app.data.local.ai

import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.model.GitDiffSummary
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import java.util.Locale
import java.util.UUID

/**
 * 本地 Agent 模式的进度卡片报告器。
 *
 * 一轮对话只创建一个卡片 ID，后续步骤持续更新该卡片，完成时再将其标记为完成。
 */
internal class LocalAgentProgressReporter(
    private val parentMessageId: String?,
    private val onUpdate: (ThinkingCard) -> Unit,
    private val onCheckpoint: (ThinkingCard) -> Unit = {},
    private val sessionId: String = "",
    private val nowNanos: () -> Long = System::nanoTime,
    private val streamIntervalNanos: Long = 120_000_000L,
    private val streamCharBatch: Int = 96,
    private val cardId: String = UUID.randomUUID().toString(),
    /**
     * 实时 git 扫描入口：返回工作区相关仓库当前**相对 HEAD 的全部更改**。
     * 为 null 时不启动监控（单元测试等无工作区场景）。
     */
    private val gitScanProvider: (() -> com.nekobot.app.data.local.WorkspaceGitDiff.GitScanResult)? = null,
    /** 实时 git 扫描间隔（毫秒）。 */
    private val gitScanIntervalMs: Long = DEFAULT_GIT_SCAN_INTERVAL_MS
) : ProgressReporter() {

    private companion object {
        /** 默认扫描间隔：兼顾“有变化马上看到”与移动端性能。 */
        const val DEFAULT_GIT_SCAN_INTERVAL_MS = 2_000L
    }

    /** 优先从本地化上下文取资源；JVM 单测等无上下文场景回落到中文默认文案。 */
    private fun progressText(resId: Int, fallback: String): String =
        ServiceContainer.localizedContext?.getString(resId) ?: fallback

    private fun progressText(resId: Int, fallback: String, arg: Any): String =
        ServiceContainer.localizedContext?.getString(resId, arg)
            ?: String.format(Locale.getDefault(), fallback, arg)

    /**
     * 卡片步骤列表。
     *
     * 使用写时复制列表：实时 git 监控在后台线程刷新卡片，而流水线线程同时在追加步骤，
     * 普通 ArrayList 的并发读写可能让快照读到尚未写入的元素。
     */
    private val steps = java.util.concurrent.CopyOnWriteArrayList<ThinkingStep>()
    /**
     * 当前这一轮思考的正文累加器。
     *
     * Agent 循环每迭代一次就是「思考 →（可能的回复）→ 工具调用」，
     * 因此每轮思考各自成一个步骤，正文不跨轮拼接（跨轮拼接会把整轮思考全堆在卡片顶部）。
     */
    private val reasoningContent = StringBuilder()
    private var lastStreamingEmitNanos: Long? = null
    private var lastEmittedReasoningLength: Int = 0
    /**
     * 每个工具开始执行的时间戳（nanoTime）队列，按工具名分组。
     * 工具循环按调用顺序回放同批结果，同名工具因此按先入先出配对。
     * 被中断（onToolStart 后无 onToolDone）的残留条目在 onDone 统一清理。
     */
    private val toolStartNanosByName = mutableMapOf<String, MutableList<Long>>()

    // ---------- 实时 git 仓库监控 ----------
    /**
     * 仓库当前相对 HEAD 的更改摘要。
     *
     * 与步骤列表分开存放：监控线程只改这个字段，卡片快照时再合并成 git_diff 步骤，
     * 从而不必让监控线程去碰流水线线程正在写的步骤列表。
     */
    @Volatile
    private var gitDiffSummary: GitDiffSummary? = null
    private val gitDiffLock = Any()
    private var gitWatcher: com.nekobot.app.data.local.WorkspaceGitWatcher? = null
    /** 收尾/停止后不再接受监控结果，避免旧扫描覆盖最终卡片。 */
    @Volatile
    private var gitWatchClosed = false
    /** 最近一次卡片头部文本与完成态：监控触发的刷新沿用它们，不覆盖流水线文案。 */
    private var lastHeader: String = ""
    private var lastComplete: Boolean = false

    /** 启动实时监控（幂等）：按间隔扫描仓库，发现变化就刷新卡片。 */
    private fun ensureGitWatcher() {
        val provider = gitScanProvider ?: return
        if (gitWatchClosed || gitWatcher != null) return
        val watcher = com.nekobot.app.data.local.WorkspaceGitWatcher(
            intervalMs = gitScanIntervalMs,
            scan = provider,
            // 轮询更新不落库：卡片最终状态由 onDone 的收尾扫描随进度卡一起持久化，
            // 避免每两秒写一次大 JSON。
            onSummary = { summary -> updateGitDiff(summary, checkpoint = false) }
        )
        gitWatcher = watcher
        watcher.start()
    }

    /** 工具结束后请求立即补扫一次，不必等下一个轮询周期。 */
    private fun requestGitScan() {
        if (gitWatchClosed) return
        gitWatcher?.requestScan() ?: ensureGitWatcher()
    }

    private fun stopGitWatcher() {
        gitWatchClosed = true
        gitWatcher?.stop()
        gitWatcher = null
    }

    /**
     * 本轮运行结束（正常/被停止/异常）：停止实时监控。
     * 幂等；[onDone] 已经收尾时这里不再做任何事。
     */
    fun stopMonitoring() {
        if (gitWatchClosed) return
        refreshGitSummaryNow()
        stopGitWatcher()
    }

    /**
     * 收尾同步扫描：保证最终卡片包含这一轮结束时刻的仓库状态。
     *
     * 扫描不可信（[GitScanResult.Unavailable]）时保留上一次结果——收尾读盘失败同样不该把
     * 已经展示的变更区域抹掉。
     */
    private fun refreshGitSummaryNow() {
        val provider = gitScanProvider ?: return
        val result = runCatching { provider() }.getOrNull() ?: return
        when (result) {
            is com.nekobot.app.data.local.WorkspaceGitDiff.GitScanResult.Changed ->
                synchronized(gitDiffLock) { gitDiffSummary = result.summary }
            is com.nekobot.app.data.local.WorkspaceGitDiff.GitScanResult.Clean ->
                synchronized(gitDiffLock) { gitDiffSummary = null }
            is com.nekobot.app.data.local.WorkspaceGitDiff.GitScanResult.Unavailable -> Unit
        }
    }

    /**
     * 写入实时 git 摘要并刷新卡片。
     *
     * summary 为 null（无仓库 / 无更改，例如 AI 提交了这些文件）时移除卡片上的变更区域。
     *
     * @param checkpoint 是否落库；轮询刷新传 false，只在收尾时随进度卡持久化
     */
    fun updateGitDiff(summary: GitDiffSummary?, checkpoint: Boolean = true) {
        if (gitWatchClosed) return
        val normalized = summary?.takeIf { it.hasChanges }
        val changed = synchronized(gitDiffLock) {
            if (normalized == gitDiffSummary) {
                false
            } else {
                gitDiffSummary = normalized
                true
            }
        }
        if (!changed) return
        emit(
            content = lastHeader.ifBlank {
                progressText(R.string.agent_progress_processing, "AI 正在处理...")
            },
            isComplete = lastComplete,
            checkpoint = checkpoint,
            syncReasoning = false
        )
    }

    /** 由当前 git 摘要生成的卡片步骤（无更改时为 null）。 */
    private fun gitDiffStep(): ThinkingStep? {
        val summary = gitDiffSummary?.takeIf { it.hasChanges } ?: return null
        return ThinkingStep(
            type = "git_diff",
            name = progressText(R.string.agent_git_step_name, "文件变更（git）"),
            status = "done",
            detail = progressText(
                R.string.agent_git_files_changed,
                "共 %1\$d 个文件变更",
                summary.files.size
            ),
            gitDiff = summary
        )
    }

    private fun syncThinkingStep() {
        if (reasoningContent.isEmpty()) return
        val fullReasoning = reasoningContent.toString()
        steps.indexOfLast { it.type == "thinking" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(
                detail = fullReasoning.takeLast(AgentToolLimits.PROGRESS_REASONING_DETAIL_CHARS),
                thinkingContent = fullReasoning
            )
        }
    }

    /**
     * 开始新一轮思考。
     *
     * 思考步骤按时间顺序追加：上一轮思考在此收尾，新步骤排在已经完成的工具步骤之后，
     * 于是进度卡片里的顺序就是真实执行顺序（思考 → 工具 → 思考 → 工具 …），
     * 而不是把所有思考堆在卡片顶部。
     */
    private fun beginThinkingRound() {
        val index = steps.indexOfLast { it.type == "thinking" }
        val previous = steps.getOrNull(index)
        if (previous != null && !previous.status.equals("done", ignoreCase = true)) {
            // 本轮思考已经开始且还没产出正文：复用该占位步骤，避免连续两个空气泡
            val reusable = reasoningContent.isEmpty() &&
                previous.thinkingContent.isNullOrBlank() &&
                index == steps.lastIndex
            if (reusable) return
        }
        if (previous != null) finishThinkingStep(index)
        reasoningContent.setLength(0)
        lastEmittedReasoningLength = 0
        lastStreamingEmitNanos = null
        steps.add(
            ThinkingStep(
                type = "thinking",
                name = progressText(R.string.agent_progress_thinking, "AI 正在思考..."),
                status = "active"
            )
        )
    }

    /**
     * 收尾指定思考步骤：有正文就标记完成，没有正文的占位步骤直接移除。
     * 模型没有输出思考内容时（思考强度关闭 / 该轮未产出思考），卡片里不留空气泡。
     *
     * 空占位不承载任何内容，移除不会改变其余步骤的相对顺序；只有保留「非末尾空占位」
     * 才会在中间回复步骤插入后留下一条无内容的思考行。
     */
    private fun finishThinkingStep(index: Int) {
        val step = steps.getOrNull(index) ?: return
        if (step.status.equals("done", ignoreCase = true)) return
        if (step.thinkingContent.isNullOrBlank()) {
            steps.removeAt(index)
        } else {
            steps[index] = step.copy(status = "done")
        }
    }

    /** 工具开始执行：本轮思考就此收尾（后续思考属于下一轮）。 */
    private fun finishCurrentThinkingRound() {
        val index = steps.indexOfLast { it.type == "thinking" }
        if (index >= 0) finishThinkingStep(index)
    }

    /** 兜底：确保存在一个能承载当前轮思考正文的步骤。 */
    private fun ensureThinkingStep() {
        val index = steps.indexOfLast { it.type == "thinking" }
        val active = index >= 0 && !steps[index].status.equals("done", ignoreCase = true)
        if (!active) beginThinkingRound()
    }

    private fun emit(
        content: String,
        isComplete: Boolean = false,
        checkpoint: Boolean = true,
        /** 监控线程刷新时不做思考正文同步，避免与流水线线程写思考缓冲相互影响。 */
        syncReasoning: Boolean = true
    ) {
        if (syncReasoning) syncThinkingStep()
        lastHeader = content
        lastComplete = isComplete
        val card = ThinkingCard(
            id = cardId,
            content = content,
            steps = stepsWithGitDiff(),
            isComplete = isComplete,
            isAgent = true,
            timestamp = LocalRepository.nowIsoStatic(),
            parentMessageId = parentMessageId
        )
        onUpdate(card)
        if (checkpoint) onCheckpoint(card)
        if (reasoningContent.isNotEmpty()) {
            lastStreamingEmitNanos = nowNanos()
            lastEmittedReasoningLength = reasoningContent.length
        }
    }

    /** 步骤快照 + 实时 git 变更步骤（插在末尾的「处理完成」步骤之前）。 */
    private fun stepsWithGitDiff(): List<ThinkingStep> {
        val snapshot = steps.toMutableList()
        val gitStep = gitDiffStep() ?: return snapshot
        val doneIndex = snapshot.indexOfLast {
            it.type.equals("done", ignoreCase = true)
        }
        if (doneIndex >= 0) snapshot.add(doneIndex, gitStep) else snapshot.add(gitStep)
        return snapshot
    }

    override fun onPreparingStart(ctx: PipelineContext) {
        // Agent 一轮运行开始：启动 git 仓库实时监控（无仓库时监控器自动空转）
        ensureGitWatcher()
        if (steps.none { it.type == "preparing" }) {
            steps.add(
                ThinkingStep(
                    type = "preparing",
                    name = progressText(R.string.agent_progress_preparing, "正在准备 Agent..."),
                    status = "active"
                )
            )
        }
        emit(progressText(R.string.agent_progress_preparing, "正在准备 Agent..."))
    }

    override fun onThinkingStart(ctx: PipelineContext) {
        steps.indexOfLast { it.type == "preparing" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(status = "done")
        }
        beginThinkingRound()
        emit(progressText(R.string.agent_progress_processing, "AI 正在处理..."))
    }

    override fun onThinkingContent(ctx: PipelineContext, content: String) {
        if (content.isEmpty()) return
        AgentOverlayBus.onThinking(sessionId, content)
        ensureThinkingStep()
        reasoningContent.append(content)
        val now = nowNanos()
        val elapsed = lastStreamingEmitNanos?.let { now - it } ?: Long.MAX_VALUE
        val pendingChars = reasoningContent.length - lastEmittedReasoningLength
        if (
            lastStreamingEmitNanos == null ||
            elapsed >= streamIntervalNanos ||
            pendingChars >= streamCharBatch
        ) {
            emit(progressText(R.string.agent_progress_thinking, "AI 正在思考..."), checkpoint = false)
        }
    }

    /**
     * 中间回复正文：模型带工具调用时输出的回复会被下一轮 StreamStart 从气泡清空，
     * 这里以「无头部、完整正文」的步骤留档到进度卡片，方便用户回看。
     *
     * 不在此时收尾思考步骤：思考正文若只在模型响应里返回（未流式下发），
     * 之后 onToolStart 还会把它补进当前思考步骤，收尾会让顺序倒置。
     */
    override fun onIntermediateContent(ctx: PipelineContext, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val bounded = if (text.length > AgentToolLimits.PROGRESS_INTERMEDIATE_CHARS) {
            text.take(AgentToolLimits.PROGRESS_INTERMEDIATE_CHARS) + "…"
        } else {
            text
        }
        steps.add(ThinkingStep(type = "agent_text", status = "done", text = bounded))
        AgentOverlayBus.onIntermediateContent(sessionId, bounded)
        AgentIntermediateReplyBus.onIntermediateContent(sessionId, bounded)
        emit(progressText(R.string.agent_progress_processing, "AI 正在处理..."))
    }

    override fun onToolStart(
        ctx: PipelineContext,
        toolName: String,
        arguments: Map<String, Any>,
        thinking: String
    ) {
        // 记录开始时间；onToolDone 时按调用顺序取出并计算耗时。
        toolStartNanosByName.getOrPut(toolName) { mutableListOf() }.add(nowNanos())
        AgentOverlayBus.onToolStart(sessionId, toolName)
        if (thinking.isNotBlank() && ctx.metadata["agent_reasoning_streamed"] != true) {
            onThinkingContent(ctx, thinking)
        }
        // 工具开始执行即本轮思考结束：思考步骤收尾后，工具步骤按时间顺序接在它后面
        finishCurrentThinkingRound()
        val argumentPreview = boundedAgentValuePreview(
            arguments,
            AgentToolLimits.PROGRESS_STEP_DETAIL_CHARS
        )
        steps.add(
            ThinkingStep(
                type = "tool",
                name = toolName,
                status = "running",
                detail = argumentPreview,
                // 进度卡只做展示：参数按「参数名 → 参数值」结构化留档（逐项限长），
                // 完整参数仍保留在 tool_call_history，模型续聊不受影响。
                arguments = boundedAgentArguments(arguments).takeIf { it.isNotEmpty() }
            )
        )
        emit(progressText(R.string.agent_progress_tool_call, "调用工具: %1\$s", toolName))
    }

    override fun onToolDone(
        ctx: PipelineContext,
        toolName: String,
        result: Map<String, Any>,
        thinking: String
    ) {
        // 工具循环按调用顺序回放结果；同名并行调用必须按 FIFO 匹配参数、结果和耗时。
        val durationMs = toolStartNanosByName[toolName]?.removeFirstOrNull()?.let { start ->
            ((nowNanos() - start) / 1_000_000L).coerceAtLeast(0L)
        }
        AgentOverlayBus.onToolDone(sessionId, toolName)
        // 展示净化：图片结果里的 data URI 不进入预览，避免卡片被 base64 刷满。
        val displayResult = sanitizeAgentToolResultForDisplay(result)
        val resultPreview = boundedAgentValuePreview(
            displayResult,
            AgentToolLimits.PROGRESS_STEP_DETAIL_CHARS
        )
        val resultTruncated = isAgentToolOutputTruncated(result)
        val index = steps.indexOfFirst {
            it.type == "tool" && it.name == toolName && it.status != "done"
        }
        if (index >= 0) {
            steps[index] = steps[index].copy(
                status = "done",
                detail = resultPreview,
                fullResult = boundedAgentValuePreview(
                    displayResult,
                    AgentToolLimits.progressPreviewChars()
                ),
                resultTruncated = resultTruncated,
                durationMs = durationMs
            )
        } else {
            steps.add(
                ThinkingStep(
                    type = "tool_done",
                    name = toolName,
                    status = "done",
                    detail = resultPreview,
                    fullResult = boundedAgentValuePreview(
                        displayResult,
                        AgentToolLimits.progressPreviewChars()
                    ),
                    resultTruncated = resultTruncated,
                    durationMs = durationMs
                )
            )
        }
        emit(progressText(R.string.agent_progress_tool_done, "工具完成: %1\$s", toolName))
        // 工具可能改动了仓库：立即补扫一次，让变更区域第一时间刷新
        requestGitScan()
    }

    override fun onToolIteration(ctx: PipelineContext, iteration: Int) {
        // 每次工具循环迭代 = 新一轮思考：新一轮的思考步骤按时间顺序追加在已完成的工具步骤之后
        beginThinkingRound()
        emit(progressText(R.string.agent_progress_processing_iteration, "AI 正在处理... (%1\$d)", iteration))
    }

    override fun onWaitingConfirmation(ctx: PipelineContext, command: String, requestId: String) {
        steps.add(
            ThinkingStep(
                type = "tool",
                name = progressText(R.string.agent_progress_wait_confirm_step, "等待命令授权"),
                status = "active",
                detail = command.take(AgentToolLimits.PROGRESS_STEP_DETAIL_CHARS)
            )
        )
        emit(progressText(R.string.agent_progress_wait_confirm, "等待命令授权..."))
    }

    override fun onSendMessage(ctx: PipelineContext, content: String) {
        steps.add(
            ThinkingStep(
                type = "send_message",
                name = progressText(R.string.agent_progress_send_message_step, "发送进度消息"),
                status = "done",
                detail = content.take(AgentToolLimits.PROGRESS_STEP_DETAIL_CHARS)
            )
        )
        emit(progressText(R.string.agent_progress_message_sent, "已发送进度消息"))
    }

    override fun onSendFile(ctx: PipelineContext, filePath: String, filename: String) {
        steps.add(
            ThinkingStep(
                type = "file",
                name = progressText(R.string.agent_progress_file_step, "准备文件: %1\$s", filename),
                status = "done",
                detail = filePath.take(AgentToolLimits.PROGRESS_STEP_DETAIL_CHARS)
            )
        )
        emit(progressText(R.string.agent_progress_file_done, "文件处理完成"))
    }

    override fun onAttachmentStart(ctx: PipelineContext, count: Int) {
        steps.add(
            ThinkingStep(
                type = "upload",
                name = progressText(R.string.agent_progress_attachments_step, "正在处理 %1\$d 个附件...", count),
                status = "running"
            )
        )
        emit(progressText(R.string.agent_progress_attachments, "正在处理附件..."))
    }

    override fun onAttachmentsDone(ctx: PipelineContext) {
        steps.indexOfLast { it.type == "upload" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(status = "done")
        }
        emit(progressText(R.string.agent_progress_attachments_done, "附件处理完成"))
    }

    override fun onKnowledgeStart(ctx: PipelineContext) {
        steps.add(
            ThinkingStep(
                type = "knowledge",
                name = progressText(R.string.agent_progress_knowledge_step, "正在检索知识库..."),
                status = "running"
            )
        )
        emit(progressText(R.string.agent_progress_knowledge_step, "正在检索知识库..."))
    }

    override fun onKnowledgeDone(ctx: PipelineContext, retrieved: Boolean) {
        steps.indexOfLast { it.type == "knowledge" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(
                status = "done",
                detail = progressText(
                    if (retrieved) R.string.agent_progress_knowledge_hit else R.string.agent_progress_knowledge_miss,
                    if (retrieved) "命中相关条目" else "未命中"
                )
            )
        }
        emit(progressText(R.string.agent_progress_knowledge_done, "知识库检索完成"))
    }

    override fun onDone(ctx: PipelineContext) {
        // 清理被中断工具（onToolStart 后未配对 onToolDone）残留的开始时间戳。
        toolStartNanosByName.clear()
        AgentOverlayBus.onRunFinished(sessionId)
        AgentIntermediateReplyBus.finishRun(sessionId)
        // 停止实时监控并做一次同步收尾扫描：最终卡片包含本轮结束时刻的仓库状态
        stopGitWatcher()
        refreshGitSummaryNow()
        for (index in steps.indices) {
            val step = steps[index]
            if (step.status == "running" || step.status == "active") {
                steps[index] = step.copy(status = "done")
            }
        }
        val doneText = progressText(R.string.agent_progress_done, "处理完成")
        steps.add(ThinkingStep(type = "done", name = doneText, status = "done"))
        emit(doneText, isComplete = true)
    }

    /**
     * 兼容入口：立即挂载一份 git 变更摘要（不经过实时监控）。
     * 语义与 [updateGitDiff] 一致；summary 为 null 时移除卡片上的变更区域。
     */
    fun attachGitDiff(summary: GitDiffSummary?) = updateGitDiff(summary, checkpoint = true)
}
