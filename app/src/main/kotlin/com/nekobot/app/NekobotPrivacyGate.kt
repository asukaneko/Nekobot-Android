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
import app.lianxu.independent.HostPrivacyGate
import app.lianxu.independent.LianxuActivity
import com.nekobot.app.ui.theme.NekobotTheme

/** One in-process grant for an explicit transition, consumed by the destination before the source stops. */
internal class AppPrivacySession {
    private var foreground: Any? = null
    private var unlocked = false
    private var source: Any? = null
    private var destination: Class<*>? = null

    fun resume(owner: Any, enabled: Boolean): Boolean {
        unlocked = !enabled || destination == owner.javaClass
        foreground = owner
        clearTransfer()
        return unlocked
    }

    fun authenticated(owner: Any): Boolean {
        if (foreground !== owner) return false
        unlocked = true
        return true
    }

    fun transferTo(target: Class<*>) {
        if (unlocked && foreground != null) {
            source = foreground
            destination = target
        }
    }

    fun pause(owner: Any) {
        if (foreground === owner) {
            foreground = null
            unlocked = false
        }
    }

    fun stop(owner: Any) {
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
    private var resumed = false
    private var pending: BiometricPrompt? = null

    init {
        applySecureFlag()
        host.lifecycle.addObserver(this)
    }

    private fun applySecureFlag() {
        if (enabled) host.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume(owner: LifecycleOwner) {
        applySecureFlag()
        resumed = true
        unlocked = session.resume(host, enabled)
        if (!unlocked) host.window.decorView.post(::requestUnlock)
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        session.pause(host)
        if (enabled) unlocked = false
    }

    override fun onStop(owner: LifecycleOwner) {
        session.stop(host)
        pending?.cancelAuthentication()
        pending = null
    }

    private fun requestUnlock() {
        if (!resumed || unlocked || pending != null || !enabled) return
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
