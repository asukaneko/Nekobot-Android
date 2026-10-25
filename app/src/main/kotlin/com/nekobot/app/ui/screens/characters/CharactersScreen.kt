package com.nekobot.app.ui.screens.characters

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import com.nekobot.app.ui.components.BorderlessAssistChip as AssistChip
import com.nekobot.app.ui.components.BorderlessOutlinedButton as OutlinedButton
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocaleHelper
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.screens.sessions.SessionModeBadge
import com.nekobot.app.ui.screens.sessions.SessionModeLabel
import com.nekobot.app.ui.adaptive.WindowWidthClass
import com.nekobot.app.ui.adaptive.rememberWindowWidthClass
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoChoiceRow
import com.nekobot.app.ui.components.NekoIconTile
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.NekoSheet
import com.nekobot.app.ui.components.NekoSheetHeader
import com.nekobot.app.ui.navigation.rememberBottomBarClearance
import com.nekobot.app.ui.screens.library.LibraryTopAction
import com.nekobot.app.ui.screens.library.LibraryTopBarActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 视图模式：列表 / 卡片网格 */
enum class CharacterViewMode { LIST, GRID }

/**
 * 角色列表页 ViewModel：管理角色列表的加载、删除。
 */
class CharactersViewModel : com.nekobot.app.ui.BaseViewModel() {

    private val _characters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()

    private val _sessionModes = MutableStateFlow<Map<String, List<SessionModeLabel>>?>(null)
    val sessionModes: StateFlow<Map<String, List<SessionModeLabel>>?> = _sessionModes.asStateFlow()
    private var loadGeneration = 0

    /** 加载角色列表 */
    fun load() {
        val generation = ++loadGeneration
        _sessionModes.value = null
        launchResult(
            block = {
                when (val result = unified.listCharacters()) {
                    is Resource.Success -> {
                        val modes = when (val sessions = unified.listSessions()) {
                            is Resource.Success -> buildCharacterSessionModes(sessions.data)
                            else -> null
                        }
                        Resource.Success(result.data to modes)
                    }
                    is Resource.Error -> result
                    is Resource.Loading -> Resource.Loading
                }
            },
            onSuccess = { (characters, modes) ->
                if (generation == loadGeneration) {
                    _characters.value = characters
                    _sessionModes.value = modes
                }
            },
            onError = { if (generation == loadGeneration) showError(it) }
        )
    }

    /** 删除指定角色 */
    fun delete(id: String) {
        launchResult(
            block = { unified.deleteCharacter(id) },
            onSuccess = {
                _characters.value = _characters.value.filterNot { it.id == id }
                showToast(string(R.string.character_deleted))
            }
        )
    }

    /** 导入角色卡（.json / .zip）。 */
    fun importCharacter(bytes: ByteArray, fileName: String) {
        launchResult(
            block = { unified.importCharacter(bytes, fileName) },
            onSuccess = { preset ->
                _characters.value = _characters.value + preset
                showToast(string(R.string.character_imported, preset.displayName))
            }
        )
    }

    /**
     * AI 生成角色卡：根据描述生成完整角色卡后调用 createCharacter 保存到库。
     * 保存成功后跳转到该角色编辑页（由 onSuccess 回调处理）。
     */
    fun aiGenerateCharacter(
        description: String,
        language: String,
        onSuccess: (CharacterPreset) -> Unit
    ) {
        launchResult(
            block = { unified.aiGenerateCharacter(description, language) },
            onSuccess = { preset ->
                // 持久化保存生成的角色
                launchResult(
                    block = {
                        val json = com.nekobot.app.ServiceContainer.gson.toJsonTree(preset)
                        unified.createCharacter(json)
                    },
                    onSuccess = { saved ->
                        _characters.value = _characters.value + saved
                        showToast(string(R.string.character_ai_generated, saved.displayName))
                        onSuccess(saved)
                    }
                )
            }
        )
    }
}

