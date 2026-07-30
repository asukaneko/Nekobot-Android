package com.nekobot.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.nekobot.app.data.local.LocaleHelper
import com.nekobot.app.integration.IncomingShareParser
import com.nekobot.app.integration.NekobotShortcutManager
import com.nekobot.app.ui.navigation.NekobotNavGraph
import com.nekobot.app.ui.theme.NekobotTheme
import com.nekobot.app.widget.NekobotWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private var appUnlocked by mutableStateOf(true)
    private var appLockError by mutableStateOf<String?>(null)
    private var biometricPromptShowing = false
    private var activityStarted = false
    private lateinit var biometricPrompt: BiometricPrompt

    override fun attachBaseContext(newBase: android.content.Context) {
        // 在 Activity 创建前应用选定语言，确保所有 Composable 资源读取使用正确 locale
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appUnlocked = !ServiceContainer.prefs.appLockEnabled
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricPromptShowing = false
                    appLockError = null
                    if (activityStarted) {
                        appUnlocked = true
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    biometricPromptShowing = false
                    appLockError = errString.toString()
                }

                override fun onAuthenticationFailed() {
                    appLockError = getString(R.string.app_lock_not_recognized)
                }
            }
        )
        handleExternalIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt())
        )
        setContent {
            NekobotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (ServiceContainer.prefs.appLockEnabled && !appUnlocked) {
                        AppLockedScreen(
                            error = appLockError,
                            onUnlock = ::requestAppUnlock
                        )
                    } else {
                        NekobotNavGraph()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        ServiceContainer.applicationScope.launch {
            NekobotShortcutManager.refresh(this@MainActivity)
            NekobotWidgetProvider.refreshAll(this@MainActivity)
        }
        if (!ServiceContainer.prefs.appLockEnabled) {
            appUnlocked = true
            return
        }
        if (!appUnlocked) {
            window.decorView.post(::requestAppUnlock)
        }
    }

    override fun onStop() {
        activityStarted = false
        super.onStop()
        if (ServiceContainer.prefs.appLockEnabled && !isChangingConfigurations) {
            appUnlocked = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(intent: Intent?) {
        intent ?: return
        val sessionId = intent.getStringExtra("session_id")
            ?: IncomingShareParser.parseDeepLinkSessionId(intent.dataString)
        if (!sessionId.isNullOrBlank()) {
            ServiceContainer.setPendingSessionId(sessionId)
            return
        }
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        lifecycleScope.launch {
            val share = withContext(Dispatchers.IO) {
                IncomingShareParser.parse(this@MainActivity, intent)
            } ?: return@launch
            ServiceContainer.setPendingShare(share)
            Toast.makeText(
                this@MainActivity,
                getString(R.string.share_choose_session),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun requestAppUnlock() {
        if (!ServiceContainer.prefs.appLockEnabled) {
            appUnlocked = true
            return
        }
        if (appUnlocked || biometricPromptShowing) return

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            appLockError = getString(R.string.app_lock_unavailable)
            return
        }

        appLockError = null
        biometricPromptShowing = true
        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock_prompt_title))
                .setSubtitle(getString(R.string.app_lock_prompt_subtitle))
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText(getString(R.string.common_cancel))
                .build()
        )
    }
}

@Composable
private fun AppLockedScreen(
    error: String?,
    onUnlock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Fingerprint,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.app_lock_screen_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = error ?: androidx.compose.ui.res.stringResource(R.string.app_lock_screen_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) {
            Text(androidx.compose.ui.res.stringResource(R.string.app_lock_unlock))
        }
    }
}
