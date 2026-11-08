package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * get_weather 的数据源、URL 拼装与响应解析。
 *
 * 改造前的实现固定请求 `wttr.in?format=j1` 但**只读取 current_condition**，
 * `days` 参数被完全忽略，所以 days=7 / days=8 与 days=1 返回同样的当天快照。
 * 而 wttr.in 的 j1 接口本身最多只有 3 天逐日预报，直接用它也满足不了「未来 7 天」的需求。
 *
 * 现在改为两级数据源（都是免费、无需 API Key 的 HTTPS 接口）：
 *
 * 1. 主用 Open-Meteo：先 Geocoding 把城市名解析成经纬度（支持中文名），
 *    再取 [MAX_FORECAST_DAYS] 天内的逐日预报，`days` 参数真正生效。
 * 2. 兜底 wttr.in：Geocoding 或主接口失败时退回旧接口，返回当天实况 + 最多
 *    [FALLBACK_MAX_FORECAST_DAYS] 天预报，并在 `note` 里说明天数上限，
 *    避免静默降级成「和 days=1 一模一样」。
 *
 * 本文件只做纯解析（输入 JSON 文本、输出工具结果 Map），网络请求留在调用方，
 * 便于用合成 JSON 做单元测试。
 */
internal object LocalWeather {

    /** Open-Meteo 单次请求可返回的逐日预报上限（天）。 */
    const val MAX_FORECAST_DAYS = 16

    /** wttr.in 兜底数据源的逐日预报上限（天）。 */
    const val FALLBACK_MAX_FORECAST_DAYS = 3

    /** 工具结果里的数据源标识。 */
    const val SOURCE_OPEN_METEO = "open-meteo"
    const val SOURCE_WTTR = "wttr.in"

    private val gson = Gson()

    /** 统一把 days 参数收敛到 1..[MAX_FORECAST_DAYS]，非数字/<=0 由调用方先兜底成 1。 */
    fun normalizeDays(raw: Int): Int = raw.coerceIn(1, MAX_FORECAST_DAYS)

    // ============ URL ============

    /**
     * 城市名 → 依次尝试的查询词。
     *
     * Open-Meteo 的 Geocoding 按名称精确匹配（"南通"有结果、"南通市"返回空），
     * 而模型/用户经常带「市/县/区/镇」后缀，因此去掉后缀再试一次，
     * 避免因为一个后缀就整条链降级到只有 3 天的兜底数据源。
     */
    fun geocodingQueries(city: String): List<String> {
        val base = city.trim()
        if (base.isEmpty()) return emptyList()
        val stripped = stripAdminSuffix(base)
        return listOfNotNull(base, stripped).distinct()
    }

    private fun stripAdminSuffix(name: String): String? {
        if (name.length < 3) return null
        if (name.last() !in ADMIN_SUFFIXES) return null
        return name.dropLast(1).trim().takeIf { it.isNotEmpty() }
    }

    private val ADMIN_SUFFIXES = charArrayOf('市', '县', '区', '镇')

    /** Geocoding 接口：取多个候选，由 [parseGeocoding] 按城市名精确匹配挑一个。 */
    fun geocodingUrl(city: String): String =
        "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${encode(city)}&count=5&language=zh&format=json"

    /** 逐日预报接口：current 给出实况，daily 给出逐日预报。 */
    fun forecastUrl(point: GeoPoint, days: Int): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${formatCoordinate(point.latitude)}" +
            "&longitude=${formatCoordinate(point.longitude)}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
            "wind_speed_10m_max,sunrise,sunset" +
            "&timezone=auto" +
            "&forecast_days=${normalizeDays(days)}"

    /** 兜底接口：旧 wttr.in j1（含实况 + 3 天逐日预报）。 */
    fun legacyUrl(city: String): String =
        "https://wttr.in/${encode(city)}?format=j1&lang=zh"

    // ============ 解析 ============

    /** Geocoding 结果里的坐标与规范城市名。 */
    data class GeoPoint(
        val latitude: Double,
        val longitude: Double,
        val resolvedName: String,
    )

