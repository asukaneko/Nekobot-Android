package com.nekobot.app.data.local.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地 Agent 的「向用户提问」工具（ask_user_question）。
 *
 * 参考 Claude Code AskUserQuestion / OpenCode ask 工具的设计：
 * AI 把结构化问题（短标题 + 正文 + 预设选项 + 是否可多选）抛给用户，
 * 管线在工具执行点挂起等待；用户在会话界面弹窗中选择或输入后，
 * 答案作为工具结果回传，AI 依据答案继续任务。
 */

/** 单个预设选项。 */
data class AskUserQuestionOption(
    val label: String,
    val description: String = ""
)

/** 单个问题。 */
data class AskUserQuestionItem(
    /** 问题标识（可选）；回答结果会原样返回，便于 AI 关联答案。 */
    val id: String,
    /** 问题正文。 */
    val question: String,
    /** 短标题（几个字），用于弹窗内的分组提示。 */
    val header: String,
    /** 预设选项；开放式问题为空列表。 */
    val options: List<AskUserQuestionOption>,
    /** 是否允许多选。 */
    val multiSelect: Boolean
)

/** 用户对单个问题的回答。 */
data class AskUserQuestionAnswer(
    val id: String,
    val question: String,
    /** 用户勾选的选项标签（多选时多于一个）。 */
    val selected: List<String>,
    /** 用户自由输入的补充文本（可空）。 */
    val text: String
)

/** 推送到会话界面的提问请求。 */
data class AskUserQuestionRequest(
    val requestId: String,
    val sessionId: String,
    val questions: List<AskUserQuestionItem>
)

/**
 * ask_user_question 参数解析器。
 *
 * 模型输出形态多变，这里做宽松归一：
 * - 标准形态：{"questions":[{id,question,header,options,multi_select}]}
 * - 扁平形态：{"question":"...","header":"...","options":[...],"multi_select":true}
 * - options 兼容字符串数组与 {label,description} 对象数组
 */
internal object AskUserQuestionCodec {

    const val MAX_QUESTIONS = 4
    const val MAX_OPTIONS = 12
    const val MAX_QUESTION_LENGTH = 500
    const val MAX_HEADER_LENGTH = 24
    const val MAX_LABEL_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 300
    const val MAX_CUSTOM_TEXT_LENGTH = 2000

    /** 解析并校验；失败时返回带原因的 Result。 */
    fun parse(args: Map<String, Any?>): Result<List<AskUserQuestionItem>> {
        val rawQuestions: List<*> = when {
            args["questions"] is List<*> -> args["questions"] as List<*>
            args["question"] != null -> listOf(args)
            else -> return Result.failure(IllegalArgumentException("缺少 questions 参数：请传入问题数组，或在顶层直接提供 question 字段"))
        }
        if (rawQuestions.isEmpty()) {
            return Result.failure(IllegalArgumentException("questions 不能为空"))
        }
        if (rawQuestions.size > MAX_QUESTIONS) {
            return Result.failure(IllegalArgumentException("问题数量过多（最多 $MAX_QUESTIONS 个），请合并或拆分调用"))
        }
        val items = mutableListOf<AskUserQuestionItem>()
        rawQuestions.forEachIndexed { index, raw ->
            val map = (raw as? Map<*, *>)
                ?: return Result.failure(IllegalArgumentException("第 ${index + 1} 个问题必须是对象"))
            val item = parseOne(map, index)
                .getOrElse { return Result.failure(it) }
            items += item
        }
        return Result.success(items)
    }

