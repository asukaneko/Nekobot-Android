package com.nekobot.app.data.local.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.nekobot.app.data.local.LocalCommandProgressReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** 已解析的插件页面：插件、页面声明与插件目录。 */
data class ResolvedPluginPage(
    val plugin: InstalledPlugin,
    val page: PluginPageManifest,
    val directory: File
)

/** 沿 ContextWrapper 链找到 Activity；找不到时返回 null（调用方回退到原上下文）。 */
private fun Context.findActivityContext(): Context? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return null
}

/**
 * 插件页面宿主：长驻 WebView 会话。
 *
 * 与命令运行时（一次执行 ↔ 一个 WebView ↔ 20s 总超时）不同，页面没有总超时，
 * 但每次 API 调用有 10s 超时、并发上限 4、响应上限 256KB；离页立即销毁。
 * 页面崩溃（onRenderProcessGone）返回 true 并转为错误卡片，避免拖垮 App 进程。
 *
 * 多页面：允许在同插件虚拟源内跳转（相对链接、`location.href`、`host.ui.openPage`），
 * 保留 WebView 历史，返回键可逐页回退；顶栏标题跟随当前页面。
 * 原生弹窗：`window.alert/confirm/prompt` 与 `host.ui.alert/confirm/prompt/select`
 * 都转成 UI 层的原生弹窗，通过 [dialog] 状态暴露、[respondDialog] 回填结果。
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
    /** 系统语言；切换页面时用于取声明页面的本地化标题。 */
    private val languageCode: String = "zh",
    private val onCloseRequested: () -> Unit
) : PluginPageDialogHost {
    sealed interface State {
        data object Loading : State
        data object Ready : State
        data class Error(val message: String, val crashed: Boolean = false) : State
    }

    /**
     * WebView 必须用 Activity 上下文创建：用 Application 上下文时，`<select>` 下拉、
     * 自动填充、JS 弹窗等 WebView 内部窗口拿不到窗口令牌而无法显示。
     * 取不到 Activity 时回退到传入上下文，保证预览等无 Activity 场景仍可创建。
     */
    private val webViewContext: Context = context.findActivityContext() ?: context
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

    private val _pageTitle = MutableStateFlow(resolved.page.localizedTitle(languageCode))

    /** 顶栏标题：跟随当前页面（声明页面取本地化标题，其他 HTML 取文档标题）。 */
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _dialog = MutableStateFlow<PluginPageDialog?>(null)

    /** 待回答的原生弹窗；非空时由 UI 层展示并调用 [respondDialog]。 */
    val dialog: StateFlow<PluginPageDialog?> = _dialog.asStateFlow()

    private val dialogMutex = Mutex()
    private val dialogIds = AtomicLong(0)

    @Volatile
    private var pendingDialog: CompletableDeferred<PluginPageDialogResult>? = null

    private val pendingJsResults: MutableSet<JsResult> = Collections.synchronizedSet(mutableSetOf())

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

    /** Compose 的 AndroidView 工厂调用；重复调用返回同一个 WebView。 */
    fun obtainWebView(): WebView {
        webView?.let { return it }
        val view = WebView(webViewContext)
        configure(view)
        webView = view
        view.addJavascriptInterface(HostBridge(), BRIDGE_NAME)
        view.loadUrl(entryUrl(resolved.page.entry, launchArgs))
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
        // 旧 WebView 上的弹窗立即作废，避免重载后仍停留在屏幕上
        pendingDialog?.complete(PluginPageDialogResult.CANCELLED)
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
        pendingDialog = null
        _dialog.value = null
        cancelPendingJsResults()
        old?.let(::destroyWebView)
        scope.cancel()
    }

    /**
     * 在同一 WebView 内切换到插件声明的另一个页面；保留历史，返回键可退回上一页。
     * 由 `host.ui.openPage(pageId, args)` 调用；页面不存在或当前不可用返回 false。
     */
    fun openPage(pageId: String, args: String? = null): Boolean {
        if (destroyed) return false
        val page = resolved.plugin.pages.firstOrNull { it.id == pageId } ?: return false
        val view = webView ?: return false
        val url = entryUrl(page.entry, args)
        mainHandler.post { runCatching { view.loadUrl(url) } }
        return true
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

    /** 页面入口 URL：虚拟源 + 逐段编码的相对路径 + 启动参数。 */
    private fun entryUrl(entry: String, rawArgs: String?): String {
        val entryPath = entry.split('/').joinToString("/") { Uri.encode(it) }
        return assetServer.virtualOrigin(resolved.plugin.id) + entryPath + launchQuery(rawArgs)
    }

    /**
     * 会话上下文与命令参数；注入脚本会解析它们并暴露为 `__NEKO_LAUNCH__`。
     * `host.ui.openPage` 切换页面时沿用同一会话，但参数按调用方显式传入。
     */
    private fun launchQuery(rawArgs: String?): String {
        val params = buildList {
            sessionId?.takeIf { it.isNotBlank() }?.let { add("sessionId=" + Uri.encode(it)) }
            rawArgs?.takeIf { it.isNotBlank() }?.let { add("args=" + Uri.encode(it)) }
        }
        return if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    /** 弹窗能力实现：`host.ui.alert/confirm/prompt/select` 经分派器进入这里。 */
    override suspend fun showDialog(request: PluginPageDialog): PluginPageDialogResult =
        requestDialog(request) ?: PluginPageDialogResult.CANCELLED

    /** UI 侧回答弹窗（确认或取消）；重复调用会被忽略。 */
    fun respondDialog(result: PluginPageDialogResult) {
        pendingDialog?.complete(result)
    }

    /** 串行化弹窗：同一时间只展示一个，等待期间新的请求排队（JS 弹窗天然串行）。 */
    private suspend fun requestDialog(request: PluginPageDialog): PluginPageDialogResult? {
        if (destroyed) return null
        return dialogMutex.withLock {
            if (destroyed) return@withLock null
            val deferred = CompletableDeferred<PluginPageDialogResult>()
            pendingDialog = deferred
            _dialog.value = request.copy(id = dialogIds.incrementAndGet())
            try {
                withTimeoutOrNull(DIALOG_TIMEOUT_MS) { deferred.await() }
            } finally {
                pendingDialog = null
                _dialog.value = null
            }
        }
    }

    private fun cancelPendingJsResults() {
        val results = synchronized(pendingJsResults) {
            pendingJsResults.toList().also { pendingJsResults.clear() }
        }
        results.forEach { result -> mainHandler.post { runCatching { result.cancel() } } }
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
        view.webChromeClient = PageChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetServer.intercept(resolved.plugin.id, request.url.toString())

            /**
             * 只放行插件目录内的页面跳转（多 HTML 页面/相对链接/location.href）；
             * 外部地址、文件与自定义 scheme 一律拦截。
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (destroyed) return true
                return assetServer.pluginRelativePath(resolved.plugin.id, request.url.toString()) == null
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (destroyed) return true
                return assetServer.pluginRelativePath(resolved.plugin.id, url) == null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!destroyed) loadFailed = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (destroyed) return
                updatePageTitle(url, view.title)
                if (!loadFailed) _state.value = State.Ready
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

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (!request.isForMainFrame || destroyed) return
                if (errorResponse.statusCode < 400) return
                loadFailed = true
                _state.value = State.Error("页面资源不存在（HTTP ${errorResponse.statusCode}）")
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

    /**
     * 标题跟随当前页面：命中声明页面用本地化标题，否则用 HTML 文档标题。
     * 都取不到时保持上一个标题，避免顶栏闪烁。
     */
    private fun updatePageTitle(url: String, documentTitle: String?) {
        val relative = assetServer.pluginRelativePath(resolved.plugin.id, url)
        val declared = relative?.let { entry ->
            resolved.plugin.pages.firstOrNull { normalizeEntry(it.entry) == entry }
        }
        val title = declared?.localizedTitle(languageCode)
            ?: documentTitle?.takeIf { it.isNotBlank() }
            ?: return
        if (title.isNotBlank()) _pageTitle.value = title
    }

    private fun normalizeEntry(raw: String): String = raw.replace('\\', '/').trimStart('/')

    /** JS 弹窗（alert/confirm/prompt）转原生弹窗；由 UI 层回答后回填 JsResult。 */
    private inner class PageChromeClient : WebChromeClient() {
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
            handleJsDialog(PluginPageDialog.Kind.ALERT, message, result, "")

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
            handleJsDialog(PluginPageDialog.Kind.CONFIRM, message, result, "")

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean = handleJsDialog(PluginPageDialog.Kind.PROMPT, message, result, defaultValue.orEmpty())

        private fun handleJsDialog(
            kind: PluginPageDialog.Kind,
            message: String?,
            result: JsResult?,
            defaultValue: String
        ): Boolean {
            if (result == null) return false
            if (destroyed) {
                runCatching { result.cancel() }
                return true
            }
            pendingJsResults.add(result)
            val request = PluginPageDialog(
                kind = kind,
                title = resolved.plugin.name.take(PluginDialogPayloads.MAX_TITLE_CHARS),
                message = message.orEmpty().take(PluginDialogPayloads.MAX_MESSAGE_CHARS),
                defaultValue = defaultValue.take(PluginDialogPayloads.MAX_DEFAULT_CHARS)
            )
            scope.launch {
                val outcome = requestDialog(request)
                val confirmed = outcome?.confirmed == true
                mainHandler.post {
                    pendingJsResults.remove(result)
                    runCatching {
                        when {
                            kind == PluginPageDialog.Kind.ALERT -> result.confirm()
                            !confirmed -> result.cancel()
                            kind == PluginPageDialog.Kind.PROMPT -> {
                                val promptResult = result as? JsPromptResult
                                if (promptResult != null) {
                                    promptResult.confirm(outcome?.text.orEmpty())
                                } else {
                                    result.cancel()
                                }
                            }
                            else -> result.confirm()
                        }
                    }
                }
            }
            return true
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
                val timeoutMs = when (name) {
                    "ai.complete" -> AI_TIMEOUT_MS
                    // 原生弹窗要等用户回答，不能按普通 API 的 10 秒超时
                    "ui.alert", "ui.confirm", "ui.prompt", "ui.select" -> DIALOG_TIMEOUT_MS
                    else -> API_TIMEOUT_MS
                }
                val outcome = runCatching {
                    callLimiter.withPermit {
                        withTimeout(timeoutMs) {
                            dispatcher.dispatch(
                                PluginApiDispatcher.CallContext(
                                    plugin = resolved.plugin,
                                    sessionId = sessionId,
                                    progressReporter = progressReporter,
                                    closePage = { mainHandler.post(onCloseRequested) },
                                    isPage = true,
                                    openPage = { pageId, args -> openPage(pageId, args) },
                                    dialogs = this@PluginPageHost
                                ),
                                name,
                                payloadJson.orEmpty()
                            )
                        }
                    }
                }
                val envelopeJson = outcome.fold(
                    onSuccess = { value -> gson.toJson(mapOf("ok" to true, "value" to value)) },
                    onFailure = { error -> gson.toJson(mapOf("ok" to false, "error" to errorEnvelope(error, timeoutMs))) }
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

    private fun errorEnvelope(error: Throwable, timeoutMs: Long = API_TIMEOUT_MS): Map<String, String> = when (error) {
        is TimeoutCancellationException ->
            mapOf("error" to "API 调用超时（${timeoutMs / 1000} 秒）", "code" to "timeout")
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

        /** 原生弹窗最多等待用户 5 分钟，超时按取消处理（避免永远挂住页面）。 */
        const val DIALOG_TIMEOUT_MS = 300_000L
    }
}
