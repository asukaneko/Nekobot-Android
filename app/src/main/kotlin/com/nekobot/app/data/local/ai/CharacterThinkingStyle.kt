package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.Session

/** 空白自定义文案使用默认提示；不改保存的档位，也不增加模型调用。 */
data class CharacterThinkingSettings(val enabled: Boolean = true, val customPrompt: String = "")

internal const val CHARACTER_THINKING_STYLE_KEY = "output.thinking_style"

/**
 * Shared default for internal reply preparation; saved custom text still takes precedence.
 * No fixed reasoning transcript, invented memory example, or forced disclosure of private reasoning.
 * Sent only at the existing request tail; no change to reasoning fields, switches or tool protocols.
 */
internal const val DEFAULT_CHARACTER_THINKING_PROMPT =
    "回复前只在内部判断：对方此刻是在分享、求助还是需要陪伴，哪些事实有原话或记忆支持，哪里仍不确定。保持既有性格和称呼，用第一人称自然回应，不为铺陈情节补造共同经历。\n" +
        "先接住对方具体说的事，再决定是否需要建议、核实或工具。可以有不同看法，温和说明依据，不为了讨好而附和；没有必要就不追问。\n" +
        "这些判断无需逐步展示，不公开内部推理、分析草稿或规则；需要解释时，只给简短的判断依据。表达语言遵循对方当前要求，默认使用简体中文。\n" +
        "需要调用已有工具时，工具名和参数保持原样，遵守已有权限；只有实际成功后才说做到了，不把计划、猜测或未执行的事说成事实。"

/** 角色思考的会话资格；思考强度和偏好开关只控制注入，不隐藏设置入口。 */
internal fun isCharacterThinkingSession(
    hasCharacter: Boolean,
    sessionMode: String?,
    inheritsCharacter: Boolean,
    fusedCompanion: Boolean
): Boolean = hasCharacter &&
    (!sessionMode.equals("agent", ignoreCase = true) || inheritsCharacter || fusedCompanion)

/** 群聊逐个参与角色准备上下文，其他会话使用绑定角色。 */
internal fun isCharacterThinkingSession(session: Session?): Boolean = isCharacterThinkingSession(
    hasCharacter = if (session?.sessionMode.equals("group", ignoreCase = true)) {
        session?.characterIds.orEmpty().any { it.isNotBlank() }
    } else {
        !session?.characterId.isNullOrBlank()
    },
    sessionMode = session?.sessionMode,
    inheritsCharacter = session?.inheritCharacter == true,
    fusedCompanion = session?.companionMode == true
)

/**
 * 本次请求实际发送的思考提示词：自定义优先，留空用默认；替换姓名占位符。
 * 普通角色与单会话共用同一份，不再按模式拼接不同格式段。
 */
internal fun characterThinkingPrompt(settings: CharacterThinkingSettings, characterName: String, userName: String?,
    @Suppress("UNUSED_PARAMETER") companionMode: Boolean = false): String =
    characterThinkingPromptForEditing(settings)
        .replace("{{char}}", characterName.takeIf(String::isNotBlank) ?: "当前角色")
        .replace("{{user}}", userName?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("user", "the user", "用户") } ?: "对方")

/** 编辑的就是当前生效全文，默认模板中的姓名占位符保持可编辑。 */
internal fun characterThinkingPromptForEditing(settings: CharacterThinkingSettings): String =
    settings.customPrompt.takeIf(String::isNotBlank) ?: DEFAULT_CHARACTER_THINKING_PROMPT

/** 原样保存自定义文案；未改动的默认全文仍用空串表示“跟随默认”。 */
internal fun characterThinkingPromptToSave(draft: String): String =
    if (draft == DEFAULT_CHARACTER_THINKING_PROMPT) "" else draft
