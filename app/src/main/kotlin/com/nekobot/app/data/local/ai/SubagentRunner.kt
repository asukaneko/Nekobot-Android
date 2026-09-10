package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.model.ThinkingStep

/**
 * 子代理一次运行的规格与执行结果。
 */
data class SubagentRunSpec(
    /** 子代理任务标识（由 [SubagentTaskStore] 分配）。 */
    val taskId: String,
    /** 委派给子代理的独立任务文本。 */
    val prompt: String,
    /** 当前嵌套深度（顶层父会话为 1，其子代理为 2，以此类推）。 */
    val depth: Int,
    /** 父级子代理任务 id（顶层调用为 null，用于限制最大深度与追踪）。 */
    val parentTaskId: String?,
    /** 是否后台运行：后台时本处登记任务后立即返回，执行在独立协程。 */
    val runInBackground: Boolean
)

/** 子代理执行结果。 */
data class SubagentRunResult(
    val content: String,
    val error: String? = null,
    val status: SubagentTaskStatus = SubagentTaskStatus.SUCCEEDED,
    val modelName: String = "",
    val toolCalls: Int = 0
)

/**
 * 子代理执行回调：由 LocalPipelineCallbacks 注入，用于让子代理复用当前会话的
 * 故障转移模型队列、本地工具执行器与停止信号。
 *
 * 对齐 DSH 的 subagent 设计：子代理是一个全新的、独立的 Agent 上下文，只携带
 * 委派任务文本 + 轻量系统提示词，不继承父会话的角色、世界书或历史消息；但它
 * 使用与父模型相同的故障转移队列完成推理，并可调用与主会话一致的本地工具，从而
 * 真正执行任务。
 */
fun interface SubagentRunDelegate {
    /** 以给定规格运行子代理，返回执行结果。 */
    suspend fun run(spec: SubagentRunSpec): SubagentRunResult
}

/**
 * 子代理执行器。
 *
 * 负责把委派任务包装成"系统提示词 + 单条用户任务"的独立上下文中，运行有限的
 * 工具循环（复用 [runToolLoopSession]），最终把子代理的结论文本返回给父模型。
 *
 * 深度限制：新建子代理前调用 [assertSubagentDepth]，超过 [SubagentTaskStore] 当前
 * 深度上限时直接拒绝，防止无限递归（对齐 DSH 的 maxDepth）。
 */
internal object SubagentRunner {

    private const val TAG = "SubagentRunner"

    /**
     * 检查是否允许再创建一层子代理。
     *
     * @param requestedDepth 将要创建的子代理深度（顶层父会话 depth=1 → 子代理 depth=2）。
     * @param maxDepth 配置的最大层级（0 表示禁止委派）。
     * @return null 表示允许；否则返回拒绝原因文本。
     */
    fun guardDepth(requestedDepth: Int, maxDepth: Int): String? = when {
        maxDepth <= 0 -> "Subagent 功能已禁用（最大深度为 0）"
        requestedDepth > maxDepth ->
            "已达到子代理最大嵌套深度（$maxDepth），无法再创建子代理。请在父会话内直接完成该任务。"
        else -> null
    }

