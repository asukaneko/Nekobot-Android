package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionMemoryEntity
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CompanionImportPolicyTest {
    private val memory = CompanionMemoryEntity("m", "s", "k", 1, "fact", "玩家", "旧记忆", "active",
        createdAt = "2020-01-01", updatedAt = "2020-01-01")
    private val now = Instant.parse("2026-10-09T04:00:00Z")

    @Test fun expiryUsesAnInstantAndRejectsUnknownFormats() {
        assertTrue(CompanionImportPolicy.usable(memory, now))
        assertTrue(CompanionImportPolicy.usable(memory.copy(expiresAt = "2026-10-09T12:00:01+08:00"), now))
        assertFalse(CompanionImportPolicy.usable(memory.copy(expiresAt = "2026-10-09T12:00:00+08:00"), now))
        assertFalse(CompanionImportPolicy.usable(memory.copy(expiresAt = "无法识别的旧格式"), now))
        assertFalse(CompanionImportPolicy.usable(memory.copy(valid = false), now))
    }

    @Test fun importedEvidenceNeverClaimsManualConfirmation() {
        val imported = memory.copy(provenance = CompanionImportPolicy.IMPORTED_UNVERIFIED)
        assertTrue(CompanionImportPolicy.evidence(imported).contains("未验证"))
        assertFalse(imported.manual)
        assertEquals("玩家手动记录", CompanionImportPolicy.evidence(imported.copy(manual = true)))
        val verified = imported.copy(provenance = CompanionImportPolicy.IMPORTED_VERIFIED)
        assertTrue(CompanionImportPolicy.evidence(verified).contains("可核验"))
        assertFalse(CompanionImportPolicy.evidence(verified).contains("玩家手动"))
    }
}
