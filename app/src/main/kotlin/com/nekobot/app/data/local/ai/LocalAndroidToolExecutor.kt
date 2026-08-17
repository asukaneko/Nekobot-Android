package com.nekobot.app.data.local.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.nekobot.app.integration.NekobotAccessibilityService
import com.nekobot.app.integration.NekobotNotificationListenerService
import java.io.File
import java.util.Locale
import java.util.TimeZone

internal data class LaunchableAppCandidate(
    val label: String,
    val packageName: String,
    val activityName: String
)

internal data class RankedLaunchableApp(
    val app: LaunchableAppCandidate,
    val score: Int
)

/** 与 Android API 解耦的应用名/包名排序，供 JVM 单元测试覆盖。 */
internal fun rankLaunchableApps(
    apps: List<LaunchableAppCandidate>,
    query: String
): List<RankedLaunchableApp> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
    if (normalizedQuery.isEmpty()) {
        return apps.map { RankedLaunchableApp(it, 100) }
            .sortedWith(compareBy({ it.app.label.lowercase(Locale.ROOT) }, { it.app.packageName }))
    }
    return apps.mapNotNull { app ->
        val label = app.label.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        val packageName = app.packageName.lowercase(Locale.ROOT)
        val score = when {
            packageName == normalizedQuery -> 0
            label == normalizedQuery -> 1
            label.startsWith(normalizedQuery) -> 2
            packageName.startsWith(normalizedQuery) -> 3
            label.contains(normalizedQuery) -> 4
            packageName.contains(normalizedQuery) -> 5
            else -> return@mapNotNull null
        }
        RankedLaunchableApp(app, score)
    }.sortedWith(compareBy({ it.score }, { it.app.label.length }, { it.app.label.lowercase(Locale.ROOT) }))
}

/**
 * Agent 可调用的 Android 原生能力。
 *
 * 这里仅封装 Android API 和系统 Intent，不直接模拟点击其他应用，也不绕过系统权限或确认页面。
 */
