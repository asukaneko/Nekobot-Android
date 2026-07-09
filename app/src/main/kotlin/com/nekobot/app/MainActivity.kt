package com.nekobot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nekobot.app.ui.navigation.NekobotNavGraph
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.NekobotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.value.toInt()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.value.toInt())
        )
        setContent {
            NekobotTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgDark)
                ) {
                    NekobotNavGraph()
                }
            }
        }
    }
}
