package com.nekobot.app.ui.screens.chat

import androidx.lifecycle.viewModelScope
import com.nekobot.app.data.local.ai.LocalBrowserPreviewRegistry
import com.nekobot.app.data.local.ai.LocalBrowserTabInfo
import com.nekobot.app.ui.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 内置浏览器里的一个标签页。
 *
 * 只保存渲染所需的轻量状态；WebView 实例由界面层按 [id] 持有，避免 ViewModel 里出现 View。
 */
internal data class BrowserTabState(
    val id: String,
    /** 当前地址（也是让 WebView 跟随加载的依据）。 */
    val url: String = "",
    val title: String = "",
    /** 绑定的 Agent 标签页 id；用于识别镜像关系。 */
    val agentTabId: Int? = null,
    /** 是否继续跟随 Agent 的地址变化（用户手动输入过地址后解绑）。 */
    val followAgent: Boolean = false,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
) {
    /** 标签卡片上展示的域名；本地文件没有域名，显示文件名。 */
    val domain: String
        get() {
            if (url.isBlank()) return ""
            if (url.startsWith("file://", ignoreCase = true)) {
                return url.substringAfterLast('/').ifBlank { url }
            }
            return if ("://" in url) {
                url.substringAfter("://")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
            } else {
                url
            }
        }
}

/**
 * 内置浏览器的标签页状态中心。
 *
 * 标签列表与当前选中项都在这里维护，界面只做渲染与手势；同时把 Agent 的 browser_use
 * 标签页镜像进来，并在打开界面时自动切到 AI 当前所在的那一个。
 */
internal class BuiltinBrowserViewModel : BaseViewModel() {

    private val _tabs = MutableStateFlow<List<BrowserTabState>>(emptyList())
    val tabs: StateFlow<List<BrowserTabState>> = _tabs.asStateFlow()

    /** 当前选中标签页下标；标签列表变化时自动收敛到合法范围。 */
    private val _selectedIndex = MutableStateFlow(0)
    val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

    private var serial = 0
    private var boundSessionId: String? = null
    private var syncJob: Job? = null
    private var firstSync = true

    /** 用户主动关掉的 Agent 标签页：不再被镜像逻辑重新建出来。 */
    private val dismissedAgentTabs = mutableSetOf<Int>()

    /** 当前选中标签页的 id；下标只是对外投影。 */
    private var selectedId: String? = null

    /**
     * 绑定会话：首次（或换会话）时清空并镜像 Agent 标签页，之后持续跟随。
     *
     * 打开界面时若一个标签页都没有，自动补一个空白标签页。
     */
    fun bind(sessionId: String) {
        if (boundSessionId != sessionId) {
            boundSessionId = sessionId
            firstSync = true
            dismissedAgentTabs.clear()
            _tabs.value = emptyList()
            selectedId = null
            _selectedIndex.value = 0
            syncJob?.cancel()
            syncJob = viewModelScope.launch {
                LocalBrowserPreviewRegistry.tabs.collect { all ->
                    onAgentTabs(all[sessionId].orEmpty())
                }
            }
        }
        if (_tabs.value.isEmpty()) newTab()
    }

    fun newTab(
        url: String = "",
        title: String = "",
        agentTabId: Int? = null,
    ): String {
        val id = "tab-${serial++}"
        _tabs.value = _tabs.value + BrowserTabState(
            id = id,
            url = url,
            title = title,
            agentTabId = agentTabId,
            followAgent = agentTabId != null,
        )
        selectedId = id
        refreshSelection()
        return id
    }

    fun select(index: Int) {
        _tabs.value.getOrNull(index)?.let { tab ->
            selectedId = tab.id
            refreshSelection()
        }
    }

    fun closeTab(id: String) {
        val list = _tabs.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index].agentTabId?.let { dismissedAgentTabs += it }
        _tabs.value = list.filterNot { it.id == id }
        if (selectedId == id) {
            selectedId = _tabs.value.getOrNull(index.coerceAtMost(_tabs.value.lastIndex))?.id
        }
        refreshSelection()
    }

    /** 关闭全部标签页：界面据此退出浏览器。 */
    fun closeAll() {
        _tabs.value.forEach { tab -> tab.agentTabId?.let { dismissedAgentTabs += it } }
        _tabs.value = emptyList()
        selectedId = null
        _selectedIndex.value = 0
    }

    fun updateTab(id: String, block: (BrowserTabState) -> BrowserTabState) {
        _tabs.value = _tabs.value.map { tab -> if (tab.id == id) block(tab) else tab }
    }

    /** 用户手动输入地址：解绑 Agent 跟随，之后 AI 换页不再覆盖这一页。 */
    fun markUserNavigated(id: String, url: String) {
        updateTab(id) { it.copy(url = url, followAgent = false) }
    }

    private fun refreshSelection() {
        val list = _tabs.value
        val index = list.indexOfFirst { it.id == selectedId }
        _selectedIndex.value = when {
            index >= 0 -> index
            list.isEmpty() -> 0
            else -> list.lastIndex
        }
    }

    /** 首次同步：镜像 Agent 已打开的标签页，并选中 AI 当前所在的那个。 */
    private fun onAgentTabs(infos: List<LocalBrowserTabInfo>) {
        if (firstSync) {
            firstSync = false
            if (infos.isEmpty()) {
                if (_tabs.value.isEmpty()) newTab()
                return
            }
            infos.forEach { addMirrored(it) }
            val focus = infos.firstOrNull { it.selected } ?: infos.last()
            _tabs.value.firstOrNull { it.agentTabId == focus.tabId }?.let { selectedId = it.id }
            refreshSelection()
            return
        }

        // 之后持续镜像：AI 新开标签页就跟着建，AI 换页时跟随它的地址与标题
        infos.forEach { info ->
            val existing = _tabs.value.firstOrNull { it.agentTabId == info.tabId }
            when {
                existing == null -> {
                    if (info.tabId !in dismissedAgentTabs) addMirrored(info)
                }
                existing.followAgent && info.url.isNotBlank() && info.url != existing.url -> {
                    updateTab(existing.id) {
                        it.copy(url = info.url, title = info.title.ifBlank { it.title })
                    }
                }
                info.title.isNotBlank() && info.title != existing.title -> {
                    updateTab(existing.id) { it.copy(title = info.title) }
                }
            }
        }
    }

    private fun addMirrored(info: LocalBrowserTabInfo) {
        val id = "tab-${serial++}"
        _tabs.value = _tabs.value + BrowserTabState(
            id = id,
            url = info.url,
            title = info.title,
            agentTabId = info.tabId,
            followAgent = true,
        )
    }

    override fun onCleared() {
        syncJob?.cancel()
        super.onCleared()
    }
}
