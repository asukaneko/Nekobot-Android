package com.nekobot.app.data.local.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * Agent 模式的会话级原生浏览器工具。
 *
 * 设计参考 OpenMinis 的 browser_use：模型通过单个工具选择动作，页面变化后自动回传
 * 当前页面文本、可交互元素与截图。WebView 只允许 http(s)，文件产物固定写入会话工作区。
 *
 * Agent 工具循环运行在 Dispatchers.IO；Android WebView 的所有调用仍统一切回主线程。
 */
internal class LocalBrowserTool(
    context: Context,
    private val sessionId: String,
    workspaceRoot: File
) {
    companion object {
        private const val NAVIGATION_TIMEOUT_MS = 30_000L
        private const val JAVASCRIPT_TIMEOUT_MS = 15_000L
        private const val MAIN_THREAD_TIMEOUT_MS = 15_000L
        private const val IMAGE_LOAD_TIMEOUT_MS = 4_000L
        private const val DEFAULT_VIEWPORT_WIDTH_CSS = 412
        private const val DEFAULT_VIEWPORT_HEIGHT_CSS = 800
        private const val MAX_FULL_PAGE_HEIGHT_PX = 4096
        private const val DEFAULT_TEXT_LIMIT = 30_000
        private const val DEFAULT_HTML_LIMIT = 60_000
        private const val DEFAULT_ELEMENT_LIMIT = 80
        private const val DEFAULT_LINK_LIMIT = 200
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
    }

    private val appContext = context.applicationContext
    private val workspace = workspaceRoot.canonicalFile
    private val browserDir = File(workspace, "browser").canonicalFile.also { directory ->
        require(
            directory.path == workspace.path ||
                directory.path.startsWith(workspace.path + File.separator)
        ) { "浏览器输出目录越过会话工作区" }
        directory.mkdirs()
    }
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executionLock = ReentrantLock()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var currentUrl: String = ""

    @Volatile
    private var currentTitle: String = ""

    @Volatile
    private var navigationLatch: CountDownLatch? = null

    @Volatile
    private var navigationError: String? = null

    init {
        LocalBrowserPreviewRegistry.register(sessionId, this)
    }

    fun execute(args: Map<String, Any>): Map<String, Any> = executionLock.withLock {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return failure("浏览器工具不能在主线程同步执行")
        }

        val action = args.string("action").trim().lowercase()
        if (action.isBlank()) return failure("browser_use 缺少 action")

        try {
            ensureWebView()
            when (action) {
                "navigate" -> navigate(args.string("url"))
                    .withVisualState(action)

                "screenshot", "understand_screenshot" ->
                    screenshot(args.boolean("full_page")) + ("action" to action)
                "click" -> click(args).withVisualState(action)
                "type" -> type(args).withVisualState(action)
                "get_text" -> getText(args.string("selector").ifBlank { null })
                "get_readable" -> getReadable()
                "get_html", "get_source" -> getHtml(
                    selector = args.string("selector").ifBlank { null },
                    maxChars = args.int("max_chars", DEFAULT_HTML_LIMIT)
                        .coerceIn(1_000, 120_000)
                )

                "get_links" -> getLinks(
                    selector = args.string("selector").ifBlank { null },
                    limit = args.int("max_results", DEFAULT_LINK_LIMIT)
                        .coerceIn(1, 500)
                )

                "get_backbone" -> getBackbone(
                    args.int("max_depth", DEFAULT_ELEMENT_LIMIT)
                        .coerceIn(1, 200)
                )

                "find_elements" -> findElements(
                    selector = args.string("selector"),
                    limit = args.int("max_depth", DEFAULT_ELEMENT_LIMIT).coerceIn(1, 200)
                )

                "get_page_info" -> getPageInfo()
                "scroll" -> scroll(args).withVisualState(action)
                "execute_js" -> executeJavaScript(args.string("script"))
                "wait" -> waitForPage(args.int("wait_ms", 1_000))
                "back" -> historyNavigation(action) { view ->
                    if (!view.canGoBack()) false else {
                        view.goBack()
                        true
                    }
                }.withVisualState(action)

                "forward" -> historyNavigation(action) { view ->
                    if (!view.canGoForward()) false else {
                        view.goForward()
                        true
                    }
                }.withVisualState(action)

                "reload" -> historyNavigation(action) { view ->
                    view.reload()
                    true
                }.withVisualState(action)

                else -> failure(
                    "不支持的浏览器动作: $action",
                    "supported_actions" to listOf(
                        "navigate",
                        "screenshot",
                        "understand_screenshot",
                        "click",
                        "type",
                        "get_text",
                        "get_readable",
                        "get_html",
                        "get_source",
                        "get_links",
                        "get_backbone",
                        "find_elements",
                        "get_page_info",
                        "scroll",
                        "execute_js",
                        "wait",
                        "back",
                        "forward",
                        "reload"
                    )
                )
            }
        } catch (error: Exception) {
            failure(error.message ?: "浏览器工具执行失败")
        }
    }

    fun close() {
        LocalBrowserPreviewRegistry.unregister(sessionId, this)
        val view = webView ?: return
        webView = null
        runCatching {
            runOnMain(5_000) {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.clearHistory()
                view.removeAllViews()
                view.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return runOnMain {
            webView ?: WebView(appContext).apply browserView@ {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadsImagesAutomatically = true
                    blockNetworkImage = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    userAgentString = MOBILE_USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                // WebView 不挂载到 Activity 视图树；软件层可确保 draw(Canvas) 截图包含图片层。
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                setBackgroundColor(android.graphics.Color.WHITE)
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@browserView, true)
                }
                webViewClient = createWebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        currentTitle = title.orEmpty()
                        LocalBrowserPreviewRegistry.update(
                            sessionId = sessionId,
                            url = currentUrl,
                            title = currentTitle
                        )
                    }
                }
                applyDefaultViewport(this)
                webView = this
            }
        }
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean = shouldBlockNavigation(request?.url?.toString())

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            shouldBlockNavigation(url)

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            currentUrl = url.orEmpty()
            navigationError = null
            LocalBrowserPreviewRegistry.update(
                sessionId = sessionId,
                url = currentUrl,
                title = currentTitle,
                isLoading = true,
                advanceRevision = true
            )
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            currentUrl = url.orEmpty()
            currentTitle = view?.title.orEmpty()
            LocalBrowserPreviewRegistry.update(
                sessionId = sessionId,
                url = currentUrl,
                title = currentTitle,
                isLoading = false,
                advanceRevision = true
            )
            navigationLatch?.countDown()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                navigationError = error?.description?.toString().orEmpty()
                    .ifBlank { "网页加载失败" }
                LocalBrowserPreviewRegistry.update(
                    sessionId = sessionId,
                    url = currentUrl,
                    title = currentTitle,
                    isLoading = false,
                    advanceRevision = true
                )
                navigationLatch?.countDown()
            }
        }
    }

    private fun shouldBlockNavigation(url: String?): Boolean {
        if (url.isNullOrBlank() || isAllowedWebUrl(url)) return false
        navigationError = "已阻止非 http(s) 地址: $url"
        LocalBrowserPreviewRegistry.update(
            sessionId = sessionId,
            isLoading = false,
            advanceRevision = true
        )
        navigationLatch?.countDown()
        return true
    }

    private fun navigate(rawUrl: String): Map<String, Any> {
        if (rawUrl.isBlank()) return failure("navigate 缺少 url")
        val normalized = normalizeWebUrl(rawUrl)
            ?: return failure("只允许打开 http(s) URL")

        val navigation = CountDownLatch(1)
        navigationLatch = navigation
        navigationError = null
        LocalBrowserPreviewRegistry.update(
            sessionId = sessionId,
            url = normalized,
            isLoading = true,
            advanceRevision = true
        )
        runOnMain { requireWebView().loadUrl(normalized) }
        val completed = navigation.await(NAVIGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        navigationLatch = null

        if (!completed) {
            runCatching { runOnMain { requireWebView().stopLoading() } }
            LocalBrowserPreviewRegistry.update(
                sessionId = sessionId,
                url = currentUrl.ifBlank { normalized },
                isLoading = false,
                advanceRevision = true
            )
            return failure("网页加载超时", "url" to normalized)
        }
        navigationError?.takeIf { it.isNotBlank() }?.let { message ->
            return failure(message, "url" to currentUrl.ifBlank { normalized })
        }
        waitForDomToSettle(700)
        return success(
            "action" to "navigate",
            "content" to "已打开网页",
            "url" to currentUrl.ifBlank { normalized },
            "title" to currentTitle
        )
    }

    private fun historyNavigation(
        action: String,
        operation: (WebView) -> Boolean
    ): Map<String, Any> {
        val navigation = CountDownLatch(1)
        navigationLatch = navigation
        navigationError = null
        val started = runOnMain { operation(requireWebView()) }
        if (!started) {
            navigationLatch = null
            return failure(
                when (action) {
                    "back" -> "没有可后退的页面"
                    "forward" -> "没有可前进的页面"
                    else -> "无法执行浏览器导航"
                }
            )
        }
        val completed = navigation.await(NAVIGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        navigationLatch = null
        if (!completed) {
            runCatching { runOnMain { requireWebView().stopLoading() } }
            LocalBrowserPreviewRegistry.update(
                sessionId = sessionId,
                url = currentUrl,
                title = currentTitle,
                isLoading = false,
                advanceRevision = true
            )
            return failure("浏览器 $action 超时")
        }
        navigationError?.takeIf(String::isNotBlank)?.let { return failure(it) }
        waitForDomToSettle(500)
        return success("action" to action, "content" to "浏览器已执行 $action")
    }

    private fun click(args: Map<String, Any>): Map<String, Any> {
        val selector = args.string("selector").ifBlank { null }
        val x = args.optionalInt("coordinate_x")
        val y = args.optionalInt("coordinate_y")
        if (selector == null && (x == null || y == null)) {
            return failure("click 需要 selector 或 coordinate_x/coordinate_y")
        }
        val target = if (selector != null) {
            "document.querySelector(${gson.toJson(selector)})"
        } else {
            "document.elementFromPoint($x, $y)"
        }
        val raw = evaluate(
            """
                (() => {
                  try {
                    const el = $target;
                    if (!el) return JSON.stringify({error: "未找到要点击的元素"});
                    el.scrollIntoView({block: "center", inline: "center"});
                    el.focus();
                    el.click();
                    return JSON.stringify({
                      clicked: true,
                      tag: (el.tagName || "").toLowerCase(),
                      text: (el.innerText || el.getAttribute("aria-label") || "").trim().slice(0, 200)
                    });
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        val result = javascriptResult(raw, "已点击元素")
        if (result["success"] == true) waitForDomToSettle(700)
        return result
    }

    private fun type(args: Map<String, Any>): Map<String, Any> {
        val selector = args.string("selector")
        if (selector.isBlank()) return failure("type 缺少 selector")
        if (!args.containsKey("text")) return failure("type 缺少 text")
        val text = args.string("text")
        val raw = evaluate(
            """
                (() => {
                  try {
                    const el = document.querySelector(${gson.toJson(selector)});
                    if (!el) return JSON.stringify({error: "未找到输入元素"});
                    el.scrollIntoView({block: "center", inline: "center"});
                    el.focus();
                    if (el.isContentEditable) {
                      el.textContent = ${gson.toJson(text)};
                    } else {
                      const proto = el.tagName === "TEXTAREA"
                        ? HTMLTextAreaElement.prototype
                        : HTMLInputElement.prototype;
                      const setter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
                      if (setter) setter.call(el, ${gson.toJson(text)});
                      else el.value = ${gson.toJson(text)};
                    }
                    el.dispatchEvent(new InputEvent("input", {bubbles: true, inputType: "insertText", data: ${gson.toJson(text)}}));
                    el.dispatchEvent(new Event("change", {bubbles: true}));
                    return JSON.stringify({typed: true, length: ${text.length}});
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        val result = javascriptResult(raw, "已输入 ${text.length} 个字符")
        if (result["success"] == true) waitForDomToSettle(300)
        return result
    }

    private fun getText(selector: String?): Map<String, Any> {
        val target = selector?.let { "document.querySelector(${gson.toJson(it)})" }
            ?: "document.body"
        val raw = evaluate(
            """
                (() => {
                  try {
                    const el = $target;
                    if (!el) return JSON.stringify({error: "未找到元素"});
                    const full = (el.innerText || el.textContent || "").trim();
                    return JSON.stringify({
                      text: full.slice(0, $DEFAULT_TEXT_LIMIT),
                      total_chars: full.length,
                      truncated: full.length > $DEFAULT_TEXT_LIMIT
                    });
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        return textResult(raw)
    }

    private fun getReadable(): Map<String, Any> {
        val raw = evaluate(
            """
                (() => {
                  try {
                    const source = document.querySelector("article, main, [role='main']") || document.body;
                    if (!source) return JSON.stringify({error: "页面没有可读取内容"});
                    const clone = source.cloneNode(true);
                    clone.querySelectorAll("script,style,noscript,svg,canvas,nav,footer,aside,form").forEach(el => el.remove());
                    const full = (clone.innerText || clone.textContent || "")
                      .replace(/\n{3,}/g, "\n\n")
                      .trim();
                    return JSON.stringify({
                      title: document.title || "",
                      url: location.href,
                      text: full.slice(0, $DEFAULT_TEXT_LIMIT),
                      total_chars: full.length,
                      truncated: full.length > $DEFAULT_TEXT_LIMIT
                    });
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        return textResult(raw)
    }

    /**
     * 获取页面当前的动态 DOM 源码，而不是只读首次网络响应。
     *
     * 这样能包含 JavaScript 渲染后的链接和属性；可用 selector 缩小范围，避免把整页源码
     * 塞进模型上下文。
     */
    private fun getHtml(selector: String?, maxChars: Int): Map<String, Any> {
        val target = selector?.let { "document.querySelector(${gson.toJson(it)})" }
            ?: "document.documentElement"
        val raw = evaluate(
            """
                (() => {
                  try {
                    const el = $target;
                    if (!el) return JSON.stringify({error: "未找到元素"});
                    const full = el.outerHTML || "";
                    return JSON.stringify({
                      html: full.slice(0, $maxChars),
                      total_chars: full.length,
                      truncated: full.length > $maxChars
                    });
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        val parsed = parseJsonMap(raw)
        parsed["error"]?.toString()?.takeIf(String::isNotBlank)?.let { return failure(it) }
        val html = parsed["html"]?.toString().orEmpty()
        return success(
            "action" to "get_html",
            "content" to html,
            "source_type" to "live_dom",
            "url" to currentUrl,
            "title" to currentTitle,
            "total_chars" to ((parsed["total_chars"] as? Number)?.toInt() ?: html.length),
            "truncated" to (parsed["truncated"] as? Boolean ?: false)
        )
    }

    /**
     * 结构化提取 DOM 中的链接、资源地址和表单目标，自动将相对地址解析为绝对 URL。
     */
    private fun getLinks(selector: String?, limit: Int): Map<String, Any> {
        val query = selector?.let { gson.toJson(it) }
            ?: gson.toJson(
                "a[href],area[href],link[href],img[src],script[src],source[src]," +
                    "video[src],audio[src],iframe[src],form[action],[data-src],[data-url],[data-href]"
            )
        val raw = evaluate(
            """
                (() => {
                  try {
                    const attributes = ["href", "src", "action", "poster", "data-src", "data-url", "data-href"];
                    const seen = new Set();
                    const links = [];
                    for (const el of document.querySelectorAll($query)) {
                      for (const attribute of attributes) {
                        const rawUrl = el.getAttribute(attribute);
                        if (!rawUrl) continue;
                        let resolved;
                        try {
                          resolved = new URL(rawUrl, document.baseURI).href;
                        } catch (_) {
                          continue;
                        }
                        if (!/^https?:/i.test(resolved) || seen.has(resolved)) continue;
                        seen.add(resolved);
                        links.push({
                          url: resolved,
                          raw_url: rawUrl,
                          tag: (el.tagName || "").toLowerCase(),
                          attribute: attribute,
                          text: (el.innerText || el.getAttribute("alt") || el.getAttribute("title") || "")
                            .trim()
                            .slice(0, 200),
                          rel: el.getAttribute("rel") || ""
                        });
                      }
                    }
                    return JSON.stringify({
                      links: links.slice(0, $limit),
                      total: links.length,
                      truncated: links.length > $limit
                    });
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        val parsed = parseJsonMap(raw)
        parsed["error"]?.toString()?.takeIf(String::isNotBlank)?.let { return failure(it) }
        val links = parsed["links"] as? List<*> ?: emptyList<Any>()
        return success(
            "action" to "get_links",
            "content" to "找到 ${links.size} 个网页 URL",
            "url" to currentUrl,
            "title" to currentTitle,
            "links" to links,
            "total" to ((parsed["total"] as? Number)?.toInt() ?: links.size),
            "truncated" to (parsed["truncated"] as? Boolean ?: false)
        )
    }

    private fun getBackbone(limit: Int): Map<String, Any> {
        val raw = evaluate(elementCollectionScript(null, limit))
        return elementResult(raw, "页面可交互元素")
    }

    private fun findElements(selector: String, limit: Int): Map<String, Any> {
        if (selector.isBlank()) return failure("find_elements 缺少 selector")
        val raw = evaluate(elementCollectionScript(selector, limit))
        return elementResult(raw, "匹配的页面元素")
    }

    private fun getPageInfo(): Map<String, Any> {
        val state = pageState(textLimit = 0, elementLimit = 0)
        return success(
            "action" to "get_page_info",
            "content" to "当前页面信息"
        ) + state
    }

    private fun scroll(args: Map<String, Any>): Map<String, Any> {
        val direction = args.string("direction").ifBlank { "down" }.lowercase()
        if (direction !in setOf("up", "down")) return failure("direction 只能是 up 或 down")
        val amount = args.int("amount", 600).coerceIn(1, 10_000)
        val signedAmount = if (direction == "up") -amount else amount
        val selector = args.string("selector").ifBlank { null }
        val target = selector?.let { "document.querySelector(${gson.toJson(it)})" } ?: "window"
        val raw = evaluate(
            """
                (() => {
                  try {
                    const target = $target;
                    if (!target) return JSON.stringify({error: "未找到滚动目标"});
                    if (target === window) window.scrollBy({top: $signedAmount, behavior: "auto"});
                    else target.scrollBy({top: $signedAmount, behavior: "auto"});
                    const position = target === window ? window.scrollY : target.scrollTop;
                    const height = target === window
                      ? document.documentElement.scrollHeight
                      : target.scrollHeight;
                    return JSON.stringify({
                      scrolled: true,
                      direction: ${gson.toJson(direction)},
                      amount: $amount,
                      position: position,
                      scroll_height: height
                    });
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        val result = javascriptResult(raw, "页面已向${if (direction == "up") "上" else "下"}滚动 $amount 像素")
        if (result["success"] == true) waitForDomToSettle(300)
        return result
    }

    private fun executeJavaScript(script: String): Map<String, Any> {
        if (script.isBlank()) return failure("execute_js 缺少 script")
        val raw = evaluate(
            """
                (() => {
                  try {
                    const value = (function() {
                      $script
                    }).call(window);
                    return JSON.stringify({value: value === undefined ? null : value});
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        val parsed = parseJsonMap(raw)
        parsed["error"]?.toString()?.takeIf(String::isNotBlank)?.let { return failure(it) }
        return success(
            "action" to "execute_js",
            "content" to gson.toJson(parsed["value"]),
            "result" to (parsed["value"] ?: "null"),
            "url" to currentUrl
        )
    }

    private fun waitForPage(waitMs: Int): Map<String, Any> {
        val bounded = waitMs.coerceIn(0, 10_000)
        if (bounded > 0) Thread.sleep(bounded.toLong())
        return success(
            "action" to "wait",
            "content" to "已等待 ${bounded}ms",
            "url" to currentUrl,
            "title" to currentTitle
        )
    }

    private fun Map<String, Any>.withVisualState(action: String): Map<String, Any> {
        if (this["success"] != true) return this
        val merged = toMutableMap()
        merged["action"] = action
        runCatching { merged.putAll(pageState(textLimit = 6_000, elementLimit = 40)) }
        runCatching {
            merged.putAll(
                screenshot(fullPage = false, quality = 70, auto = true)
                    .filterKeys { key ->
                        key !in setOf("success", "session_id", "content", "url", "title")
                    }
            )
        }
        return merged
    }

    private fun pageState(textLimit: Int, elementLimit: Int): Map<String, Any> {
        val raw = evaluate(
            """
                (() => {
                  try {
                    const bodyText = (document.body?.innerText || "").trim();
                    const elements = ${if (elementLimit > 0) collectElementsExpression(null, elementLimit) else "[]"};
                    return JSON.stringify({
                      url: location.href,
                      title: document.title || "",
                      page_text: ${if (textLimit > 0) "bodyText.slice(0, $textLimit)" else "\"\""},
                      page_text_truncated: ${if (textLimit > 0) "bodyText.length > $textLimit" else "false"},
                      scroll_x: window.scrollX || 0,
                      scroll_y: window.scrollY || 0,
                      viewport_width: window.innerWidth || 0,
                      viewport_height: window.innerHeight || 0,
                      page_width: document.documentElement?.scrollWidth || 0,
                      page_height: document.documentElement?.scrollHeight || 0,
                      elements: elements
                    });
                  } catch (error) {
                    return JSON.stringify({error: String(error)});
                  }
                })()
            """.trimIndent()
        )
        val parsed = parseJsonMap(raw).toMutableMap()
        parsed.remove("error")
        currentUrl = parsed["url"]?.toString().orEmpty().ifBlank { currentUrl }
        currentTitle = parsed["title"]?.toString().orEmpty().ifBlank { currentTitle }
        return parsed.filterValues { value ->
            when (value) {
                is String -> value.isNotEmpty()
                is Collection<*> -> value.isNotEmpty()
                else -> true
            }
        }
    }

    private fun screenshot(
        fullPage: Boolean,
        quality: Int = 82,
        auto: Boolean = false
    ): Map<String, Any> {
        val imageState = waitForPageImages(IMAGE_LOAD_TIMEOUT_MS)
        val view = requireWebView()
        val originalWidth = runOnMain { view.width.coerceAtLeast(1) }
        val originalHeight = runOnMain { view.height.coerceAtLeast(1) }
        var captureHeight = originalHeight
        var truncated = false

        if (fullPage) {
            val scrollHeightCss = evaluate(
                "Math.max(document.body?.scrollHeight || 0, document.documentElement?.scrollHeight || 0)"
            ).toDoubleOrNull()?.roundToInt() ?: DEFAULT_VIEWPORT_HEIGHT_CSS
            val density = appContext.resources.displayMetrics.density
            val requestedHeight = (scrollHeightCss * density).roundToInt()
                .coerceAtLeast(originalHeight)
            captureHeight = requestedHeight.coerceAtMost(MAX_FULL_PAGE_HEIGHT_PX)
            truncated = requestedHeight > MAX_FULL_PAGE_HEIGHT_PX
            runOnMain {
                view.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        originalWidth,
                        android.view.View.MeasureSpec.EXACTLY
                    ),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        captureHeight,
                        android.view.View.MeasureSpec.EXACTLY
                    )
                )
                view.layout(0, 0, originalWidth, captureHeight)
            }
            Thread.sleep(120)
        }

        val bitmap = try {
            runOnMain {
                val width = view.width.coerceAtLeast(1)
                val height = view.height.coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
                    view.draw(Canvas(image))
                }
            }
        } finally {
            if (fullPage) {
                runOnMain {
                    view.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            originalWidth,
                            android.view.View.MeasureSpec.EXACTLY
                        ),
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            originalHeight,
                            android.view.View.MeasureSpec.EXACTLY
                        )
                    )
                    view.layout(0, 0, originalWidth, originalHeight)
                }
            }
        }

        val filename = "${if (auto) "snapshot" else "screenshot"}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
        val output = File(browserDir, filename)
        output.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 95), stream)
        }
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        bitmap.recycle()

        val relativePath = workspace.toPath().relativize(output.toPath()).toString()
            .replace(File.separatorChar, '/')
        LocalBrowserPreviewRegistry.update(
            sessionId = sessionId,
            url = currentUrl,
            title = currentTitle,
            isLoading = false,
            advanceRevision = true
        )
        return success(
            "content" to if (auto) "已自动记录当前页面截图" else "已截取当前页面",
            "url" to currentUrl,
            "title" to currentTitle,
            "screenshot_path" to relativePath,
            "screenshot_absolute_path" to output.absolutePath,
            "image_url" to relativePath,
            "image_width" to imageWidth,
            "image_height" to imageHeight,
            "page_image_count" to imageState.total,
            "loaded_page_image_count" to imageState.loaded,
            "failed_page_image_count" to imageState.failed,
            "pending_page_image_count" to imageState.pending,
            "full_page" to fullPage,
            "truncated" to truncated
        )
    }

    internal fun capturePreviewBitmap(maxWidth: Int, maxHeight: Int): Bitmap? {
        val view = webView ?: return null
        return runCatching {
            runOnMain {
                val sourceWidth = view.width.coerceAtLeast(1)
                val sourceHeight = view.height.coerceAtLeast(1)
                val scale = minOf(
                    maxWidth.coerceAtLeast(1).toFloat() / sourceWidth,
                    maxHeight.coerceAtLeast(1).toFloat() / sourceHeight,
                    1f
                )
                val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(
                    targetWidth,
                    targetHeight,
                    Bitmap.Config.RGB_565
                ).also { preview ->
                    Canvas(preview).apply {
                        scale(
                            targetWidth.toFloat() / sourceWidth,
                            targetHeight.toFloat() / sourceHeight
                        )
                        view.draw(this)
                    }
                }
            }
        }.getOrNull()
    }

    private fun textResult(raw: String): Map<String, Any> {
        val parsed = parseJsonMap(raw)
        parsed["error"]?.toString()?.takeIf(String::isNotBlank)?.let { return failure(it) }
        val text = parsed["text"]?.toString().orEmpty()
        return success(
            "content" to text,
            "url" to (parsed["url"]?.toString() ?: currentUrl),
            "title" to (parsed["title"]?.toString() ?: currentTitle),
            "total_chars" to ((parsed["total_chars"] as? Number)?.toInt() ?: text.length),
            "truncated" to (parsed["truncated"] as? Boolean ?: false)
        )
    }

    private fun elementResult(raw: String, label: String): Map<String, Any> {
        val parsed = parseJsonMap(raw)
        parsed["error"]?.toString()?.takeIf(String::isNotBlank)?.let { return failure(it) }
        val elements = parsed["elements"] as? List<*> ?: emptyList<Any>()
        return success(
            "content" to "$label：${elements.size} 个",
            "url" to currentUrl,
            "title" to currentTitle,
            "elements" to elements,
            "truncated" to (parsed["truncated"] as? Boolean ?: false)
        )
    }

    private fun javascriptResult(raw: String, defaultMessage: String): Map<String, Any> {
        val parsed = parseJsonMap(raw)
        parsed["error"]?.toString()?.takeIf(String::isNotBlank)?.let { return failure(it) }
        return success(
            "content" to defaultMessage,
            "url" to currentUrl,
            "result" to parsed
        )
    }

    private fun elementCollectionScript(selector: String?, limit: Int): String =
        """
            (() => {
              try {
                const elements = ${collectElementsExpression(selector, limit)};
                const total = ${if (selector == null) "document.querySelectorAll(\"a[href],button,input,textarea,select,[role='button'],[role='link'],[contenteditable='true'],summary\").length" else "document.querySelectorAll(${gson.toJson(selector)}).length"};
                return JSON.stringify({elements: elements, truncated: total > $limit});
              } catch (error) {
                return JSON.stringify({error: String(error)});
              }
            })()
        """.trimIndent()

    private fun collectElementsExpression(selector: String?, limit: Int): String {
        val query = selector?.let { gson.toJson(it) }
            ?: "\"a[href],button,input,textarea,select,[role='button'],[role='link'],[contenteditable='true'],summary\""
        return """
            (() => {
              const makeSelector = (el) => {
                if (el.id) return "#" + CSS.escape(el.id);
                const name = el.getAttribute("name");
                if (name) return el.tagName.toLowerCase() + "[name=" + JSON.stringify(name) + "]";
                const parts = [];
                let node = el;
                while (node && node.nodeType === 1 && node !== document.body && parts.length < 4) {
                  let part = node.tagName.toLowerCase();
                  const classes = Array.from(node.classList || []).filter(Boolean).slice(0, 2);
                  if (classes.length) part += "." + classes.map(c => CSS.escape(c)).join(".");
                  const siblings = node.parentElement
                    ? Array.from(node.parentElement.children).filter(child => child.tagName === node.tagName)
                    : [];
                  if (siblings.length > 1) part += ":nth-of-type(" + (siblings.indexOf(node) + 1) + ")";
                  parts.unshift(part);
                  node = node.parentElement;
                }
                return parts.join(" > ");
              };
              return Array.from(document.querySelectorAll($query))
                .filter(el => {
                  const rect = el.getBoundingClientRect();
                  const style = getComputedStyle(el);
                  return rect.width > 0 && rect.height > 0 &&
                    style.visibility !== "hidden" && style.display !== "none";
                })
                .slice(0, $limit)
                .map((el, index) => {
                  const rect = el.getBoundingClientRect();
                  return {
                    index: index,
                    tag: el.tagName.toLowerCase(),
                    type: el.getAttribute("type") || "",
                    text: (el.innerText || "").trim().slice(0, 160),
                    aria_label: el.getAttribute("aria-label") || "",
                    placeholder: el.getAttribute("placeholder") || "",
                    href: el.href || "",
                    selector: makeSelector(el),
                    disabled: !!el.disabled,
                    rect: {
                      x: Math.round(rect.x),
                      y: Math.round(rect.y),
                      width: Math.round(rect.width),
                      height: Math.round(rect.height)
                    }
                  };
                });
            })()
        """.trimIndent()
    }

    private fun waitForDomToSettle(delayMs: Long) {
        if (delayMs > 0) Thread.sleep(delayMs)
        runCatching {
            var previous = ""
            repeat(4) {
                val current = evaluate(
                    "JSON.stringify({" +
                        "ready:document.readyState," +
                        "text:document.body?.innerText?.length||0," +
                        "height:document.documentElement?.scrollHeight||0" +
                        "})"
                )
                if (current == previous) return
                previous = current
                Thread.sleep(200)
            }
        }
    }

    /**
     * 将常见的懒加载图片提升为 eager，并等待当前文档中的图片完成。
     *
     * onPageFinished 只代表主文档完成，图片资源仍可能在后台加载；如果立即 draw(Canvas)，
     * 截图和实时预览会永久记录到占位图。这里在每次截图前等待，并返回资源统计供 Agent 判断。
     */
    private fun waitForPageImages(timeoutMs: Long): PageImageState {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var latest = PageImageState()
        do {
            val raw = runCatching {
                evaluate(
                    """
                        (() => {
                          const images = Array.from(document.images || []);
                          const lazyAttributes = ["data-src", "data-original", "data-lazy-src", "data-url"];
                          const lazySrcsetAttributes = ["data-srcset", "data-lazy-srcset"];
                          images.forEach(img => {
                            img.loading = "eager";
                            try { img.fetchPriority = "high"; } catch (_) {}
                            const source = img.currentSrc || img.getAttribute("src") || "";
                            const isPlaceholder =
                              !source ||
                              source === "about:blank" ||
                              (source.startsWith("data:image/") && source.length < 512);
                            if (isPlaceholder) {
                              const lazySource = lazyAttributes
                                .map(name => img.getAttribute(name))
                                .find(Boolean);
                              if (lazySource) img.src = lazySource;
                            }
                            if (!img.getAttribute("srcset")) {
                              const lazySrcset = lazySrcsetAttributes
                                .map(name => img.getAttribute(name))
                                .find(Boolean);
                              if (lazySrcset) img.srcset = lazySrcset;
                            }
                          });
                          return JSON.stringify({
                            total: images.length,
                            loaded: images.filter(img => img.complete && img.naturalWidth > 0).length,
                            failed: images.filter(img => img.complete && img.naturalWidth === 0).length,
                            pending: images.filter(img => !img.complete).length
                          });
                        })()
                    """.trimIndent()
                )
            }.getOrNull() ?: break
            val parsed = parseJsonMap(raw)
            latest = PageImageState(
                total = (parsed["total"] as? Number)?.toInt() ?: 0,
                loaded = (parsed["loaded"] as? Number)?.toInt() ?: 0,
                failed = (parsed["failed"] as? Number)?.toInt() ?: 0,
                pending = (parsed["pending"] as? Number)?.toInt() ?: 0
            )
            if (latest.pending == 0) break
            Thread.sleep(200)
        } while (System.nanoTime() < deadline)

        runCatching {
            runOnMain {
                requireWebView().invalidate()
            }
        }
        return latest
    }

    private fun applyDefaultViewport(view: WebView) {
        val density = appContext.resources.displayMetrics.density
        val width = (DEFAULT_VIEWPORT_WIDTH_CSS * density).roundToInt()
            .coerceIn(720, 1440)
        val height = (DEFAULT_VIEWPORT_HEIGHT_CSS * density).roundToInt()
            .coerceIn(1200, 2400)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(
                width,
                android.view.View.MeasureSpec.EXACTLY
            ),
            android.view.View.MeasureSpec.makeMeasureSpec(
                height,
                android.view.View.MeasureSpec.EXACTLY
            )
        )
        view.layout(0, 0, width, height)
    }

    private fun evaluate(script: String): String {
        val result = AtomicReference("null")
        val completed = CountDownLatch(1)
        runOnMain {
            requireWebView().evaluateJavascript(script) { raw ->
                result.set(decodeJavaScriptResult(raw))
                completed.countDown()
            }
        }
        if (!completed.await(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("JavaScript 执行超时")
        }
        return result.get()
    }

    private fun decodeJavaScriptResult(raw: String?): String {
        if (raw == null || raw == "null") return "null"
        val decoded = runCatching {
            gson.fromJson(raw, Any::class.java)
        }.getOrNull()
        return when (decoded) {
            is String -> decoded
            null -> raw
            else -> gson.toJson(decoded)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonMap(raw: String): Map<String, Any> =
        runCatching { gson.fromJson(raw, Map::class.java) as? Map<String, Any> }
            .getOrNull()
            ?: mapOf("value" to raw)

    private fun normalizeWebUrl(raw: String): String? {
        val normalized = raw.trim().let { value ->
            if ("://" in value) value else "https://$value"
        }
        return normalized.takeIf(::isAllowedWebUrl)
    }

    private fun isAllowedWebUrl(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    private fun requireWebView(): WebView =
        webView ?: throw IllegalStateException("浏览器尚未初始化")

    private fun <T> runOnMain(
        timeoutMs: Long = MAIN_THREAD_TIMEOUT_MS,
        block: () -> T
    ): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        if (!mainHandler.post(task)) throw IllegalStateException("无法调度浏览器主线程任务")
        return task.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun success(vararg values: Pair<String, Any>): Map<String, Any> =
        buildMap {
            put("success", true)
            put("session_id", sessionId)
            values.forEach { (key, value) -> put(key, value) }
        }

    private fun failure(
        message: String,
        vararg values: Pair<String, Any>
    ): Map<String, Any> = buildMap {
        put("success", false)
        put("session_id", sessionId)
        put("error", message)
        values.forEach { (key, value) -> put(key, value) }
    }

    private fun Map<String, Any>.string(key: String): String =
        this[key]?.toString().orEmpty()

    private fun Map<String, Any>.int(key: String, default: Int): Int =
        (this[key] as? Number)?.toInt()
            ?: this[key]?.toString()?.toIntOrNull()
            ?: default

    private fun Map<String, Any>.optionalInt(key: String): Int? =
        (this[key] as? Number)?.toInt()
            ?: this[key]?.toString()?.toIntOrNull()

    private fun Map<String, Any>.boolean(key: String): Boolean =
        this[key] as? Boolean
            ?: this[key]?.toString()?.equals("true", ignoreCase = true)
            ?: false

    private data class PageImageState(
        val total: Int = 0,
        val loaded: Int = 0,
        val failed: Int = 0,
        val pending: Int = 0
    )
}
