package app.lianxu.independent

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import java.io.File

internal object ProcessDiagnostics {
    @Volatile private var evidence:ProcessEvidence?=null
    @Volatile private var updateHistory:(()->Unit)?=null
    val sessionId: String? get() = evidence?.sessionId
    fun refresh() { runCatching { updateHistory?.invoke() } }
    fun start(app:Application, journal:File) {
        runCatching {
            if(Build.VERSION.SDK_INT>=28 && Application.getProcessName()!=app.applicationInfo.processName)return
            val notes=ProcessEvidence(File(journal,"process"),Process.myPid())
            evidence=notes
            val previous=Thread.getDefaultUncaughtExceptionHandler()
            if(previous!=null) Thread.setDefaultUncaughtExceptionHandler(notes.before(previous))
            if(Build.VERSION.SDK_INT>=30) {
                updateHistory = { readExits(app,notes) }
                Thread(::refresh,"lianxu-exit-history").apply { isDaemon=true;start() }
            }
        }
    }
    @androidx.annotation.RequiresApi(30)
    private fun readExits(app: Application, notes: ProcessEvidence) {
        val manager = app.getSystemService(ActivityManager::class.java)
        val exits = manager.getHistoricalProcessExitReasons(app.packageName, 0, 32).mapNotNull { info ->
            if (info.processName != app.applicationInfo.processName || info.packageUid != app.applicationInfo.uid)
                return@mapNotNull null
            val reason = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH -> ExitKind.JAVA_CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitKind.NATIVE_CRASH
                ApplicationExitInfo.REASON_ANR -> ExitKind.ANR
                ApplicationExitInfo.REASON_LOW_MEMORY -> ExitKind.LOW_MEMORY
                ApplicationExitInfo.REASON_USER_REQUESTED -> ExitKind.USER_REQUESTED
                ApplicationExitInfo.REASON_USER_STOPPED -> ExitKind.USER_STOPPED
                ApplicationExitInfo.REASON_SIGNALED -> ExitKind.SIGNAL
                else -> return@mapNotNull null
            }
            OwnExit(info.pid, info.timestamp, reason)
        }
        notes.recover(exits)
    }

    fun view(page:String) { runCatching { evidence?.view(page) } }
    fun action(action:ProcessAction) { runCatching { evidence?.action(action) } }
}
