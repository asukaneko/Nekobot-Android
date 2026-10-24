package com.nekobot.app.ui.screens.memory

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.nekobot.app.R
import java.time.LocalDate

internal data class MemoryDateLabel(val resource: Int, val arguments: List<Int> = emptyList())

/** 只格式化已有日期，不推算时间词，不改写持久化日期。 */
internal fun memoryDateLabel(value: String, today: LocalDate): MemoryDateLabel? {
    val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
    return when (date) {
        today -> MemoryDateLabel(R.string.chat_today)
        today.minusDays(1) -> MemoryDateLabel(R.string.chat_yesterday)
        else -> if (date.year == today.year) MemoryDateLabel(R.string.chat_month_day, listOf(date.monthValue, date.dayOfMonth))
            else MemoryDateLabel(R.string.chat_year_month_day, listOf(date.year, date.monthValue, date.dayOfMonth))
    }
}

@Composable
internal fun memoryDisplayDate(value: String): String {
    val resources = LocalResources.current
    val label = memoryDateLabel(value, LocalDate.now()) ?: return value
    return resources.getString(label.resource, *label.arguments.toTypedArray())
}
