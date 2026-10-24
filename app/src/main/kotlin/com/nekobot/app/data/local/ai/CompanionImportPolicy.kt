package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionMemoryEntity
import java.time.*

/** 旧记忆的来源声明与有效期属于事实本身，不由迁入时间或 UI 标签代替。 */
internal object CompanionImportPolicy {
    const val NATIVE = "native"
    const val IMPORTED_UNVERIFIED = "imported_unverified"
    const val IMPORTED_VERIFIED = "imported_verified"
    const val UNVERIFIED_EVIDENCE = "迁入的旧记忆，原话来源未验证；不能当作玩家已确认的事实"

    fun usable(memory: CompanionMemoryEntity, now: Instant = Instant.now()): Boolean {
        if (!memory.valid) return false
        val expiry = memory.expiresAt?.takeIf { it.isNotBlank() } ?: return true
        // 不能解析的非空过期时间按待核对处理，不能将其扩大成永久有效。
        val instant = runCatching { Instant.parse(expiry) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(expiry).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(expiry.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(expiry).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
            ?: return false
        return now.isBefore(instant)
    }

    fun evidence(memory: CompanionMemoryEntity): String = when {
        memory.manual -> "玩家手动记录"
        memory.provenance == IMPORTED_UNVERIFIED -> UNVERIFIED_EVIDENCE
        memory.provenance == IMPORTED_VERIFIED -> "迁入的旧记忆，已有可核验的聊天原话"
        else -> "可核验的聊天原话"
    }
}
