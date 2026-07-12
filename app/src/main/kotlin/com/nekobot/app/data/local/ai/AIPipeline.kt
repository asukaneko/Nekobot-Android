package com.nekobot.app.data.local.ai

import java.time.Instant
import java.util.UUID

/**
 * 统一 AI 处理管道，对应原仓库 nbot/core/ai_pipeline.py:AIPipeline。
 *
 * 所有频道的 AI 请求经过此管道处理，提供：
 * - 附件解析
 * - 知识库检索（RAG）
 * - 工具调用循环
 * - 流式输出
 * - 角色运行时编排（before_turn / after_turn）
 * - 进度报告
 * - 模型故障转移
 *
 * 7 个处理阶段：
 * 1. 附件解析
 * 2. 知识库检索
 * 3. 上下文准备（消息历史 + PromptStack 合成 + 角色运行时 before_turn）
 * 4. AI 响应（工具循环 / 直接补全 / 流式）
 * 5. 结果组装（角色运行时 after_turn + 自动记忆）
 * 6. (跳过) 剧情选项生成
 * 7. (跳过) 群聊旁白生成
 */
class AIPipeline {

    companion object {
        private const val TAG = "AIPipeline"

        /** 文本 MIME 类型集合 */
        private val TEXT_MIME_TYPES = setOf(
            "text/plain", "text/markdown", "text/csv", "text/html",
            "text/css", "text/javascript", "text/xml", "application/json",
            "application/xml", "application/javascript"
        )

        /** 文本扩展名集合 */
        private val TEXT_EXTENSIONS = setOf(
            ".txt", ".md", ".csv", ".json", ".xml", ".html", ".css", ".js",
            ".py", ".sh", ".java", ".go", ".rs", ".ts", ".yaml", ".yml",
            ".toml", ".ini", ".cfg", ".conf", ".log", ".sql", ".kt"
        )

        /** 文档 MIME 类型集合 */
        private val DOCUMENT_MIME_TYPES = setOf(
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
    }

    /**
     * 执行完整的 AI 处理管道。
     *
     * @param ctx 管道上下文（含 ChatRequest 和 adapter）
     * @param callbacks 频道回调实现
     * @param tools 可用工具定义列表（空列表表示不启用工具）
     * @param maxToolIterations 工具循环最大迭代次数
     * @param maxContextChars 上下文最大字符数
     * @return PipelineResult 可转为 ChatResponse
     */
    suspend fun process(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        tools: List<Map<String, Any>> = emptyList(),
        maxToolIterations: Int = 50,
        maxContextChars: Int = 100000
    ): PipelineResult {
        val startTime = System.nanoTime()
        ctx.metadata["_pipeline_start_time"] = startTime
        com.nekobot.app.data.local.LocalLogger.i(TAG, "Pipeline 开始 | 会话=${ctx.chatRequest.conversationId} | 用户消息=${ctx.chatRequest.content.take(80)}")

        val progress = callbacks.getProgressReporter(ctx)

        // Phase 1: 附件解析
        phaseAttachments(ctx, callbacks, progress)

        // Phase 2: 知识库检索
        phaseKnowledge(ctx, callbacks, progress)

        // Phase 3: 上下文准备
        phasePrepareContext(ctx, callbacks, tools, maxContextChars)

        // Phase 4: AI 响应
        phaseAiResponse(ctx, callbacks, tools, maxToolIterations, progress)

        // Phase 5: 结果组装
        val result = phaseAssembleResult(ctx, callbacks)

        // 计算耗时
        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
        val resultMeta = result.metadata.toMutableMap()
        resultMeta["duration_ms"] = durationMs
        if (!resultMeta.containsKey("ttft_ms")) resultMeta["ttft_ms"] = durationMs

        val modelInfo = resultMeta["model_name"]?.let { " | 模型=$it" } ?: ""
        val usageInfo = result.usage.takeIf { it.isNotEmpty() }?.let { " | usage=$it" } ?: ""
        com.nekobot.app.data.local.LocalLogger.i(TAG, "Pipeline 完成 | 耗时=${"%.0f".format(durationMs)}ms$modelInfo$usageInfo | 回复长度=${result.finalContent.length}")

        return result.copy(metadata = resultMeta)
    }

    // ------------------------------------------------------------------
    // Phase 1: 附件解析
    // ------------------------------------------------------------------

    private fun phaseAttachments(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        progress: ProgressReporter
    ) {
        val attachments = ctx.chatRequest.attachments
        if (attachments.isEmpty()) return

        progress.onAttachmentStart(ctx, attachments.size)

        for (att in attachments) {
            val attType = (att["type"] as? String ?: "").lowercase()
            val attName = (att["name"] as? String) ?: (att["filename"] as? String) ?: ""
            val resolved = callbacks.resolveAttachmentData(ctx, att)

            when {
                attType.startsWith("image/") || looksLikeImage(att) ->
                    handleImageAttachment(ctx, progress, att, resolved)
                isTextType(attType, attName) ->
                    handleTextAttachment(ctx, progress, att, resolved)
                isDocumentType(attType, attName) ->
                    handleDocumentAttachment(ctx, progress, att, resolved)
            }
        }

        progress.onAttachmentsDone(ctx)
    }

    private fun handleImageAttachment(
        ctx: PipelineContext,
        progress: ProgressReporter,
        att: Map<String, Any>,
        resolved: Map<String, Any>?
    ) {
        val name = (att["name"] as? String) ?: (att["filename"] as? String) ?: "image"
        progress.onAttachmentItem(ctx, name, "image")

        val data = resolved?.get("data") as? String
        val path = resolved?.get("path") as? String
        val url = att["url"] as? String ?: att["path"] as? String

        when {
            !data.isNullOrEmpty() -> {
                ctx.imageUrls.add(data)
                progress.onAttachmentItemDone(ctx, name, true)
            }
            !path.isNullOrEmpty() -> {
                ctx.imageUrls.add(path)
                progress.onAttachmentItemDone(ctx, name, true)
            }
            !url.isNullOrEmpty() -> {
                ctx.imageUrls.add(url)
                progress.onAttachmentItemDone(ctx, name, true)
            }
            else -> progress.onAttachmentItemDone(ctx, name, false, "无法解析图片")
        }
    }

    private fun handleTextAttachment(
        ctx: PipelineContext,
        progress: ProgressReporter,
        att: Map<String, Any>,
        resolved: Map<String, Any>?
    ) {
        val name = (att["name"] as? String) ?: (att["filename"] as? String) ?: "file"
        progress.onAttachmentItem(ctx, name, "file")

        val content = (resolved?.get("text_content") as? String) ?: (resolved?.get("data") as? String)
        if (!content.isNullOrEmpty()) {
            ctx.fileContents.add("【文件 $name 内容】:\n${content.take(10000)}")
            val preview = content.take(200).replace("\n", " ")
            progress.onAttachmentItemDone(ctx, name, true, preview)
        } else {
            progress.onAttachmentItemDone(ctx, name, false, "无法读取文件内容")
        }
    }

    private fun handleDocumentAttachment(
        ctx: PipelineContext,
        progress: ProgressReporter,
        att: Map<String, Any>,
        resolved: Map<String, Any>?
    ) {
        val name = (att["name"] as? String) ?: (att["filename"] as? String) ?: "document"
        progress.onAttachmentItem(ctx, name, "document")
        // 本地模式简化：仅记录文档名
        progress.onAttachmentItemDone(ctx, name, true, "文档已记录（未提取文本）")
    }

    // ------------------------------------------------------------------
    // Phase 2: 知识库检索
    // ------------------------------------------------------------------

    private fun phaseKnowledge(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        progress: ProgressReporter
    ) {
        progress.onKnowledgeStart(ctx)
        ctx.knowledgeText = callbacks.searchKnowledge(ctx, ctx.chatRequest.content)
        ctx.knowledgeRetrieved = ctx.knowledgeText.isNotEmpty()
        progress.onKnowledgeDone(ctx, ctx.knowledgeRetrieved)
    }

    // ------------------------------------------------------------------
    // Phase 3: 上下文准备
    // ------------------------------------------------------------------

    private suspend fun phasePrepareContext(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        tools: List<Map<String, Any>>,
        maxContextChars: Int
    ) {
        // 加载消息历史
        val messagesRaw = callbacks.loadMessages(ctx)
        var messagesForAi = messagesRaw.map { it.toMap() }

        // 追加当前用户消息（如果 loadMessages 未包含）
        val userContent = ctx.chatRequest.content
        val lastMsg = messagesForAi.lastOrNull()
        if (lastMsg == null || lastMsg["role"] != "user" || lastMsg["content"] != userContent) {
            messagesForAi = messagesForAi + mapOf("role" to "user", "content" to userContent)
        }

        // 注入附件内容到最后一条 user 消息
        if (ctx.fileContents.isNotEmpty()) {
            val enhancedContent = userContent + "\n\n" + ctx.fileContents.joinToString("\n\n")
            messagesForAi = messagesForAi.mapIndexed { idx, msg ->
                if (idx == messagesForAi.lastIndex && msg["role"] == "user") {
                    msg.toMutableMap().apply { put("content", enhancedContent) }
                } else msg
            }
        }

        // 图片 URL 注入提示
        if (ctx.imageUrls.isNotEmpty()) {
            messagesForAi = messagesForAi.mapIndexed { idx, msg ->
                if (idx == messagesForAi.lastIndex && msg["role"] == "user") {
                    msg.toMutableMap().apply {
                        put("content", "[附图片 ${ctx.imageUrls.size} 张，已通过视觉模型识别]\n${msg["content"] ?: ""}")
                    }
                } else msg
            }
        }

        // 分离原有 system prompt 和历史消息
        val (basePrompt, historyMessages) = PromptStack.splitSystemPrompt(messagesForAi)

        // 知识库注入 → PromptStack
        if (ctx.knowledgeText.isNotEmpty()) {
            ctx.promptStack.add("knowledge.rag", ctx.knowledgeText, priority = PromptStack.Priority.KNOWLEDGE_RAG)
        }

        // 角色运行时 before_turn
        phaseCharacterRuntimeBeforeTurn(ctx, callbacks)

        // 使用角色运行时编译的提示词作为 basePrompt（包含角色卡 systemPrompt / 基本信息 / 性格 / 状态 / 关系 / 记忆 / 世界书）
        val characterBasePrompt = ctx.characterTurn?.promptText ?: ""

        // 应用会话级提示词栈禁用列表（对管线栈 knowledge.rag / custom:* 生效）
        @Suppress("UNCHECKED_CAST")
        val disabledKeys = (ctx.metadata["disabled_prompt_keys"] as? List<String>) ?: emptyList()
        if (disabledKeys.isNotEmpty()) {
            ctx.promptStack.disableKeys(disabledKeys)
        }

        // 注入用户自定义提示词（从会话数据中读取）
        @Suppress("UNCHECKED_CAST")
        val customPrompts = (ctx.metadata["custom_prompts"] as? List<Map<String, Any>>) ?: emptyList()
        if (customPrompts.isNotEmpty()) {
            val sorted = customPrompts.sortedBy { (it["order"] as? Number)?.toInt() ?: 0 }
            for (cp in sorted) {
                val content = ((cp["content"] as? String) ?: "").trim()
                if (content.isBlank()) continue
                val order = (cp["order"] as? Number)?.toInt() ?: 0
                val title = (cp["title"] as? String)?.trim() ?: ""
                val key = if (title.isNotEmpty()) "custom:$order:$title" else "custom:$order"
                ctx.promptStack.add(key, content, priority = 35, scope = "session")
            }
        }

        // PromptStack 合成最终 system prompt
        // characterBasePrompt（角色运行时 promptText）已包含角色卡基础信息 + character.* 注入项，
        // 管线栈额外包含 knowledge.rag / custom:* 项。
        // 不能用 ctx.promptStack.render(characterBasePrompt)，因为 render 内部的 stripDynamicPromptSections
        // 会剥离 characterBasePrompt 中的 character.* 动态段，导致丢失角色运行时注入内容。
        // 改为直接拼接 characterBasePrompt + 管线栈额外 items。
        val pipelineExtraItems = ctx.promptStack.getItems()
            .filter { it.enabled && it.content.isNotBlank() }
            .sortedBy { it.priority }
        val composedSystem = if (pipelineExtraItems.isEmpty()) {
            characterBasePrompt.ifBlank { basePrompt }
        } else {
            val extraParts = pipelineExtraItems.joinToString("\n\n") { "## ${it.key}\n${it.content.trim()}" }
            (characterBasePrompt.ifBlank { basePrompt }).trim() + "\n\n" + extraParts
        }
        ctx.metadata["composed_system_prompt"] = composedSystem

        // 合并提示词栈调试信息：管线栈 + 角色运行时栈（character.* 注入项）
        // 角色运行时在 beforeTurn 内部使用独立的 PromptStack 实例注册 character.* 注入，
        // 这里将其 items 合并到管线栈 debug 输出，供会话详情页展示完整栈。
        val pipelineDebug = ctx.promptStack.renderDebug().toMutableList()
        val characterItems = ctx.characterTurn?.promptStackItems ?: emptyList()
        val pipelineKeys = pipelineDebug.mapNotNull { it["key"] as? String }.toSet()
        for (item in characterItems) {
            if (item.key !in pipelineKeys) {
                pipelineDebug.add(mapOf(
                    "key" to item.key,
                    "content" to (if (item.content.length > 200) item.content.take(200) + "..." else item.content),
                    "priority" to item.priority,
                    "role" to item.role,
                    "scope" to item.scope,
                    "enabled" to item.enabled
                ))
            }
        }
        ctx.metadata["prompt_stack_debug"] = pipelineDebug.sortedBy { (it["priority"] as? Int) ?: 100 }

        com.nekobot.app.data.local.LocalLogger.i(TAG, "上下文准备完成 | system prompt=${composedSystem.length}字符 | 提示词栈=${pipelineDebug.size}项 | 历史=${historyMessages.size}条")

        messagesForAi = listOf(mapOf("role" to "system", "content" to composedSystem)) + historyMessages

        // 裁剪上下文
        val prepared = prepareChatContext(
            messagesForAi,
            userContent,
            knowledgeText = "",
            maxTotalChars = maxContextChars
        )
        ctx.messages = prepared.messages
        ctx.toolCallHistory = prepared.toolCallHistory
    }

    // ------------------------------------------------------------------
    // 角色运行时 before_turn
    // ------------------------------------------------------------------

    private suspend fun phaseCharacterRuntimeBeforeTurn(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks
    ) {
        val runtime = callbacks.getCharacterRuntime(ctx) ?: return
        val identity = callbacks.getCharacterContext(ctx) ?: return

        try {
            // 加载最近消息用于世界书多源召回
            val recentMessages = try {
                callbacks.loadMessages(ctx).mapNotNull { (it["content"] as? String) }
            } catch (e: Exception) {
                emptyList()
            }

            val turn = runtime.beforeTurn(ctx.chatRequest, identity, recentMessages)
            ctx.characterTurn = turn

            // 角色运行时已在 beforeTurn 内部调用 buildCharacterInjections 注册了 PromptStack 注入项
            // 这里无需重复注册
            com.nekobot.app.data.local.LocalLogger.i(TAG, "CharacterRuntime before_turn 完成 | 角色=${turn.profile.name} | 心情=${turn.state.mood} | 注入项=${turn.promptStackItems.size}个 | promptText=${turn.promptText.length}字符")
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "CharacterRuntime before_turn 异常: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // Phase 4: AI 响应
    // ------------------------------------------------------------------

    private suspend fun phaseAiResponse(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        tools: List<Map<String, Any>>,
        maxToolIterations: Int,
        progress: ProgressReporter
    ) {
        progress.onThinkingStart(ctx)

        // 尝试流式（无工具时）
        if (tools.isEmpty()) {
            val streamer = callbacks.buildModelCallStreaming(ctx, tools)
            if (streamer != null) {
                runStreaming(ctx, callbacks, streamer, progress)
                progress.onDone(ctx)
                return
            }
        }

        // 尝试工具循环
        if (tools.isNotEmpty()) {
            runToolLoop(ctx, callbacks, tools, maxToolIterations, progress)
            progress.onDone(ctx)
            return
        }

        // 简单路径：单次模型调用
        runSimple(ctx, callbacks)
        progress.onDone(ctx)
    }

    /** 简单的单次模型调用（无工具、无流式） */
    private fun runSimple(ctx: PipelineContext, callbacks: PipelineCallbacks) {
        val modelCall = callbacks.buildModelCall(ctx, emptyList())
        try {
            val response = modelCall(ctx.messages, ctx.stopped)
            // 提取模型追踪信息
            extractModelTrace(ctx, response)
            ctx.finalContent = (response["content"] as? String) ?: ""
            @Suppress("UNCHECKED_CAST")
            ctx.usage = (response["usage"] as? Map<String, Any>) ?: emptyMap()
            com.nekobot.app.data.local.LocalLogger.i(TAG, "模型调用完成(simple) | 回复=${ctx.finalContent.length}字符")
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.e(TAG, "Simple model call failed: ${e.message}", e)
            ctx.error = e.message ?: "AI 调用失败"
            ctx.finalContent = "AI 调用失败: ${e.message}"
        }
    }

    /** 运行工具调用循环 */
    private fun runToolLoop(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        tools: List<Map<String, Any>>,
        maxToolIterations: Int,
        progress: ProgressReporter
    ) {
        val modelCall = callbacks.buildModelCall(ctx, tools)
        ctx.toolContext = callbacks.getWorkspaceContext(ctx)

        // 工具执行器
        val toolExecutor: (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any> = { toolCall, thinking, iteration, messages ->
            val name = (toolCall["name"] as? String) ?: ""
            @Suppress("UNCHECKED_CAST")
            var args = (toolCall["arguments"] as? Map<String, Any>) ?: emptyMap()
            // arguments 可能是 JSON 字符串
            if (args.isEmpty() && toolCall["arguments"] is String) {
                args = parseJsonArgs(toolCall["arguments"] as String)
            }

            val result = callbacks.executeTool(name, args, ctx.toolContext)

            // 处理确认请求
            if (result["require_confirmation"] == true) {
                val requestId = (result["request_id"] as? String) ?: ""
                val command = (result["command"] as? String) ?: ""
                callbacks.onConfirmationRequired(ctx, requestId, command)
                progress.onWaitingConfirmation(ctx, command, requestId)
                throw ToolLoopExit(
                    (result["message"] as? String) ?: "⚠️ 命令需要确认: $command\n[请求ID: $requestId]\n请回复「确认」执行，或「取消」放弃。"
                )
            }
            result
        }

        // 钩子
        val hooks = ToolLoopHooks(
            onIterationStart = { iteration, _ -> progress.onToolIteration(ctx, iteration) },
            onToolStart = { toolCall, thinking, _, _ ->
                val name = (toolCall["name"] as? String) ?: ""
                @Suppress("UNCHECKED_CAST")
                val args = (toolCall["arguments"] as? Map<String, Any>) ?: emptyMap()
                progress.onToolStart(ctx, name, args, thinking)
            },
            onToolResult = { toolCall, result, thinking, _, _ ->
                val name = (toolCall["name"] as? String) ?: ""
                progress.onToolDone(ctx, name, result, thinking)
                // 处理特殊工具结果
                (result["_send_message"] as? String)?.let { progress.onSendMessage(ctx, it) }
                val filePath = result["_file_path"] as? String
                val fileName = result["_file_name"] as? String
                if (filePath != null && fileName != null) {
                    progress.onSendFile(ctx, filePath, fileName)
                }
                null  // 使用默认 tool message 格式
            }
        )

        val session = ToolLoopSession(
            initialMessages = ctx.messages,
            modelCall = modelCall,
            toolExecutor = toolExecutor,
            toolCallHistory = ctx.toolCallHistory,
            maxIterations = maxToolIterations,
            hooks = hooks
        )

        try {
            val executionResult = runToolLoopSession(session)
            val loopResult = executionResult.loopResult

            @Suppress("UNCHECKED_CAST")
            ctx.usage = (loopResult.usage as? Map<String, Any>) ?: emptyMap()

            // 提取模型追踪信息
            if (loopResult.modelId.isNotEmpty()) ctx.metadata["model_id"] = loopResult.modelId
            if (loopResult.modelName.isNotEmpty()) ctx.metadata["model_name"] = loopResult.modelName
            if (loopResult.failoverEvents.isNotEmpty()) ctx.metadata["failover_events"] = loopResult.failoverEvents

            if (loopResult.stopped) {
                ctx.stoppedPrematurely = true
                ctx.toolTrace = extractToolCallHistory(loopResult.toolMessages)
                ctx.finalContent = "【生成已停止 - 工具调用记录已保存，回复「继续」可继续执行】"
                return
            }

            ctx.finalContent = resolveLoopFinalContent(loopResult)
        } catch (e: ToolLoopModelError) {
            val errorStr = e.message ?: "工具循环执行失败"
            com.nekobot.app.data.local.LocalLogger.e(TAG, "Tool loop failed: $errorStr", e)

            // 首次模型调用失败（iteration==0）时回退到无工具对话
            if (e.iteration <= 0 && "400" in errorStr) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "首次模型调用返回400错误，回退到无工具对话")
                progress.onThinkingStart(ctx)
                runSimple(ctx, callbacks)
                return
            }
            ctx.error = errorStr
            ctx.finalContent = "工具循环执行失败: $errorStr"
        } catch (e: Exception) {
            val errorStr = e.message ?: "工具循环执行失败"
            com.nekobot.app.data.local.LocalLogger.e(TAG, "Tool loop failed: $errorStr", e)
            ctx.error = errorStr
            ctx.finalContent = "工具循环执行失败: $errorStr"
        }
    }

    /** 运行流式模型调用 */
    private fun runStreaming(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        streamer: StreamModelCall,
        progress: ProgressReporter
    ) {
        val messageId = UUID.randomUUID().toString()
        val fullContent = StringBuilder()
        val streamStart = System.nanoTime()
        var ttftMs: Double? = null

        try {
            val events = streamer(ctx.messages, ctx.stopped)
            for (event in events) {
                if (ctx.stopped) break

                // 提取模型追踪信息
                extractModelTrace(ctx, event)

                @Suppress("UNCHECKED_CAST")
                val usage = (event["usage"] as? Map<String, Any>)
                if (usage != null) ctx.usage = usage

                val chunk = (event["content"] as? String) ?: ""
                if (chunk.isEmpty()) continue

                if (fullContent.isEmpty()) {
                    // 首块：记录 TTFT
                    ttftMs = (System.nanoTime() - streamStart) / 1_000_000.0
                    val msg = mapOf("role" to "assistant", "content" to "", "id" to messageId)
                    callbacks.onStreamStart(ctx, msg)
                    ctx.streamedMessage = msg
                }

                fullContent.append(chunk)
                callbacks.onStreamChunk(ctx, chunk, messageId)
            }
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.e(TAG, "Streaming failed: ${e.message}", e)
            ctx.error = e.message ?: "流式输出失败"
            if (fullContent.isEmpty()) {
                fullContent.append("流式输出失败: ${e.message}")
            }
        }

        ctx.finalContent = fullContent.toString()
        val durationMs = (System.nanoTime() - streamStart) / 1_000_000.0
        ctx.metadata["duration_ms"] = durationMs
        ctx.metadata["ttft_ms"] = ttftMs ?: durationMs
        com.nekobot.app.data.local.LocalLogger.i(TAG, "流式输出完成 | TTFT=${ttftMs?.let { "%.0f".format(it) } ?: "?"}ms | 总耗时=${"%.0f".format(durationMs)}ms | 内容=${fullContent.length}字符")

        // 流式在首块到达前就失败（如 400），需要创建消息
        if (ctx.finalContent.isNotEmpty() && ctx.streamedMessage == null) {
            val msg = mapOf("role" to "assistant", "content" to "", "id" to messageId)
            callbacks.onStreamStart(ctx, msg)
            callbacks.onStreamChunk(ctx, ctx.finalContent, messageId)
            ctx.streamedMessage = msg
        }

        if (ctx.streamedMessage != null) {
            ctx.metadata["streamed"] = true
            ctx.metadata["stream_end_pending"] = true
            ctx.metadata["stream_message_id"] = messageId
        }
    }

    // ------------------------------------------------------------------
    // Phase 5: 结果组装
    // ------------------------------------------------------------------

    private suspend fun phaseAssembleResult(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks
    ): PipelineResult {
        // 流式消息处理
        if (ctx.metadata["streamed"] == true && ctx.streamedMessage != null) {
            @Suppress("UNCHECKED_CAST")
            val streamedMsg = (ctx.streamedMessage as Map<String, Any>).toMutableMap()
            streamedMsg["content"] = ctx.finalContent
            ctx.streamedMessage = streamedMsg

            callbacks.saveAssistantMessage(ctx, streamedMsg)

            val result = PipelineResult(
                finalContent = ctx.finalContent,
                assistantMessage = streamedMsg,
                toolTrace = ctx.toolTrace,
                canContinue = ctx.toolTrace.isNotEmpty(),
                stoppedPrematurely = ctx.stoppedPrematurely,
                usage = ctx.usage,
                error = ctx.error,
                metadata = ctx.metadata.toMap()
            )

            if (ctx.metadata.remove("stream_end_pending") == true) {
                val msgId = (ctx.metadata["stream_message_id"] as? String) ?: (streamedMsg["id"] as? String) ?: ""
                callbacks.onStreamEnd(ctx, msgId)
            }

            postProcessResult(ctx, callbacks, result)
            return result
        }

        // 错误处理
        if (ctx.error != null) {
            val errorContent = ctx.finalContent.ifEmpty { ctx.error ?: "未知错误" }
            val assistantMessage = mapOf(
                "role" to "assistant",
                "content" to errorContent,
                "error" to true,
                "id" to UUID.randomUUID().toString(),
                "timestamp" to Instant.now().toString()
            )
            callbacks.saveAssistantMessage(ctx, assistantMessage)
            callbacks.sendResponse(ctx, assistantMessage)

            val result = PipelineResult(
                finalContent = errorContent,
                assistantMessage = assistantMessage,
                error = ctx.error,
                metadata = ctx.metadata.toMap()
            )
            postProcessResult(ctx, callbacks, result)
            return result
        }

        // 非流式：构建 assistant_message
        val assistantMessage = mutableMapOf<String, Any>(
            "id" to UUID.randomUUID().toString(),
            "role" to "assistant",
            "content" to ctx.finalContent,
            "timestamp" to Instant.now().toString(),
            "sender" to "AI"
        )

        // 添加工具调用历史（用于「继续」功能）
        if (ctx.toolTrace.isNotEmpty()) {
            assistantMessage["tool_call_history"] = ctx.toolTrace
            assistantMessage["can_continue"] = true
        }

        // 保存历史
        callbacks.saveAssistantMessage(ctx, assistantMessage)

        // 发送回复
        callbacks.sendResponse(ctx, assistantMessage)

        val result = PipelineResult(
            finalContent = ctx.finalContent,
            assistantMessage = assistantMessage,
            toolTrace = ctx.toolTrace,
            canContinue = ctx.toolTrace.isNotEmpty(),
            stoppedPrematurely = ctx.stoppedPrematurely,
            usage = ctx.usage,
            error = ctx.error,
            metadata = ctx.metadata.toMap()
        )
        postProcessResult(ctx, callbacks, result)
        return result
    }

    /** 后处理：角色运行时 after_turn → 对话审查 → on_response_complete */
    private suspend fun postProcessResult(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        result: PipelineResult
    ) {
        // 角色运行时 after_turn
        phaseCharacterRuntimeAfterTurn(ctx, callbacks, result)

        // 对话后审查（ReviewPipeline）
        phaseReview(ctx, callbacks, result)

        // on_response_complete
        callbacks.onResponseComplete(ctx, result)
    }

    /**
     * Phase 5b: 对话后审查。
     *
     * 使用 RuleReview 对本轮对话进行规则审查，产出评分/记忆/关系增量/剧情更新。
     * 审查结果存入 ctx.metadata 供后续流程使用，不影响主流程。
     */
    private suspend fun phaseReview(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        result: PipelineResult
    ) {
        val identity = callbacks.getCharacterContext(ctx) ?: return
        val turn = ctx.characterTurn ?: return

        try {
            val reviewInput = ReviewInput(
                conversationId = ctx.chatRequest.conversationId,
                characterId = identity.characterId,
                userId = ctx.chatRequest.userId ?: "",
                userMessage = ctx.chatRequest.content,
                assistantMessage = result.finalContent,
                relationshipState = turn.relationship,
                characterState = turn.state,
                plotMode = (ctx.metadata["plot_mode"] as? Boolean) ?: false
            )
            val reviewOutput = getGlobalReviewPipeline().run(reviewInput)
            ctx.metadata["review_output"] = mapOf(
                "scores" to reviewOutput.scores,
                "memory_items" to reviewOutput.memoryItems,
                "relationship_delta" to reviewOutput.relationshipDelta,
                "skipped" to reviewOutput.skipped
            )

            // 关系增量回写（对应原仓库 review pipeline 行为）。
            // 合并顺序：StateMachine → AutoState → review 增量，此处为最后一环。
            if (!reviewOutput.skipped) {
                val d = reviewOutput.relationshipDelta
                val runtime = callbacks.getCharacterRuntime(ctx)
                if (runtime != null) {
                    val applied = runtime.applyRelationshipDelta(
                        characterId = identity.characterId,
                        targetId = identity.targetId.ifEmpty { ctx.chatRequest.userId ?: "local-user" },
                        deltas = mapOf(
                            "affection" to d.affection,
                            "trust" to d.trust,
                            "familiarity" to d.familiarity,
                            "dependency" to d.dependency,
                            "security" to d.security,
                            "jealousy" to d.jealousy
                        )
                    )
                    if (applied) com.nekobot.app.data.local.LocalLogger.i(TAG, "审查关系增量已回写: $d")
                }
            }
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "ReviewPipeline 审查异常: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // 角色运行时 after_turn
    // ------------------------------------------------------------------

    private suspend fun phaseCharacterRuntimeAfterTurn(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        result: PipelineResult
    ) {
        val runtime = callbacks.getCharacterRuntime(ctx) ?: return
        val identity = callbacks.getCharacterContext(ctx) ?: return
        val turn = ctx.characterTurn ?: return

        try {
            runtime.afterTurn(ctx.chatRequest, result.finalContent, turn)
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "CharacterRuntime after_turn 异常: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 从模型响应中提取追踪信息（model_id / model_name / failover_events） */
    private fun extractModelTrace(ctx: PipelineContext, response: Map<String, Any>) {
        (response["_model_id"] as? String)?.let { ctx.metadata["model_id"] = it }
        (response["_model_name"] as? String)?.let { ctx.metadata["model_name"] = it }
        @Suppress("UNCHECKED_CAST")
        (response["_failover_events"] as? List<Map<String, Any>>)?.let { ctx.metadata["failover_events"] = it }
    }

    /** 判断附件是否像图片 */
    private fun looksLikeImage(att: Map<String, Any>): Boolean {
        val attType = (att["type"] as? String ?: "").lowercase()
        val attName = (att["name"] as? String ?: (att["filename"] as? String) ?: "").lowercase()
        if (attType.startsWith("image/")) return true
        val imageExt = setOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg")
        val ext = if ("." in attName) "." + attName.substringAfterLast(".") else ""
        return ext in imageExt
    }

    /** 判断附件是否为文本类型 */
    private fun isTextType(attType: String, attName: String): Boolean {
        if (attType in TEXT_MIME_TYPES) return true
        val ext = if ("." in attName) "." + attName.substringAfterLast(".").lowercase() else ""
        return ext in TEXT_EXTENSIONS
    }

    /** 判断附件是否为文档类型 */
    private fun isDocumentType(attType: String, attName: String): Boolean {
        if (attType in DOCUMENT_MIME_TYPES) return true
        val docExt = setOf(".pdf", ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt")
        val ext = if ("." in attName) "." + attName.substringAfterLast(".").lowercase() else ""
        return ext in docExt
    }

    /** 解析 JSON 字符串参数 */
    private fun parseJsonArgs(argsStr: String): Map<String, Any> {
        if (argsStr.isBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            com.google.gson.Gson().fromJson(argsStr, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

// ============================================================================
// 全局单例
// ============================================================================

/** 全局 AIPipeline 单例 */
val aiPipeline = AIPipeline()
