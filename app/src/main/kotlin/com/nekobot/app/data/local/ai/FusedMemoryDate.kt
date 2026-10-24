package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.ai.time.TimeWordEngine
import com.nekobot.app.data.local.db.LocalMessageEntity
import java.time.LocalDate

internal data class FusedMemoryDate(val day: LocalDate?, val source: String, val missingTimeEvidence: Boolean = false, val overridden: Boolean = false) {
    companion object {
        fun resolve(op: FusedMemoryOperation, evidence: List<LocalMessageEntity>): FusedMemoryDate {
            if (op.dateType == "habit") return FusedMemoryDate(null, "none")
            val matching = evidence.firstOrNull { op.timeText.isNotBlank() && op.timeText in it.content }
            val spoken = matching?.let(::agentUtteranceDay)
            val missing = op.timeText.isNotBlank() && matching == null
            if (op.timeText.isBlank() || missing) return FusedMemoryDate(null, "none", missing)
            val model = runCatching { LocalDate.parse(op.modelDate) }.getOrNull()
            val calculated = spoken?.let { TimeWordEngine.resolve(op.timeText, it) } as? TimeWordEngine.Result.Day
            return if (calculated != null) FusedMemoryDate(calculated.date, "program", missing, model != null && calculated.date != model)
                else FusedMemoryDate(null, "none", missing)
        }
    }
}