    /**
     * 解析 Geocoding 响应。
     *
     * 候选里优先取与查询词完全同名的一条（忽略大小写与空格），否则取第一条
     * （上游按相关度/人口排序）。同名候选有多条时取人口最多的，避免选到同名小村镇。
     */
    fun parseGeocoding(raw: String, city: String): GeoPoint? {
        val root = parseObject(raw) ?: return null
        val results = root["results"] as? List<*> ?: return null
        val candidates = results.mapNotNull { it as? Map<*, *> }
            .filter { it["latitude"] != null && it["longitude"] != null }
        if (candidates.isEmpty()) return null
        val query = city.replace(" ", "")
        val exactMatches = candidates.filter { candidate ->
            candidate["name"]?.toString()?.replace(" ", "")?.equals(query, ignoreCase = true) == true
        }
        // 同名候选（如多省同名）优先人口最多的，避免解析到同名小村镇
        val matched: Map<*, *> = (if (exactMatches.isEmpty()) candidates else exactMatches)
            .maxBy { candidate -> (candidate["population"] as? Number)?.toDouble() ?: 0.0 }
        val latitude = (matched["latitude"] as? Number)?.toDouble() ?: return null
        val longitude = (matched["longitude"] as? Number)?.toDouble() ?: return null
        return GeoPoint(
            latitude = latitude,
            longitude = longitude,
            resolvedName = resolvedName(matched),
        )
    }

    /** "南通 / 江苏 / 中国" —— 让模型与用户都能确认查的是哪个城市。 */
    private fun resolvedName(candidate: Map<*, *>): String {
        val parts = listOfNotNull(
            candidate["name"]?.toString()?.takeIf(String::isNotBlank),
            candidate["admin1"]?.toString()?.takeIf(String::isNotBlank),
            candidate["country"]?.toString()?.takeIf(String::isNotBlank),
        ).distinct()
        return parts.joinToString(" / ")
    }

    /** 解析 Open-Meteo 逐日预报，返回可直接作为 get_weather 结果的 Map；解析失败返回 null。 */
    fun parseForecast(
        raw: String,
        city: String,
        requestedDays: Int,
        resolvedCity: String? = null,
    ): Map<String, Any>? {
        val root = parseObject(raw) ?: return null
        val current = root["current"] as? Map<*, *>
        val daily = root["daily"] as? Map<*, *>
        val dates = stringsAt(daily)
        if (dates.isEmpty()) return null
        val days = normalizeDays(requestedDays)
        val actual = minOf(days, dates.size)
        val forecast = (0 until actual).map { index ->
            buildMap<String, Any> {
                put("date", dates[index])
                put("weekday", weekdayLabel(dates[index]))
                numberAt(daily, "weather_code", index)?.let { code ->
                    put("description", weatherText(code.toInt()))
                }
                numberAt(daily, "temperature_2m_max", index)?.let { put("temp_max_c", it.roundToInt()) }
                numberAt(daily, "temperature_2m_min", index)?.let { put("temp_min_c", it.roundToInt()) }
                numberAt(daily, "precipitation_probability_max", index)?.let {
                    put("precipitation_probability", it.roundToInt().coerceIn(0, 100))
                }
                numberAt(daily, "wind_speed_10m_max", index)?.let { put("wind_max_kmph", it.roundToInt()) }
                textAt(daily, "sunrise", index)?.takeIf(String::isNotBlank)?.let { put("sunrise", clock(it)) }
                textAt(daily, "sunset", index)?.takeIf(String::isNotBlank)?.let { put("sunset", clock(it)) }
            }
        }
        return buildMap {
            put("success", true)
            put("source", SOURCE_OPEN_METEO)
            put("city", city)
            resolvedCity?.takeIf(String::isNotBlank)?.let { put("resolved_city", it) }
            put("timezone", root["timezone"]?.toString().orEmpty())
            current?.let { now ->
                scalarNumber(now, "temperature_2m")?.let { put("temperature_c", it.roundToInt()) }
                scalarNumber(now, "apparent_temperature")?.let { put("feels_like_c", it.roundToInt()) }
                scalarNumber(now, "relative_humidity_2m")?.let { put("humidity", it.roundToInt()) }
                scalarNumber(now, "wind_speed_10m")?.let { put("wind_kmph", it.roundToInt()) }
                scalarNumber(now, "weather_code")?.let { put("description", weatherText(it.toInt())) }
                now["time"]?.toString()?.takeIf(String::isNotBlank)?.let { put("observed_at", it) }
            }
            put("requested_days", requestedDays)
            put("days", forecast.size)
            put("max_days", MAX_FORECAST_DAYS)
            put("forecast", forecast)
            limitNote(requestedDays, forecast.size, MAX_FORECAST_DAYS)?.let { put("note", it) }
        }
    }