    /**
     * 执行一个前台子代理：构建独立上下文并运行工具循环。
     *
     * @param delegate 提供 modelCall 与工具执行能力的委托（见 [SubagentDelegateScope]）。
     * @param taskId 任务登记 id
     * @param prompt 委派任务
     * @param language 当前的系统提示词语言（zh/en/ja/ko）
     * @param maxToolIterations 子代理内部允许的最大工具调用迭代数
     * @param shouldStop 全局停止信号（父会话被停止时一并停止子代理）
     * @param onProgress 可选：子代理每一步进度变化回调，参数依次为（卡片头部文本、是否完成、当前步骤列表）。
     */
    internal suspend fun runForeground(
        delegate: SubagentDelegateScope,
        taskId: String,
        description: String,
        prompt: String,
        language: String,
        maxToolIterations: Int,
        shouldStop: () -> Boolean,
        onProgress: ((header: String, isComplete: Boolean, steps: List<ThinkingStep>) -> Unit)? = null,
        /** 子代理自己的输入 token 预算（0 表示不做循环内上下文管理）。 */
        contextBudgetTokens: Int = 0
    ): SubagentRunResult {
        if (shouldStop()) {
            return SubagentRunResult("", error = "生成已停止", status = SubagentTaskStatus.KILLED)
        }
        // 独立上下文：轻量子代理系统提示 + 委派任务。
        val systemPrompt = buildSubagentSystemPrompt(language)
        val messages = listOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to prompt)
        )

        val stepSink = SubagentProgressCollector(description)
        val hooks = ToolLoopHooks(
            onIterationStart = { iteration, _ ->
                stepSink.markIteration(iteration)
                onProgress?.invoke(stepSink.header(), stepSink.complete, stepSink.steps())
            },
            onToolStart = { toolCall, thinking, iteration, _ ->
                stepSink.onToolStart(toolCall, thinking)
                onProgress?.invoke(stepSink.header(), stepSink.complete, stepSink.steps())
            },
            onToolResult = { toolCall, result, thinking, iteration, _ ->
                stepSink.onToolResult(toolCall, result)
                onProgress?.invoke(stepSink.header(), stepSink.complete, stepSink.steps())
                null
            }
        )

        // 子代理模型调用仍走与主会话相同的故障转移队列。
        val session = ToolLoopSession(
            initialMessages = messages,
            modelCall = delegate.buildModelCall(),
            toolExecutor = delegate.toolExecutor,
            maxIterations = maxToolIterations,
            maxConsecutiveErrors = 3,
            shouldStop = shouldStop,
            pendingUserMessages = { emptyList() },
            hooks = hooks,
            contextBudgetTokens = { contextBudgetTokens }
        )
        return try {
            val execution = runToolLoopSession(session)
            val loop = execution.loopResult
            val content = resolveLoopFinalContent(loop)
            if (loop.stopped) {
                stepSink.finish(SubagentTaskStatus.KILLED)
                onProgress?.invoke(stepSink.header(), true, stepSink.steps())
                SubagentRunResult(
                    content = content,
                    error = "子代理执行被取消（父会话停止）",
                    status = SubagentTaskStatus.KILLED,
                    modelName = loop.modelName,
                    toolCalls = loop.iterations.coerceAtMost(maxToolIterations)
                )
            } else {
                stepSink.finish(SubagentTaskStatus.SUCCEEDED)
                onProgress?.invoke(stepSink.header(), true, stepSink.steps())
                SubagentRunResult(
                    content = content,
                    error = null,
                    status = SubagentTaskStatus.SUCCEEDED,
                    modelName = loop.modelName,
                    toolCalls = loop.iterations.coerceAtMost(maxToolIterations)
                )
            }
        } catch (e: ToolLoopModelError) {
            LocalLogger.w(TAG, "子代理模型循环失败（iteration=${e.iteration}）: ${e.message}")
            stepSink.finish(SubagentTaskStatus.FAILED)
            onProgress?.invoke(stepSink.header(), true, stepSink.steps())
            SubagentRunResult(
                content = "",
                error = e.message ?: "子代理模型调用失败",
                status = SubagentTaskStatus.FAILED
            )
        } catch (e: Exception) {
            if (shouldStop()) {
                stepSink.finish(SubagentTaskStatus.KILLED)
                onProgress?.invoke(stepSink.header(), true, stepSink.steps())
                SubagentRunResult("", error = "生成已停止", status = SubagentTaskStatus.KILLED)
            } else {
                LocalLogger.w(TAG, "子代理执行异常: ${e.message}")
                stepSink.finish(SubagentTaskStatus.FAILED)
                onProgress?.invoke(stepSink.header(), true, stepSink.steps())
                SubagentRunResult(content = "", error = e.message ?: "子代理执行失败", status = SubagentTaskStatus.FAILED)
            }
        }
    }

    /**
     * 子代理的轻量系统提示词：只包含核心行为契约 + 子代理专属约束，
     * 不注入角色、世界书、会话历史等父会话上下文。
     */
    internal fun buildSubagentSystemPrompt(language: String = "zh"): String {
        val base = buildLocalAgentBasePrompt(language)
        val normalized = language.lowercase().substringBefore('-').substringBefore('_')
        val guard = when (normalized) {
            "zh" -> """
                ## 你是子代理（Subagent）
                - 你是被父 Agent 委派执行一个独立任务的子代理。你的职责是专注于完成下面给出的这个明确任务，并输出清晰的结论。
                - 你只拥有本任务提供的上下文，看不到父会话的完整历史、角色或世界书；因此请在需要时通过工具自行读取/检索所需信息。
                - 完成任务后，仅返回任务结论给父 Agent，不要输出工具调用记录以外的中间过程。
            """.trimIndent()
            "ja" -> """
                ## あなたはサブエージェントです
                - あなたは親 Agent から委託された独立したタスクを実行するサブエージェントです。以下の明確なタスクを完了し、明確な結論を出力してください。
                - あなたはこのタスクで提供された情報だけを持ち、親セッションの完全な履歴・キャラクター・ワールドブックは見えません。必要な情報はツールで読むか取得してください。
                - タスク完了後は、ツール呼び出し記録以外の中間過程を出さず、結論だけを親 Agent に返してください。
            """.trimIndent()
            "ko" -> """
                ## 당신은 서브 에이전트입니다
                - 당신은 부모 Agent가 위임한 독립된 작업을 실행하는 서브 에이전트입니다. 아래의 명확한 작업을 완료하고 명확한 결론을 출력하세요.
                - 당신은 이 작업에서 제공된 정보만 가지며, 부모 세션의 전체 기록·캐릭터·월드북을 볼 수 없습니다. 필요한 정보는 도구로 읽거나 가져오세요.
                - 작업 완료 후에는 도구 호출 기록 외의 중간 과정 없이 결론만 부모 Agent에게 반환하세요.
            """.trimIndent()
            else -> """
                ## You are a Subagent
                - You are a subagent delegated one independent task by a parent Agent. Complete the specific task below and produce a clear conclusion.
                - You only have the context provided in this task; you cannot see the parent session's full history, characters, or world book. Read or retrieve needed information with tools.
                - After completing the task, return only the conclusion to the parent Agent, not the intermediate tool-work beyond the tool-call log.
            """.trimIndent()
        }
        return "$base\n\n$guard"
    }
}

