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
        maxToolIterations: Int = 150,
        maxContextChars: Int = 100000,
        progressReporter: ProgressReporter? = null
    ): PipelineResult {
        val startTime = System.nanoTime()
        ctx.metadata["_pipeline_start_time"] = startTime
        com.nekobot.app.data.local.LocalLogger.i(
            TAG,
            "Pipeline 开始 | 会话=${ctx.chatRequest.conversationId} | 用户消息长度=${ctx.chatRequest.content.length}"
        )

        val progress = progressReporter ?: callbacks.getProgressReporter(ctx)

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

    /**
     * 仅准备会话上下文与 PromptStack，不触发模型生成、工具调用或消息持久化。
     *
     * Live/Reatime 在收到首段音频前尚无可用的文本输入，但仍需要以普通聊天
     * 相同的角色状态、记忆、世界书和会话提示词启动连接。
     */
    suspend fun prepareContext(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        tools: List<Map<String, Any>> = emptyList(),
        maxContextChars: Int = 100000
    ) {
        phasePrepareContext(ctx, callbacks, tools, maxContextChars)
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
                ctx.imageNames.add(name)
                progress.onAttachmentItemDone(ctx, name, true)
            }
            !path.isNullOrEmpty() -> {
                ctx.imageUrls.add(path)
                ctx.imageNames.add(name)
                progress.onAttachmentItemDone(ctx, name, true)
            }
            !url.isNullOrEmpty() -> {
                ctx.imageUrls.add(url)
                ctx.imageNames.add(name)
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

        // 图片视觉识别：调用 vision 模型获取描述，注入到用户消息
        if (ctx.imageUrls.isNotEmpty()) {
            val descriptions = try {
                callbacks.resolveImages(ctx, ctx.imageUrls.toList())
            } catch (e: Exception) {
                com.nekobot.app.data.local.LocalLogger.w(TAG, "视觉识别异常: ${e.message}", e)
                emptyList()
            }
            if (descriptions.isNotEmpty()) {
                ctx.imageDescriptions.addAll(descriptions)
                val imageBlock = buildString {
                    descriptions.forEachIndexed { idx, desc ->
                        val name = ctx.imageNames.getOrNull(idx)
                        if (!name.isNullOrBlank()) {
                            append("【图片 $name】\n")
                        } else {
                            append("【图片${idx + 1}】\n")
                        }
                        append(desc)
                        if (idx < descriptions.lastIndex) append("\n\n")
                    }
                }
                messagesForAi = messagesForAi.mapIndexed { idx, msg ->
                    if (idx == messagesForAi.lastIndex && msg["role"] == "user") {
                        msg.toMutableMap().apply {
                            val existing = (this["content"] as? String).orEmpty()
                            put("content", existing + "\n\n" + imageBlock)
                        }
                    } else msg
                }
                com.nekobot.app.data.local.LocalLogger.i(TAG, "视觉识别完成 | 图片=${ctx.imageUrls.size}张 | 描述总长=${imageBlock.length}字符")
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

        // 现实时间连续性 + 昼夜节律 + 离线剧情推进注入（对齐原仓库 _phase_real_time_prompt_fallback
        // 与 character/runtime._inject_real_time_context / _inject_circadian_state）
        // CharacterRuntime 未注入时由管线兜底注入，确保现实时间感知生效。
        phaseRealTimeAndCircadianInjection(ctx)

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

        // 注入本会话用户人设/背景（来自 SessionDetailScreen 的 userPersona 字段）
        // 作为高优先级行为约束，让 AI 按此身份理解玩家（包括玩家姓名、偏好、处境等）
        // 注意：senderName 是 AI 扮演的角色名，不是玩家名；玩家身份由 userPersona 字段承载
        val userPersona = ctx.metadata["user_persona"] as? String ?: ""
        if (userPersona.isNotBlank()) {
            ctx.promptStack.add(
                "user.persona",
                "Player persona / background (use this to interpret the player's identity, name, preferences, and circumstances; address or refer to the player by the name given in this description rather than generic terms like \"user\"):\n$userPersona",
                priority = PromptStack.Priority.BEHAVIOR,
                scope = "session"
            )
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
                    // 注入详情界面展示完整内容，不再截断（与 PromptStack.renderDebug 行为一致）
                    "content" to item.content,
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
    // 现实时间连续性 / 昼夜节律 / 离线剧情推进注入
    // ------------------------------------------------------------------

    /**
     * 注入 real_time.continuity + character.circadian + plot.real_time_sync。
     *
     * 对齐原仓库：
     * - nbot/character/runtime._inject_real_time_context / _inject_circadian_state
     * - nbot/core/ai_pipeline._phase_real_time_prompt_fallback
     *
     * Agent 模式跳过（与原仓库 mode_policy.inject_real_time_prompt_fallback 一致）。
     */
    private fun phaseRealTimeAndCircadianInjection(ctx: PipelineContext) {
        try {
            // Agent 模式不注入现实时间相关 prompt
            val sessionMode = (ctx.metadata["session_mode"] as? String).orEmpty()
            if (sessionMode.equals("agent", ignoreCase = true)) return

            // === 1. real_time.continuity ===
            // 上次互动时间取自角色运行时状态 lastActiveAt（由 CharacterRuntime 维护）
            val previousTurnTime = ctx.characterTurn?.state?.lastActiveAt?.takeIf { it.isNotBlank() }
            val realTimeContext = TimeContext.buildRealTimeContext(previousTurnTime)
            ctx.metadata["real_time_context"] = realTimeContext

            val realTimeText = TimeContext.formatRealTimePromptContext(realTimeContext)
            if (realTimeText.isNotBlank()) {
                ctx.promptStack.add(
                    "real_time.continuity",
                    realTimeText,
                    priority = PromptStack.Priority.CHARACTER_STATE + 1,
                    scope = "turn"
                )
            }

            // === 2. character.circadian ===
            val circadianState = TimeContext.buildCircadianState()
            ctx.metadata["circadian_state"] = circadianState

            val circadianText = TimeContext.formatCircadianPrompt(circadianState)
            if (circadianText.isNotBlank()) {
                ctx.promptStack.add(
                    "character.circadian",
                    circadianText,
                    priority = PromptStack.Priority.CHARACTER_STATE + 2,
                    scope = "turn"
                )
            }

            // === 3. plot.real_time_sync（剧情模式 + 同步现实时间 开启时）===
            val plotMode = (ctx.metadata["plot_mode"] as? Boolean) == true ||
                (ctx.chatRequest.metadata["plot_mode"] as? Boolean) == true
            val plotRealTimeSync = (ctx.metadata["plot_realtime_sync"] as? Boolean) == true ||
                (ctx.chatRequest.metadata["plot_realtime_sync"] as? Boolean) == true
            if (plotMode && plotRealTimeSync) {
                val timeLevel = (realTimeContext["continuity_level"] as? String).orEmpty()
                if (timeLevel in listOf("same_day_gap", "days", "long_absence")) {
                    val elapsedLabel = (realTimeContext["elapsed_label"] as? String).orEmpty()
                    val reviewInput = ReviewInput(
                        conversationId = ctx.chatRequest.conversationId,
                        characterId = ctx.characterTurn?.profile?.id ?: "",
                        userId = ctx.chatRequest.userId ?: "",
                        userMessage = ctx.chatRequest.content,
                        realTimeContext = realTimeContext,
                        plotMode = true,
                        plotRealTimeSync = true
                    )
                    val offlineUpdate = RuleReview.buildOfflinePlotUpdate(reviewInput, timeLevel, elapsedLabel)
                    if (offlineUpdate.shouldInject && offlineUpdate.promptText.isNotBlank()) {
                        ctx.promptStack.add(
                            "plot.real_time_sync",
                            offlineUpdate.promptText,
                            priority = PromptStack.Priority.REACTION_PLAN + 1,
                            scope = "turn"
                        )
                        com.nekobot.app.data.local.LocalLogger.i(
                            TAG,
                            "离线剧情推进注入 | level=$timeLevel | elapsed=$elapsedLabel | promptLen=${offlineUpdate.promptText.length}"
                        )
                    }
                }
            }

            com.nekobot.app.data.local.LocalLogger.i(
                TAG,
                "现实时间注入完成 | level=${realTimeContext["continuity_level"]} | elapsed=${realTimeContext["elapsed_label"]} | circadian=${circadianState["phase"]}"
            )
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.w(TAG, "现实时间注入异常: ${e.message}", e)
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
        if (ctx.shouldStop()) {
            markStopped(ctx)
            progress.onDone(ctx)
            return
        }
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
            if (ctx.finalReasoning.isNotBlank() && ctx.metadata["agent_reasoning_streamed"] != true) {
                progress.onThinkingContent(ctx, ctx.finalReasoning)
            }
            progress.onDone(ctx)
            return
        }

        // 简单路径：单次模型调用
        runSimple(ctx, callbacks)
        if (ctx.finalReasoning.isNotBlank() && ctx.metadata["agent_reasoning_streamed"] != true) {
            progress.onThinkingContent(ctx, ctx.finalReasoning)
        }
        progress.onDone(ctx)
    }

    /** 简单的单次模型调用（无工具、无流式） */
    private fun runSimple(ctx: PipelineContext, callbacks: PipelineCallbacks) {
        if (ctx.shouldStop()) {
            markStopped(ctx)
            return
        }
        val modelCall = callbacks.buildModelCall(ctx, emptyList())
        try {
            val response = modelCall(ctx.messages, ctx.shouldStop())
            if (ctx.shouldStop()) {
                markStopped(ctx)
                return
            }
            // 提取模型追踪信息
            extractModelTrace(ctx, response)
            ctx.finalContent = (response["content"] as? String) ?: ""
            ctx.finalReasoning = (response["reasoning_content"] as? String)
                ?: (response["thinking_content"] as? String)
                ?: ""
            @Suppress("UNCHECKED_CAST")
            ctx.usage = (response["usage"] as? Map<String, Any>) ?: emptyMap()
            com.nekobot.app.data.local.LocalLogger.i(TAG, "模型调用完成(simple) | 回复=${ctx.finalContent.length}字符")
        } catch (e: Exception) {
            if (ctx.shouldStop()) {
                markStopped(ctx)
                return
            }
            com.nekobot.app.data.local.LocalLogger.e(TAG, "Simple model call failed: ${e.message}", e)
            ctx.error = e.message ?: "AI 调用失败"
            ctx.finalContent = "AI 调用失败: ${e.message}"
        }
    }

    /** 运行工具调用循环 */
    private suspend fun runToolLoop(
        ctx: PipelineContext,
        callbacks: PipelineCallbacks,
        tools: List<Map<String, Any>>,
        maxToolIterations: Int,
        progress: ProgressReporter
    ) {
        val modelCall = callbacks.buildModelCall(ctx, tools)
        ctx.toolContext = callbacks.getWorkspaceContext(ctx)
        val preparedMessageCount = ctx.messages.size + ctx.toolCallHistory.orEmpty().size

        // 工具执行器
        val toolExecutor: suspend (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any> = { toolCall, thinking, iteration, messages ->
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
            onIterationStart = { iteration, messages ->
                progress.onToolIteration(ctx, iteration)
                val safeHistory = extractCurrentTurnToolCallHistory(messages, preparedMessageCount)
                ctx.toolTrace = safeHistory
                callbacks.saveAgentCheckpoint(
                    ctx = ctx,
                    toolCallHistory = safeHistory,
                    stage = AgentRunStage.THINKING,
                    lastToolName = lastCompletedAgentToolName(safeHistory)
                )
            },
            onToolStart = { toolCall, thinking, _, _ ->
                val name = (toolCall["name"] as? String) ?: ""
                @Suppress("UNCHECKED_CAST")
                val args = (toolCall["arguments"] as? Map<String, Any>) ?: emptyMap()
                progress.onToolStart(ctx, name, args, thinking)
                callbacks.markAgentToolRunning(ctx, name)
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
                    // 工具结果本身不会自动进入助手正文；保存工作区相对路径，
                    // 由结果组装阶段补成 [File: ...]，这样 UI 才能渲染文件卡片。
                    val fileReference = (result["path"] as? String)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: fileName
                    ctx.sentFileReferences += fileReference
                }
                null  // 使用默认 tool message 格式
            },
            onCheckpoint = { _, messages ->
                val safeHistory = extractCurrentTurnToolCallHistory(messages, preparedMessageCount)
                ctx.toolTrace = safeHistory
                callbacks.saveAgentCheckpoint(
                    ctx = ctx,
                    toolCallHistory = safeHistory,
                    stage = AgentRunStage.THINKING,
                    lastToolName = lastCompletedAgentToolName(safeHistory)
                )
            }
        )

        val session = ToolLoopSession(
            initialMessages = ctx.messages,
            modelCall = modelCall,
            toolExecutor = toolExecutor,
            toolCallHistory = ctx.toolCallHistory,
            maxIterations = maxToolIterations,
            hooks = hooks,
            shouldStop = ctx::shouldStop
        )

        try {
            val executionResult = runToolLoopSession(session)
            val loopResult = executionResult.loopResult
            ctx.finalReasoning = loopResult.finalReasoning

            @Suppress("UNCHECKED_CAST")
            ctx.usage = (loopResult.usage as? Map<String, Any>) ?: emptyMap()

            // 提取模型追踪信息
            if (loopResult.modelId.isNotEmpty()) ctx.metadata["model_id"] = loopResult.modelId
            if (loopResult.modelName.isNotEmpty()) ctx.metadata["model_name"] = loopResult.modelName
            if (loopResult.modelActualName.isNotEmpty()) ctx.metadata["model_actual_name"] = loopResult.modelActualName
            if (loopResult.failoverEvents.isNotEmpty()) ctx.metadata["failover_events"] = loopResult.failoverEvents

            // 只保存当前轮新增的 assistant/tool 消息。既有上下文已在数据库中，
            // 若再次写入会造成工具历史跨轮重复嵌套。
            ctx.toolTrace = extractCurrentTurnToolCallHistory(
                loopResult.toolMessages,
                executionResult.preparedMessages.size
            )

            if (loopResult.stopped) {
                ctx.stoppedPrematurely = true
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
        val fullReasoning = StringBuilder()
        val streamStart = System.nanoTime()
        var ttftMs: Double? = null

        try {
            val events = streamer(ctx.messages, ctx.shouldStop())
            for (event in events) {
                if (ctx.shouldStop()) break

                // 提取模型追踪信息
                extractModelTrace(ctx, event)

                @Suppress("UNCHECKED_CAST")
                val usage = (event["usage"] as? Map<String, Any>)
                if (usage != null) ctx.usage = usage

                val reasoningChunk = (event["thinking_content"] as? String)
                    ?: (event["reasoning_content"] as? String)
                    ?: ""
                val chunk = (event["content"] as? String) ?: ""
                if (reasoningChunk.isEmpty() && chunk.isEmpty()) continue

                if (ctx.streamedMessage == null) {
                    // 首块：记录 TTFT
                    ttftMs = (System.nanoTime() - streamStart) / 1_000_000.0
                    val msg = mapOf("role" to "assistant", "content" to "", "id" to messageId)
                    if (event["_relayed"] != true) callbacks.onStreamStart(ctx, msg)
                    ctx.streamedMessage = msg
                }

                if (reasoningChunk.isNotEmpty()) {
                    fullReasoning.append(reasoningChunk)
                    if (event["_relayed"] != true) {
                        callbacks.onReasoningChunk(ctx, reasoningChunk, messageId)
                    }
                }

                if (chunk.isNotEmpty()) {
                    fullContent.append(chunk)
                    if (event["_relayed"] != true) callbacks.onStreamChunk(ctx, chunk, messageId)
                }
            }
        } catch (e: Exception) {
            com.nekobot.app.data.local.LocalLogger.e(TAG, "Streaming failed: ${e.message}", e)
            ctx.error = e.message ?: "流式输出失败"
            if (fullContent.isEmpty()) {
                fullContent.append("流式输出失败: ${e.message}")
            }
        }

        ctx.finalContent = fullContent.toString()
        ctx.finalReasoning = fullReasoning.toString()
        if (ctx.shouldStop()) {
            markStopped(ctx)
        }
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
        // workspace_send_file/download_file 只返回文件元数据，必须将引用写入最终消息。
        // 这一步放在流式/错误/普通消息分支之前，确保所有收尾路径都不会漏掉文件卡片。
        ctx.finalContent = appendAgentFileReferences(ctx.finalContent, ctx.sentFileReferences)

        // 流式消息处理
        if (ctx.metadata["streamed"] == true && ctx.streamedMessage != null) {
            @Suppress("UNCHECKED_CAST")
            val streamedMsg = (ctx.streamedMessage as Map<String, Any>).toMutableMap()
            streamedMsg["content"] = ctx.finalContent
            if (ctx.finalReasoning.isNotBlank()) streamedMsg["reasoning_content"] = ctx.finalReasoning
            (ctx.metadata["group_speaker_name"] as? String)?.takeIf { it.isNotBlank() }?.let {
                streamedMsg["sender"] = it
            }
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
            "sender" to ((ctx.metadata["group_speaker_name"] as? String)?.takeIf { it.isNotBlank() } ?: "AI")
        )
        if (ctx.finalReasoning.isNotBlank()) assistantMessage["reasoning_content"] = ctx.finalReasoning

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
        if (ctx.shouldStop() || ctx.stoppedPrematurely) return
        // 静默的内部触发不是用户交互，不能改变角色对用户的情绪、好感和关系状态。
        if (ctx.metadata["skip_character_after_turn"] != true) {
            phaseCharacterRuntimeAfterTurn(ctx, callbacks, result)
            // 对话后审查可能回写关系增量，因此与角色 after_turn 使用同一跳过语义。
            phaseReview(ctx, callbacks, result)
        }

        // on_response_complete
        callbacks.onResponseComplete(ctx, result)
    }

    private fun markStopped(ctx: PipelineContext) {
        ctx.stopped = true
        ctx.stoppedPrematurely = true
        ctx.error = null
        ctx.finalContent = "【生成已停止 - 工具调用记录已保存，回复「继续」可继续执行】"
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

    /** 从模型响应中提取追踪信息（model_id / model_name / model_actual_name / failover_events） */
    private fun extractModelTrace(ctx: PipelineContext, response: Map<String, Any>) {
        (response["_model_id"] as? String)?.let { ctx.metadata["model_id"] = it }
        (response["_model_name"] as? String)?.let { ctx.metadata["model_name"] = it }
        (response["_model_actual_name"] as? String)?.let { ctx.metadata["model_actual_name"] = it }
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
