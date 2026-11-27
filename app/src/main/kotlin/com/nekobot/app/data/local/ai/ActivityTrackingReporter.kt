package com.nekobot.app.data.local.ai

import com.nekobot.app.data.SessionActivityBus

/**
 * 进度回调包装器：把流水线阶段同步到 [SessionActivityBus]，其余行为原样转发。
 *
 * 放在 [AIPipeline.process] 入口统一包装，就不必在十几处 `progress.onXxx(...)` 调用点上
 * 分别埋点；对传入的 reporter 也不做改动（`onPreparingStart` 等调用方提前触发的方法照旧生效）。
 */
internal class ActivityTrackingReporter(
    private val delegate: ProgressReporter,
    private val sessionId: String
) : ProgressReporter() {

    init {
        // 流水线开跑即视为「思考中」，覆盖上报器创建与首个思考回调之间的空档
        SessionActivityBus.onThinking(sessionId)
    }

    override fun onPreparingStart(ctx: PipelineContext) {
        SessionActivityBus.onThinking(sessionId)
        delegate.onPreparingStart(ctx)
    }

    override fun onAttachmentStart(ctx: PipelineContext, count: Int) =
        delegate.onAttachmentStart(ctx, count)

    override fun onAttachmentItem(ctx: PipelineContext, name: String, itemType: String) =
        delegate.onAttachmentItem(ctx, name, itemType)

    override fun onAttachmentItemDone(
        ctx: PipelineContext,
        name: String,
        success: Boolean,
        resultPreview: String
    ) = delegate.onAttachmentItemDone(ctx, name, success, resultPreview)

    override fun onAttachmentsDone(ctx: PipelineContext) = delegate.onAttachmentsDone(ctx)

    override fun onKnowledgeStart(ctx: PipelineContext) = delegate.onKnowledgeStart(ctx)

    override fun onKnowledgeDone(ctx: PipelineContext, retrieved: Boolean) =
        delegate.onKnowledgeDone(ctx, retrieved)

    override fun onThinkingStart(ctx: PipelineContext) {
        SessionActivityBus.onThinking(sessionId)
        delegate.onThinkingStart(ctx)
    }

    override fun onThinkingContent(ctx: PipelineContext, content: String) {
        SessionActivityBus.onThinking(sessionId)
        delegate.onThinkingContent(ctx, content)
    }

    override fun onIntermediateContent(ctx: PipelineContext, content: String) {
        SessionActivityBus.onThinking(sessionId)
        delegate.onIntermediateContent(ctx, content)
    }

    override fun onToolStart(
        ctx: PipelineContext,
        toolName: String,
        arguments: Map<String, Any>,
        thinking: String
    ) {
        SessionActivityBus.onToolStart(sessionId, toolName)
        delegate.onToolStart(ctx, toolName, arguments, thinking)
    }

    override fun onToolDone(
        ctx: PipelineContext,
        toolName: String,
        result: Map<String, Any>,
        thinking: String
    ) {
        SessionActivityBus.onToolDone(sessionId)
        delegate.onToolDone(ctx, toolName, result, thinking)
    }

    override fun onToolIteration(ctx: PipelineContext, iteration: Int) {
        SessionActivityBus.onThinking(sessionId)
        delegate.onToolIteration(ctx, iteration)
    }

    override fun onSendMessage(ctx: PipelineContext, content: String) =
        delegate.onSendMessage(ctx, content)

    override fun onSendFile(ctx: PipelineContext, filePath: String, filename: String) =
        delegate.onSendFile(ctx, filePath, filename)

    override fun onDone(ctx: PipelineContext) = delegate.onDone(ctx)

    override fun onWaitingConfirmation(ctx: PipelineContext, command: String, requestId: String) {
        SessionActivityBus.onWaiting(sessionId)
        delegate.onWaitingConfirmation(ctx, command, requestId)
    }
}
