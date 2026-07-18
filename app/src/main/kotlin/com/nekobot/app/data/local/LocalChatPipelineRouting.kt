package com.nekobot.app.data.local

/**
 * 角色会话需要 Pipeline 提供角色运行时；Agent 会话即使没有绑定角色，
 * 也必须进入 Pipeline 才能产生进度卡片。
 */
internal fun shouldUseLocalPipeline(sessionMode: String, hasCharacter: Boolean): Boolean =
    hasCharacter || sessionMode.equals("agent", ignoreCase = true)
