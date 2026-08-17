package com.nekobot.app.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.security.SecurePreferenceStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 本地模式诊断日志。
 *
 * 记录先保留在内存环形缓存，再批量写入 Android Keystore 加密的偏好文件。
 * 日志不能作为聊天、模型输出或凭据的副本，因此写入前会截断并脱敏。
 */
object LocalLogger {

    private const val PREF_NAME = "local_logs"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 500
    private const val MAX_MESSAGE_CHARS = 1_000
    private const val PERSIST_DELAY_MS = 750L

    private val gson = Gson()
    private val lock = ReentrantLock()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val persistenceExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "NekobotLocalLogger").apply { isDaemon = true }
    }
    private val records = ArrayDeque<Record>()

    private var legacyPrefs: android.content.SharedPreferences? = null
    private var securePrefs: SecurePreferenceStore? = null
    private var persistScheduled = false

    const val LEVEL_DEBUG = "debug"
    const val LEVEL_INFO = "info"
    const val LEVEL_WARNING = "warning"
    const val LEVEL_ERROR = "error"

    data class Record(
        val time: String,
        val date: String,
        val level: String,
        val tag: String,
        val message: String
    )

    fun init(context: Context) {
        lock.withLock {
            val appContext = context.applicationContext
            legacyPrefs = runCatching {
                appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            }.getOrNull()
            // 日志是诊断能力，Keystore 或历史数据异常时不能阻断应用启动。
            securePrefs = runCatching { SecurePreferenceStore(appContext) }.getOrNull()
            records.clear()
            val raw = runCatching {
                securePrefs?.getString(KEY_RECORDS, legacyPrefs)
                    ?: legacyPrefs?.getString(KEY_RECORDS, null)
            }.getOrNull().orEmpty()
            val type = object : TypeToken<List<Record>>() {}.type
            val restored = runCatching {
                gson.fromJson<List<Record>>(raw, type).orEmpty()
            }.getOrDefault(emptyList())
            restored.takeLast(MAX_RECORDS).forEach(records::addLast)
        }
    }

    fun d(tag: String, msg: String) = log(LEVEL_DEBUG, tag, msg)

    fun i(tag: String, msg: String) = log(LEVEL_INFO, tag, msg)

    fun w(tag: String, msg: String, throwable: Throwable? = null) =
        log(LEVEL_WARNING, tag, exceptionMessage(msg, throwable))

    fun e(tag: String, msg: String, throwable: Throwable? = null) =
        log(LEVEL_ERROR, tag, exceptionMessage(msg, throwable))

    private fun log(level: String, tag: String, rawMessage: String) {
        val message = redactForLocalLog(rawMessage)
        writeToLog {
            when (level) {
                LEVEL_DEBUG -> Log.d(tag, message)
                LEVEL_INFO -> Log.i(tag, message)
                LEVEL_WARNING -> Log.w(tag, message)
                else -> Log.e(tag, message)
            }
        }
        append(level, tag, message)
    }

    private fun exceptionMessage(message: String, throwable: Throwable?): String =
        if (throwable == null) message
        else "$message | ${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"

    private inline fun writeToLog(block: () -> Int) {
        runCatching(block)
    }

    private fun append(level: String, tag: String, message: String) {
        val now = Date()
        lock.withLock {
            records.addLast(
                Record(
                    time = timeFormat.format(now),
                    date = dateFormat.format(now),
                    level = level,
                    tag = tag,
                    message = message
                )
            )
            while (records.size > MAX_RECORDS) records.removeFirst()
            schedulePersistLocked()
        }
    }

    private fun schedulePersistLocked() {
        if (persistScheduled || securePrefs == null) return
        persistScheduled = true
        persistenceExecutor.schedule({
            val snapshot = lock.withLock {
                persistScheduled = false
                gson.toJson(records.toList())
            }
            runCatching { securePrefs?.putString(KEY_RECORDS, snapshot) }
        }, PERSIST_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    fun listLogs(): List<Record> = lock.withLock { records.toList().asReversed() }

    fun clear() {
        val preferences = lock.withLock {
            records.clear()
            securePrefs to legacyPrefs
        }
        runCatching { preferences.first?.remove(KEY_RECORDS, preferences.second) }
    }
}

/** 供日志系统和 JVM 单元测试复用的保守脱敏规则。 */
internal fun redactForLocalLog(message: String): String {
    val withoutCredentials = message
        .replace(
            Regex("(?i)(authorization|x-auth-token|api[_-]?key|password|token|secret)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+"),
            "\$1=<redacted>"
        )
        .replace(Regex("(?i)bearer\\s+[a-z0-9._~+/=-]+"), "Bearer <redacted>")
        .replace(Regex("(?i)(https?://[^\\s?#]+)\\?[^\\s]+"), "\$1?<redacted>")
    return if (withoutCredentials.length > 1_000) {
        withoutCredentials.take(1_000) + "...<truncated>"
    } else {
        withoutCredentials
    }
}
