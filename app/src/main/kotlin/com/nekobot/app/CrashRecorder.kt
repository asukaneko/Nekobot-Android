package com.nekobot.app

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.nekobot.app.data.local.LocalLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自记录：把未捕获异常（含「点击后闪退」这类现场）落成纯文本，便于事后定位。
 *
 * 只做最小工作：格式化堆栈 → 写文件 → 交给系统默认处理。记录本身失败必须静默忽略，
 * 否则会在崩溃线程上二次抛出，把现场彻底丢掉。
 *
 * 记录位置（按优先级）：
 * 1. `Android/data/com.nekobot.app/files/crash/last_crash.txt`（可直接用 adb pull 取回）；
 * 2. 取不到外部目录时退回应用私有 `files/crash/last_crash.txt`。
 */
internal object CrashRecorder {

    private const val TAG = "NekoCrash"
    private const val DIR_NAME = "crash"
    private const val FILE_NAME = "last_crash.txt"

    /** 记录正文上限：极端堆栈（递归爆栈）也能写下，但不会写爆磁盘。 */
    private const val MAX_CHARS = 64_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(appContext, thread, error) }
            // 保持系统原有的崩溃流程（写 tombstone、弹崩溃提示、结束进程）
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val text = buildString {
            appendLine("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            appendLine("thread: ${thread.name} (id=${thread.id})")
            appendLine(
                "device: ${Build.MANUFACTURER} ${Build.MODEL} / " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            appendLine("app: " + appVersion(context))
            appendLine()
            val writer = StringWriter()
            PrintWriter(writer).use { error.printStackTrace(it) }
            append(writer.toString().take(MAX_CHARS))
        }
        android.util.Log.e(TAG, text)
        // 同时写进应用内日志（更多 → 日志查看可直接看到，不必再取文件）
        runCatching { LocalLogger.e(TAG, text) }
        val target = File(crashDir(context), FILE_NAME)
        runCatching { target.parentFile?.mkdirs() }
        target.writeText(text)
    }

    private fun crashDir(context: Context): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).resolve(DIR_NAME)

    /** 版本信息：不依赖 BuildConfig（项目未开启 buildConfig 生成）。 */
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // longVersionCode 需要 API 28，minSdk 26 下直接调用会在 Android 8.x 抛 NoSuchMethodError
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault(context.packageName)

    /** 读取最近一次崩溃记录（供诊断页展示）；没有记录时返回 null。 */
    fun readLastCrash(context: Context): String? = runCatching {
        File(crashDir(context), FILE_NAME).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** 清除最近一次崩溃记录。 */
    fun clear(context: Context) {
        runCatching { File(crashDir(context), FILE_NAME).delete() }
    }
}
