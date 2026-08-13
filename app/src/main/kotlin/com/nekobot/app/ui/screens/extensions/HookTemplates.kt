package com.nekobot.app.ui.screens.extensions

import androidx.annotation.StringRes
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.HookRequest

/**
 * Hook 内置模板：对应原仓库 nbot-methods.js 中的 window.__nbotHookTemplates。
 *
 * 每个模板可直接套用为 HookRequest，便于用户从示例快速创建可用的 Hook。
 */
data class HookTemplate(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val buildRequest: () -> HookRequest
)

/** 5 个内置 Hook 模板，沿用原仓库的事件、作用域、条件与动作语义。 */
val BuiltInHookTemplates: List<HookTemplate> = listOf(
    HookTemplate(
        key = "high_affection_notice",
        titleRes = R.string.hook_template_high_affection_title,
        descRes = R.string.hook_template_high_affection_desc,
        buildRequest = {
            HookRequest(
                name = ServiceContainer.getString(R.string.hook_template_high_affection_name),
                event = "character.before_turn.finished",
                scope = "global",
                priority = 10,
                triggerMode = "always",
                conditions = JsonParser.parseString("""{"affection_gte": 80}"""),
                actions = listOf(
                    logAction(R.string.hook_template_affection_log)
                )
            )
        }
    ),
    HookTemplate(
        key = "high_affection_once_notice",
        titleRes = R.string.hook_template_high_affection_once_title,
        descRes = R.string.hook_template_high_affection_once_desc,
        buildRequest = {
            HookRequest(
                name = ServiceContainer.getString(R.string.hook_template_high_affection_once_name),
                event = "character.before_turn.finished",
                scope = "global",
                priority = 10,
                triggerMode = "once_per_conversation",
                conditions = JsonParser.parseString("""{"affection_gte": 80}"""),
                actions = listOf(
                    logAction(R.string.hook_template_affection_log)
                )
            )
        }
    ),
    HookTemplate(
        key = "low_energy_notice",
        titleRes = R.string.hook_template_low_energy_title,
        descRes = R.string.hook_template_low_energy_desc,
        buildRequest = {
            HookRequest(
                name = ServiceContainer.getString(R.string.hook_template_low_energy_name),
                event = "character.before_turn.finished",
                scope = "global",
                priority = 20,
                triggerMode = "always",
                conditions = JsonParser.parseString("""{"energy_lte": 30}"""),
                actions = listOf(
                    logAction(R.string.hook_template_low_energy_log)
                )
            )
        }
    ),
    HookTemplate(
        key = "relationship_gain_memory",
        titleRes = R.string.hook_template_relationship_title,
        descRes = R.string.hook_template_relationship_desc,
        buildRequest = {
            HookRequest(
                name = ServiceContainer.getString(R.string.hook_template_relationship_name),
                event = "character.after_turn.finished",
                scope = "global",
                priority = 30,
                triggerMode = "once_per_conversation",
                conditions = JsonParser.parseString("""{"affection_gte": 60, "trust_gte": 50}"""),
                actions = listOf(
                    relationshipMemoryAction(),
                    logAction(R.string.hook_template_relationship_log)
                )
            )
        }
    ),
    HookTemplate(
        key = "model_call_logger",
        titleRes = R.string.hook_template_model_log_title,
        descRes = R.string.hook_template_model_log_desc,
        buildRequest = {
            HookRequest(
                name = ServiceContainer.getString(R.string.hook_template_model_log_name),
                event = "model.after_call",
                scope = "global",
                priority = 100,
                triggerMode = "always",
                conditions = JsonParser.parseString("""{}"""),
                actions = listOf(
                    logAction(R.string.hook_template_model_log)
                )
            )
        }
    )
)

private fun logAction(@StringRes messageRes: Int) = JsonObject().apply {
    addProperty("type", "log")
    addProperty("level", "info")
    addProperty("message", ServiceContainer.getString(messageRes))
}

private fun relationshipMemoryAction() = JsonObject().apply {
    addProperty("type", "memory_write")
    addProperty("category", "character_persona")
    addProperty("title", ServiceContainer.getString(R.string.hook_template_relationship_memory_title))
    addProperty("summary", ServiceContainer.getString(R.string.hook_template_relationship_memory_summary))
    addProperty("content", ServiceContainer.getString(R.string.hook_template_relationship_memory_content))
    addProperty("importance", 0.7)
}
