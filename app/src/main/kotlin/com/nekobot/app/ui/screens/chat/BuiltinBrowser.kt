package com.nekobot.app.ui.screens.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalWorkspaceStorage
import com.nekobot.app.data.local.ai.LocalBrowserConfig
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 内置浏览器可加载的目标。 */
internal sealed interface BrowserTarget {
    /** 远程页面：https，或本机回环的明文 http。 */
    data class Remote(val url: String) : BrowserTarget

    /** 本地 HTML 文件（工作区或磁盘上的 .html/.htm）。 */
    data class LocalHtml(val file: File) : BrowserTarget
}

/**
 * 内置浏览器的地址解析。
 *
 * 与 browser_use 的导航白名单保持一致：https、本机回环 http、本地 .html 文件。
 * 无协议的输入先按工作区相对路径找 HTML，找不到再当作域名补 https。
 */
internal object BuiltinBrowserTargets {
    /** 工作区 HTML 入口的数量上限。 */
    const val MAX_WORKSPACE_PAGES = 20

    /** 扫描工作区 HTML 的最大目录深度，避免大工作区全量遍历。 */
    private const val MAX_SCAN_DEPTH = 4

    private val HTML_EXTENSIONS = setOf("html", "htm")

    /** 合法主机名形态（首尾为字母数字，不含路径分隔符），用于排除误输入的相对路径。 */
    private val HOST_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")

    /** 文件名或路径是否为 HTML（忽略 query / fragment）。 */
    fun isHtmlName(name: String): Boolean {
        val path = name.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS
    }

    /** 地址栏输入 → 可加载目标；无法识别时返回 null。 */
    fun resolve(raw: String, workspaceRoot: File?): BrowserTarget? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        if (value.startsWith("file://", ignoreCase = true)) {
            val file = File(value.substring("file://".length))
            return file.takeIf { isHtmlName(it.name) && it.isFile }?.let(BrowserTarget::LocalHtml)
        }
        if ("://" in value) {
            return value.takeIf(::isAllowedNavigationUrl)?.let(BrowserTarget::Remote)
        }

        workspaceHtmlFile(value, workspaceRoot)?.let { return BrowserTarget.LocalHtml(it) }
        if (File(value).isAbsolute) {
            val file = File(value)
            return file.takeIf { isHtmlName(it.name) && it.isFile }?.let(BrowserTarget::LocalHtml)
        }

        // 剩下的一律当域名：主机段必须像主机（含点或就是 localhost），否则视为无法识别
        if (!looksLikeHost(value.substringBefore('/'))) return null
        val scheme = if (isLoopbackHost(value)) "http" else "https"
        return BrowserTarget.Remote("$scheme://$value")
    }

    /**
     * 导航白名单：https、本机回环 http、本地 .html/.htm 文件。
     *
     * 页面内跳转与地址栏共用同一份判断，避免内置浏览器被页面当作跳板
     * 转到明文 http、自定义协议或非 HTML 的本地文件。
     */
    fun isAllowedNavigationUrl(url: String): Boolean {
        val scheme = url.substringBefore("://").lowercase()
        return when (scheme) {
            "https" -> true
            "http" -> isLoopbackHost(url.substringAfter("://"))
            "file" -> isHtmlName(url.substringAfterLast('/'))
            else -> false
        }
    }

    /** 工作区内可直接打开的 HTML 文件（按路径排序，最多 [limit] 个）。 */
    fun workspaceHtmlFiles(
        workspaceRoot: File?,
        limit: Int = MAX_WORKSPACE_PAGES
    ): List<File> {
        val root = workspaceRoot?.takeIf { it.isDirectory } ?: return emptyList()
        return root.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile && isHtmlName(it.name) }
            .take(limit)
            .sortedBy { it.path }
            .toList()
    }

    /** 相对/绝对路径解析到工作区内的 HTML 文件；越界或不存在返回 null。 */
    private fun workspaceHtmlFile(value: String, workspaceRoot: File?): File? {
        if (workspaceRoot == null || !isHtmlName(value)) return null
        val root = runCatching { workspaceRoot.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, value).canonicalFile }.getOrNull() ?: return null
        val inside = file.path == root.path || file.path.startsWith(root.path + File.separator)
        return file.takeIf { inside && it.isFile }
    }

    /** 无协议输入是否像主机名：只含主机名合法字符、带点（域名/IP）或就是 localhost。 */
    private fun looksLikeHost(value: String): Boolean {
        val host = value.substringBefore(':')
        if (!HOST_REGEX.matches(host)) return false
        return '.' in host || host.equals("localhost", ignoreCase = true)
    }

    /** 主机名（或含端口/路径的地址）是否为回环地址。 */
    private fun isLoopbackHost(value: String): Boolean {
        val authority = value
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return host.equals("localhost", ignoreCase = true) ||
            host == "::1" ||
            host.startsWith("127.")
    }
}

