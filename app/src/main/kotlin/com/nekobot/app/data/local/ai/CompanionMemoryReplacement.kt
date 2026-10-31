package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionMemoryEntity

/** 只有已确认替换的同一主题可以删除；相似正文、相同分类都不是删除依据。 */
internal object CompanionMemoryReplacement {
    fun replacedIds(rows: List<CompanionMemoryEntity>, scopeId: String, key: String, keepId: String? = null): List<String> {
        val replacement = keepId?.let { id -> rows.firstOrNull {
            it.id == id && it.scopeId == scopeId && it.factKey == key && it.valid && it.current &&
                it.status in setOf("active", "completed", "cancelled")
        } ?: return emptyList() }
        return rows.filter { it.scopeId == scopeId && it.factKey == key && it.id != keepId && !it.locked &&
            it.status !in setOf("conflict", "locked_conflict") &&
            (replacement == null || it.version < replacement.version) }.map { it.id }
    }

    /** 导入时先去掉同主题已被明确新版本替代的历史，未确认矛盾和人工保护仍保留。 */
    fun imported(rows: List<CompanionMemoryEntity>): List<CompanionMemoryEntity> =
        rows.groupBy { it.scopeId to it.factKey }.values.flatMap { versions ->
            val final = versions.filter { it.valid && (it.current && it.status in setOf("active", "completed", "cancelled") || it.status == "retracted") }
                .maxByOrNull { it.version }
            versions.filter { it.locked || final == null || it.version > final.version ||
                it.version == final.version && final.status != "retracted" || it.status in setOf("conflict", "locked_conflict") }
        }
}
