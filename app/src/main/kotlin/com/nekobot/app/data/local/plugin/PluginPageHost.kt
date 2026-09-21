package com.nekobot.app.data.local.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.nekobot.app.data.local.LocalCommandProgressReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.File

/** 已解析的插件页面：插件、页面声明与插件目录。 */
data class ResolvedPluginPage(
    val plugin: InstalledPlugin,
    val page: PluginPageManifest,
    val directory: File
)

/**
 * 插件页面宿主：长驻 WebView 会话。
 *
 * 与命令运行时（一次执行 ↔ 一个 WebView ↔ 20s 总超时）不同，页面没有总超时，
 * 但每次 API 调用有 10s 超时、并发上限 4、响应上限 256KB；离页立即销毁。
 * 页面崩溃（onRenderProcessGone）返回 true 并转为错误卡片，避免拖垮 App 进程。
 */
internal class PluginPageHost(
    context: Context,
    private val resolved: ResolvedPluginPage,
    private val dispatcher: PluginApiDispatcher,
    private val sessionId: String?,
    /** 命令触发时携带的参数原文；页面通过 `__NEKO_LAUNCH__.argsText` 读取。 */
    private val launchArgs: String? = null,
    /** 命令的用户消息 id；有值时页面可用 `host.progress.update` 更新该消息的进度卡片。 */
    private val progressParentMessageId: String? = null,
    /** 首次加载前就已知的主题；必须与 Compose 当前主题一致，否则首屏会用到默认配色。 */
    private val initialTheme: PluginThemeTokens = PluginThemeTokens(),
    private val onCloseRequested: () -> Unit
) {
    sealed interface State {
        data object Loading : State
        data object Ready : State
        data class Error(val message: String, val crashed: Boolean = false) : State
    }

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callLimiter = Semaphore(MAX_CONCURRENT_CALLS)

    private val assetServer = PluginAssetServer(
        pluginDirectoryProvider = { if (it == resolved.plugin.id) resolved.directory else null }
    ).also { it.theme = initialTheme }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 命令触发时把进度卡片写回该命令所在的用户消息；非命令入口为 null。 */
    private val progressReporter: LocalCommandProgressReporter? = progressParentMessageId
        ?.takeIf { it.isNotBlank() }
        ?.let { messageId ->
            LocalCommandProgressReporter(
                parentMessageId = messageId,
                onUpdate = { card ->
                    runCatching {
                        com.nekobot.app.ServiceContainer.localRepository
                            .updateMessageThinkingCards(messageId, listOf(card))
                    }
                }
            )
        }

    @Volatile
    private var webView: WebView? = null
    private var loadFailed = false

    @Volatile
    private var destroyed = false

    val pluginId: String get() = resolved.plugin.id
    val pageTitle: String get() = resolved.page.title

    /** Compose 的 AndroidView 工厂调用；重复调用返回同一个 WebView。 */
    fun obtainWebView(): WebView {
        webView?.let { return it }
        val view = WebView(appContext)
        configure(view)
        webView = view
        view.addJavascriptInterface(HostBridge(), BRIDGE_NAME)
        val entryPath = resolved.page.entry.split('/').joinToString("/") { Uri.encode(it) }
        view.loadUrl(assetServer.virtualOrigin(resolved.plugin.id) + entryPath + launchQuery())
        return view
    }

    /** AndroidView 被移除时调用；销毁当前 WebView。 */
    fun releaseWebView(view: WebView) {
        if (webView === view) {
            webView = null
            destroyWebView(view)
        }
    }

    fun reload() {
        if (destroyed) return
        val old = webView
        webView = null
        old?.let(::destroyWebView)
        loadFailed = false
        _state.value = State.Loading
        _revision.value += 1
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        val old = webView
        webView = null
        old?.let(::destroyWebView)
        scope.cancel()
    }

    /**
     * 更新主题：写入资源服务（下次加载生效）并就地刷新已渲染页面的 CSS 变量与
     * `window.__NEKO_THEME__`，避免主题切换后需要手动刷新。
     */
    fun updateTheme(tokens: PluginThemeTokens) {
        if (assetServer.theme == tokens) return
        assetServer.theme = tokens
        val view = webView ?: return
        themeBackgroundColor()?.let { color ->
            mainHandler.post { runCatching { view.setBackgroundColor(color) } }
        }
        val script = assetServer.buildThemePatchScript()
        mainHandler.post { runCatching { view.evaluateJavascript(script, null) } }
    }

    /** 会话上下文与命令参数；注入脚本会解析它们并暴露为 `__NEKO_LAUNCH__`。 */
    private fun launchQuery(): String {
        val params = buildList {
            sessionId?.takeIf { it.isNotBlank() }?.let { add("sessionId=" + Uri.encode(it)) }
            launchArgs?.takeIf { it.isNotBlank() }?.let { add("args=" + Uri.encode(it)) }
        }
        return if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true
            // 第三方脚本不能使用 fetch、XHR、图片或导航绕过 Bridge 的网络权限。
            blockNetworkLoads = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }
        themeBackgroundColor()?.let(view::setBackgroundColor)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetServer.intercept(resolved.plugin.id, request.url.toString())

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                if (!loadFailed && !destroyed) _state.value = State.Ready
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame || destroyed) return
                loadFailed = true
                _state.value = State.Error(error.description?.toString() ?: "页面加载失败")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!destroyed) {
                    loadFailed = true
                    _state.value = State.Error(
                        if (detail.didCrash()) "页面进程已崩溃，请重载" else "页面进程已被系统回收，请重载",
                        crashed = true
                    )
                }
                if (webView === view) {
                    webView = null
                }
                destroyWebView(view)
                return true
            }
        }
    }

    private fun destroyWebView(view: WebView) {
        runCatching { view.removeJavascriptInterface(BRIDGE_NAME) }
        runCatching { view.stopLoading() }
        runCatching { view.clearHistory() }
        runCatching { view.destroy() }
    }

    private fun themeBackgroundColor(): Int? {
        val raw = assetServer.theme.colors["bg"] ?: return null
        return runCatching { Color.parseColor(raw) }.getOrNull()
    }

    private inner class HostBridge {
        @JavascriptInterface
        fun call(requestId: String?, name: String?, payloadJson: String?) {
            if (destroyed || requestId.isNullOrBlank() || name.isNullOrBlank()) return
            scope.launch {
                val timeoutMs = if (name == "ai.complete") AI_TIMEOUT_MS else API_TIMEOUT_MS
                val outcome = runCatching {
                    callLimiter.withPermit {
                        withTimeout(timeoutMs) {
                            dispatcher.dispatch(
                                PluginApiDispatcher.CallContext(
                                    plugin = resolved.plugin,
                                    sessionId = sessionId,
                                    progressReporter = progressReporter,
                                    closePage = { mainHandler.post(onCloseRequested) },
                                    isPage = true
                                ),
                                name,
                                payloadJson.orEmpty()
                            )
                        }
                    }
                }
                val envelopeJson = outcome.fold(
                    onSuccess = { value -> gson.toJson(mapOf("ok" to true, "value" to value)) },
                    onFailure = { error -> gson.toJson(mapOf("ok" to false, "error" to errorEnvelope(error))) }
                )
                val payloadJsonResult = if (envelopeJson.length > MAX_RESPONSE_CHARS) {
                    gson.toJson(
                        mapOf(
                            "ok" to false,
                            "error" to errorEnvelope(PluginApiException("响应过大", "response_too_large"))
                        )
                    )
                } else {
                    envelopeJson
                }
                deliver(requestId, payloadJsonResult)
            }
        }
    }

    private fun errorEnvelope(error: Throwable): Map<String, String> = when (error) {
        is TimeoutCancellationException ->
            mapOf("error" to "API 调用超时（${API_TIMEOUT_MS / 1000} 秒）", "code" to "timeout")
        is PluginApiException ->
            mapOf("error" to (error.message ?: "API 调用失败"), "code" to error.code)
        is CancellationException ->
            mapOf("error" to "调用已取消", "code" to "cancelled")
        else ->
            mapOf("error" to (error.message ?: "API 调用失败"), "code" to "internal")
    }

    private fun deliver(requestId: String, payloadJson: String) {
        val view = webView ?: return
        val script = "window.__nekoHostResult(${gson.toJson(requestId)},${gson.toJson(payloadJson)});"
        mainHandler.post {
            runCatching { view.evaluateJavascript(script, null) }
        }
    }

    companion object {
        const val BRIDGE_NAME = "NekoHost"
        const val API_TIMEOUT_MS = 10_000L

        /** AI 调用需要等待模型生成。 */
        const val AI_TIMEOUT_MS = 120_000L
        const val MAX_CONCURRENT_CALLS = 4
        const val MAX_RESPONSE_CHARS = 256 * 1024
    }
}
