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
internal fun shouldInjectWorldBooks(sessionMode: String?): Boolean =
    !sessionMode.equals("agent", ignoreCase = true)
