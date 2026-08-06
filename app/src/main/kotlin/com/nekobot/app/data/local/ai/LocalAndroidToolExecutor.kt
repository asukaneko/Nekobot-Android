package com.nekobot.app.data.local.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.AlarmClock
import android.provider.Settings
import com.nekobot.app.data.remote.ExecAuthorization
import com.nekobot.app.data.remote.ExecConfirmationRequest
import java.util.Locale
import java.util.TimeZone

/**
 * Agent 可调用的 Android 原生能力。
 *
 * 这里仅封装 Android API 和系统 Intent，不直接模拟点击其他应用，也不绕过系统权限或确认页面。
 */
internal class LocalAndroidToolExecutor(
    private val context: Context?,
    private val sessionId: String,
    private val authorizationManager: LocalExecAuthorizationManager,
    private val onConfirmationRequired: (ExecConfirmationRequest) -> Unit
) {
    fun execute(toolName: String, args: Map<String, Any>): Map<String, Any> {
        val appContext = context?.applicationContext
            ?: return failure("Android 应用上下文不可用")
        return try {
            when (toolName) {
                "android_device_info" -> deviceInfo(appContext)
                "android_battery_status" -> batteryStatus(appContext)
                "android_clipboard_read" -> readClipboard(appContext)
                "android_clipboard_write" -> writeClipboard(appContext, args)
                "android_open_url" -> openUrl(appContext, args)
                "android_open_app" -> openApp(appContext, args)
                "android_open_settings" -> openSettings(appContext, args)
                "android_create_calendar_event" -> createCalendarEvent(appContext, args)
                "android_set_alarm" -> setAlarm(appContext, args)
                "android_volume" -> volume(appContext, args)
                else -> failure("Android 模式不支持工具: $toolName")
            }
        } catch (error: Exception) {
            failure(error.message ?: "Android 工具执行失败")
        }
    }

    private fun deviceInfo(context: Context): Map<String, Any> {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val packageVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val airplaneMode = runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
        }.getOrDefault(false)

        return success(
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "android_version" to Build.VERSION.RELEASE,
            "api_level" to Build.VERSION.SDK_INT,
            "locale" to Locale.getDefault().toLanguageTag(),
            "timezone" to (TimeZone.getDefault().id ?: "UTC"),
            "network_transports" to transports,
            "airplane_mode" to airplaneMode,
            "power_save_mode" to (powerManager?.isPowerSaveMode ?: false),
            "app_version" to (packageVersion ?: "unknown")
        )
    }

    private fun batteryStatus(context: Context): Map<String, Any> {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return failure("无法读取 Android 电池状态")
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = if (level >= 0) (level * 100.0 / scale).coerceIn(0.0, 100.0) else -1.0
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return success(
            "level_percent" to percent,
            "charging" to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL),
            "status" to batteryStatusName(status),
            "plugged" to pluggedName(plugged),
            "temperature_c" to if (temperature == Int.MIN_VALUE) null else temperature / 10.0,
            "voltage_mv" to battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
            "health" to batteryHealthName(battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)),
            "power_save_mode" to (powerManager?.isPowerSaveMode ?: false)
        )
    }

    private fun readClipboard(context: Context): Map<String, Any> {
        val authorization = authorizationManager.requestAuthorization(
            sessionId = sessionId,
            command = "android_clipboard_read",
            mainCommand = "android_clipboard_read",
            onRequest = onConfirmationRequired
        )
        if (authorization == ExecAuthorization.Reject) {
            return failure("用户拒绝读取系统剪贴板")
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return failure("系统剪贴板不可用")
        val clip = clipboard.primaryClip
            ?: return success("has_clip" to false, "text" to "", "item_count" to 0)
        val rawText = runCatching {
            clip.getItemAt(0).coerceToText(context).toString()
        }.getOrDefault("")
        val text = rawText.take(MAX_CLIPBOARD_CHARS)
        return success(
            "has_clip" to true,
            "text" to text,
            "truncated" to (rawText.length > text.length),
            "item_count" to clip.itemCount,
            "source" to (clip.description?.label?.toString() ?: "unknown")
        )
    }

    private fun writeClipboard(context: Context, args: Map<String, Any>): Map<String, Any> {
        val text = args.string("text")
        if (text.isBlank()) return failure("clipboard_write 缺少非空 text")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return failure("系统剪贴板不可用")
        clipboard.setPrimaryClip(ClipData.newPlainText("Nekobot Agent", text))
        return success("character_count" to text.length)
    }

    private fun openUrl(context: Context, args: Map<String, Any>): Map<String, Any> {
        val url = args.string("url").trim()
        if (url.isBlank()) return failure("url 不能为空")
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in ALLOWED_URL_SCHEMES) {
            return failure("只允许打开 http、https、mailto、tel 或 geo 链接")
        }
        startActivity(context, Intent(Intent.ACTION_VIEW, uri))
        return success("url" to url, "scheme" to scheme.orEmpty())
    }

    private fun openApp(context: Context, args: Map<String, Any>): Map<String, Any> {
        val packageName = args.string("package_name").trim()
        if (packageName.isBlank()) return failure("package_name 不能为空")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return failure("未找到可启动的应用: $packageName")
        startActivity(context, launchIntent)
        return success("package_name" to packageName)
    }

    private fun openSettings(context: Context, args: Map<String, Any>): Map<String, Any> {
        val target = args.string("target").trim().lowercase(Locale.ROOT).ifBlank { "main" }
        val intent = when (target) {
            "main", "settings" -> Intent(Settings.ACTION_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound", "audio" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "language", "locale" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "app", "app_details" -> {
                val packageName = args.string("package_name")
                if (packageName.isBlank()) return failure("打开应用设置时必须提供 package_name")
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            }
            "notifications", "app_notifications" -> {
                val packageName = args.string("package_name")
                if (packageName.isBlank()) return failure("打开通知设置时必须提供 package_name")
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            }
            else -> return failure("不支持的设置页面: $target")
        }
        startActivity(context, intent)
        return success("target" to target)
    }

    private fun createCalendarEvent(context: Context, args: Map<String, Any>): Map<String, Any> {
        val begin = args.long("start_time", 0L)
        val end = args.long("end_time", 0L)
        if (begin <= 0L || end <= begin) {
            return failure("start_time 和 end_time 必须是有效的毫秒时间戳，且 end_time 大于 start_time")
        }
        val title = args.string("title").ifBlank { "Nekobot Agent 事件" }
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, args.string("description"))
            putExtra(CalendarContract.Events.EVENT_LOCATION, args.string("location"))
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.Events.ALL_DAY, args.boolean("all_day"))
        }
        startActivity(context, intent)
        return success(
            "title" to title,
            "start_time" to begin,
            "end_time" to end,
            "system_confirmation_required" to true
        )
    }

    private fun setAlarm(context: Context, args: Map<String, Any>): Map<String, Any> {
        val hour = args.int("hour", -1)
        val minute = args.int("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return failure("hour 必须在 0-23 范围内，minute 必须在 0-59 范围内")
        }
        val message = args.string("message").ifBlank { "Nekobot Agent 闹钟" }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
        }
        startActivity(context, intent)
        return success(
            "hour" to hour,
            "minute" to minute,
            "message" to message,
            "system_confirmation_required" to true
        )
    }

    private fun volume(context: Context, args: Map<String, Any>): Map<String, Any> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return failure("音频服务不可用")
        val streamName = args.string("stream").lowercase(Locale.ROOT).ifBlank { "music" }
        val stream = streamType(streamName)
            ?: return failure("不支持的音频流: $streamName")
        val max = audio.getStreamMaxVolume(stream)
        val action = args.string("action").lowercase(Locale.ROOT).ifBlank { "get" }
        if (action == "set") {
            val level = args.int("level", -1)
            if (level !in 0..max) return failure("level 必须在 0-$max 范围内")
            audio.setStreamVolume(stream, level, 0)
        } else if (action != "get") {
            return failure("volume action 只支持 get 或 set")
        }
        val current = audio.getStreamVolume(stream)
        return success(
            "stream" to streamName,
            "action" to action,
            "level" to current,
            "max_level" to max,
            "percent" to if (max == 0) 0.0 else current * 100.0 / max,
            "ringer_mode" to ringerModeName(audio.ringerMode)
        )
    }

    private fun startActivity(context: Context, intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun streamType(name: String): Int? = when (name) {
        "music", "media" -> AudioManager.STREAM_MUSIC
        "ring", "ringer" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        "system" -> AudioManager.STREAM_SYSTEM
        "voice_call", "call" -> AudioManager.STREAM_VOICE_CALL
        else -> null
    }

    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun pluggedName(plugged: Int): String = when {
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
        else -> "none"
    }

    private fun batteryHealthName(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    private fun ringerModeName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> "unknown"
    }

    private fun Map<String, Any>.string(key: String): String = this[key]?.toString().orEmpty()

    private fun Map<String, Any>.int(key: String, default: Int): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

    private fun Map<String, Any>.long(key: String, default: Long): Long =
        (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: default

    private fun Map<String, Any>.boolean(key: String, default: Boolean = false): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> default
    }

    private fun success(vararg values: Pair<String, Any?>): Map<String, Any> = buildMap {
        put("success", true)
        values.forEach { (key, value) -> if (value != null) put(key, value) }
    }

    private fun failure(message: String): Map<String, Any> = mapOf(
        "success" to false,
        "error" to message
    )

    private companion object {
        const val MAX_CLIPBOARD_CHARS = 20_000
        val ALLOWED_URL_SCHEMES = setOf("http", "https", "mailto", "tel", "geo")
    }
}
