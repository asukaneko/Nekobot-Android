package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * get_weather 逐日预报解析的回归测试（issue #34：days>1 时只返回当天快照）。
 *
 * 全部使用合成 JSON 占位数据，不依赖网络。
 */
class LocalWeatherTest {

    @Test
    fun `days 参数被收敛到 1 到上限之间`() {
        assertEquals(1, LocalWeather.normalizeDays(0))
        assertEquals(1, LocalWeather.normalizeDays(-3))
        assertEquals(7, LocalWeather.normalizeDays(7))
        assertEquals(LocalWeather.MAX_FORECAST_DAYS, LocalWeather.normalizeDays(99))
    }

    @Test
    fun `逐日预报按 days 返回对应天数且字段完整`() {
        val payload = LocalWeather.parseForecast(sampleForecastJson(days = 7), "示例市", 7)
        assertNotNull(payload)
        payload!!

        assertEquals(true, payload["success"])
        assertEquals(LocalWeather.SOURCE_OPEN_METEO, payload["source"])
        assertEquals("示例市", payload["city"])
        assertEquals(7, payload["days"])
        assertEquals(7, payload["requested_days"])
        assertEquals(LocalWeather.MAX_FORECAST_DAYS, payload["max_days"])
        // 请求天数没超上限时不该出现降级说明
        assertFalse(payload.containsKey("note"))

        @Suppress("UNCHECKED_CAST")
        val forecast = payload["forecast"] as List<Map<String, Any>>
        assertEquals(7, forecast.size)
        assertEquals("2026-11-08", forecast.first()["date"])
        assertEquals("周日", forecast.first()["weekday"])
        assertEquals("阴", forecast.first()["description"])
        // 16.6 / 14.0 / 22.6 四舍五入后落到整数，保持与旧版本「整数温度」体验一致
        assertEquals(17, forecast.first()["temp_max_c"])
        assertEquals(14, forecast.first()["temp_min_c"])
        assertEquals(100, forecast.first()["precipitation_probability"])
        assertEquals(23, forecast.first()["wind_max_kmph"])
        assertEquals("06:19", forecast.first()["sunrise"])
        assertEquals("17:01", forecast.first()["sunset"])

        // 实况字段保持与旧版本一致，避免调用方/模型侧回归
        assertEquals(15, payload["temperature_c"])
        assertEquals(12, payload["feels_like_c"])
        assertEquals(92, payload["humidity"])
        assertEquals(22, payload["wind_kmph"])
        assertEquals("阴", payload["description"])
    }

