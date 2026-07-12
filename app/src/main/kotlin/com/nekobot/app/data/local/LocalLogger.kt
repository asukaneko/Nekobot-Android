package com.nekobot.app.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

/**
 * 本地模式运行日志记录器。
 *
 * 将关键流程（AIPipeline / CharacterRuntime / LocalRepository 等）的日志
 * 持久化到 SharedPreferences，供应用内日志查看界面展示。
 * 同时输出到 Logcat，保持与原有 android.util.Log 行为一致。
 *
 * 存储：SharedPreferences("local_logs")，key="records"，JSON 数组，上限 2000 条。
 */
object LocalLogger {

    private const val PREF_NAME = "local_logs"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 2000

    private val gson = Gson()
    private val lock = ReentrantLock()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var prefs: android.content.SharedPreferences? = null

    /** 日志等级 */
    const val LEVEL_DEBUG = "debug"
    const val LEVEL_INFO = "info"
    const val LEVEL_WARNING = "warning"
    const val LEVEL_ERROR = "error"

    /** 初始化（在 ServiceContainer.init 中调用） */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    data class Record(
        val time: String,
        val date: String,
        val level: String,
        val tag: String,
        val message: String
    )

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append(LEVEL_DEBUG, tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append(LEVEL_INFO, tag, msg)
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
        val full = if (throwable != null) "$msg | ${throwable.javaClass.simpleName}: ${throwable.message}" else msg
        append(LEVEL_WARNING, tag, full)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
        val full = if (throwable != null) "$msg | ${throwable.javaClass.simpleName}: ${throwable.message}" else msg
        append(LEVEL_ERROR, tag, full)
    }

    private fun append(level: String, tag: String, message: String) {
        val sp = prefs ?: return
        val now = Date()
        val record = JsonObject().apply {
            addProperty("time", timeFormat.format(now))
            addProperty("date", dateFormat.format(now))
            addProperty("level", level)
            addProperty("tag", tag)
            addProperty("message", message)
        }
        lock.lock()
        try {
            val existing = sp.getString(KEY_RECORDS, "[]") ?: "[]"
            val arr = try {
                JsonParser.parseString(existing).asJsonArray
            } catch (_: Exception) {
                com.google.gson.JsonArray()
            }
            arr.add(record)
            while (arr.size() > MAX_RECORDS) arr.remove(0)
            sp.edit().putString(KEY_RECORDS, arr.toString()).apply()
        } catch (_: Exception) {
            // 日志记录失败不应影响主流程
        } finally {
            lock.unlock()
        }
    }

    /** 读取全部日志记录（按时间倒序，最新在前） */
    fun listLogs(): List<Record> {
        val sp = prefs ?: return emptyList()
        val raw = sp.getString(KEY_RECORDS, "[]") ?: "[]"
        return try {
            val arr = JsonParser.parseString(raw).asJsonArray
            arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                Record(
                    time = obj.get("time")?.asString ?: "",
                    date = obj.get("date")?.asString ?: "",
                    level = obj.get("level")?.asString ?: "info",
                    tag = obj.get("tag")?.asString ?: "",
                    message = obj.get("message")?.asString ?: ""
                )
            }.reversed()  // 最新在前
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 清空全部日志 */
    fun clear() {
        val sp = prefs ?: return
        lock.lock()
        try {
            sp.edit().remove(KEY_RECORDS).apply()
        } finally {
            lock.unlock()
        }
    }
}
