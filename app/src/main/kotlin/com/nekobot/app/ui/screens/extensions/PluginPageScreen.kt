package com.nekobot.app.ui.screens.extensions

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.plugin.PluginManifestValidator
import com.nekobot.app.data.local.plugin.PluginPageHost
import com.nekobot.app.data.local.plugin.PluginThemeTokens
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay

/**
 * 插件页面宿主：Compose 容器 + 长驻 WebView。
 *
 * 页面资源来自插件目录，经虚拟源加载；离页销毁 WebView，崩溃时展示错误卡片可重载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginPageScreen(
    pluginId: String,
    pageId: String,
    sessionId: String?,
    launchArgs: String? = null,
    progressParentId: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val resolved = remember(pluginId, pageId) {
        ServiceContainer.pluginManager.resolvePage(pluginId, pageId)
    }

    if (resolved == null) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.plugin_page_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { padding ->
            PluginPageMessage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                message = stringResource(R.string.plugin_page_not_found)
            )
        }
        return
    }

    val configuration = LocalConfiguration.current
    val languageCode = configuration.locales[0]?.language.orEmpty()
    // 首屏 WebView 在 LaunchedEffect 之前就已创建，主题必须在这里算好并交给宿主。
    val tokens = pluginThemeTokens(languageCode)
    val host = remember(resolved, launchArgs, languageCode) {
        PluginPageHost(
            context = context,
            resolved = resolved,
            dispatcher = ServiceContainer.pluginManager.apiDispatcher,
            files = ServiceContainer.pluginManager.pluginFiles,
            sessionId = sessionId,
            launchArgs = launchArgs,
            progressParentMessageId = progressParentId,
            initialTheme = tokens,
            languageCode = languageCode,
            onCloseRequested = onBack
        )
    }
    val state by host.state.collectAsStateWithLifecycle()
    val revision by host.revision.collectAsStateWithLifecycle()
    val pageTitle by host.pageTitle.collectAsStateWithLifecycle()
    val dialogRequest by host.dialog.collectAsStateWithLifecycle()
    // 页面 <input type="file">：单选/多选分别用系统选择器，结果复制到插件私有目录
    val pickSingleFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        host.deliverFileChooserResult(listOfNotNull(uri))
    }
    val pickMultipleFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        host.deliverFileChooserResult(uris)
    }
    LaunchedEffect(host) {
        host.filePicker = { mimeTypes, allowMultiple ->
            runCatching {
                if (allowMultiple) pickMultipleFiles.launch(mimeTypes) else pickSingleFile.launch(mimeTypes)
            }.onFailure { host.deliverFileChooserResult(null) }
        }
    }
    LaunchedEffect(tokens) { host.updateTheme(tokens) }
    DisposableEffect(host) {
        onDispose { host.destroy() }
    }
    BackHandler {
        if (host.canGoBack()) host.goBack() else onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = pageTitle,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { host.reload() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.plugin_page_reload)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            key(revision) {
                var created by remember { mutableStateOf<WebView?>(null) }
                AndroidView(
                    factory = { ctx ->
                        host.obtainWebView().also { created = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                DisposableEffect(revision) {
                    onDispose { created?.let(host::releaseWebView) }
                }
            }
            when (val current = state) {
                is PluginPageHost.State.Loading -> LoadingOverlay(visible = true)
                is PluginPageHost.State.Error -> PluginPageMessage(
                    modifier = Modifier.fillMaxSize(),
                    message = current.message,
                    onReload = { host.reload() }
                )
                is PluginPageHost.State.Ready -> Unit
            }
        }
        // 原生弹窗（alert/confirm/prompt/select）：由页面脚本或 host.ui.* 触发。
        dialogRequest?.let { request ->
            PluginPageDialogRenderer(request = request, onResult = host::respondDialog)
        }
    }
}

@Composable
private fun PluginPageMessage(
    modifier: Modifier,
    message: String,
    onReload: (() -> Unit)? = null
) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (onReload != null) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onReload) {
                        Text(stringResource(R.string.plugin_page_reload))
                    }
                }
            }
        }
    }
}

/** 从当前 Compose 主题提取插件页可用的 CSS 变量。 */
@Composable
private fun pluginThemeTokens(languageCode: String): PluginThemeTokens {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.toArgb().let { color ->
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        (0.299 * r + 0.587 * g + 0.114 * b) < 128
    }
    val appVersion = remember {
        runCatching {
            val context = ServiceContainer.appContext
            context?.packageManager?.getPackageInfo(context.packageName, 0)?.versionName
        }.getOrNull().orEmpty()
    }
    return PluginThemeTokens(
        mode = if (dark) "dark" else "light",
        colors = mapOf(
            "bg" to scheme.background.toCssHex(),
            "surface" to scheme.surface.toCssHex(),
            "surface-variant" to scheme.surfaceVariant.toCssHex(),
            "on-surface" to scheme.onSurface.toCssHex(),
            "on-surface-variant" to scheme.onSurfaceVariant.toCssHex(),
            "primary" to scheme.primary.toCssHex(),
            "on-primary" to scheme.onPrimary.toCssHex(),
            "secondary" to scheme.secondary.toCssHex(),
            "outline" to scheme.outline.toCssHex(),
            "error" to scheme.error.toCssHex()
        ),
        locale = languageCode.ifBlank { "zh" },
        appVersion = appVersion,
        apiVersion = PluginManifestValidator.CURRENT_API_VERSION
    )
}

private fun Color.toCssHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())
