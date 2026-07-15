package com.nekobot.app.ui.screens.characters

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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
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

    /** 加载角色列表 */
    fun load() {
        launchResult(
            block = { unified.listCharacters() },
            onSuccess = { _characters.value = it ?: emptyList() }
        )
    }

    /** 删除指定角色 */
    fun delete(id: String) {
        launchResult(
            block = { unified.deleteCharacter(id) },
            onSuccess = {
                _characters.value = _characters.value.filterNot { it.id == id }
                showToast("已删除角色")
            }
        )
    }

    /** 导入角色卡（.json / .zip）。 */
    fun importCharacter(bytes: ByteArray, fileName: String) {
        launchResult(
            block = { unified.importCharacter(bytes, fileName) },
            onSuccess = { preset ->
                _characters.value = _characters.value + preset
                showToast("已导入角色：${preset.displayName}")
            }
        )
    }

    /**
     * AI 生成角色卡：根据描述生成完整角色卡后调用 createCharacter 保存到库。
     * 保存成功后跳转到该角色编辑页（由 onSuccess 回调处理）。
     */
    fun aiGenerateCharacter(description: String, onSuccess: (CharacterPreset) -> Unit) {
        launchResult(
            block = { unified.aiGenerateCharacter(description) },
            onSuccess = { preset ->
                // 持久化保存生成的角色
                launchResult(
                    block = {
                        val json = com.nekobot.app.ServiceContainer.gson.toJsonTree(preset)
                        unified.createCharacter(json)
                    },
                    onSuccess = { saved ->
                        _characters.value = _characters.value + saved
                        showToast("AI 已生成角色：${saved.displayName}")
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
    viewModel: CharactersViewModel = viewModel()
) {
    val characters by viewModel.characters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var viewMode by remember {
        mutableStateOf(
            runCatching { CharacterViewMode.valueOf(ServiceContainer.prefs.characterViewMode) }
                .getOrDefault(CharacterViewMode.LIST)
        )
    }
    var showAddMenu by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }
    var showAiGeneratingHint by remember { mutableStateOf(false) }

    // 模式切换或返回页面时自动刷新角色列表
    val appMode by ServiceContainer.appModeFlow.collectAsState()
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("角色", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
                            contentDescription = if (viewMode == CharacterViewMode.LIST) "卡片视图" else "列表视图",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    // 导入角色卡按钮
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed", "*/*"))
                    }) {
                        Icon(Icons.Filled.Upload, contentDescription = "导入角色卡", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    // 新建按钮 + 下拉菜单：新建 / AI 生成
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "新建角色", tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建角色") },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showAddMenu = false
                                    onOpenCharacter("new")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("AI 生成角色") },
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
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (characters.isEmpty() && !loading) {
                EmptyState(
                    title = "暂无角色",
                    hint = "点击右上角新建一个角色",
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorBanner(message = error!!, onRetry = {
                                viewModel.clearError()
                                viewModel.load()
                            })
                        }
                    }
                    items(characters, key = { it.id ?: it.name ?: it.hashCode().toString() }) { character ->
                        CharacterListItem(character = character, onClick = {
                            character.id?.let { onOpenCharacter(it) }
                        })
                    }
                }
            } else {
                // 卡片网格视图
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                            ErrorBanner(message = error!!, onRetry = {
                                viewModel.clearError()
                                viewModel.load()
                            })
                        }
                    }
                    items(characters, key = { it.id ?: it.name ?: it.hashCode().toString() }) { character ->
                        CharacterGridItem(character = character, onClick = {
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
            onConfirm = { description ->
                showAiDialog = false
                // 立即弹出"后台生成中"提示，AI 任务继续在后台执行
                showAiGeneratingHint = true
                viewModel.aiGenerateCharacter(description) { preset ->
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
            title = "后台生成中",
            message = "AI 正在生成角色卡，请稍候片刻。生成完成后将自动跳转到编辑页。",
            confirmText = "知道了",
            confirmEnabled = true,
            onConfirm = { showAiGeneratingHint = false },
            cancelText = null,
            onCancel = null
        )
    }
}

/** 列表模式：单条角色卡片（头像 + 名称 + 描述 + 标签） */
@Composable
private fun CharacterListItem(character: CharacterPreset, onClick: () -> Unit) {
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
                contentDescription = "头像",
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
private fun CharacterGridItem(character: CharacterPreset, onClick: () -> Unit) {
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
            // 上方：角色立绘（方形，圆角）
            val portraitUrl = character.avatarUrl?.let { resolveImageUrl(it) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!portraitUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = portraitUrl,
                        contentDescription = "角色立绘",
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
        ?: throw IllegalStateException("无法读取文件")
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
    onConfirm: (description: String) -> Unit
) {
    var description by remember { mutableStateOf("") }

    NekoDialog(
        onDismiss = onDismiss,
        title = "AI 生成角色",
        confirmText = "生成角色卡",
        cancelText = "取消",
        confirmEnabled = description.isNotBlank(),
        onConfirm = {
            if (description.isBlank()) return@NekoDialog
            onConfirm(description.trim())
        },
        onCancel = onDismiss
    ) {
        Text(
            "角色描述",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            singleLine = false,
            minLines = 6,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "例如：一个来自异世界的精灵弓箭手，外表高冷但内心温柔，擅长吐槽，喜欢在月光下喝蜂蜜茶……",
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
            "提示：描述越具体（性格、外貌、背景、说话风格），生成质量越高。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
