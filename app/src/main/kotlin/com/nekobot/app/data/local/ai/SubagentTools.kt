package com.nekobot.app.data.local.ai

import com.google.gson.Gson

/**
 * Subagent 相关工具的 OpenAI function-calling 定义与工具 id。
 *
 * 对齐 DSH 的 subagent 设计：AI 可在 Agent 会话内调用 `subagent` 委托一个独立任务给
 * 新的子代理执行；`subagent_list` / `subagent_get` 用于查询后台子代理任务的进度与结果
 * （对齐 DSH 的 list_agents / job_output）。
 */

internal const val TOOL_SUBAGENT = "subagent"
internal const val TOOL_SUBAGENT_LIST = "subagent_list"
internal const val TOOL_SUBAGENT_GET = "subagent_get"
internal const val TOOL_SUBAGENT_KILL = "subagent_kill"

internal val subagentToolIds = setOf(
    TOOL_SUBAGENT,
    TOOL_SUBAGENT_LIST,
    TOOL_SUBAGENT_GET,
    TOOL_SUBAGENT_KILL
)

/** 子代理工具执行结果里的特殊键：标记需要向父会话派发后台任务。 */
internal const val SUBAGENT_RESULT_KEYS = "_subagent_result"

/**
 * 构建 Subagent 工具定义（在前台/后台分支外只描述静态 schema）。
 * 需要运行时能力的部分（是否已启用、深度上限）由执行器在调用期校验。
 */
internal fun buildSubagentToolDefinitions(): List<Map<String, Any>> {
    fun definition(name: String, description: String, parameters: Map<String, Any>): Map<String, Any> =
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to parameters
            )
        )

    return listOf(
        definition(
            TOOL_SUBAGENT,
            "委派一个独立、自包含的任务给一个新的子代理执行。子代理拥有独立的全新上下文（只包含本任务的描述），" +
                "使用与当前主模型一致的故障转移模型队列完成推理，并可调用当前会话可用的本地工具真正执行任务（读写文件、执行命令、访问网络、操作 Android 等）." +
                "子代理会返回它执行后得到的最终结论文本，而不是中间步骤。前台（默认）调用会等待子代理完成并返回结果；" +
                "设置 run_in_background=true 可让子代理在后台运行并返回任务 id，随后用 subagent_list / subagent_get 查询进度与结果。" +
                "当子任务较大、可以并行、或只需要其结论而不需立刻使用结果时，优先考虑后台运行。",
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "description" to mapOf(
                        "type" to "string",
                        "description" to "对委派任务的简短描述（3-5 个词），用于展示。"
                    ),
                    "prompt" to mapOf(
                        "type" to "string",
                        "description" to "完整的、自包含的子代理任务指令。子代理看不到当前对话的历史与角色上下文，因此请把所有必要背景、约束与期望的输出格式都写进这里。"
                    ),
                    "run_in_background" to mapOf(
                        "type" to "boolean",
                        "description" to "是否后台运行。默认 false：等待子代理完成并直接返回结果。true：立即返回任务 id，稍后用 subagent_get 查询。"
                    )
                ),
                "required" to listOf("description", "prompt")
            )
        ),
        definition(
            TOOL_SUBAGENT_LIST,
            "列出当前会话已提交的子代理任务（含后台运行的）及其状态。",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        definition(
            TOOL_SUBAGENT_GET,
            "查询指定子代理任务的结果与状态。用于后台子代理完成后取回其结论。",
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "task_id" to mapOf(
                        "type" to "string",
                        "description" to "要查询的子代理任务 id（subagent_list 返回）。"
                    )
                ),
                "required" to listOf("task_id")
            )
        ),
        definition(
            TOOL_SUBAGENT_KILL,
            "终止一个正在运行的后台子代理任务。当任务已经跑偏、长时间无进展、或用户改变需求时使用，" +
                "避免它继续消耗模型额度。终止后任务状态变为 killed，已产生的部分结果仍可用 subagent_get 查看。",
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "task_id" to mapOf(
                        "type" to "string",
                        "description" to "要终止的子代理任务 id（subagent_list 返回）。"
                    )
                ),
                "required" to listOf("task_id")
            )
        )
    )
}

/**
 * 把 subagent 工具定义合并进 Agent 会话的工具清单。若已通过其它路径（如 BuiltinTools）
 * 定义了同名工具，去重时保持最先出现的定义。
 */
internal fun mergeSubagentToolDefinitions(
    existing: List<Map<String, Any>>
): List<Map<String, Any>> {
    val subagentDefs = buildSubagentToolDefinitions()
    val existingNames = subagentToolIds
    val retained = existing.filterNot { def ->
        val name = (def["function"] as? Map<String, Any>)?.get("name")?.toString()
            ?: def["name"]?.toString()
        name in existingNames
    }
    return retained + subagentDefs
}

/**
 * 把子代理执行结果封装成父 Agent 的 tool message content。
 */
internal fun renderSubagentResultContent(
    taskId: String,
    status: SubagentTaskStatus,
    content: String,
    error: String?,
    modelName: String,
    toolCalls: Int
): String {
    val gson = Gson()
    return gson.toJson(
        buildMap {
            put("success", status == SubagentTaskStatus.SUCCEEDED)
            put("task_id", taskId)
            put("status", status.name.lowercase())
            put("model", modelName)
            put("tool_calls", toolCalls)
            if (status == SubagentTaskStatus.SUCCEEDED) {
                put("result", content.take(20_000))
            } else {
                put("error", error ?: "子代理执行失败")
                if (content.isNotBlank()) put("partial_result", content.take(8_000))
            }
        }
    )
}