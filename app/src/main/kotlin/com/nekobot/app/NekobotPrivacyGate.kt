package com.nekobot.app

import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.lianxu.independent.HostPrivacyGate
import app.lianxu.independent.LianxuActivity
import com.nekobot.app.ui.theme.NekobotTheme

/** Retained only by this Activity's configuration successor. Process death drops the grant. */
internal class PrivacyRecreationGrant : ViewModel() {
    var target: Class<*>? = null
}

internal class AppPrivacySession {
    private var startedOwner: Any? = null
    private var unlocked = false
    private var source: Any? = null
    private var destination: Class<*>? = null

    fun start(owner: Any, enabled: Boolean, recreation: PrivacyRecreationGrant? = null): Boolean {
        unlocked = !enabled || destination == owner.javaClass || recreation?.target == owner.javaClass
        recreation?.target = null
        startedOwner = owner
        clearTransfer()
        return unlocked
    }

    fun authenticated(owner: Any): Boolean {
        if (startedOwner !== owner) return false
        unlocked = true
        return true
    }

    fun transferTo(target: Class<*>) {
        if (unlocked && startedOwner != null) {
            source = startedOwner
            destination = target
        }
    }

    fun pause(owner: Any, promptPending: Boolean = false, recreating: PrivacyRecreationGrant? = null): Boolean {
        if (startedOwner !== owner) return false
        if (recreating != null && unlocked) recreating.target = owner.javaClass
        else if (!promptPending) unlocked = false
        return unlocked
    }

    fun stop(owner: Any) {
        if (startedOwner === owner) {
            startedOwner = null
            unlocked = false
        }
        if (source === owner) clearTransfer()
    }

    private fun clearTransfer() {
        source = null
        destination = null
    }
}

internal class NekobotPrivacyGate(private val host: FragmentActivity) : HostPrivacyGate, DefaultLifecycleObserver {
    companion object {
        private val session = AppPrivacySession()
        fun enteringCompanion() = session.transferTo(LianxuActivity::class.java)
    }

    override fun returningToHost() = session.transferTo(MainActivity::class.java)

    private val enabled get() = ServiceContainer.prefs.appLockEnabled
    private var unlocked by mutableStateOf(!enabled)
    private var error by mutableStateOf<String?>(null)
    private val recreation = ViewModelProvider(host)[PrivacyRecreationGrant::class.java]
    private var started = false
    private var pending: BiometricPrompt? = null

    init {
        applySecureFlag()
        host.lifecycle.addObserver(this)
    }

    private fun applySecureFlag() {
        if (enabled) host.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        unlocked = session.start(host, enabled, recreation)
        if (!unlocked) host.window.decorView.post(::requestUnlock)
    }

    override fun onResume(owner: LifecycleOwner) = applySecureFlag()

    override fun onPause(owner: LifecycleOwner) {
        val keepUnlocked = session.pause(host, pending != null, recreation.takeIf { host.isChangingConfigurations })
        if (enabled) unlocked = keepUnlocked
    }

    override fun onStop(owner: LifecycleOwner) {
        started = false
        session.stop(host)
        if (!host.isChangingConfigurations) {
            recreation.target = null
            if (enabled) unlocked = false
        }
        val prompt = pending
        pending = null
        prompt?.cancelAuthentication()
    }

    private fun requestUnlock() {
        if (!started || unlocked || pending != null || !enabled) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(host).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            error = host.getString(R.string.app_lock_unavailable)
            return
        }
        error = null
        lateinit var prompt: BiometricPrompt
        prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (pending !== prompt) return
                    pending = null
                    error = null
                    unlocked = session.authenticated(host)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (pending !== prompt) return
                    pending = null
                    error = errString.toString()
                }
                override fun onAuthenticationFailed() {
                    if (pending === prompt) error = host.getString(R.string.app_lock_not_recognized)
                }
            })
        pending = prompt
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(host.getString(R.string.app_lock_prompt_title))
            .setSubtitle(host.getString(R.string.app_lock_prompt_subtitle))
            .setAllowedAuthenticators(authenticators)
            .setNegativeButtonText(host.getString(R.string.common_cancel))
            .build())
    }

    @Composable
    override fun Content(content: @Composable () -> Unit) {
        val state = rememberSaveableStateHolder()
        if (unlocked) state.SaveableStateProvider("unlocked", content)
        else NekobotTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppLockedScreen(error, ::requestUnlock)
            }
        }
    }
}
