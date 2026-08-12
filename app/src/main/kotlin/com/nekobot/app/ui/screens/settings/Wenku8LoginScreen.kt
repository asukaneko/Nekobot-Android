package com.nekobot.app.ui.screens.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nekobot.app.NekobotApp
import com.nekobot.app.ui.components.GlassCard

private const val WENKU8_URL = "https://www.wenku8.net/login.php"

/** 判断 URL 是否表示登录成功（跳转到了登录后的页面）。 */
private fun isLoginSuccessUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    // 登录成功后通常会跳转到 index.php 或控制面板，不再是 login.php
    val successPatterns = listOf(
        "/index.php",
        "/useredit.php",
        "/bookcase.php",
        "/modules/article/",
        "/main.php"
    )
    if (url.contains("/login.php")) return false
    return successPatterns.any { url.contains(it) } || url.endsWith("wenku8.net/")
}

/** 判断 Cookie 中是否包含登录态关键字（jieqiUserInfo 或 PHPSESSID 已登录）。 */
private fun hasLoginCookie(cookie: String): Boolean {
    return cookie.contains("jieqiUserInfo=") &&
        !cookie.contains("jieqiUserId%3D0") &&
        cookie.contains("PHPSESSID=")
}

/**
 * wenku8 内置浏览器登录页。
 *
 * 用户在此页面完成登录后，自动提取 Cookie 和 WebView 的 User-Agent 并保存。
 * 这样保证了 IP、UA、Cookie 三者一致，避免 CloudFlare cf_clearance 403。
 *
 * 入口：在聊天框输入 `/wenku8_login` 或 `/wenku_login`。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Wenku8LoginScreen(onBack: () -> Unit) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf("") }
    var cookieSaved by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("请在下方登录 wenku8 账号喵~") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("wenku8 登录", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        webViewRef?.reload()
                    }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
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
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 状态提示卡片
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (cookieSaved) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (cookieSaved) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                            modifier = Modifier.size(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (cookieSaved) "登录成功喵！" else "等待登录中...",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // WebView 登录区域
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                // 允许混合内容（wenku8 部分资源是 HTTP）
                                settings.mixedContentMode =
                                    android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                                // 启用 Cookie 持久化（必须在 WebView 创建后配置）
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                webViewClient = object : WebViewClient() {
                                    private val mainHandler = Handler(Looper.getMainLooper())

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?
                                    ) {
                                        mainHandler.post { currentUrl = url.orEmpty() }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val finishedUrl = url.orEmpty()
                                        mainHandler.post {
                                            currentUrl = finishedUrl
                                            // 同步 Cookie 到持久化存储
                                            CookieManager.getInstance().flush()

                                            // 检测登录成功：URL 跳转 + Cookie 含 jieqiUserInfo
                                            val cookie = CookieManager.getInstance()
                                                .getCookie("https://www.wenku8.net/").orEmpty()
                                            if (isLoginSuccessUrl(finishedUrl) && hasLoginCookie(cookie)) {
                                                val saved = extractAndSaveCredentials(view)
                                                if (saved) {
                                                    cookieSaved = true
                                                    statusMessage = "Cookie + UA 已保存，现在可以使用 /findbook 等命令"
                                                }
                                            }
                                        }
                                    }
                                }

                                // 加载登录页
                                loadUrl(WENKU8_URL)
                                webViewRef = this
                            }
                        },
                        update = { webView ->
                            webViewRef = webView
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(480.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )

                // 说明卡片
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "使用说明",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "1. 在上方登录框输入 wenku8 账号密码并登录\n" +
                                "2. 登录成功后 Cookie 和 User-Agent 会自动保存\n" +
                                "3. 如遇 CloudFlare 验证，请完成验证后再登录\n" +
                                "4. 保存后即可使用 /findbook、/hotnovel 等命令",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 从 WebView 提取 Cookie 和 User-Agent 并保存到 PrefsManager。
 * @return true 表示保存成功
 */
private fun extractAndSaveCredentials(view: WebView?): Boolean {
    if (view == null) return false
    val cookie = CookieManager.getInstance().getCookie("https://www.wenku8.net/").orEmpty()
    val userAgent = view.settings.userAgentString.orEmpty()

    if (cookie.isBlank()) return false

    // 保存到 PrefsManager
    val prefs = (view.context.applicationContext as? NekobotApp)
        ?.let { com.nekobot.app.ServiceContainer.prefs }
    if (prefs == null) {
        Toast.makeText(view.context, "保存失败：无法访问应用配置", Toast.LENGTH_SHORT).show()
        return false
    }

    prefs.wenku8Cookie = cookie
    prefs.wenku8UserAgent = userAgent

    Toast.makeText(
        view.context,
        "✅ Cookie + UA 保存成功喵！现在可以使用 /findbook 等命令了",
        Toast.LENGTH_LONG
    ).show()
    return true
}