    @Test
    fun `上游天数不足时给出上限说明而不是静默降级`() {
        val payload = LocalWeather.parseForecast(sampleForecastJson(days = 3), "示例市", 8)
        assertNotNull(payload)
        payload!!

        assertEquals(3, payload["days"])
        assertEquals(8, payload["requested_days"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(3, (payload["forecast"] as List<*>).size)
        val note = payload["note"] as String
        assertTrue(note, note.contains("8"))
        assertTrue(note, note.contains("3"))
    }

    @Test
    fun `请求超过最大天数时说明数据源上限`() {
        val payload = LocalWeather.parseForecast(sampleForecastJson(days = 16), "示例市", 30)
        assertNotNull(payload)
        payload!!

        assertEquals(LocalWeather.MAX_FORECAST_DAYS, payload["days"])
        val note = payload["note"] as String
        assertTrue(note, note.contains("30"))
        assertTrue(note, note.contains(LocalWeather.MAX_FORECAST_DAYS.toString()))
    }

    @Test
    fun `响应缺少 daily 时返回 null 交给兜底数据源`() {
        assertNull(LocalWeather.parseForecast("""{"current":{"temperature_2m":1.0}}""", "示例市", 7))
        assertNull(LocalWeather.parseForecast("", "示例市", 7))
        assertNull(LocalWeather.parseForecast("不是 JSON", "示例市", 7))
    }

    @Test
    fun `带行政后缀的城市名会去掉后缀再试`() {
        // 原名优先，去后缀作为第二次尝试（上游对「示例市」查不到、对「示例」能查到）
        assertEquals(listOf("示例市", "示例"), LocalWeather.geocodingQueries(" 示例市 "))
        // 去掉后缀后过短或后缀不同名时保持原样
        assertEquals(listOf("示例市新城"), LocalWeather.geocodingQueries("示例市新城"))
        assertEquals(listOf("沪"), LocalWeather.geocodingQueries("沪"))
        assertEquals(emptyList<String>(), LocalWeather.geocodingQueries("   "))
        val stripped = LocalWeather.geocodingQueries("某某市")
        assertEquals(listOf("某某市", "某某"), stripped)
    }

    @Test
    fun `城市解析优先精确同名候选`() {
        val json = """
            {"results":[
              {"name":"示例市新城区","latitude":1.1,"longitude":2.2,"population":5000,
               "admin1":"示例省","country":"示例国"},
              {"name":"示例市","latitude":32.03,"longitude":120.87,"population":2000000,
               "admin1":"示例省","country":"示例国"}
            ]}
        """.trimIndent()
        val point = LocalWeather.parseGeocoding(json, "示例市")
        assertNotNull(point)
        assertEquals(32.03, point!!.latitude, 0.0001)
        assertEquals(120.87, point.longitude, 0.0001)
        assertEquals("示例市 / 示例省 / 示例国", point.resolvedName)
    }

    @Test
    fun `同名候选选人口最多的一个`() {
        val json = """
            {"results":[
              {"name":"示例市","latitude":24.0,"longitude":110.0,"population":30000,
               "admin1":"示例省","country":"示例国"},
              {"name":"示例市","latitude":32.03,"longitude":120.87,"population":2000000,
               "admin1":"示例省","country":"示例国"}
            ]}
        """.trimIndent()
        val point = LocalWeather.parseGeocoding(json, "示例市")
        assertNotNull(point)
        assertEquals(32.03, point!!.latitude, 0.0001)
    }

    @Test
    fun `城市解析无结果时返回 null`() {
        assertNull(LocalWeather.parseGeocoding("""{"generationtime_ms":0.1}""", "示例市"))
        assertNull(LocalWeather.parseGeocoding("""{"results":[]}""", "示例市"))
        assertNull(LocalWeather.parseGeocoding("", "示例市"))
    }

    @Test
    fun `预报 URL 携带天数与坐标`() {
        val point = LocalWeather.GeoPoint(32.03028, 120.87472, "示例市")
        val url = LocalWeather.forecastUrl(point, 7)
        assertTrue(url, url.contains("forecast_days=7"))
        assertTrue(url, url.contains("latitude=32.0303"))
        assertTrue(url, url.contains("timezone=auto"))
        assertTrue(url, url.contains("daily=weather_code"))
        // 超上限请求也要被收敛
        assertTrue(LocalWeather.forecastUrl(point, 99).contains("forecast_days=16"))
    }

    @Test
    fun `兜底数据源返回当天实况与最多三天预报`() {
        val payload = LocalWeather.parseLegacy(sampleLegacyJson(), "示例市", 7)
        assertNotNull(payload)
        payload!!

        assertEquals(LocalWeather.SOURCE_WTTR, payload["source"])
        assertEquals(15, payload["temperature_c"])
        assertEquals(12, payload["feels_like_c"])
        assertEquals(92, payload["humidity"])
        assertEquals(22, payload["wind_kmph"])
        assertEquals(LocalWeather.FALLBACK_MAX_FORECAST_DAYS, payload["days"])
        assertEquals(LocalWeather.FALLBACK_MAX_FORECAST_DAYS, payload["max_days"])

        @Suppress("UNCHECKED_CAST")
        val forecast = payload["forecast"] as List<Map<String, Any>>
        assertEquals(3, forecast.size)
        assertEquals("2026-11-08", forecast.first()["date"])
        assertEquals(16, forecast.first()["temp_max_c"])
        assertEquals(14, forecast.first()["temp_min_c"])
        assertEquals("阴", forecast.first()["description"])
        assertEquals(80, forecast.first()["precipitation_probability"])
        assertEquals("06:19", forecast.first()["sunrise"])

        val note = payload["note"] as String
        assertTrue(note, note.contains("7"))
        assertTrue(note, note.contains("3"))
    }

    @Test
    fun `兜底数据源的中文描述优先用天气代码映射`() {
        // wttr.in 的 lang_zh 实测会直接回英文，此时用 weatherCode 映射中文
        val json = """
            {"current_condition":[
              {"temp_C":"15","weatherCode":"122",
               "lang_zh":[{"value":"Overcast"}],"weatherDesc":[{"value":"Overcast"}]}
            ],
             "weather":[{"date":"2026-11-08","maxtempC":"16","mintempC":"14","hourly":[
               {"time":"1200","weatherCode":"296","lang_zh":[{"value":"Light rain"}],
                "weatherDesc":[{"value":"Light rain"}]}
             ]}]}
        """.trimIndent()
        val payload = LocalWeather.parseLegacy(json, "示例市", 1)!!
        assertEquals("阴", payload["description"])
        @Suppress("UNCHECKED_CAST")
        val forecast = payload["forecast"] as List<Map<String, Any>>
        assertEquals("小雨", forecast.first()["description"])
    }

    @Test
    fun `天气代码与星期文案`() {
        assertEquals("晴", LocalWeather.weatherText(0))
        assertEquals("阴", LocalWeather.weatherText(3))
        assertEquals("雷阵雨伴强冰雹", LocalWeather.weatherText(99))
        assertEquals("未知", LocalWeather.weatherText(12345))

        assertEquals("晴", LocalWeather.wwoWeatherText(113))
        assertEquals("大雨", LocalWeather.wwoWeatherText(308))
        assertEquals("", LocalWeather.wwoWeatherText(1))

        assertEquals("周日", LocalWeather.weekdayLabel("2026-11-08"))
        assertEquals("周一", LocalWeather.weekdayLabel("2026-11-09"))
        assertEquals("无法解析", LocalWeather.weekdayLabel("无法解析"))
    }

    /** 合成 Open-Meteo 响应：第 1 天固定值，其余天用重复占位数据。 */
    private fun sampleForecastJson(days: Int): String {
        val dates = (0 until days).joinToString(",") { index ->
            val day = 8 + index
            "\"2026-11-${day.toString().padStart(2, '0')}\""
        }
        val codes = (0 until days).joinToString(",") { "3" }
        val maxTemps = (0 until days).joinToString(",") { "16.6" }
        val minTemps = (0 until days).joinToString(",") { "14.0" }
        val precip = (0 until days).joinToString(",") { "100" }
        val winds = (0 until days).joinToString(",") { "22.6" }
        val sunrises = (0 until days).joinToString(",") { "\"2026-11-08T06:19\"" }
        val sunsets = (0 until days).joinToString(",") { "\"2026-11-08T17:01\"" }
        return """
            {
              "latitude": 32.03, "longitude": 120.87,
              "timezone": "Asia/Shanghai",
              "current": {
                "time": "2026-11-08T23:00", "temperature_2m": 15.2,
                "apparent_temperature": 12.4, "relative_humidity_2m": 92,
                "weather_code": 3, "wind_speed_10m": 22.1
              },
              "daily": {
                "time": [$dates],
                "weather_code": [$codes],
                "temperature_2m_max": [$maxTemps],
                "temperature_2m_min": [$minTemps],
                "precipitation_probability_max": [$precip],
                "wind_speed_10m_max": [$winds],
                "sunrise": [$sunrises],
                "sunset": [$sunsets]
              }
            }
        """.trimIndent()
    }

    /** 合成 wttr.in j1 响应（截取必要字段）。 */
    private fun sampleLegacyJson(): String = """
        {
          "current_condition": [{
            "temp_C": "15", "FeelsLikeC": "12", "humidity": "92",
            "windspeedKmph": "22", "observation_time": "01:46 PM",
            "lang_zh": [{"value": "阴"}], "weatherDesc": [{"value": "Overcast"}]
          }],
          "weather": [
            {
              "date": "2026-11-08", "maxtempC": "16", "mintempC": "14",
              "astronomy": [{"sunrise": "06:19 AM", "sunset": "05:01 PM"}],
              "hourly": [
                {"time": "0", "chanceofrain": "10", "windspeedKmph": "12",
                 "lang_zh": [{"value": "晴"}], "weatherDesc": [{"value": "Clear"}]},
                {"time": "1200", "chanceofrain": "80", "windspeedKmph": "22",
                 "lang_zh": [{"value": "阴"}], "weatherDesc": [{"value": "Overcast"}]}
              ]
            },
            {
              "date": "2026-11-09", "maxtempC": "15", "mintempC": "12",
              "astronomy": [{"sunrise": "06:20 AM", "sunset": "05:00 PM"}],
              "hourly": [
                {"time": "1200", "chanceofrain": "30", "windspeedKmph": "18",
                 "lang_zh": [{"value": "多云"}], "weatherDesc": [{"value": "Cloudy"}]}
              ]
            },
            {
              "date": "2026-11-10", "maxtempC": "17", "mintempC": "13",
              "astronomy": [{"sunrise": "06:21 AM", "sunset": "04:59 PM"}],
              "hourly": [
                {"time": "1200", "chanceofrain": "0", "windspeedKmph": "9",
                 "weatherDesc": [{"value": "Sunny"}]}
              ]
            }
          ]
        }
    """.trimIndent()
}
