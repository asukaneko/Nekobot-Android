package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionMemoryEntity

internal object CompanionUserProfile {
    /** 引号内完整保留原文，避免把玩家的“我”误改成角色身份；不推测额外事实或日期。 */
    fun converted(persona: String?, scopeId: String, now: String): CompanionMemoryEntity? = persona?.trim()
        ?.takeIf(String::isNotEmpty)?.let { original ->
            CompanionMemoryEntity(id = CompanionMemoryPolicy.digest("$scopeId:converted-user-persona"), scopeId = scopeId,
                factKey = "converted_user_profile", version = 1, kind = "fact", subject = "用户",
                content = "我记得对方这样介绍自己：“$original”", status = "active", category = "user_profile",
                manual = true, provenance = "converted_user_persona", createdAt = now, updatedAt = now)
        }
}
