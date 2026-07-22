package com.nekobot.app.data.local.ai

/** 这些类别代表当前状态，写入时必须整体替换，不能累积历史版本。 */
internal fun isSingleSlotMemoryCategory(category: String): Boolean =
    category == "user_persona" || category == "recent_digest"
