package com.nekobot.app.data.local.automation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 本地自动化下一次运行时间计算。
 *
 * Cron 使用标准 5 字段格式（分 时 日 月 周），支持通配符、步长、范围和逗号列表。
 * WorkManager 最终使用一次性任务；每次执行结束后重新计算，避免系统休眠后周期漂移。
 */
object LocalScheduleCalculator {
    private const val MAX_CRON_SEARCH_MINUTES = 366 * 24 * 60

    fun nextRun(
        trigger: String,
        configJson: String?,
        from: ZonedDateTime = ZonedDateTime.now()
    ): Instant? {
        val config = parseConfig(configJson)
        return when (trigger.lowercase()) {
            "interval" -> {
                val minutes = config.int("interval_minutes", 60).coerceAtLeast(1)
                from.plusMinutes(minutes.toLong()).toInstant()
            }
            "cron" -> nextCron(config.string("cron").orEmpty(), from)?.toInstant()
            "run_at", "date" -> parseRunAt(config.string("run_at"), from.zone)?.toInstant()
            else -> null
        }
    }

    fun nextCron(expression: String, from: ZonedDateTime): ZonedDateTime? {
        val parts = expression.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (parts.size != 5) return null
        val minute = CronField.parse(parts[0], 0, 59) ?: return null
        val hour = CronField.parse(parts[1], 0, 23) ?: return null
        val day = CronField.parse(parts[2], 1, 31) ?: return null
        val month = CronField.parse(parts[3], 1, 12) ?: return null
        val weekday = CronField.parse(parts[4], 0, 7, normalizeSunday = true) ?: return null

        var candidate = from.withSecond(0).withNano(0).plusMinutes(1)
        repeat(MAX_CRON_SEARCH_MINUTES) {
            val cronWeekday = candidate.dayOfWeek.value % 7
            if (
                minute.matches(candidate.minute) &&
                hour.matches(candidate.hour) &&
                day.matches(candidate.dayOfMonth) &&
                month.matches(candidate.monthValue) &&
                weekday.matches(cronWeekday)
            ) {
                return candidate
            }
            candidate = candidate.plusMinutes(1)
        }
        return null
    }

    fun parseRunAt(raw: String?, zoneId: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        runCatching { return ZonedDateTime.parse(value) }
        runCatching { return OffsetDateTime.parse(value).toZonedDateTime() }
        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        )
        for (formatter in formatters) {
            try {
                return LocalDateTime.parse(value, formatter).atZone(zoneId)
            } catch (_: DateTimeParseException) {
                // 尝试下一种常见格式。
            }
        }
        return null
    }

    private fun parseConfig(raw: String?): JsonObject = runCatching {
        raw?.takeIf(String::isNotBlank)
            ?.let(JsonParser::parseString)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
    }.getOrNull() ?: JsonObject()

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String, default: Int): Int =
        runCatching { get(key)?.asInt }.getOrNull() ?: default

    private data class CronField(
        private val allowed: Set<Int>
    ) {
        fun matches(value: Int): Boolean = value in allowed

        companion object {
            fun parse(
                raw: String,
                min: Int,
                max: Int,
                normalizeSunday: Boolean = false
            ): CronField? {
                val values = linkedSetOf<Int>()
                for (part in raw.split(',')) {
                    val token = part.trim()
                    if (token.isEmpty()) return null
                    val (rangeToken, step) = if ('/' in token) {
                        val segments = token.split('/', limit = 2)
                        val parsedStep = segments.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                            ?: return null
                        segments[0] to parsedStep
                    } else {
                        token to 1
                    }
                    val range = when {
                        rangeToken == "*" -> min..max
                        '-' in rangeToken -> {
                            val bounds = rangeToken.split('-', limit = 2)
                            val start = bounds.getOrNull(0)?.toIntOrNull() ?: return null
                            val end = bounds.getOrNull(1)?.toIntOrNull() ?: return null
                            if (start > end) return null
                            start..end
                        }
                        else -> {
                            val single = rangeToken.toIntOrNull() ?: return null
                            single..single
                        }
                    }
                    for (value in range step step) {
                        if (value !in min..max) return null
                        values += if (normalizeSunday && value == 7) 0 else value
                    }
                }
                return values.takeIf { it.isNotEmpty() }?.let(::CronField)
            }
        }
    }
}
