package com.nekobot.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.nekobot.app.data.local.LocaleHelper
import com.nekobot.app.integration.IncomingShareParser
import com.nekobot.app.integration.NekobotShortcutManager
import com.nekobot.app.ui.components.MemoryMigrationDialog
import com.nekobot.app.ui.components.LiquidGlassMenuHost
import com.nekobot.app.ui.navigation.NekobotNavGraph
import com.nekobot.app.ui.screens.chat.ChatSendShortcutHost
import com.nekobot.app.ui.theme.NekobotTheme
import com.nekobot.app.widget.NekobotWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private lateinit var privacy: NekobotPrivacyGate

    /** Android 13+ 通知权限：Agent 等待提醒依赖它，启动后请求一次。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 授权结果无需处理：未授权时提醒自动降级为会话列表提示 */ }

    override fun attachBaseContext(newBase: android.content.Context) {
        // 在 Activity 创建前应用选定语言，确保所有 Composable 资源读取使用正确 locale
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privacy = NekobotPrivacyGate(this)
        handleExternalIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.auto(Color.Transparent.value.toInt(), Color.Transparent.value.toInt())
        )
        setContentView(
            shortcutAwareRoot().apply {
                addView(
                    ComposeView(this@MainActivity).apply {
                        setContent {
                            privacy.Content {
                                NekobotTheme {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background
                                    ) {
                                        LiquidGlassMenuHost {
                                            NekobotNavGraph()
                                            // 记忆迁移询问与业务页面无关，且可能在任意 Profile 切换时出现，挂在这里最稳妥。
                                            // 必须放在解锁分支内：隐私锁生效时不应在锁屏之上弹出任何业务弹窗。
                                            MemoryMigrationDialog()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /**
     * 根 View：在 pre-IME 阶段截获硬件键盘快捷键。
     *
     * `ViewPreImeInputStage` 早于输入法处理按键，所以即使输入法先一步消费了 Ctrl+Enter、
     * 或输入框一时失焦，也能把按键交给聊天输入区（[ChatSendShortcutHost]）。
     */
    private fun shortcutAwareRoot(): FrameLayout = object : FrameLayout(this) {
        override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
            if (ChatSendShortcutHost.handleHardwareEnter(event)) return true
            return super.dispatchKeyEventPreIme(event)
        }
    }

    override fun onStart() {
        super.onStart()

        ServiceContainer.applicationScope.launch {
            NekobotShortcutManager.refresh(this@MainActivity)
            NekobotWidgetProvider.refreshAll(this@MainActivity)
        }
        if (!ServiceContainer.prefs.appLockEnabled) requestNotificationPermissionIfNeeded()
    }

    /** 首次进入应用时请求通知权限（Android 13+），只请求一次。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (ServiceContainer.prefs.notificationPermissionAsked) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        ServiceContainer.prefs.notificationPermissionAsked = true
        runCatching {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    private fun handleExternalIntent(intent: Intent?) {
        intent ?: return
        // 插件页面网格小组件：直接打开对应插件页面。
        val widgetPluginId = intent.getStringExtra(com.nekobot.app.widget.PluginPagesWidgetProvider.EXTRA_PLUGIN_ID)
        val widgetPageId = intent.getStringExtra(com.nekobot.app.widget.PluginPagesWidgetProvider.EXTRA_PAGE_ID)
        if (!widgetPluginId.isNullOrBlank() && !widgetPageId.isNullOrBlank()) {
            ServiceContainer.requestPluginPage(
                pluginId = widgetPluginId,
                pageId = widgetPageId,
                sessionId = null,
                args = ""
            )
            return
        }
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
}

@Composable
internal fun AppLockedScreen(
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