// ── 布局常量 ────────────────────────────────────────────────────────────

/** 顶栏固定高度：永远只有这一行。 */
private val TopBarHeight = 56.dp

/** 抽屉收起态露出的把手条高度（不含系统导航栏）。 */
private val DrawerHandleHeight = 52.dp

/** 把手条与系统导航栏之间留出的间距：避开底部手势区，避免上滑被系统接管。 */
private val DrawerBottomGap = 12.dp

/**
 * 抽屉展开态最多占屏幕高度的比例。
 *
 * 上限只在内容超长时才生效（卡片本身 wrapContent）：取 0.75 是为了让 5~6 行这种
 * 常见数量能一次看全，不被卡片底边切掉最后一行。
 */
private const val DrawerExpandedFraction = 0.75f

/** 松手吸附的速度阈值（px/s）：超过则按方向直接吸附。 */
private const val DrawerSnapVelocity = 600f

/** 位移小于展开行程的这个比例时，松手回到拖动前的锚点（避免误触改状态）。 */
private const val DrawerMinDragFraction = 0.2f

// ── 粉色主题（跟随系统深浅色） ───────────────────────────────────────────

private val BrowserPinkLight = lightColorScheme(
    primary = Color(0xFFBC004B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E1),
    onPrimaryContainer = Color(0xFF3F0015),
    secondary = Color(0xFF74565C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E0),
    onSecondaryContainer = Color(0xFF2B151A),
    tertiary = Color(0xFF7C5635),
    onTertiary = Color.White,
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF201A1B),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF201A1B),
    surfaceVariant = Color(0xFFF3DDE0),
    onSurfaceVariant = Color(0xFF524346),
    outline = Color(0xFF847377),
    outlineVariant = Color(0xFFD6C2C5),
)

private val BrowserPinkDark = darkColorScheme(
    primary = Color(0xFFFFB0C8),
    onPrimary = Color(0xFF66002B),
    primaryContainer = Color(0xFF900043),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFE4BDC3),
    onSecondary = Color(0xFF43292F),
    secondaryContainer = Color(0xFF5B4045),
    onSecondaryContainer = Color(0xFFFFD9E0),
    tertiary = Color(0xFFEEBB93),
    onTertiary = Color(0xFF47290C),
    background = Color(0xFF1A1113),
    onBackground = Color(0xFFECE0E1),
    surface = Color(0xFF1A1113),
    onSurface = Color(0xFFECE0E1),
    surfaceVariant = Color(0xFF524346),
    onSurfaceVariant = Color(0xFFD6C2C5),
    outline = Color(0xFF9F8C90),
    outlineVariant = Color(0xFF524346),
)

/**
 * 内置浏览器：独立窗口的全屏浏览器。
 *
 * 走 Dialog 而不是主窗口覆盖层，是因为首页的液态玻璃采样会把 NavHost 内容录进离屏图层，
 * 而 WebView 属于 AndroidView 互操作视图、不在该图层内，绘制顺序会错乱（页面白底会盖住顶栏）。
 */
