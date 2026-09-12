package com.nekobot.app.data.local.ai

import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.PrefsManager

/**
 * Agent 工具输出与进度卡预览的统一截断上限。
 *
 * 改造前每个工具各自硬编码一份截断字符数（12000 / 20000 / 30000 / 50000 / 60000 …），
 * 进度卡又另有一套（100 / 120 / 1500 / 3000），子代理更是绕开常量直接写魔法数字。
 * 现在收敛为两档用户可调配置，**所有截断数字只允许出现在本文件里**：
 *
 * - [toolOutputChars]：所有「返回文本内容」的工具统一使用的输出上限
 *   （文件读取、网页抓取、HTTP 请求、命令输出、剪贴板、浏览器正文、插件源码等）。
 * - [progressPreviewChars]：进度卡里工具参数预览与结果预览的统一上限，主 Agent 与子代理共用。
 *
 * 其余为结构性上限（命中条数、单行展示宽度、思考正文长度等），语义与「工具输出」不同，
 * 因此不可调，但同样集中在这里，避免再次散落。
 */
internal object AgentToolLimits {

    // ============ 用户可调：工具输出截断 ============

    /** 工具输出统一默认上限（字符）。 */
    const val DEFAULT_TOOL_OUTPUT_CHARS = 50_000

    /** 工具输出统一下限：设得太小工具会完全不可用。 */
    const val MIN_TOOL_OUTPUT_CHARS = 1_000

    /** 工具输出统一上限（用户可调范围的最大值）。 */
    const val MAX_TOOL_OUTPUT_CHARS = 200_000

    // ============ 用户可调：进度卡工具预览截断 ============

    /** 进度卡工具参数/结果预览统一默认上限（字符）。 */
    const val DEFAULT_PROGRESS_PREVIEW_CHARS = 3_000

    /** 进度卡预览下限：低于此值详情弹窗已看不出调用了什么。 */
    const val MIN_PROGRESS_PREVIEW_CHARS = 200

    /** 进度卡预览上限。 */
    const val MAX_PROGRESS_PREVIEW_CHARS = 20_000

    // ============ 固定：结构性上限 ============

    /** grep 单行展示上限：超长行（压缩文件、单行 JSON）截断，避免一条命中吃掉整轮上下文。 */
    const val GREP_LINE_PREVIEW_CHARS = 400

    /** 进度卡工具步骤行内明细（detail）上限：参数摘要、命令行、文件路径、结果摘要共用。 */
    const val PROGRESS_STEP_DETAIL_CHARS = 120

    /** 进度卡思考步骤 detail 预览上限（仅用于行内展示，正文另算）。 */
    const val PROGRESS_REASONING_DETAIL_CHARS = 160

    /** 进度卡思考正文上限：落库与详情弹窗统一使用，避免"看得到、重启后变短"。 */
    const val PROGRESS_REASONING_CHARS = 20_000

    /** 进度卡行内实时思考预览上限（非详情弹窗）。 */
    const val PROGRESS_REASONING_LIVE_CHARS = 1_200

    /** 进度卡落库时单步名称上限。 */
    const val PROGRESS_PERSISTED_NAME_CHARS = 200

    /** 进度卡落库时保留的最大步骤数（超出时保留思考步骤 + 最近若干步）。 */
    const val PROGRESS_PERSISTED_STEPS = 64

    /** 进度卡落库时卡片正文上限。 */
    const val PROGRESS_PERSISTED_CONTENT_CHARS = 500

    /** 把上一轮进度卡回灌进模型上下文时的总字符预算（旧格式兜底路径）。 */
    const val PROGRESS_CONTEXT_BUDGET_CHARS = 12_000

    /** 回灌上下文时单条工具参数 JSON 上限。 */
    const val PROGRESS_CONTEXT_ARGUMENT_CHARS = 1_000

    /** 回灌上下文时单条工具结果 JSON 上限。 */
    const val PROGRESS_CONTEXT_RESULT_CHARS = 2_000

    /** 回灌上下文时单步摘要上限。 */
    const val PROGRESS_CONTEXT_SUMMARY_CHARS = 240

    /** 子代理参数预览里单个标量值的上限。 */
    const val PROGRESS_SCALAR_PREVIEW_CHARS = 40

    // ============ 取值入口 ============

    /** 工具输出统一上限：用户设置在「设置 → Agent 设置」里调整，未初始化（JVM 单测）时回落默认值。 */
    fun toolOutputChars(): Int = resolve(
        readPref { it.agentToolOutputChars },
        DEFAULT_TOOL_OUTPUT_CHARS,
        MIN_TOOL_OUTPUT_CHARS,
        MAX_TOOL_OUTPUT_CHARS,
    )

    /** 进度卡工具预览统一上限。 */
    fun progressPreviewChars(): Int = resolve(
        readPref { it.agentProgressPreviewChars },
        DEFAULT_PROGRESS_PREVIEW_CHARS,
        MIN_PROGRESS_PREVIEW_CHARS,
        MAX_PROGRESS_PREVIEW_CHARS,
    )

    /**
     * 把工具调用参数里的 `max_chars` 解析为实际生效值。
     *
     * 统一语义：**用户设置就是硬上限**。参数缺省或 <=0（原「不限制」写法）都按上限处理，
     * 显式传入更小的值才真正生效——避免模型绕过设置把整个文件塞进上下文。
     */
    fun resolveRequestedMaxChars(requested: Int, limit: Int = toolOutputChars()): Int =
        if (requested <= 0) limit else requested.coerceIn(1, limit)

    private inline fun readPref(block: (PrefsManager) -> Int): Int? =
        runCatching { block(ServiceContainer.prefs) }.getOrNull()

    private fun resolve(value: Int?, fallback: Int, min: Int, max: Int): Int =
        (value ?: fallback).coerceIn(min, max)
}
