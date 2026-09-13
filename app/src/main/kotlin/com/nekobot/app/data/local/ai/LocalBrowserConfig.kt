package com.nekobot.app.data.local.ai

import com.nekobot.app.ServiceContainer

/**
 * browser_use 工具的 User-Agent 预设。
 *
 * - [MOBILE]：Android 移动端 Chrome 身份，适合绝大多数移动版页面与省流站点；
 * - [DESKTOP]：桌面 Linux Chrome 身份，适合只在桌面版布局下提供完整内容的站点；
 * - [CUSTOM]：完全自定义，模型仍可在会话内用 `set_user_agent` 临时覆盖。
 */
enum class BrowserUserAgentMode {
    MOBILE,
    DESKTOP,
    CUSTOM;

    companion object {
        fun fromStorage(value: String?): BrowserUserAgentMode =
            entries.firstOrNull { it.name == value } ?: MOBILE
    }
}

/**
 * browser_use 工具的用户可调配置（「设置 → Agent 设置 → 浏览器设置」）。
 *
 * 这些值决定**新建标签页**的初始身份与视口；模型在会话内通过 `set_viewport` /
 * `set_user_agent` 的临时调整不受影响，也不会被本配置覆盖。
 */
data class LocalBrowserConfig(
    val userAgentMode: BrowserUserAgentMode = BrowserUserAgentMode.MOBILE,
    val customUserAgent: String = "",
    val viewportWidthCss: Int = DEFAULT_VIEWPORT_WIDTH_CSS,
    val viewportHeightCss: Int = DEFAULT_VIEWPORT_HEIGHT_CSS,
    val javascriptEnabled: Boolean = true,
    val imagesEnabled: Boolean = true,
    val maxTabs: Int = DEFAULT_MAX_TABS,
) {
    /** 当前生效的 User-Agent：自定义留空时回落移动端身份，避免发出空 UA。 */
    val userAgent: String
        get() = when (userAgentMode) {
            BrowserUserAgentMode.MOBILE -> MOBILE_USER_AGENT
            BrowserUserAgentMode.DESKTOP -> DESKTOP_USER_AGENT
            BrowserUserAgentMode.CUSTOM -> customUserAgent.trim().ifBlank { MOBILE_USER_AGENT }
        }

    companion object {
        /** 移动端身份：与主流 Android Chrome 保持一致，避免站点降级到极简页面。 */
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

        /** 桌面端身份：用于只在桌面布局下提供完整内容的站点。 */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        const val DEFAULT_VIEWPORT_WIDTH_CSS = 412
        const val MIN_VIEWPORT_WIDTH_CSS = 280
        const val MAX_VIEWPORT_WIDTH_CSS = 2_560

        const val DEFAULT_VIEWPORT_HEIGHT_CSS = 800
        const val MIN_VIEWPORT_HEIGHT_CSS = 320
        const val MAX_VIEWPORT_HEIGHT_CSS = 4_096

        const val DEFAULT_MAX_TABS = 5
        const val MIN_MAX_TABS = 1
        const val MAX_MAX_TABS = 10

        /** 自定义 User-Agent 的最大长度，防止写入超长字符串。 */
        const val MAX_CUSTOM_USER_AGENT_CHARS = 1_000

        /**
         * 读取当前配置。
         *
         * JVM 单元测试等未初始化 [ServiceContainer] 的场景回落默认值，
         * 语义与「用户从未修改过设置」一致。
         */
        fun current(): LocalBrowserConfig = runCatching {
            val prefs = ServiceContainer.prefs
            LocalBrowserConfig(
                userAgentMode = BrowserUserAgentMode.fromStorage(prefs.browserUserAgentMode),
                customUserAgent = prefs.browserCustomUserAgent,
                viewportWidthCss = prefs.browserViewportWidth,
                viewportHeightCss = prefs.browserViewportHeight,
                javascriptEnabled = prefs.browserJavascriptEnabled,
                imagesEnabled = prefs.browserImagesEnabled,
                maxTabs = prefs.browserMaxTabs,
            )
        }.getOrDefault(LocalBrowserConfig())
    }
}