@Composable
internal fun BuiltinBrowserDialog(
    sessionId: String,
    viewModel: BuiltinBrowserViewModel,
    onDismiss: () -> Unit,
    onOpenBrowserSettings: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) BrowserPinkDark else BrowserPinkLight,
        ) {
            BuiltinBrowserScreen(
                sessionId = sessionId,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onOpenBrowserSettings = onOpenBrowserSettings,
            )
        }
    }
}

@Composable
private fun BuiltinBrowserScreen(
    sessionId: String,
    viewModel: BuiltinBrowserViewModel,
    onDismiss: () -> Unit,
    onOpenBrowserSettings: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val config = remember { LocalBrowserConfig.current() }

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val selectedIndex by viewModel.selectedIndex.collectAsStateWithLifecycle()
    val selectedTab = tabs.getOrNull(selectedIndex)

    val workspaceRoot = remember(sessionId) {
        if (ServiceContainer.prefs.isLocalMode) {
            LocalWorkspaceStorage.resolve(context.filesDir, sessionId)
        } else {
            null
        }
    }
    val workspacePages = remember(workspaceRoot) {
        BuiltinBrowserTargets.workspaceHtmlFiles(workspaceRoot)
    }

    // 每个标签页一个 WebView；只挂载当前标签页的实例，其余留在表里保活
    val webViews = remember(sessionId) { mutableMapOf<String, WebView>() }
    // 已经为某个标签页发起过的地址：避免加载失败后无限重试
    val attemptedUrls = remember(sessionId) { mutableMapOf<String, String>() }
    var showTabManager by remember { mutableStateOf(false) }

    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 收起态可见高度 = 把手条 + 与导航栏的间距 + 导航栏本身（卡片背景铺到屏幕底）
    val drawerPeek = DrawerHandleHeight + DrawerBottomGap + navBarInset

    val invalidUrlText = stringResource(R.string.chat_browser_invalid_url)
    val closeDesc = stringResource(R.string.chat_browser_close)
    val backDesc = stringResource(R.string.chat_browser_back)
    val reloadDesc = stringResource(R.string.chat_browser_reload)
    val openDesc = stringResource(R.string.chat_browser_open)
    val newTabText = stringResource(R.string.chat_browser_new_tab)
    val closeTabText = stringResource(R.string.chat_browser_close_tab)
    val moreDesc = stringResource(R.string.chat_more)
    val copiedText = stringResource(R.string.chat_browser_copied)

    LaunchedEffect(sessionId) { viewModel.bind(sessionId) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** 地址栏与工作区入口的统一入口：解析失败只提示，不改变当前页面。 */
    fun openInTab(tabId: String, raw: String) {
        val resolved = when (val target = BuiltinBrowserTargets.resolve(raw, workspaceRoot)) {
            null -> {
                toast(invalidUrlText)
                return
            }
            is BrowserTarget.Remote -> target.url
            is BrowserTarget.LocalHtml -> Uri.fromFile(target.file).toString()
        }
        // 手动导航解绑 Agent 跟随；同时允许重复提交同一地址
        attemptedUrls.remove(tabId)
        viewModel.markUserNavigated(tabId, resolved)
    }

    // 标签页被关闭后销毁对应 WebView
    LaunchedEffect(tabs) {
        val alive = tabs.mapTo(HashSet()) { it.id }
        webViews.keys.filterNot { it in alive }.forEach { id ->
            destroyBrowserView(webViews.remove(id))
            attemptedUrls.remove(id)
        }
    }

    // 状态 → WebView：地址变化才加载（覆盖首次创建、手动导航与 Agent 跟随）
    LaunchedEffect(tabs, selectedIndex) {
        tabs.forEach { tab ->
            if (tab.url.isBlank()) return@forEach
            val view = webViews[tab.id] ?: return@forEach
            if (attemptedUrls[tab.id] == tab.url) return@forEach
            attemptedUrls[tab.id] = tab.url
            if (view.url != tab.url) view.loadUrl(tab.url)
        }
    }

    DisposableEffect(sessionId) {
        onDispose {
            webViews.values.forEach(::destroyBrowserView)
            webViews.clear()
        }
    }

    BackHandler {
        val view = selectedTab?.let { webViews[it.id] }
        if (view != null && view.canGoBack()) view.goBack() else onDismiss()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 顶栏避开状态栏；底部留给抽屉卡片自己贴到屏幕底部
                    .statusBarsPadding()
            ) {
                BrowserTopBar(
                    address = selectedTab?.url.orEmpty(),
                    loading = selectedTab?.loading == true,
                    tabCount = tabs.size,
                    canGoBack = selectedTab?.canGoBack == true,
                    onSubmit = { raw -> selectedTab?.let { openInTab(it.id, raw) } },
                    onOpenTabs = { showTabManager = true },
                    onNewTab = { viewModel.newTab() },
                    onReload = { selectedTab?.let { webViews[it.id]?.reload() } },
                    onBack = { selectedTab?.let { webViews[it.id]?.goBack() } },
                    onCopyUrl = {
                        val url = selectedTab?.url.orEmpty()
                        if (url.isNotBlank()) {
                            clipboard.setText(AnnotatedString(url))
                            toast(copiedText)
                        }
                    },
                    onOpenSettings = {
                        onDismiss()
                        onOpenBrowserSettings()
                    },
                    onClose = onDismiss,
                    openDesc = openDesc,
                    backDesc = backDesc,
                    reloadDesc = reloadDesc,
                    closeDesc = closeDesc,
                    moreDesc = moreDesc,
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                ) {
                    // 展开高度按「屏幕高度的 60%」算：内容区 + 状态栏 + 顶栏 = 整窗高度，
                    // 再受内容区高度约束，避免超过可用空间
                    val statusBarInset = WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding()
                    val windowHeight = maxHeight + statusBarInset + TopBarHeight
                    val sheetMaxHeight = minOf(maxHeight, windowHeight * DrawerExpandedFraction)
                    val showDrawer = workspacePages.isNotEmpty()

                    // 网页区：底部让出把手条，页面内容不被抽屉遮挡
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = if (showDrawer) drawerPeek else 0.dp)
                    ) {
                        val tab = selectedTab
                        if (tab != null && tab.url.isNotBlank()) {
                            BrowserWebView(
                                tab = tab,
                                config = config,
                                webViews = webViews,
                                onLoadingChange = { loading ->
                                    viewModel.updateTab(tab.id) { it.copy(loading = loading) }
                                },
                                onTitleChanged = { title ->
                                    viewModel.updateTab(tab.id) {
                                        it.copy(title = title.ifBlank { it.title })
                                    }
                                },
                                onPageChanged = { url, title, canGoBack ->
                                    viewModel.updateTab(tab.id) {
                                        it.copy(
                                            url = url.ifBlank { it.url },
                                            title = title.ifBlank { it.title },
                                            loading = false,
                                            canGoBack = canGoBack,
                                        )
                                    }
                                },
                                onFailed = { message ->
                                    viewModel.updateTab(tab.id) { it.copy(loading = false) }
                                    toast(context.getString(R.string.chat_browser_load_failed, message))
                                },
                                onBlocked = { url ->
                                    viewModel.updateTab(tab.id) { it.copy(loading = false) }
                                    toast(context.getString(R.string.chat_browser_restricted, url))
                                },
                            )
                        } else {
                            BrowserStartPage()
                        }
                    }

                    if (showDrawer) {
                        WorkspaceDrawer(
                            files = workspacePages,
                            workspaceRoot = workspaceRoot,
                            maxSheetHeight = sheetMaxHeight,
                            peekHeight = drawerPeek,
                            onOpen = { entry -> selectedTab?.let { openInTab(it.id, entry) } },
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }

            if (showTabManager) {
                BrowserTabManager(
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    onSelect = { index ->
                        viewModel.select(index)
                        showTabManager = false
                    },
                    onCloseTab = { id ->
                        val wasLast = tabs.size <= 1
                        viewModel.closeTab(id)
                        if (wasLast) onDismiss()
                    },
                    onNewTab = {
                        viewModel.newTab()
                        showTabManager = false
                    },
                    onCloseAll = {
                        viewModel.closeAll()
                        showTabManager = false
                        onDismiss()
                    },
                    onDismiss = { showTabManager = false },
                    newTabText = newTabText,
                    closeTabText = closeTabText,
                )
            }
        }
    }
}

