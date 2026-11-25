package app.lianxu.independent

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import java.io.File

interface HostPrivacyGate {
    @Composable
    fun Content(content: @Composable () -> Unit)
    fun returningToHost()
}

object LianxuModule {
    internal lateinit var privacyGate: (FragmentActivity) -> HostPrivacyGate
        private set
    internal lateinit var onReturnToHost: (Context) -> Unit
        private set

    internal lateinit var hostModels: HostModelBridge
        private set

    lateinit var roles: HostRoleSync
        private set
    val rolesIfInitialized: HostRoleSync? get() = if (::roles.isInitialized) roles else null

    fun initialize(application: Application, hostModels: HostModelBridge, hostRoles: HostRoleBridge,
        privacyGate: (FragmentActivity) -> HostPrivacyGate, onReturnToHost: (Context) -> Unit) {
        this.privacyGate = privacyGate
        roles = HostRoleSync(application,hostRoles)
        this.hostModels = hostModels
        this.onReturnToHost = onReturnToHost
        val journal = File(application.filesDir, "diagnostics/${AppServices.vaultNamespace}")
        ProcessDiagnostics.start(application, journal)
        Diagnostics.startLocalRecording(application, AppServices.vaultNamespace)
        DiagnosticEnvironment.start(application)
    }
}
