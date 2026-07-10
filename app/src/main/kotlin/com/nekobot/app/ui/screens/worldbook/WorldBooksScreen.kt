package com.nekobot.app.ui.screens.worldbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookRequest
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
class WorldBooksViewModel : com.nekobot.app.ui.BaseViewModel() {

    private val _books = MutableStateFlow<List<WorldBook>>(emptyList())
    val books: StateFlow<List<WorldBook>> = _books.asStateFlow()

    init {
        load()
    }

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
                showToast("已创建世界书")
            }
        )
    }

    /** 删除世界书 */
    fun delete(id: String) {
        launchResult(
            block = { unified.deleteWorldBook(id) },
            onSuccess = {
                _books.value = _books.value.filterNot { it.id == id }
                showToast("已删除世界书")
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
    val books by viewModel.books.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }

    // 模式切换时自动刷新世界书列表
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    LaunchedEffect(appMode) { viewModel.load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("世界书", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建世界书", tint = MaterialTheme.colorScheme.primary)
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
                    title = "暂无世界书",
                    hint = "点击右上角新建一本世界书",
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
                    items(books, key = { it.id ?: it.name ?: it.hashCode().toString() }) { book ->
                        WorldBookItem(
                            book = book,
                            onClick = { book.id?.let { onOpenBook(it) } }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading && books.isEmpty())
        }
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
}

/** 单个世界书卡片 */
@Composable
private fun WorldBookItem(book: WorldBook, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
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
                    text = "条目数：${book.entries?.size ?: 0}",
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
        title = "新建世界书",
        confirmText = "创建",
        cancelText = "取消",
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            onConfirm(name.trim(), desc.trim().takeIf { it.isNotBlank() })
        },
        onCancel = onDismiss
    ) {
        Text("名称", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("描述", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