/**
 * 子代理运行时委托作用域：提供模型调用与工具执行，由 LocalPipelineCallbacks 装配。
 */
internal class SubagentDelegateScope(
    internal val buildModelCall: () -> ModelCall,
    internal val toolExecutor: suspend (Map<String, Any>, String, Int, List<Map<String, Any>>) -> Map<String, Any>
)

/**
 * 收集子代理内部进度步骤，产出进度卡片的头部文本与步骤列表。
 *
 * 供 SubagentRunner 在工具循环期间把子代理调用过的工具、迭代次数转成
 * [ThinkingStep]，通过 [SubagentRunner.runForeground] 的 onProgress 回调推到 UI。
 */
internal class SubagentProgressCollector(
    private val description: String
) {
    private val _steps = mutableListOf<ThinkingStep>()
    private var iteration = 0
    private var pendingToolName: String? = null
    private var pendingToolDetail: String? = null
    var complete: Boolean = false
        private set

    /** 当前头部文本：运行中或已完成 / 失败。 */
    fun header(): String = when {
        complete -> "✅ 子代理完成: $description"
        pendingToolName != null -> "子代理: $description · $pendingToolName"
        else -> "子代理: $description"
    }

    fun steps(): List<ThinkingStep> = _steps.toList()

    fun markIteration(iteration: Int) {
        this.iteration = iteration
    }

    fun onToolStart(toolCall: Map<String, Any>, thinking: String) {
        val name = (toolCall["name"] as? String) ?: "tool"
        pendingToolName = name
        pendingToolDetail = previewArgs(toolCall["arguments"])
        _steps.add(
            ThinkingStep(
                type = "tool",
                name = name,
                status = "running",
                detail = pendingToolDetail,
                arguments = mapOf("preview" to (pendingToolDetail ?: ""))
            )
        )
    }

    fun onToolResult(toolCall: Map<String, Any>, result: Map<String, Any>) {
        val name = (toolCall["name"] as? String) ?: "tool"
        val preview = previewResult(result)
        val index = _steps.indexOfLast { it.type == "tool" && it.name == name && it.status != "done" }
        if (index >= 0) {
            _steps[index] = _steps[index].copy(status = "done", detail = preview, fullResult = result)
        } else {
            _steps.add(ThinkingStep(type = "tool_done", name = name, status = "done", detail = preview))
        }
        pendingToolName = null
    }

    fun finish(status: SubagentTaskStatus) {
        complete = true
        for (i in _steps.indices) {
            if (_steps[i].status == "running" || _steps[i].status == "active") {
                _steps[i] = _steps[i].copy(status = "done")
            }
        }
        _steps.add(
            ThinkingStep(
                type = "done",
                name = if (status == SubagentTaskStatus.SUCCEEDED) "子代理完成" else "子代理失败",
                status = "done"
            )
        )
    }

    private fun previewArgs(arguments: Any?): String? {
        if (arguments == null) return null
        return when (arguments) {
            is String -> if (arguments.length > 100) arguments.take(100) + "…" else arguments
            is Map<*, *> -> (arguments["preview"] as? String)
                ?: (arguments.entries.joinToString(",") { "${it.key}: ${previewScalar(it.value)}" }.take(120))
            else -> arguments.toString().take(120)
        }
    }

    private fun previewScalar(v: Any?): String = when (v) {
        null -> ""
        is String -> if (v.length > 40) v.take(40) + "…" else v
        else -> v.toString()
    }

    private fun previewResult(result: Map<String, Any>): String? {
        if ((result["success"] as? Boolean) == false) {
            return (result["error"] as? String)?.take(120) ?: "执行失败"
        }
        return (result["preview"] as? String) ?: if (result.isEmpty()) "" else result.toString().take(120)
    }
}