internal class LocalAndroidToolExecutor(
    private val context: Context?,
    private val sessionId: String,
    private val workspaceRoot: File?,
    private val authorizationManager: LocalExecAuthorizationManager,
    private val onConfirmationRequired: (ExecConfirmationRequest) -> Unit
) {
    suspend fun execute(toolName: String, args: Map<String, Any>): Map<String, Any> {
        val appContext = context?.applicationContext
            ?: return failure("Android 应用上下文不可用")
        return try {
            when (toolName) {
                "android_device_info" -> deviceInfo(appContext)
                "android_battery_status" -> batteryStatus(appContext)
                "android_clipboard_read" -> readClipboard(appContext)
                "android_clipboard_write" -> writeClipboard(appContext, args)
                "android_open_url" -> openUrl(appContext, args)
                "android_list_apps" -> listApps(appContext, args)
                "android_open_app" -> openApp(appContext, args)
                "android_open_settings" -> openSettings(appContext, args)
                "android_create_calendar_event" -> createCalendarEvent(appContext, args)
                "android_set_alarm" -> setAlarm(appContext, args)
                "android_volume" -> volume(appContext, args)
                "android_accessibility_status" -> accessibilityStatus(appContext)
                "android_ui_tree" -> uiTree(args)
                "android_ui_click" -> uiClick(args)
                "android_ui_set_text" -> uiSetText(args)
                "android_ui_scroll" -> uiScroll(args)
                "android_global_action" -> globalAction(args)
                "android_screenshot" -> screenshot()
                "android_notifications" -> notifications(args)
                "android_notification_action" -> notificationAction(args)
                "android_media_control" -> mediaControl(args)
                else -> failure("Android 模式不支持工具: $toolName")
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
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

    private suspend fun readClipboard(context: Context): Map<String, Any> {
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

    private suspend fun writeClipboard(context: Context, args: Map<String, Any>): Map<String, Any> {
        val text = args.string("text")
        if (!authorize("android_clipboard_write", "write ${text.take(120)}")) {
            return failure("用户拒绝写入系统剪贴板")
        }
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

    private fun listApps(context: Context, args: Map<String, Any>): Map<String, Any> {
        val query = args.string("query")
        val limit = args.int("limit", 50).coerceIn(1, 200)
        val allApps = queryLaunchableApps(context.packageManager)
        val matches = rankLaunchableApps(allApps, query).take(limit)
        return success(
            "query" to query,
            "apps" to matches.map { it.toMap() },
            "count" to matches.size,
            "total_launchable" to allApps.size
        )
    }

    private fun openApp(context: Context, args: Map<String, Any>): Map<String, Any> {
        val packageName = args.string("package_name").trim()
        val query = packageName
            .ifBlank { args.string("app_name").trim() }
            .ifBlank { args.string("query").trim() }
        if (query.isBlank()) return failure("请提供 package_name、app_name 或 query")

        val ranked = rankLaunchableApps(queryLaunchableApps(context.packageManager), query)
        val best = ranked.firstOrNull()
        if (best == null && packageName.isNotBlank()) {
            val fallback = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: return failure("未找到可启动的应用: $packageName")
            startActivity(context, fallback)
            return success("package_name" to packageName, "matched_by" to "package_fallback")
        }
        if (best == null) return failure("没有找到与“$query”匹配的可启动应用")

        val equallyRanked = ranked.takeWhile { it.score == best.score }
        if (equallyRanked.size > 1) {
            return failure(
                "“$query”匹配到多个应用，请使用候选包名重试",
                "candidates" to equallyRanked.take(10).map { it.toMap() }
            )
        }

        val app = best.app
        val launchIntent = Intent.makeMainActivity(ComponentName(app.packageName, app.activityName))
        startActivity(context, launchIntent)
        return success(
            "label" to app.label,
            "package_name" to app.packageName,
            "activity_name" to app.activityName,
            "matched_by" to matchType(best.score)
        )
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchableApps(packageManager: PackageManager): List<LaunchableAppCandidate> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolved ->
                val info = resolved.activityInfo ?: return@mapNotNull null
                val label = runCatching { resolved.loadLabel(packageManager).toString().trim() }
                    .getOrDefault("")
                    .ifBlank { info.packageName }
                LaunchableAppCandidate(
                    label = label,
                    packageName = info.packageName,
                    activityName = info.name
                )
            }
            .distinctBy { it.packageName }
    }

    private fun RankedLaunchableApp.toMap(): Map<String, Any> = mapOf(
        "label" to app.label,
        "package_name" to app.packageName,
        "activity_name" to app.activityName,
        "match" to matchType(score)
    )

    private fun matchType(score: Int): String = when (score) {
        0 -> "exact_package"
        1 -> "exact_label"
        2 -> "label_prefix"
        3 -> "package_prefix"
        4 -> "label_contains"
        5 -> "package_contains"
        else -> "all"
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
            "notification_listener", "notification_access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
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

    private suspend fun volume(context: Context, args: Map<String, Any>): Map<String, Any> {
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
            if (!authorize("android_volume", "set $streamName volume to $level")) {
                return failure("用户拒绝更改系统音量")
            }
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

    private fun accessibilityStatus(context: Context): Map<String, Any> {
        val accessibilityComponent = ComponentName(context, NekobotAccessibilityService::class.java)
        val notificationComponent = ComponentName(context, NekobotNotificationListenerService::class.java)
        return success(
            "accessibility_enabled" to isComponentEnabled(
                context,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                accessibilityComponent
            ),
            "accessibility_connected" to (NekobotAccessibilityService.instance != null),
            "notification_access_enabled" to isComponentEnabled(
                context,
                "enabled_notification_listeners",
                notificationComponent
            ),
            "notification_listener_connected" to (NekobotNotificationListenerService.instance != null),
            "accessibility_settings_target" to "accessibility",
            "notification_settings_target" to "notification_listener"
        )
    }

    private suspend fun uiTree(args: Map<String, Any>): Map<String, Any> {
        if (!authorize("android_ui_tree", "read current UI tree")) return failure("用户拒绝读取当前界面")
        val service = accessibilityService() ?: return accessibilityUnavailable()
        val snapshot = service.snapshot(args.int("max_nodes", NekobotAccessibilityService.DEFAULT_MAX_NODES))
        return success(
            "package_name" to snapshot.packageName,
            "window_count" to snapshot.windowCount,
            "nodes" to snapshot.nodes,
            "node_count" to snapshot.nodes.size,
            "truncated" to snapshot.truncated
        )
    }

    private suspend fun uiClick(args: Map<String, Any>): Map<String, Any> {
        val selector = args.string("selector")
        if (selector.isBlank()) return failure("selector 不能为空")
        if (!authorize("android_ui_click", "click $selector")) return failure("用户拒绝点击界面元素")
        val result = accessibilityService()?.click(
            selector,
            args.string("field").ifBlank { "auto" },
            args.boolean("exact")
        ) ?: return accessibilityUnavailable()
        return actionResult(result.success, result.message, result.matched, result.metadata)
    }

    private suspend fun uiSetText(args: Map<String, Any>): Map<String, Any> {
        val selector = args.string("selector")
        val text = args.string("text")
        if (selector.isBlank()) return failure("selector 不能为空")
        if (!authorize("android_ui_set_text", "set text on $selector")) return failure("用户拒绝向界面输入文字")
        val result = accessibilityService()?.setText(
            selector,
            text,
            args.string("field").ifBlank { "auto" },
            args.boolean("exact")
        ) ?: return accessibilityUnavailable()
        return actionResult(result.success, result.message, result.matched, result.metadata)
    }

    private suspend fun uiScroll(args: Map<String, Any>): Map<String, Any> {
        val direction = args.string("direction").ifBlank { "down" }
        if (!authorize("android_ui_scroll", "scroll $direction")) return failure("用户拒绝滚动当前界面")
        val result = accessibilityService()?.scroll(
            direction,
            args.string("selector").takeIf(String::isNotBlank),
            args.string("field").ifBlank { "auto" },
            args.boolean("exact")
        ) ?: return accessibilityUnavailable()
        return actionResult(result.success, result.message, result.matched, result.metadata)
    }

    private suspend fun globalAction(args: Map<String, Any>): Map<String, Any> {
        val action = args.string("action")
        if (action.isBlank()) return failure("action 不能为空")
        if (!authorize("android_global_action", action)) return failure("用户拒绝系统全局动作")
        val result = accessibilityService()?.runGlobalAction(action) ?: return accessibilityUnavailable()
        return actionResult(result.success, result.message, result.matched, result.metadata)
    }

    private suspend fun screenshot(): Map<String, Any> {
        if (!authorize("android_screenshot", "capture current screen")) return failure("用户拒绝截取当前屏幕")
        val service = accessibilityService() ?: return accessibilityUnavailable()
        val root = workspaceRoot?.canonicalFile
            ?: return failure("当前 Agent 会话工作区不可用，无法保存截图")
        val relativePath = "screenshots/android-${System.currentTimeMillis()}.png"
        val output = File(root, relativePath).canonicalFile
        if (!output.path.startsWith(root.path + File.separator)) return failure("截图输出路径无效")
        val result = service.captureScreenshot(output)
        return actionResult(
            result.success,
            result.message,
            result.matched,
            result.metadata + mapOf("path" to relativePath, "mime_type" to "image/png")
        )
    }

    private suspend fun notifications(args: Map<String, Any>): Map<String, Any> {
        if (!authorize("android_notifications", "read active notifications")) return failure("用户拒绝读取通知")
        val service = notificationService() ?: return notificationUnavailable()
        val entries = service.notificationSnapshot(
            packageName = args.string("package_name").takeIf(String::isNotBlank),
            limit = args.int("limit", 30),
            includeContent = args.boolean("include_content", true)
        )
        return success("notifications" to entries, "count" to entries.size)
    }

    private suspend fun notificationAction(args: Map<String, Any>): Map<String, Any> {
        val key = args.string("notification_key")
        val action = args.string("action")
        if (key.isBlank() || action.isBlank()) return failure("notification_key 和 action 不能为空")
        if (!authorize("android_notification_action", "$action notification $key")) {
            return failure("用户拒绝执行通知操作")
        }
        val result = notificationService()?.notificationAction(key, action, args.int("action_index", 0))
            ?: return notificationUnavailable()
        return actionResult(result.success, result.message, metadata = mapOf("package_name" to result.packageName))
    }

    private suspend fun mediaControl(args: Map<String, Any>): Map<String, Any> {
        val action = args.string("action").ifBlank { "get" }
        if (!authorize("android_media_control", action)) return failure("用户拒绝访问媒体会话")
        val service = notificationService() ?: return notificationUnavailable()
        if (action == "get" || action == "list") {
            val sessions = service.mediaSessions()
            return success("sessions" to sessions, "count" to sessions.size)
        }
        val result = service.mediaControl(action, args.string("package_name").takeIf(String::isNotBlank))
        return actionResult(result.success, result.message, metadata = mapOf("package_name" to result.packageName))
    }

    private fun accessibilityService(): NekobotAccessibilityService? = NekobotAccessibilityService.instance

    private fun notificationService(): NekobotNotificationListenerService? = NekobotNotificationListenerService.instance

    private fun accessibilityUnavailable(): Map<String, Any> =
        failure("Nekobot Agent 辅助功能未连接，请先通过 android_open_settings 打开 accessibility")

    private fun notificationUnavailable(): Map<String, Any> =
        failure("Nekobot 通知使用权未连接，请先通过 android_open_settings 打开 notification_listener")

    private suspend fun authorize(mainCommand: String, details: String): Boolean =
        authorizationManager.requestAuthorization(
            sessionId = sessionId,
            command = "$mainCommand: $details",
            mainCommand = mainCommand,
            onRequest = onConfirmationRequired
        ) != ExecAuthorization.Reject

    private fun isComponentEnabled(context: Context, setting: String, component: ComponentName): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, setting).orEmpty()
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == component }
    }

    private fun actionResult(
        succeeded: Boolean,
        message: String,
        matched: Map<String, Any?>? = null,
        metadata: Map<String, Any?> = emptyMap()
    ): Map<String, Any> = buildMap {
        put("success", succeeded)
        if (succeeded) put("message", message) else put("error", message)
        matched?.let { put("matched", it) }
        metadata.forEach { (key, value) -> if (value != null) put(key, value) }
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

    private fun failure(message: String, vararg values: Pair<String, Any>): Map<String, Any> = buildMap {
        put("success", false)
        put("error", message)
        values.forEach { (key, value) -> put(key, value) }
    }

    private companion object {
        const val MAX_CLIPBOARD_CHARS = 20_000
        val ALLOWED_URL_SCHEMES = setOf("http", "https", "mailto", "tel", "geo")
    }
}