/**
 * 顶部栏：固定 56dp 的单行。
 *
 * 从左到右依次是地址栏（占满剩余宽度，加载中左侧图标换成进度）、标签计数按钮、更多菜单。
 * 标签列表不占顶部空间，统一收进 [BrowserTabManager] 弹层。
 */
@Composable
private fun BrowserTopBar(
    address: String,
    loading: Boolean,
    tabCount: Int,
    canGoBack: Boolean,
    onSubmit: (String) -> Unit,
    onOpenTabs: () -> Unit,
    onNewTab: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    openDesc: String,
    backDesc: String,
    reloadDesc: String,
    closeDesc: String,
    moreDesc: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // 地址栏草稿只在提交时回写状态，避免逐字符触发网页加载
    var draft by remember { mutableStateOf(address) }
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(address, focused) {
        if (!focused) draft = address
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            interactionSource = interactionSource,
            placeholder = {
                Text(
                    text = stringResource(R.string.chat_browser_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            leadingIcon = {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            trailingIcon = {
                IconButton(
                    onClick = { onSubmit(draft) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = openDesc,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit(draft) }),
        )

        Spacer(Modifier.width(8.dp))

        // 标签计数按钮：数字超过 9 显示 9+
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                .clickable(onClick = onOpenTabs),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (tabCount > 9) "9+" else tabCount.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = moreDesc,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (canGoBack) {
                    DropdownMenuItem(
                        text = { Text(backDesc) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onBack()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_browser_new_tab)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onNewTab()
                    },
                )
                DropdownMenuItem(
                    text = { Text(reloadDesc) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onReload()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_browser_copy_url)) },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onCopyUrl()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_browser_settings)) },
                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(closeDesc, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onClose()
                    },
                )
            }
        }
    }
}

