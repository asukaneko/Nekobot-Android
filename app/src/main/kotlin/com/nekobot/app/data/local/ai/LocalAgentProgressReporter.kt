package com.nekobot.app.data.local.ai

import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalRepository
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
    private val nowNanos: () -> Long = System::nanoTime,
    private val streamIntervalNanos: Long = 120_000_000L,
    private val streamCharBatch: Int = 96,
    private val cardId: String = UUID.randomUUID().toString()
) : ProgressReporter() {

    /** 优先从本地化上下文取资源；JVM 单测等无上下文场景回落到中文默认文案。 */
    private fun progressText(resId: Int, fallback: String): String =
        ServiceContainer.localizedContext?.getString(resId) ?: fallback

    private fun progressText(resId: Int, fallback: String, arg: Any): String =
        ServiceContainer.localizedContext?.getString(resId, arg)
            ?: String.format(Locale.getDefault(), fallback, arg)

    private val steps = mutableListOf<ThinkingStep>()
    private val reasoningContent = StringBuilder()
    private var lastStreamingEmitNanos: Long? = null
    private var lastEmittedReasoningLength: Int = 0

    private fun syncThinkingStep() {
        if (reasoningContent.isEmpty()) return
        val fullReasoning = reasoningContent.toString()
        steps.indexOfLast { it.type == "thinking" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(
                detail = fullReasoning.takeLast(160),
                thinkingContent = fullReasoning
            )
        }
    }

    private fun emit(
        content: String,
        isComplete: Boolean = false,
        checkpoint: Boolean = true
    ) {
        syncThinkingStep()
        val card = ThinkingCard(
            id = cardId,
            content = content,
            steps = steps.toList(),
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

    override fun onPreparingStart(ctx: PipelineContext) {
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
        if (steps.none { it.type == "thinking" }) {
            steps.add(
                ThinkingStep(
                    type = "thinking",
                    name = progressText(R.string.agent_progress_thinking, "AI 正在思考..."),
                    status = "active"
                )
            )
        }
        emit(progressText(R.string.agent_progress_processing, "AI 正在处理..."))
    }

    override fun onThinkingContent(ctx: PipelineContext, content: String) {
        if (content.isEmpty()) return
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

    override fun onToolStart(
        ctx: PipelineContext,
        toolName: String,
        arguments: Map<String, Any>,
        thinking: String
    ) {
        if (thinking.isNotBlank() && ctx.metadata["agent_reasoning_streamed"] != true) {
            onThinkingContent(ctx, thinking)
        }
        val argumentPreview = boundedAgentValuePreview(arguments, 100)
        steps.add(
            ThinkingStep(
                type = "tool",
                name = toolName,
                status = "running",
                detail = argumentPreview,
                // 进度卡是展示状态，不承载模型续聊数据；完整参数仍保留在 tool_call_history。
                arguments = mapOf(
                    "preview" to boundedAgentValuePreview(
                        arguments,
                        MAX_AGENT_PROGRESS_ARGUMENT_PREVIEW_CHARS
                    )
                )
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
        val resultPreview = boundedAgentValuePreview(result, 120)
        val resultTruncated = isAgentToolOutputTruncated(result)
        val index = steps.indexOfLast {
            it.type == "tool" && it.name == toolName && it.status != "done"
        }
        if (index >= 0) {
            steps[index] = steps[index].copy(
                status = "done",
                detail = resultPreview,
                fullResult = boundedAgentValuePreview(
                    result,
                    MAX_AGENT_PROGRESS_RESULT_PREVIEW_CHARS
                ),
                resultTruncated = resultTruncated
            )
        } else {
            steps.add(
                ThinkingStep(
                    type = "tool_done",
                    name = toolName,
                    status = "done",
                    detail = resultPreview,
                    fullResult = boundedAgentValuePreview(
                        result,
                        MAX_AGENT_PROGRESS_RESULT_PREVIEW_CHARS
                    ),
                    resultTruncated = resultTruncated
                )
            )
        }
        emit(progressText(R.string.agent_progress_tool_done, "工具完成: %1\$s", toolName))
    }

    override fun onToolIteration(ctx: PipelineContext, iteration: Int) {
        emit(progressText(R.string.agent_progress_processing_iteration, "AI 正在处理... (%1\$d)", iteration))
    }

    override fun onWaitingConfirmation(ctx: PipelineContext, command: String, requestId: String) {
        steps.add(
            ThinkingStep(
                type = "tool",
                name = progressText(R.string.agent_progress_wait_confirm_step, "等待命令授权"),
                status = "active",
                detail = command.take(120)
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
                detail = content.take(120)
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
                detail = filePath.take(120)
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
}
