package com.nekobot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nekobot.app.data.local.LocaleHelper
import com.nekobot.app.ui.navigation.NekobotNavGraph
import com.nekobot.app.ui.theme.NekobotTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        // 在 Activity 创建前应用选定语言，确保所有 Composable 资源读取使用正确 locale
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationIntent(intent)
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
                    NekobotNavGraph()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val sid = intent?.getStringExtra("session_id") ?: return
        ServiceContainer.setPendingSessionId(sid)
    }
}
