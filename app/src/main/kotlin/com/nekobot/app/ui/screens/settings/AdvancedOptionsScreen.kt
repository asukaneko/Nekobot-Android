package com.nekobot.app.ui.screens.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.components.GlassCard

/**
 * 高级选项：把「高级设置」页里的若干开关收敛到同一个子界面，
 * 主页面只保留入口，避免首页被大量开关与说明文字挤满。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedOptionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val recentSessionsIncludeArchived by ServiceContainer.prefs.recentSessionsIncludeArchivedFlow.collectAsStateWithLifecycle()
    val openLatestSessionOnLaunch by ServiceContainer.prefs.openLatestSessionOnLaunchFlow.collectAsStateWithLifecycle()
    val homeBannerAggregated by ServiceContainer.prefs.homeBannerAggregatedFlow.collectAsStateWithLifecycle()

    val appLockState = remember { mutableStateOf(ServiceContainer.prefs.appLockEnabled) }
    var appLockEnabled by appLockState
    val pendingAppLockState = remember { mutableStateOf<Boolean?>(null) }
    var imagePromptOptimizeEnabled by remember {
        mutableStateOf(ServiceContainer.prefs.messageImagePromptOptimizeEnabled)
    }
    val activity = remember(context) { context.findFragmentActivity() }
    val appLockPrompt = remember(activity) {
        activity?.let { host ->
            BiometricPrompt(
                host,
                ContextCompat.getMainExecutor(host),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        val enabled = pendingAppLockState.value ?: return
                        ServiceContainer.prefs.appLockEnabled = enabled
                        appLockState.value = enabled
                        pendingAppLockState.value = null
                        // 立即更新 FLAG_SECURE，保证下次切后台时最近任务页预览已被隐藏
                        if (enabled) {
                            host.window.setFlags(
                                WindowManager.LayoutParams.FLAG_SECURE,
                                WindowManager.LayoutParams.FLAG_SECURE
                            )
                        } else {
                            host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                        Toast.makeText(
                            context,
                            context.getString(
                                if (enabled) {
                                    R.string.settings_app_lock_enabled
                                } else {
                                    R.string.settings_app_lock_disabled
                                }
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        pendingAppLockState.value = null
                        Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    fun requestAppLockChange(enabled: Boolean) {
        if (enabled == appLockState.value || pendingAppLockState.value != null) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val available = activity != null &&
            BiometricManager.from(context).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (!available || appLockPrompt == null) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_app_lock_unavailable),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        pendingAppLockState.value = enabled
        appLockPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.settings_app_lock_auth_title))
                .setSubtitle(
                    context.getString(
                        if (enabled) {
                            R.string.settings_app_lock_enable_auth_desc
                        } else {
                            R.string.settings_app_lock_disable_auth_desc
                        }
                    )
                )
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText(context.getString(R.string.common_cancel))
                .build()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_advanced_options),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    SettingsSectionHeader(title = stringResource(R.string.settings_advanced_options_switches))
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        icon = Icons.Filled.Fingerprint,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_app_lock),
                        subtitle = stringResource(R.string.settings_app_lock_desc),
                        checked = appLockEnabled,
                        enabled = pendingAppLockState.value == null,
                        onCheckedChange = ::requestAppLockChange
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Tune,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_recent_sessions_include_archived),
                        subtitle = stringResource(R.string.settings_recent_sessions_include_archived_desc),
                        checked = recentSessionsIncludeArchived,
                        onCheckedChange = { enabled ->
                            ServiceContainer.prefs.recentSessionsIncludeArchived = enabled
                        }
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Tune,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_open_latest_session_on_launch),
                        subtitle = stringResource(R.string.settings_open_latest_session_on_launch_desc),
                        checked = openLatestSessionOnLaunch,
                        onCheckedChange = { enabled ->
                            ServiceContainer.prefs.openLatestSessionOnLaunch = enabled
                        }
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Tune,
                        iconColor = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_home_banner_aggregated),
                        subtitle = stringResource(R.string.settings_home_banner_aggregated_desc),
                        checked = homeBannerAggregated,
                        onCheckedChange = { enabled ->
                            ServiceContainer.prefs.homeBannerAggregated = enabled
                        }
                    )
                    if (appMode == AppMode.LOCAL) {
                        SettingDivider()
                        SettingSwitchRow(
                            icon = Icons.Filled.AutoAwesome,
                            iconColor = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.settings_message_image_prompt_optimize),
                            subtitle = stringResource(R.string.settings_message_image_prompt_optimize_desc),
                            checked = imagePromptOptimizeEnabled,
                            onCheckedChange = { enabled ->
                                imagePromptOptimizeEnabled = enabled
                                ServiceContainer.prefs.messageImagePromptOptimizeEnabled = enabled
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