    /**
     * 解析兜底的 wttr.in j1 响应：实况沿用旧字段，逐日预报取 3 天，并显式标注天数上限。
     */
    fun parseLegacy(raw: String, city: String, requestedDays: Int): Map<String, Any>? {
        val root = parseObject(raw) ?: return null
        val current = (root["current_condition"] as? List<*>)?.firstOrNull() as? Map<*, *>
        val weather = (root["weather"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        if (current == null && weather.isEmpty()) return null
        val days = normalizeDays(requestedDays)
        val actual = minOf(days, FALLBACK_MAX_FORECAST_DAYS, weather.size)
        val forecast = (0 until actual).map { index ->
            val day = weather[index]
            val noon = (day["hourly"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.firstOrNull { it["time"]?.toString() == NOON_SLOT }
                ?: (day["hourly"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.firstOrNull()
            buildMap<String, Any> {
                val date = day["date"]?.toString().orEmpty()
                if (date.isNotBlank()) {
                    put("date", date)
                    put("weekday", weekdayLabel(date))
                }
                noon?.let { slot ->
                    localizedText(slot)?.takeIf(String::isNotBlank)?.let { put("description", it) }
                    scalarNumber(slot, "chanceofrain")?.let { put("precipitation_probability", it.roundToInt()) }
                }
                scalarNumber(day, "maxtempC")?.let { put("temp_max_c", it.roundToInt()) }
                scalarNumber(day, "mintempC")?.let { put("temp_min_c", it.roundToInt()) }
                hourlies(day["hourly"])
                    .mapNotNull { slot -> scalarNumber(slot, "windspeedKmph") }
                    .maxOrNull()
                    ?.let { put("wind_max_kmph", it.roundToInt()) }
                astro(day, "sunrise")?.let { put("sunrise", clock(it)) }
                astro(day, "sunset")?.let { put("sunset", clock(it)) }
            }
        }
        return buildMap {
            put("success", true)
            put("source", SOURCE_WTTR)
            put("city", city)
            current?.let { now ->
                now["temp_C"]?.toString()?.takeIf(String::isNotBlank)?.let { put("temperature_c", it.trim().toIntOrNull() ?: it) }
                now["FeelsLikeC"]?.toString()?.takeIf(String::isNotBlank)?.let { put("feels_like_c", it.trim().toIntOrNull() ?: it) }
                localizedText(now)?.takeIf(String::isNotBlank)?.let { put("description", it) }
                now["humidity"]?.toString()?.takeIf(String::isNotBlank)?.let { put("humidity", it.trim().toIntOrNull() ?: it) }
                now["windspeedKmph"]?.toString()?.takeIf(String::isNotBlank)?.let { put("wind_kmph", it.trim().toIntOrNull() ?: it) }
                now["observation_time"]?.toString()?.takeIf(String::isNotBlank)?.let { put("observed_at", it) }
            }
            put("requested_days", requestedDays)
            put("days", forecast.size)
            put("max_days", FALLBACK_MAX_FORECAST_DAYS)
            put("forecast", forecast)
            limitNote(requestedDays, forecast.size, FALLBACK_MAX_FORECAST_DAYS)?.let { put("note", it) }
        }
    }

    /** WMO 天气代码 → 中文描述（Open-Meteo 的 daily/current weather_code）。 */
    fun weatherText(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45 -> "雾"
        48 -> "雾凇"
        51 -> "小毛毛雨"
        53 -> "毛毛雨"
        55 -> "大毛毛雨"
        56 -> "冻毛毛雨"
        57 -> "强冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66 -> "冻雨"
        67 -> "强冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "米雪"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85 -> "小阵雪"
        86 -> "强阵雪"
        95 -> "雷阵雨"
        96 -> "雷阵雨伴小冰雹"
        99 -> "雷阵雨伴强冰雹"
        else -> "未知"
    }

    /** ISO 日期 → 周一..周日；无法解析时原样返回。 */
    fun weekdayLabel(isoDate: String): String {
        val date = runCatching { LocalDate.parse(isoDate.take(10)) }.getOrNull() ?: return isoDate
        return WEEKDAYS[date.dayOfWeek.value - 1]
    }

    // ============ 内部工具 ============

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** wttr.in 逐日预报里代表白天的时段（noon）。 */
    private const val NOON_SLOT = "1200"

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /** 经纬度保留 4 位小数足够定位到城市级，避免 URL 过长。 */
    private fun formatCoordinate(value: Double): String =
        String.format(java.util.Locale.US, "%.4f", value)

    private fun parseObject(raw: String): Map<*, *>? {
        if (raw.isBlank()) return null
        return runCatching { gson.fromJson(raw, Map::class.java) }.getOrNull()
    }

    /** 「请求天数 > 实际返回天数」时给模型一句明确说明，而不是让它以为拿到了完整数据。 */
    private fun limitNote(requestedDays: Int, actual: Int, maxDays: Int): String? {
        if (requestedDays <= actual) return null
        return if (requestedDays > maxDays) {
            "请求 $requestedDays 天超出数据源上限（最多 $maxDays 天），已返回 $actual 天逐日预报"
        } else {
            "数据源本次只返回 $actual 天逐日预报（请求 $requestedDays 天）"
        }
    }

    private fun stringsAt(daily: Map<*, *>?): List<String> =
        (daily?.get("time") as? List<*>).orEmpty()
            .mapNotNull { it?.toString()?.takeIf(String::isNotBlank)?.take(10) }

    private fun numberAt(source: Map<*, *>?, key: String, index: Int): Double? {
        val list = source?.get(key) as? List<*> ?: return null
        val value = list.getOrNull(index) ?: return null
        return (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull()
    }

    /** 标量字段取值（wttr.in 与 Open-Meteo 的 current 对象都是 "key": "value" 形式）。 */
    private fun scalarNumber(source: Map<*, *>, key: String): Double? {
        val value = source[key] ?: return null
        return (value as? Number)?.toDouble() ?: value.toString().trim().toDoubleOrNull()
    }

    private fun textAt(source: Map<*, *>?, key: String, index: Int): String? {
        val list = source?.get(key) as? List<*> ?: return null
        return list.getOrNull(index)?.toString()
    }

    private fun hourlies(raw: Any?): List<Map<*, *>> =
        (raw as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }

    private fun astro(day: Map<*, *>, key: String): String? =
        ((day["astronomy"] as? List<*>).orEmpty().firstOrNull() as? Map<*, *>)
            ?.get(key)?.toString()?.takeIf(String::isNotBlank)

    /**
     * wttr.in 的描述文案。
     *
     * 上游 `lang_zh` 经常直接回英文（实测 "Patchy rain nearby"），因此优先用天气代码映射中文；
     * 代码缺失时才退回 `lang_zh`（含中文才用）与 `weatherDesc`。
     */
    private fun localizedText(source: Map<*, *>): String? {
        val langZh = ((source["lang_zh"] as? List<*>).orEmpty().firstOrNull() as? Map<*, *>)
            ?.get("value")?.toString()?.trim()
        scalarNumber(source, "weatherCode")?.toInt()?.let { code ->
            wwoWeatherText(code).takeIf { it.isNotBlank() }?.let { return it }
        }
        if (!langZh.isNullOrBlank() && langZh.any { it in CJK_RANGE }) return langZh
        return ((source["weatherDesc"] as? List<*>).orEmpty().firstOrNull() as? Map<*, *>)
            ?.get("value")?.toString()?.trim()
            ?: langZh
    }

    /** WorldWeatherOnline 天气代码（wttr.in 的 weatherCode）→ 中文，仅兜底数据源使用。 */
    fun wwoWeatherText(code: Int): String = when (code) {
        113 -> "晴"
        116 -> "多云"
        119, 122 -> "阴"
        143 -> "薄雾"
        176, 263, 293 -> "零星小雨"
        179, 323, 326 -> "小雪"
        182, 317, 320, 362, 365 -> "雨夹雪"
        185, 281 -> "冻毛毛雨"
        284 -> "强冻毛毛雨"
        200 -> "雷阵雨"
        227 -> "吹雪"
        230 -> "暴风雪"
        248 -> "雾"
        260 -> "冻雾"
        266, 296 -> "小雨"
        299, 302 -> "中雨"
        305, 308 -> "大雨"
        311 -> "冻雨"
        314 -> "强冻雨"
        329, 332 -> "中雪"
        335, 338 -> "大雪"
        350, 374, 377 -> "冰粒"
        353 -> "小阵雨"
        356 -> "阵雨"
        359 -> "强阵雨"
        368 -> "小阵雪"
        371 -> "强阵雪"
        386 -> "雷阵雨"
        389 -> "强雷阵雨"
        392 -> "雷阵雪"
        395 -> "强雷阵雪"
        else -> ""
    }

    private val CJK_RANGE = '\u4e00'..'\u9fff'

    /** "06:19 AM" / "2026-11-08T06:19" → "06:19"。 */
    private fun clock(raw: String): String {
        val text = raw.trim()
        if (text.contains("T")) {
            return text.substringAfter("T").take(5)
        }
        return runCatching {
            val parsed = java.time.LocalTime.parse(text, CLOCK_FORMATTER)
            String.format(java.util.Locale.US, "%02d:%02d", parsed.hour, parsed.minute)
        }.getOrDefault(text)
    }

    private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
}
