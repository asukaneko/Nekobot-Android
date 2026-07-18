package com.nekobot.app.ui.screens.extensions

import com.google.gson.JsonParser
import com.nekobot.app.data.model.HookRequest

/**
 * Hook 内置模板：对应原仓库 nbot-methods.js 中的 window.__nbotHookTemplates。
 *
 * 每个模板可直接套用为 HookRequest，便于用户从示例快速创建可用的 Hook。
 */
data class HookTemplate(
    val key: String,
    val title: String,
    val desc: String,
    val buildRequest: () -> HookRequest
)

/** 5 个内置 Hook 模板，沿用原仓库的事件、作用域、条件与动作语义。 */
val BuiltInHookTemplates: List<HookTemplate> = listOf(
    HookTemplate(
        key = "high_affection_notice",
        title = "高好感提示",
        desc = "好感度达到 80 后，在聊天中显示亲近提示。",
        buildRequest = {
            HookRequest(
                name = "高好感度提示",
                event = "character.before_turn.finished",
                scope = "global",
                priority = 10,
                triggerMode = "always",
                conditions = JsonParser.parseString("""{"affection_gte": 80}"""),
                actions = listOf(
                    JsonParser.parseString(
                        """{"type":"log","level":"info","message":"好感度超过80，角色对用户更加亲近"}"""
                    )
                )
            )
        }
    ),
    HookTemplate(
        key = "high_affection_once_notice",
        title = "高好感首次提示",
        desc = "每个会话第一次达到高好感时提醒一次。",
        buildRequest = {
            HookRequest(
                name = "高好感度首次提示",
                event = "character.before_turn.finished",
                scope = "global",
                priority = 10,
                triggerMode = "once_per_conversation",
                conditions = JsonParser.parseString("""{"affection_gte": 80}"""),
                actions = listOf(
                    JsonParser.parseString(
                        """{"type":"log","level":"info","message":"好感度超过80，角色对用户更加亲近"}"""
                    )
                )
            )
        }
    ),
    HookTemplate(
        key = "low_energy_notice",
        title = "低精力提醒",
        desc = "角色精力较低时提示当前状态。",
        buildRequest = {
            HookRequest(
                name = "低精力提醒",
                event = "character.before_turn.finished",
                scope = "global",
                priority = 20,
                triggerMode = "always",
                conditions = JsonParser.parseString("""{"energy_lte": 30}"""),
                actions = listOf(
                    JsonParser.parseString(
                        """{"type":"log","level":"info","message":"角色精力较低，回复会更疲惫或克制"}"""
                    )
                )
            )
        }
    ),
    HookTemplate(
        key = "relationship_gain_memory",
        title = "关系升温记忆",
        desc = "关系达到阈值后写入一条短期记忆。",
        buildRequest = {
            HookRequest(
                name = "关系升温记忆",
                event = "character.after_turn.finished",
                scope = "global",
                priority = 30,
                triggerMode = "once_per_conversation",
                conditions = JsonParser.parseString("""{"affection_gte": 60, "trust_gte": 50}"""),
                actions = listOf(
                    JsonParser.parseString(
                        """{"type":"memory_write","category":"character_persona","title":"关系升温","summary":"角色对用户的亲近感上升","content":"用户与角色的关系正在升温，角色会更自然地表达亲近。","importance":0.7}"""
                    ),
                    JsonParser.parseString(
                        """{"type":"log","level":"info","message":"已记录关系升温记忆"}"""
                    )
                )
            )
        }
    ),
    HookTemplate(
        key = "model_call_logger",
        title = "模型调用日志",
        desc = "模型响应完成后记录一次调试日志。",
        buildRequest = {
            HookRequest(
                name = "模型调用日志",
                event = "model.after_call",
                scope = "global",
                priority = 100,
                triggerMode = "always",
                conditions = JsonParser.parseString("""{}"""),
                actions = listOf(
                    JsonParser.parseString(
                        """{"type":"log","level":"info","message":"模型调用完成"}"""
                    )
                )
            )
        }
    )
)
