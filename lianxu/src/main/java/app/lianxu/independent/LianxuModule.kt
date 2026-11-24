package app.lianxu.independent

import android.app.Application
import android.content.Context
import java.io.File

object LianxuModule {
    internal lateinit var onReturnToHost: (Context) -> Unit
        private set

    internal lateinit var hostModels: HostModelBridge
        private set

    fun initialize(application: Application, hostModels: HostModelBridge, onReturnToHost: (Context) -> Unit) {
        this.hostModels = hostModels
        this.onReturnToHost = onReturnToHost
        val journal = File(application.filesDir, "diagnostics/${AppServices.vaultNamespace}")
        ProcessDiagnostics.start(application, journal)
        Diagnostics.startLocalRecording(application, AppServices.vaultNamespace)
        DiagnosticEnvironment.start(application)
    }
}
