package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.model.ThinkingCard
import com.nekobot.app.data.model.ThinkingStep
import java.util.UUID

/**
 * 本地 Agent 模式的进度卡片报告器。
 *
 * 一轮对话只创建一个卡片 ID，后续步骤持续更新该卡片，完成时再将其标记为完成。
 */
internal class LocalAgentProgressReporter(
    private val parentMessageId: String?,
    private val onUpdate: (ThinkingCard) -> Unit,
    private val cardId: String = UUID.randomUUID().toString()
) : ProgressReporter() {

    private val steps = mutableListOf<ThinkingStep>()
    private val reasoningContent = StringBuilder()
    private var lastReasoningSegment: String = ""

    private fun emit(content: String, isComplete: Boolean = false) {
        onUpdate(
            ThinkingCard(
                id = cardId,
                content = content,
                steps = steps.toList(),
                isComplete = isComplete,
                isAgent = true,
                timestamp = LocalRepository.nowIsoStatic(),
                parentMessageId = parentMessageId
            )
        )
    }

    override fun onPreparingStart(ctx: PipelineContext) {
        if (steps.none { it.type == "preparing" }) {
            steps.add(ThinkingStep(type = "preparing", name = "正在准备 Agent...", status = "active"))
        }
        emit("正在准备 Agent...")
    }

    override fun onThinkingStart(ctx: PipelineContext) {
        steps.indexOfLast { it.type == "preparing" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(status = "done")
        }
        if (steps.none { it.type == "thinking" }) {
            steps.add(ThinkingStep(type = "thinking", name = "AI 正在思考...", status = "active"))
        }
        emit("AI 正在处理...")
    }

    override fun onThinkingContent(ctx: PipelineContext, content: String) {
        if (content.isBlank() || content == lastReasoningSegment) return
        lastReasoningSegment = content
        reasoningContent.append(content)
        val fullReasoning = reasoningContent.toString()
        steps.indexOfLast { it.type == "thinking" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(
                detail = fullReasoning.takeLast(160),
                thinkingContent = fullReasoning
            )
        }
        emit("AI 正在思考...")
    }

    override fun onToolStart(
        ctx: PipelineContext,
        toolName: String,
        arguments: Map<String, Any>,
        thinking: String
    ) {
        if (thinking.isNotBlank()) onThinkingContent(ctx, thinking)
        val argumentPreview = arguments.entries
            .joinToString(", ") { "${it.key}=${it.value}" }
            .take(100)
        steps.add(
            ThinkingStep(
                type = "tool",
                name = toolName,
                status = "running",
                detail = argumentPreview,
                arguments = arguments,
                thinkingContent = thinking.takeIf { it.isNotBlank() }
            )
        )
        emit("调用工具: $toolName")
    }

    override fun onToolDone(
        ctx: PipelineContext,
        toolName: String,
        result: Map<String, Any>,
        thinking: String
    ) {
        val resultPreview = result.toString().take(120)
        val index = steps.indexOfLast {
            it.type == "tool" && it.name == toolName && it.status != "done"
        }
        if (index >= 0) {
            steps[index] = steps[index].copy(
                status = "done",
                detail = resultPreview,
                fullResult = result
            )
        } else {
            steps.add(
                ThinkingStep(
                    type = "tool_done",
                    name = toolName,
                    status = "done",
                    detail = resultPreview,
                    fullResult = result
                )
            )
        }
        emit("工具完成: $toolName")
    }

    override fun onToolIteration(ctx: PipelineContext, iteration: Int) {
        emit("AI 正在处理... ($iteration)")
    }

    override fun onWaitingConfirmation(ctx: PipelineContext, command: String, requestId: String) {
        steps.add(
            ThinkingStep(
                type = "tool",
                name = "等待命令授权",
                status = "active",
                detail = command.take(120)
            )
        )
        emit("等待命令授权...")
    }

    override fun onSendMessage(ctx: PipelineContext, content: String) {
        steps.add(
            ThinkingStep(
                type = "send_message",
                name = "发送进度消息",
                status = "done",
                detail = content.take(120)
            )
        )
        emit("已发送进度消息")
    }

    override fun onSendFile(ctx: PipelineContext, filePath: String, filename: String) {
        steps.add(
            ThinkingStep(
                type = "file",
                name = "准备文件: $filename",
                status = "done",
                detail = filePath.take(120)
            )
        )
        emit("文件处理完成")
    }

    override fun onAttachmentStart(ctx: PipelineContext, count: Int) {
        steps.add(
            ThinkingStep(
                type = "upload",
                name = "正在处理 $count 个附件...",
                status = "running"
            )
        )
        emit("正在处理附件...")
    }

    override fun onAttachmentsDone(ctx: PipelineContext) {
        steps.indexOfLast { it.type == "upload" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(status = "done")
        }
        emit("附件处理完成")
    }

    override fun onKnowledgeStart(ctx: PipelineContext) {
        steps.add(
            ThinkingStep(
                type = "knowledge",
                name = "正在检索知识库...",
                status = "running"
            )
        )
        emit("正在检索知识库...")
    }

    override fun onKnowledgeDone(ctx: PipelineContext, retrieved: Boolean) {
        steps.indexOfLast { it.type == "knowledge" }.takeIf { it >= 0 }?.let { index ->
            steps[index] = steps[index].copy(
                status = "done",
                detail = if (retrieved) "命中相关条目" else "未命中"
            )
        }
        emit("知识库检索完成")
    }

    override fun onDone(ctx: PipelineContext) {
        for (index in steps.indices) {
            val step = steps[index]
            if (step.status == "running" || step.status == "active") {
                steps[index] = step.copy(status = "done")
            }
        }
        steps.add(ThinkingStep(type = "done", name = "处理完成", status = "done"))
        emit("处理完成", isComplete = true)
    }
}
