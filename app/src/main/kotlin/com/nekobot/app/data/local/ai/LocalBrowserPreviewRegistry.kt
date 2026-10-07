package com.nekobot.app.data.local.ai

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器运行时与 Compose 预览层之间的只读桥梁。
 *
 * 注册表只暴露轻量元数据和缩小后的 Bitmap 快照，不把 WebView 交给 UI，
 * 从而保证 Agent 执行、聊天缩略图与全屏预览始终观察同一个页面实例。
 */
internal data class LocalBrowserPreviewState(
    val sessionId: String,
    val url: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val revision: Long = 0L
)

/** Agent 浏览器标签页的只读快照，供内置浏览器镜像展示与跟随。 */
internal data class LocalBrowserTabInfo(
    val tabId: Int,
    val url: String,
    val title: String,
    val selected: Boolean
)

internal object LocalBrowserPreviewRegistry {
    private val browsers = ConcurrentHashMap<String, LocalBrowserTool>()
    private val _states = MutableStateFlow<Map<String, LocalBrowserPreviewState>>(emptyMap())
    val states: StateFlow<Map<String, LocalBrowserPreviewState>> = _states.asStateFlow()

    private val _tabs = MutableStateFlow<Map<String, List<LocalBrowserTabInfo>>>(emptyMap())
    val tabs: StateFlow<Map<String, List<LocalBrowserTabInfo>>> = _tabs.asStateFlow()

    fun register(sessionId: String, browser: LocalBrowserTool) {
        browsers[sessionId] = browser
        _states.update { current ->
            current + (
                sessionId to (
                    current[sessionId]
                        ?: LocalBrowserPreviewState(sessionId = sessionId)
                    )
                )
        }
        // 新实例意味着全新的标签页集合，旧的镜像结果不能留
        _tabs.update { current -> current + (sessionId to emptyList()) }
    }

    /** 更新 Agent 浏览器的标签页快照（由 [LocalBrowserTool] 在标签页变化时推送）。 */
    fun updateTabs(sessionId: String, tabs: List<LocalBrowserTabInfo>) {
        _tabs.update { current -> current + (sessionId to tabs) }
    }

    fun update(
        sessionId: String,
        url: String? = null,
        title: String? = null,
        isLoading: Boolean? = null,
        advanceRevision: Boolean = false
    ) {
        _states.update { current ->
            val previous = current[sessionId] ?: LocalBrowserPreviewState(sessionId)
            current + (
                sessionId to previous.copy(
                    url = url ?: previous.url,
                    title = title ?: previous.title,
                    isLoading = isLoading ?: previous.isLoading,
                    revision = if (advanceRevision) previous.revision + 1 else previous.revision
                )
                )
        }
    }

    fun unregister(sessionId: String, browser: LocalBrowserTool) {
        browsers.remove(sessionId, browser)
        if (!browsers.containsKey(sessionId)) {
            _states.update { current -> current - sessionId }
            _tabs.update { current -> current - sessionId }
        }
    }

    suspend fun capturePreview(
        sessionId: String,
        maxWidth: Int = 640,
        maxHeight: Int = 960
    ): Bitmap? = withContext(Dispatchers.IO) {
        browsers[sessionId]?.capturePreviewBitmap(maxWidth, maxHeight)
    }
}