    private fun parseOne(map: Map<*, *>, index: Int): Result<AskUserQuestionItem> {
        val ordinal = index + 1
        val question = map["question"]?.toString()?.trim().orEmpty()
        if (question.isEmpty()) {
            return Result.failure(IllegalArgumentException("第 $ordinal 个问题缺少 question 文本"))
        }
        val rawOptions = map["options"] as? List<*>
        val options = rawOptions.orEmpty().take(MAX_OPTIONS).mapIndexedNotNull { optionIndex, rawOption ->
            when (rawOption) {
                is Map<*, *> -> {
                    val label = rawOption["label"]?.toString()?.trim().orEmpty()
                        .ifBlank { rawOption["name"]?.toString()?.trim().orEmpty() }
                        .ifBlank { rawOption["value"]?.toString()?.trim().orEmpty() }
                    if (label.isEmpty()) {
                        android.util.Log.w(
                            "AskUserQuestion",
                            "第 ${optionIndex + 1} 个选项缺少 label，已忽略"
                        )
                        null
                    } else {
                        AskUserQuestionOption(
                            label = label.take(MAX_LABEL_LENGTH),
                            description = rawOption["description"]?.toString()?.trim().orEmpty().take(MAX_DESCRIPTION_LENGTH)
                        )
                    }
                }
                else -> {
                    val label = rawOption?.toString()?.trim().orEmpty()
                    if (label.isEmpty()) null else AskUserQuestionOption(label = label.take(MAX_LABEL_LENGTH))
                }
            }
        }
        val multiSelect = when (val value = map["multi_select"] ?: map["multiSelect"]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
        return Result.success(
            AskUserQuestionItem(
                id = map["id"]?.toString()?.trim().orEmpty().ifBlank { "q${ordinal}" },
                question = question.take(MAX_QUESTION_LENGTH),
                header = map["header"]?.toString()?.trim().orEmpty().take(MAX_HEADER_LENGTH),
                options = options,
                multiSelect = multiSelect
            )
        )
    }
}

/**
 * 提问等待管理器：AI 调用 ask_user_question 时挂起，直到用户回答、跳过或超时。
 *
 * 与 [LocalExecAuthorizationManager] 相同的进程内模式：
 * CompletableDeferred + requestId，UI 通过 LocalRepository 的 respond 入口回填结果；
 * 会话停止生成时统一取消，避免工具循环悬挂。
 */
class LocalAskUserQuestionManager(
    private val timeoutMs: Long = 10 * 60 * 1000L
) {
    private data class Pending(
        val sessionId: String,
        val decision: CompletableDeferred<Pair<Boolean, List<AskUserQuestionAnswer>>>
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * 发起提问并挂起等待；返回直接作为 ask_user_question 的工具结果。
     * [onRequest] 负责把请求转发到 UI（LocalRepository SharedFlow）。
     */
    suspend fun requestAnswer(
        sessionId: String,
        questions: List<AskUserQuestionItem>,
        onRequest: (AskUserQuestionRequest) -> Unit
    ): Map<String, Any> {
        val requestId = UUID.randomUUID().toString()
        val decision = CompletableDeferred<Pair<Boolean, List<AskUserQuestionAnswer>>>()
        pending[requestId] = Pending(sessionId, decision)
        onRequest(AskUserQuestionRequest(requestId = requestId, sessionId = sessionId, questions = questions))
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { decision.await() }
        } finally {
            pending.remove(requestId)
        }
        val timeoutMinutes = (timeoutMs / 60000L).coerceAtLeast(1)
        return when {
            outcome == null -> mapOf(
                "success" to false,
                "cancelled" to true,
                "error" to "提问超过 ${timeoutMinutes} 分钟未获回答，已自动跳过。请基于已有信息继续；如确有必要可稍后重新提问。"
            )
            !outcome.first -> mapOf(
                "success" to false,
                "cancelled" to true,
                "error" to "用户跳过了这些问题。请基于已有信息继续任务，不要因跳过而反复重新提问。"
            )
            else -> mapOf(
                "success" to true,
                "cancelled" to false,
                "answers" to outcome.second.map { answer ->
                    buildMap<String, Any> {
                        put("id", answer.id)
                        put("question", answer.question)
                        put("selected", answer.selected)
                        if (answer.text.isNotBlank()) put("custom_text", answer.text)
                        val selectedText = answer.selected.joinToString("；")
                        val combined = listOf(selectedText, answer.text.trim())
                            .filter(String::isNotBlank)
                            .joinToString("；")
                        put("answer", combined)
                    }
                },
                "note" to "answers 中是用户对每个问题的选择或输入（answer 为合并文本），请严格依据该结果继续任务。"
            )
        }
    }

    /** 提交用户回答；requestId 不存在或会话不匹配时返回 false。 */
    fun resolve(requestId: String, sessionId: String, answers: List<AskUserQuestionAnswer>): Boolean {
        val request = pending[requestId] ?: return false
        if (request.sessionId != sessionId) return false
        return request.decision.complete(true to answers)
    }

    /** 用户跳过提问；语义与拒绝一致，AI 会收到 cancelled 结果。 */
    fun cancel(requestId: String, sessionId: String): Boolean {
        val request = pending[requestId] ?: return false
        if (request.sessionId != sessionId) return false
        return request.decision.complete(false to emptyList())
    }

    /** 停止生成时取消该会话全部待回答提问，立即解除挂起。 */
    fun cancelSession(sessionId: String) {
        pending.entries
            .filter { (_, request) -> request.sessionId == sessionId }
            .forEach { (requestId, request) ->
                if (pending.remove(requestId, request)) {
                    request.decision.complete(false to emptyList())
                }
            }
    }
}