/** 标签管理弹层：贴顶面板 + 两列网格，半透明遮罩点击即关。 */
@Composable
private fun BrowserTabManager(
    tabs: List<BrowserTabState>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
    newTabText: String,
    closeTabText: String,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelMaxHeight = maxHeight * 0.75f
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .heightIn(max = panelMaxHeight),
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.chat_browser_tabs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNewTab) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(newTabText)
                    }
                    TextButton(onClick = onCloseAll) {
                        Text(
                            text = stringResource(R.string.chat_browser_close_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                        BrowserTabCard(
                            tab = tab,
                            selected = index == selectedIndex,
                            closeDesc = closeTabText,
                            onSelect = { onSelect(index) },
                            onClose = { onCloseTab(tab.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 标签卡片：标题、域名、关闭按钮；当前标签用主题色描边。 */
@Composable
private fun BrowserTabCard(
    tab: BrowserTabState,
    selected: Boolean,
    closeDesc: String,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.3f)
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = browserTabLabel(tab, stringResource(R.string.chat_browser_new_tab)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = closeDesc,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = tab.domain.ifBlank { stringResource(R.string.chat_browser_local_file) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 底部工作区抽屉：悬浮在网页之上，两个锚点（收起 48dp 把手 / 展开 min(60% 高度, 内容高度)）。
 *
 * - 卡片顶圆角 24dp、底部贴死屏幕底，内部按导航栏与输入法留白；
 * - 松手按速度与位置吸附到最近锚点，不会停在中间；
 * - 列表滚到顶部后继续下拉才拖动抽屉（nestedScroll），点遮罩或下滑即收起。
 */
@Composable
private fun WorkspaceDrawer(
    files: List<File>,
    workspaceRoot: File?,
    maxSheetHeight: Dp,
    peekHeight: Dp,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val peekPx = with(density) { peekHeight.toPx() }
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 列表显式限高：把手条 + 与导航栏的留白之外的剩余高度，内容超长时必然可滚动
    val listMaxHeight = (maxSheetHeight - DrawerHandleHeight - navBarInset - DrawerBottomGap)
        .coerceAtLeast(120.dp)

    var cardHeightPx by remember { mutableIntStateOf(0) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var movedByList by remember { mutableStateOf(false) }
    /** 本次拖动开始时的位置：松手后据此判断用户意图，避免「拖一点就跳走」。 */
    var dragStartOffset by remember { mutableFloatStateOf(0f) }

    val maxOffset = (cardHeightPx - peekPx).coerceAtLeast(0f)

    // 首次测量后落到「收起」锚点；内容高度变化时收敛回两端之间
    LaunchedEffect(maxOffset, cardHeightPx) {
        if (cardHeightPx <= 0) return@LaunchedEffect
        if (!initialized) {
            initialized = true
            offsetPx = maxOffset
        } else {
            offsetPx = offsetPx.coerceIn(0f, maxOffset)
        }
    }

    fun animateTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offsetPx, target, animationSpec = tween(durationMillis = 260)) { value, _ ->
                offsetPx = value
            }
        }
    }

    fun dragBy(delta: Float) {
        settleJob?.cancel()
        offsetPx = (offsetPx + delta).coerceIn(0f, maxOffset)
    }

    /** 松手吸附：速度够快按方向，否则按「相对起点的位移」决定去哪个锚点，绝不停在中间。 */
    fun settle(velocity: Float, startOffset: Float = dragStartOffset) {
        val moved = kotlin.math.abs(offsetPx - startOffset)
        val target = when {
            velocity > DrawerSnapVelocity -> maxOffset
            velocity < -DrawerSnapVelocity -> 0f
            maxOffset <= 0f -> 0f
            moved < maxOffset * DrawerMinDragFraction -> startOffset
            offsetPx > startOffset -> maxOffset
            else -> 0f
        }
        animateTo(target.coerceIn(0f, maxOffset))
    }

    val progress = if (maxOffset > 0f) (1f - offsetPx / maxOffset).coerceIn(0f, 1f) else 0f
    val dragState = rememberDraggableState { delta -> dragBy(delta) }
    val nestedScroll = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            // 列表已经到顶（下拉没被消费），这段位移交给抽屉
            if (available.y <= 0f) return Offset.Zero
            if (!movedByList) {
                movedByList = true
                dragStartOffset = offsetPx
            }
            dragBy(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (movedByList) {
                movedByList = false
                settle(available.y)
            }
            return Velocity.Zero
        }
    }

    Box(modifier = modifier.nestedScroll(nestedScroll)) {
        // 遮罩：展开时压在网页上，点击收起
        if (initialized && progress > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.32f * progress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { animateTo(maxOffset) },
                    )
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .offset { IntOffset(0, offsetPx.roundToInt()) }
                .onSizeChanged { cardHeightPx = it.height }
                .alpha(if (initialized) 1f else 0f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 14.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // 把手条：拖动指示条 + 标题 + 展开/收起按钮，整条可拖可点
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DrawerHandleHeight)
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = { dragStartOffset = offsetPx },
                            onDragStopped = { velocity -> settle(velocity) },
                        )
                        .clickable {
                            if (progress > 0.5f) animateTo(maxOffset) else animateTo(0f)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.chat_browser_workspace_html),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        // 明确的展开/收起按钮：拖动之外还有一处可点
                        IconButton(
                            onClick = {
                                if (progress > 0.5f) animateTo(maxOffset) else animateTo(0f)
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (progress > 0.5f) {
                                    Icons.Filled.ExpandMore
                                } else {
                                    Icons.Filled.ExpandLess
                                },
                                contentDescription = stringResource(
                                    if (progress > 0.5f) R.string.common_expand else R.string.common_collapse
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                // 收起态：这段留白正好是把手条到屏幕底的间距，收起时不会露出半行列表
                Spacer(Modifier.height(navBarInset + DrawerBottomGap))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = listMaxHeight),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        bottom = navBarInset + DrawerBottomGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(files, key = { it.path }) { file ->
                        val label = workspaceLabel(file, workspaceRoot)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .clickable {
                                    onOpen(label)
                                    animateTo(maxOffset)
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    // 末尾空白行：给最后一项留出滚动余量，避免被卡片底边裁掉
                    item {
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 新标签页占位：没有地址时的提示。 */
@Composable
private fun BrowserStartPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.chat_browser_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
    }
}

/** 单个标签页的 WebView 宿主：切换标签页时复用缓存实例，不重新加载。 */
@Composable
private fun BrowserWebView(
    tab: BrowserTabState,
    config: LocalBrowserConfig,
    webViews: MutableMap<String, WebView>,
    onLoadingChange: (Boolean) -> Unit,
    onTitleChanged: (String) -> Unit,
    onPageChanged: (String, String, Boolean) -> Unit,
    onFailed: (String) -> Unit,
    onBlocked: (String) -> Unit,
) {
    // key 到标签页：切换标签页时重建互操作节点，工厂才能换回对应标签页的 WebView
    key(tab.id) {
        AndroidView(
            factory = { ctx ->
                val view = webViews[tab.id] ?: createBuiltinBrowserView(ctx, config)
                // 复用旧标签页的 WebView 时先从原父容器摘下来，避免重复添加
                (view.parent as? ViewGroup)?.removeView(view)
                view.webViewClient = createBuiltinBrowserClient(
                    onStarted = { onLoadingChange(true) },
                    onFinished = { url, title -> onPageChanged(url, title, view.canGoBack()) },
                    onFailed = onFailed,
                    onBlocked = onBlocked,
                )
                view.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onTitleChanged(title.orEmpty())
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (newProgress >= 100) onLoadingChange(false)
                    }
                }
                webViews[tab.id] = view
                view
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 标签页标题：页面标题 → 地址 → 「新标签页」。 */
private fun browserTabLabel(tab: BrowserTabState, fallback: String): String =
    tab.title.ifBlank { tab.url.ifBlank { fallback } }

/** 工作区入口显示用文案：工作区内显示相对路径，工作区外显示绝对路径。 */
private fun workspaceLabel(file: File, workspaceRoot: File?): String =
    workspaceRoot?.let { root -> file.relativeToOrNull(root)?.path }
        ?: file.absolutePath

/** 关闭标签页时释放 WebView。 */
private fun destroyBrowserView(view: WebView?) {
    view ?: return
    runCatching {
        view.stopLoading()
        view.loadUrl("about:blank")
        view.clearHistory()
        view.removeAllViews()
        view.destroy()
    }
}

/** 创建浏览器 WebView：身份与渲染沿用「浏览器设置」。 */
@SuppressLint("SetJavaScriptEnabled")
private fun createBuiltinBrowserView(context: Context, config: LocalBrowserConfig): WebView =
    WebView(context).apply {
        setBackgroundColor(AndroidColor.WHITE)
        settings.apply {
            javaScriptEnabled = config.javascriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = config.imagesEnabled
            blockNetworkImage = !config.imagesEnabled
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = config.userAgent
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
    }

/**
 * 导航白名单与加载状态回调。
 *
 * 页面内跳转同样要过白名单：命中非白名单地址时拦截并提示，避免内置浏览器
 * 被页面当作跳板转到明文 http、自定义协议或非 HTML 的本地文件。
 */
private fun createBuiltinBrowserClient(
    onStarted: (String) -> Unit,
    onFinished: (String, String) -> Unit,
    onFailed: (String) -> Unit,
    onBlocked: (String) -> Unit,
): WebViewClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean = blockIfRestricted(request?.url?.toString(), onBlocked)

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
        blockIfRestricted(url, onBlocked)

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        onStarted(url.orEmpty())
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onFinished(url.orEmpty(), view?.title.orEmpty())
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame != true) return
        onFailed(error?.description?.toString().orEmpty())
    }
}

/** 白名单外的地址返回 true（拦截）；about:blank 等内部地址放行。 */
private fun blockIfRestricted(url: String?, onBlocked: (String) -> Unit): Boolean {
    if (url.isNullOrBlank() || url == "about:blank") return false
    if (BuiltinBrowserTargets.isAllowedNavigationUrl(url)) return false
    onBlocked(url)
    return true
}
