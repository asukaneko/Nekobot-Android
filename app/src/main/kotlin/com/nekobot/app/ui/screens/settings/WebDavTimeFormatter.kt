package com.nekobot.app.ui.screens.settings

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 将备份中的 ISO 时间和 WebDAV 的 HTTP 时间统一转换为手机本地时间。 */
internal fun formatWebDavTimestamp(raw: String?, zoneId: ZoneId = ZoneId.systemDefault()): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val isoValue = value.replaceFirst(' ', 'T')
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val instant = runCatching { Instant.parse(isoValue) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(isoValue).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(isoValue).toInstant() }.getOrNull()
        ?: runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull()

    if (instant != null) return instant.atZone(zoneId).format(formatter)

    // 旧服务端记录可能没有时区；保留原日期和时间，不能假定它们属于 UTC。
    return runCatching {
        LocalDateTime.parse(isoValue, DateTimeFormatter.ISO_LOCAL_DATE_TIME).format(formatter)
    }.getOrDefault(value)
}
