package com.nekobot.app.data.local

/**
 * 角色/群聊会话需要 Pipeline 提供角色运行时；Agent 会话即使没有绑定角色，
 * 也必须进入 Pipeline 才能产生进度卡片；附件请求需要 Pipeline 完成解析与内容注入。
 */
internal fun shouldUseLocalPipeline(
    sessionMode: String,
    hasCharacter: Boolean,
    hasAttachments: Boolean = false
): Boolean =
    hasAttachments || hasCharacter ||
        sessionMode.equals("agent", ignoreCase = true) ||
        sessionMode.equals("group", ignoreCase = true)

/** Agent 是通用工具会话，不继承角色世界观；世界书只属于角色/群聊链路。 */
internal fun shouldInjectWorldBooks(
    sessionMode: String?,
    inheritCharacter: Boolean = false
): Boolean =
    !sessionMode.equals("agent", ignoreCase = true) || inheritCharacter

/**
 * Agent 会话是否开启「继承完整角色能力」。
 *
 * 只对绑定了角色的 Agent 会话有意义；群聊与角色会话本身就走角色链路，无需该开关。
 */
internal fun inheritsCharacter(
    sessionMode: String?,
    inheritCharacter: Boolean?
): Boolean =
    inheritCharacter == true &&
        sessionMode.equals("agent", ignoreCase = true)

/** Agent 会话在继承角色能力时使用的元数据键（管线各阶段据此放行角色相关注入）。 */
internal const val META_INHERIT_CHARACTER = "inherit_character"
