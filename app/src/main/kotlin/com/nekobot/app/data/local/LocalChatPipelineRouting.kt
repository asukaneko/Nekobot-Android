package com.nekobot.app.data.local

/**
 * 角色会话需要 Pipeline 提供角色运行时；Agent 会话即使没有绑定角色，
 * 也必须进入 Pipeline 才能产生进度卡片；附件请求需要 Pipeline 完成解析与内容注入。
 */
internal fun shouldUseLocalPipeline(
    sessionMode: String,
    hasCharacter: Boolean,
    hasAttachments: Boolean = false
): Boolean =
    hasAttachments || hasCharacter || sessionMode.equals("agent", ignoreCase = true)