/**
 * 角色列表页：展示所有角色，支持刷新、新建、点击进入详情。
 * 支持列表/卡片网格两种视图切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(
    onOpenCharacter: (String) -> Unit,
    onOpenEdit: (String) -> Unit = onOpenCharacter,
    viewModel: CharactersViewModel = viewModel(),
    /** 嵌入「内容库」时由外层提供标题栏、搜索与分段控件。 */
    embedded: Boolean = false,
    /** 外层搜索框的关键词：按名称与描述做本地过滤。 */
    searchQuery: String = ""
) {
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val sessionModes by viewModel.sessionModes.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val bottomBarClearance = rememberBottomBarClearance()
    // 内容库里的关键词过滤：只影响展示，不改动源数据
    val visibleCharacters = remember(characters, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            characters
        } else {
            characters.filter { character ->
                character.displayName.contains(query, ignoreCase = true) ||
                    character.description?.contains(query, ignoreCase = true) == true
            }
        }
    }

    var viewMode by remember {
        mutableStateOf(
            runCatching { CharacterViewMode.valueOf(ServiceContainer.prefs.characterViewMode) }
                .getOrDefault(CharacterViewMode.LIST)
        )
    }
    // 窗口宽度断点：手机（紧凑）固定 2 列，平板（Medium/Expanded）按宽度自适应列数
    val widthClass = rememberWindowWidthClass()
    var showAddMenu by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }
    var showAiGeneratingHint by remember { mutableStateOf(false) }

    // 模式切换或返回页面时自动刷新角色列表
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    LaunchedEffect(appMode) { viewModel.load() }

    // 文件选择器：导入角色卡（.json / .zip）
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 读取文件内容并交给 ViewModel 导入（IO 线程读取，避免阻塞 UI）
            scope.launch {
                val (bytes, name) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readUriToBytes(context, uri)
                }
                viewModel.importCharacter(bytes, name)
            }
        }
    }

    // 嵌入内容库时：把顶栏动作交给外层渲染（标题栏只有一份，功能一个不丢）
    if (embedded) {
        val viewToggleLabel = stringResource(
            if (viewMode == CharacterViewMode.LIST) R.string.characters_view_grid
            else R.string.characters_view_list
        )
        val refreshLabel = stringResource(R.string.characters_refresh)
        val importLabel = stringResource(R.string.characters_import_card)
        val newLabel = stringResource(R.string.characters_new)
        LibraryTopBarActions {
            listOf(
                LibraryTopAction(
                    key = "view_mode",
                    icon = if (viewMode == CharacterViewMode.LIST) Icons.Filled.Apps else Icons.Filled.ViewList,
                    label = viewToggleLabel
                ) {
                    val newMode =
                        if (viewMode == CharacterViewMode.LIST) CharacterViewMode.GRID else CharacterViewMode.LIST
                    viewMode = newMode
                    ServiceContainer.prefs.characterViewMode = newMode.name
                },
                LibraryTopAction("refresh", Icons.Filled.Refresh, refreshLabel) { viewModel.load() },
                LibraryTopAction("import", Icons.Filled.Upload, importLabel) {
                    importLauncher.launch(
                        arrayOf(
                            "application/json", "application/zip", "application/x-zip-compressed",
                            "image/png", "*/*"
                        )
                    )
                },
                LibraryTopAction("new", Icons.Filled.Add, newLabel, prominent = true) { showAddMenu = true },
            )
        }
        // 排版更清爽的选择方式：新建 / AI 生成放进抽屉，而不是塞一个下拉菜单
        if (showAddMenu) {
            NekoSheet(onDismissRequest = { showAddMenu = false }) {
                NekoSheetHeader(
                    title = stringResource(R.string.characters_new),
                    onClose = { showAddMenu = false }
                )
                NekoChoiceRow(
                    title = stringResource(R.string.characters_new),
                    onClick = {
                        showAddMenu = false
                        onOpenCharacter("new")
                    },
                    leading = { NekoIconTile(Icons.Filled.Person) }
                )
                NekoChoiceRow(
                    title = stringResource(R.string.characters_ai_generate),
                    onClick = {
                        showAddMenu = false
                        showAiDialog = true
                    },
                    leading = { NekoIconTile(Icons.Filled.AutoAwesome) }
                )
            }
        }
    }

    Scaffold(
        containerColor = if (embedded) Color.Transparent else MaterialTheme.colorScheme.background,
        // 嵌入内容库时外层已经处理状态栏/底栏避让，子页不再重复加 inset
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!embedded) {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.characters_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(8.dp))
                        CountBadge(characters.size)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    // 视图切换按钮
                    IconButton(onClick = {
                        val newMode = if (viewMode == CharacterViewMode.LIST) CharacterViewMode.GRID else CharacterViewMode.LIST
                        viewMode = newMode
                        ServiceContainer.prefs.characterViewMode = newMode.name
                    }) {
                        Icon(
                            if (viewMode == CharacterViewMode.LIST) Icons.Filled.Apps else Icons.Filled.ViewList,
                            contentDescription = if (viewMode == CharacterViewMode.LIST) stringResource(R.string.characters_view_grid) else stringResource(R.string.characters_view_list),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.characters_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    // 导入角色卡按钮（支持 .json / .zip / .png 酒馆角色卡）
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed", "image/png", "*/*"))
                    }) {
                        Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.characters_import_card), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    // 新建按钮 + 下拉菜单：新建 / AI 生成
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.characters_new), tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.characters_new)) },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showAddMenu = false
                                    onOpenCharacter("new")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.characters_ai_generate)) },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showAddMenu = false
                                    showAiDialog = true
                                }
                            )
                        }
                    }
                }
            )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(if (embedded) Color.Transparent else MaterialTheme.colorScheme.background),
            // 宽屏下列表视图被约束到最大宽度后水平居中（网格视图铺满全宽不受影响）
            contentAlignment = Alignment.TopCenter
        ) {
            if (visibleCharacters.isEmpty() && !loading) {
                EmptyState(
                    title = stringResource(
                        if (searchQuery.isBlank()) R.string.characters_empty_title
                        else R.string.shell_library_empty
                    ),
                    hint = stringResource(
                        if (searchQuery.isBlank()) R.string.characters_empty_hint
                        else R.string.shell_library_empty_hint
                    ),
                    icon = {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                )
            } else if (viewMode == CharacterViewMode.LIST) {
                LazyColumn(
                    // 平板宽屏：列表卡片约束最大宽度居中，避免整行卡片被横向拉伸
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomBarClearance + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorToast(message = error!!, onShown = { viewModel.clearError() })
                        }
                    }
                    items(visibleCharacters, key = { it.id ?: it.name ?: it.hashCode().toString() }) { character ->
                        CharacterListItem(character = character,
                            modes = sessionModes?.get(character.id).orEmptyWhenLoaded(sessionModes != null), onClick = {
                            character.id?.let { onOpenCharacter(it) }
                        })
                    }
                }
            } else {
                // 卡片网格视图
                LazyVerticalGrid(
                    // 手机（紧凑宽度）：固定 2 列，保持原有双列卡片展示；
                    // 平板（Medium/Expanded）：按宽度自适应列数（每列最小 160dp），
                    // 避免固定 2 列在宽屏下把卡片拉成横长方形
                    columns = if (widthClass == WindowWidthClass.Compact) {
                        GridCells.Fixed(2)
                    } else {
                        GridCells.Adaptive(160.dp)
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomBarClearance + 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            ErrorToast(message = error!!, onShown = { viewModel.clearError() })
                        }
                    }
                    items(visibleCharacters, key = { it.id ?: it.name ?: it.hashCode().toString() }) { character ->
                        CharacterGridItem(character = character,
                            modes = sessionModes?.get(character.id).orEmptyWhenLoaded(sessionModes != null), onClick = {
                            character.id?.let { onOpenCharacter(it) }
                        })
                    }
                }
            }
            LoadingOverlay(visible = loading && characters.isEmpty())
        }
    }

    // AI 生成角色对话框
    if (showAiDialog) {
        AiGenerateCharacterDialog(
            onDismiss = { showAiDialog = false },
            onConfirm = { description, language ->
                showAiDialog = false
                // 立即弹出"后台生成中"提示，AI 任务继续在后台执行
                showAiGeneratingHint = true
                viewModel.aiGenerateCharacter(description, language) { preset ->
                    // 生成完成：关闭提示并跳转编辑页
                    showAiGeneratingHint = false
                    preset.id?.let { onOpenEdit(it) }
                }
            }
        )
    }

    // "后台生成中"提示对话框（任务已在后台执行，用户可关闭本提示）
    if (showAiGeneratingHint) {
        NekoDialog(
            onDismiss = { showAiGeneratingHint = false },
            title = stringResource(R.string.characters_generating_title),
            message = stringResource(R.string.characters_generating_msg),
            confirmText = stringResource(R.string.character_got_it),
            confirmEnabled = true,
            onConfirm = { showAiGeneratingHint = false },
            cancelText = null,
            onCancel = null
        )
    }
}

/** 列表模式：单条角色卡片（头像 + 名称 + 描述 + 标签） */
@Composable
private fun CharacterListItem(character: CharacterPreset, modes: List<SessionModeLabel>?, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像：圆形，相对路径拼接 baseUrl
            val avatarUrl = character.avatarUrl?.let { resolveImageUrl(it) }
            AsyncImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.character_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                CharacterModeLabels(modes, Modifier.padding(top = 5.dp))
                if (!character.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = character.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 标签 Chip 行：横向滚动，完整展示所有标签
                val tags = character.tags
                if (!tags.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        tags.forEach { tag ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        tag,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    labelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 网格模式：角色卡片（上方立绘 + 下方名称和简介） */
@Composable
private fun CharacterGridItem(character: CharacterPreset, modes: List<SessionModeLabel>?, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        cornerRadius = 16
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 上方：角色立绘（竖版 0.78:1，与世界书网格卡片封面比例一致）——
            // 随卡片宽度等比缩放，宽屏下不再被压扁成横长方形，同时保持卡片更高挑
            val portraitUrl = character.avatarUrl?.let { resolveImageUrl(it) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!portraitUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = portraitUrl,
                        contentDescription = stringResource(R.string.character_portrait),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 下方：角色名
            Text(
                text = character.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            // 简介
            CharacterModeLabels(modes, Modifier.fillMaxWidth().padding(top = 5.dp))
            if (!character.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = character.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun List<SessionModeLabel>?.orEmptyWhenLoaded(loaded: Boolean): List<SessionModeLabel>? =
    if (loaded) orEmpty() else null

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterModeLabels(modes: List<SessionModeLabel>?, modifier: Modifier = Modifier) {
    if (modes.isNullOrEmpty()) {
        Text(stringResource(if (modes == null) R.string.characters_session_modes_unavailable
            else R.string.characters_no_linked_sessions), modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    } else {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            modes.forEach { SessionModeBadge(it) }
        }
    }
}

/** 把相对路径图片地址拼成完整 URL（兼容本地 file: 路径） */
private fun resolveImageUrl(path: String): String {
    if (path.startsWith("file:") || path.startsWith("content://")) return path
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = ServiceContainer.network.baseUrl().trimEnd('/')
    return base + "/" + path.trimStart('/')
}

/** 标题旁的数量徽标 */
@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 读取 Uri 对应文件为 ByteArray，并解析原始文件名。
 */
private fun readUriToBytes(context: android.content.Context, uri: android.net.Uri): Pair<ByteArray, String> {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException(ServiceContainer.getString(R.string.character_read_file_failed))
    // 尝试从 Uri 解析文件名
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIdx)
        }
    }
    val fileName = name ?: uri.lastPathSegment ?: "character.json"
    return bytes to fileName
}

/**
 * AI 生成角色卡对话框：用户输入角色描述，点击生成后回调 onConfirm。
 *
 * 参考原仓库 nbot/web/routes/personality.py 的 ai-generate 接口，
 * 描述越具体生成质量越高（性格/外貌/背景/说话风格等）。
 */
@Composable
private fun AiGenerateCharacterDialog(
    onDismiss: () -> Unit,
    onConfirm: (description: String, language: String) -> Unit
) {
    var description by remember { mutableStateOf("") }
    val context = LocalContext.current
    val defaultLanguage = remember(context) {
        val effectiveLanguage = LocaleHelper
            .getEffectiveLanguageTag(context, ServiceContainer.prefs.language)
        when (effectiveLanguage) {
            "zh", "zh-TW", "en", "ja", "ko" -> effectiveLanguage
            else -> "en"
        }
    }
    var language by remember(defaultLanguage) { mutableStateOf(defaultLanguage) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    val languages = listOf(
        "zh" to stringResource(R.string.language_chinese),
        "zh-TW" to stringResource(R.string.language_chinese_traditional),
        "en" to stringResource(R.string.language_english),
        "ja" to stringResource(R.string.language_japanese),
        "ko" to stringResource(R.string.language_korean)
    )
    val selectedLanguage = languages.firstOrNull { it.first == language } ?: languages.first()

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.characters_ai_generate),
        confirmText = stringResource(R.string.character_ai_generate_card),
        cancelText = stringResource(R.string.common_cancel),
        confirmEnabled = description.isNotBlank(),
        onConfirm = {
            if (description.isBlank()) return@NekoDialog
            onConfirm(description.trim(), language)
        },
        onCancel = onDismiss,
        // 内容可能超出弹窗高度上限，开启滚动避免底部选项被裁掉
        contentScrollable = true
    ) {
        // 语言选择放在描述上方：描述输入框会随内容增高，放在下面会被挤出可视区
        Text(
            stringResource(R.string.character_ai_language_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { languageMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = selectedLanguage.second,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start
                )
                Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = languageMenuExpanded,
                onDismissRequest = { languageMenuExpanded = false }
            ) {
                languages.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            language = code
                            languageMenuExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.character_description_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            singleLine = false,
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    stringResource(R.string.character_ai_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.character_ai_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
