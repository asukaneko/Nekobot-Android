package com.nekobot.app.ui.screens.worldbook

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookRequest
import com.nekobot.app.ui.adaptive.listItemSemantics
import com.nekobot.app.ui.adaptive.rememberShouldUseTwoPane
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 世界书列表页 ViewModel：管理世界书列表的加载、创建、删除。
 */
private enum class WorldBookViewMode { LIST, GRID }

class WorldBooksViewModel : com.nekobot.app.ui.BaseViewModel() {

    private val _books = MutableStateFlow<List<WorldBook>>(emptyList())
    val books: StateFlow<List<WorldBook>> = _books.asStateFlow()

    /** 加载世界书列表 */
    fun load() {
        launchResult(
            block = { unified.listWorldBooks() },
            onSuccess = { _books.value = it ?: emptyList() }
        )
    }

    /** 创建世界书 */
    fun create(req: WorldBookRequest) {
        launchResult(
            block = { unified.createWorldBook(req) },
            onSuccess = { created ->
                _books.value = _books.value + created
                showToast(string(R.string.worldbook_created))
            }
        )
    }

    /** 删除世界书 */
    fun delete(id: String) {
        launchResult(
            block = { unified.deleteWorldBook(id) },
            onSuccess = {
                _books.value = _books.value.filterNot { it.id == id }
                showToast(string(R.string.worldbook_deleted))
            }
        )
    }

    /**
     * AI 生成世界书：先创建一本空世界书，再调用 AI 按主题生成条目。
     * 生成完成后回调 onSuccess(bookId)，由调用方跳转到详情页。
     */
    fun aiGenerateWorldBook(
        name: String,
        description: String?,
        topic: String?,
        onSuccess: (String) -> Unit
    ) {
        launchResult(
            block = {
                // 1. 先创建空世界书
                val created = unified.createWorldBook(
                    WorldBookRequest(name = name, description = description)
                )
                val bookId = (created as? com.nekobot.app.data.repository.Resource.Success)
                    ?.data?.id
                    ?: throw IllegalStateException(string(R.string.worldbook_create_failed))
                // 2. 调用 AI 生成条目（本地模式会立即落库，远程模式返回条目列表已落库）
                unified.aiGenerateWorldBookEntries(bookId, topic)
                com.nekobot.app.data.repository.Resource.Success(bookId)
            },
            onSuccess = { bookId ->
                // 重新加载列表以显示新书
                load()
                showToast(string(R.string.worldbook_ai_generated))
                onSuccess(bookId)
            }
        )
    }
}

