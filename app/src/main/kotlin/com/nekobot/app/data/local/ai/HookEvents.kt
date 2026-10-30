package com.nekobot.app.data.local.ai

import androidx.annotation.StringRes
import com.nekobot.app.R

/**
 * Hook 事件分组。用于 Hook 编辑界面按大类展示事件库。
 */
enum class HookEventGroup(@StringRes val titleRes: Int) {
    MESSAGE(R.string.hook_group_message),
    CHARACTER(R.string.hook_group_character),
    MODEL(R.string.hook_group_model),
    AGENT(R.string.hook_group_agent)
}

/**
 * 一条可用的 Hook 事件定义。
 *
 * @param id 事件 id（写入 Hook.event，支持末尾 `.*` 通配）
 * @param blockable 该事件是否允许用 `tool_block` 动作拦截（仅 agent.tool.before_call）
 */
data class HookEventSpec(
    val id: String,
    val group: HookEventGroup,
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
    val blockable: Boolean = false
)

/**
 * 本地模式 Hook 事件目录。
 *
 * 这里只登记"本地管线真的会触发"的事件，避免用户在事件库里选到永远不会触发的事件。
 * 角色/模型类事件沿用原仓库命名；Agent 类事件是本地 Agent 工具循环专属的新增事件。
 */
object HookEventCatalog {

    val events: List<HookEventSpec> = listOf(
        // ---- 消息 ----
        HookEventSpec(
            id = "message.before_send",
            group = HookEventGroup.MESSAGE,
            nameRes = R.string.hook_evt_message_before_send,
            descRes = R.string.hook_evt_message_before_send_desc
        ),
        HookEventSpec(
            id = "message.after_receive",
            group = HookEventGroup.MESSAGE,
            nameRes = R.string.hook_evt_message_after_receive,
            descRes = R.string.hook_evt_message_after_receive_desc
        ),
        // ---- 角色 ----
        HookEventSpec(
            id = "character.before_turn.finished",
            group = HookEventGroup.CHARACTER,
            nameRes = R.string.hook_evt_character_before_turn,
            descRes = R.string.hook_evt_character_before_turn_desc
        ),
        HookEventSpec(
            id = "character.after_turn.finished",
            group = HookEventGroup.CHARACTER,
            nameRes = R.string.hook_evt_character_after_turn,
            descRes = R.string.hook_evt_character_after_turn_desc
        ),
        // ---- 模型 ----
        HookEventSpec(
            id = "model.after_call",
            group = HookEventGroup.MODEL,
            nameRes = R.string.hook_evt_model_after_call,
            descRes = R.string.hook_evt_model_after_call_desc
        ),
        // ---- Agent（工具循环） ----
        HookEventSpec(
            id = "agent.session.start",
            group = HookEventGroup.AGENT,
            nameRes = R.string.hook_evt_agent_session_start,
            descRes = R.string.hook_evt_agent_session_start_desc
        ),
        HookEventSpec(
            id = "agent.session.end",
            group = HookEventGroup.AGENT,
            nameRes = R.string.hook_evt_agent_session_end,
            descRes = R.string.hook_evt_agent_session_end_desc
        ),
        HookEventSpec(
            id = "agent.iteration.start",
            group = HookEventGroup.AGENT,
            nameRes = R.string.hook_evt_agent_iteration_start,
            descRes = R.string.hook_evt_agent_iteration_start_desc
        ),
        HookEventSpec(
            id = "agent.tool.before_call",
            group = HookEventGroup.AGENT,
            nameRes = R.string.hook_evt_agent_tool_before,
            descRes = R.string.hook_evt_agent_tool_before_desc,
            blockable = true
        ),
        HookEventSpec(
            id = "agent.tool.after_call",
            group = HookEventGroup.AGENT,
            nameRes = R.string.hook_evt_agent_tool_after,
            descRes = R.string.hook_evt_agent_tool_after_desc
        ),
        HookEventSpec(
            id = "agent.context.trimmed",
            group = HookEventGroup.AGENT,
            nameRes = R.string.hook_evt_agent_context_trimmed,
            descRes = R.string.hook_evt_agent_context_trimmed_desc
        ),
        HookEventSpec(
            id = "agent.error",
            group = HookEventGroup.AGENT,
            nameRes = R.string.hook_evt_agent_error,
            descRes = R.string.hook_evt_agent_error_desc
        )
    )

    /** 按分组顺序排列（UI 展示用）。 */
    fun grouped(): List<Pair<HookEventGroup, List<HookEventSpec>>> =
        HookEventGroup.entries.map { group -> group to events.filter { it.group == group } }
            .filter { it.second.isNotEmpty() }

    /** 精确查找事件定义（通配符事件返回 null）。 */
    fun find(event: String): HookEventSpec? = events.firstOrNull { it.id == event }

    /** 判断是否属于 Agent 大类事件（含 `agent` / `agent.*` 通配）。 */
    fun isAgentEvent(event: String): Boolean {
        val e = event.trim()
        return e == "agent" || e == "agent.*" || e.startsWith("agent.")
    }

    /** 允许拦截工具调用的动作仅对 before_call 有效。 */
    fun isBlockableEvent(event: String): Boolean =
        find(event)?.blockable == true || event.trim() == "agent.*"
}