/**
 * 世界书列表页：展示所有世界书，支持刷新、新建、点击进入详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBooksScreen(
    onOpenBook: (String) -> Unit,
    viewModel: WorldBooksViewModel = viewModel()
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }
    var showAiGeneratingHint by remember { mutableStateOf(false) }
    var viewMode by remember {
        mutableStateOf(
            runCatching { WorldBookViewMode.valueOf(ServiceContainer.prefs.worldBookViewMode) }
                .getOrDefault(WorldBookViewMode.LIST)
        )
    }

    // 模式切换时自动刷新世界书列表
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    LaunchedEffect(appMode) { viewModel.load() }

    // 双栏布局状态：大屏模式下选中的世界书 ID
    val useTwoPane = rememberShouldUseTwoPane()
    var selectedBookId by remember { mutableStateOf<String?>(null) }
    val handleOpenBook: (String) -> Unit = if (useTwoPane) {
        { id -> selectedBookId = id }
    } else {
        onOpenBook
    }

    val scaffoldContent: @Composable () -> Unit = {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.worldbook_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(8.dp))
                        CountBadge(books.size)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.worldbook_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    // 新建按钮 + 下拉菜单：新建 / AI 生成
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.worldbook_new), tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.worldbook_new)) },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showAddMenu = false
                                    showCreateDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.worldbook_ai_generate)) },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    showAddMenu = false
                                    showAiDialog = true
                                }
                            )
                        }
                    }
                    IconButton(onClick = {
                        val newMode = if (viewMode == WorldBookViewMode.LIST) {
                            WorldBookViewMode.GRID
                        } else {
                            WorldBookViewMode.LIST
                        }
                        viewMode = newMode
                        ServiceContainer.prefs.worldBookViewMode = newMode.name
                    }) {
                        Icon(
                            if (viewMode == WorldBookViewMode.LIST) Icons.Filled.Apps else Icons.Filled.ViewList,
                            contentDescription = stringResource(
                                if (viewMode == WorldBookViewMode.LIST) R.string.worldbook_view_grid
                                else R.string.worldbook_view_list
                            ),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
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
            if (books.isEmpty() && !loading) {
                EmptyState(
                    title = stringResource(R.string.worldbook_empty_title),
                    hint = stringResource(R.string.worldbook_empty_hint),
                    icon = {
                        Icon(
                            Icons.Filled.Book,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                )
            } else {
                if (viewMode == WorldBookViewMode.LIST) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
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
                        items(books, key = { it.id ?: it.name ?: it.hashCode().toString() }) { book ->
                            WorldBookItem(book = book, onClick = { book.id?.let { handleOpenBook(it) } })
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (error != null) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                ErrorBanner(message = error!!, onRetry = {
                                    viewModel.clearError()
                                    viewModel.load()
                                })
                            }
                        }
                        gridItems(books, key = { it.id ?: it.name ?: it.hashCode().toString() }) { book ->
                            WorldBookGridItem(book = book, onClick = { book.id?.let { handleOpenBook(it) } })
                        }
                    }
                }
            }
            LoadingOverlay(visible = loading && books.isEmpty())
        }
    }
    } // 结束 scaffoldContent lambda

    // 双栏布局：大屏时左列列表 + 右列详情
    if (useTwoPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
            ) {
                scaffoldContent()
            }
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
            )
            Box(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
            ) {
                selectedBookId?.let { bookId ->
                    WorldBookDetailScreen(
                        bookId = bookId,
                        onBack = { selectedBookId = null }
                    )
                } ?: Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.worldbook_select_for_details),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {
        scaffoldContent()
    }

    // 新建世界书对话框
    if (showCreateDialog) {
        CreateWorldBookDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, desc ->
                viewModel.create(WorldBookRequest(name = name, description = desc))
                showCreateDialog = false
            }
        )
    }

    // AI 生成世界书对话框
    if (showAiDialog) {
        AiGenerateWorldBookDialog(
            onDismiss = { showAiDialog = false },
            onConfirm = { name, desc, topic ->
                showAiDialog = false
                // 立即弹出"后台生成中"提示，AI 任务继续在后台执行
                showAiGeneratingHint = true
                viewModel.aiGenerateWorldBook(name, desc, topic) { bookId ->
                    // 生成完成：关闭提示并跳转详情页
                    showAiGeneratingHint = false
                    handleOpenBook(bookId)
                }
            }
        )
    }

    // "后台生成中"提示对话框（任务已在后台执行，用户可关闭本提示）
    if (showAiGeneratingHint) {
        NekoDialog(
            onDismiss = { showAiGeneratingHint = false },
            title = stringResource(R.string.worldbook_ai_generating_title),
            message = stringResource(R.string.worldbook_ai_generating_message),
            confirmText = stringResource(R.string.worldbook_got_it),
            confirmEnabled = true,
            onConfirm = { showAiGeneratingHint = false },
            cancelText = null,
            onCancel = null
        )
    }
}

/** 单个世界书卡片 */
@Composable
private fun WorldBookItem(book: WorldBook, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .listItemSemantics(
                stringResource(
                    R.string.worldbook_item_description,
                    book.displayName,
                    book.description.orEmpty(),
                    book.resolvedEntryCount
                )
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorldBookCover(book.coverUrl, book.displayName, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!book.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = book.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.worldbook_entry_count, book.resolvedEntryCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            // 启用状态开关
            Switch(
                checked = book.enabled == true,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}

@Composable
private fun WorldBookGridItem(book: WorldBook, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .listItemSemantics(book.displayName)
    ) {
        WorldBookCover(
            coverUrl = book.coverUrl,
            contentDescription = book.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(14.dp))
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = book.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.worldbook_entry_count, book.resolvedEntryCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 新建世界书对话框：包含名称、描述输入 */
@Composable
private fun CreateWorldBookDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.worldbook_new),
        confirmText = stringResource(R.string.common_create),
        cancelText = stringResource(R.string.common_cancel),
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            onConfirm(name.trim(), desc.trim().takeIf { it.isNotBlank() })
        },
        onCancel = onDismiss
    ) {
        Text(stringResource(R.string.worldbook_field_name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
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
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.worldbook_field_description), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = desc,
            onValueChange = { desc = it },
            singleLine = false,
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
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
    }
}

/**
 * AI 生成世界书对话框：用户输入书名、描述和生成主题，
 * 由 ViewModel 先创建空书，再调用 AI 按主题生成条目。
 *
 * 参考原仓库 nbot/web/routes/world_book.py 的 ai-generate 接口。
 */
@Composable
private fun AiGenerateWorldBookDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String?, topic: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
    )

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.worldbook_ai_generate),
        confirmText = stringResource(R.string.worldbook_generate),
        cancelText = stringResource(R.string.common_cancel),
        confirmEnabled = name.isNotBlank(),
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            onConfirm(
                name.trim(),
                desc.trim().takeIf { it.isNotBlank() },
                topic.trim().takeIf { it.isNotBlank() }
            )
        },
        onCancel = onDismiss
    ) {
        Text(stringResource(R.string.worldbook_field_book_name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors
        )
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.worldbook_field_description_optional), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = desc,
            onValueChange = { desc = it },
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors
        )
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.worldbook_field_topic_optional), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    stringResource(R.string.worldbook_topic_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.worldbook_topic_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
