package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.data.model.KnowledgeDocument
import com.nekobot.app.data.model.KnowledgeDocumentRequest
import com.nekobot.app.data.model.KnowledgeSearchRequest
import com.nekobot.app.data.model.KnowledgeSearchResult
import com.nekobot.app.data.model.KnowledgeStats
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.adaptive.listItemSemantics
import com.nekobot.app.ui.adaptive.rememberShouldUseTwoPane
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.components.StatChip
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.SuccessGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 知识库管理 ViewModel：负责文档 CRUD、索引重建与向量检索。
 */
class KnowledgeViewModel : BaseViewModel() {
    private val _list = MutableStateFlow<List<KnowledgeDocument>>(emptyList())
    val list: StateFlow<List<KnowledgeDocument>> = _list.asStateFlow()

    private val _stats = MutableStateFlow<KnowledgeStats?>(null)
    val stats: StateFlow<KnowledgeStats?> = _stats.asStateFlow()

    private val _searchResults = MutableStateFlow<List<KnowledgeSearchResult>>(emptyList())
    val searchResults: StateFlow<List<KnowledgeSearchResult>> = _searchResults.asStateFlow()

    init { load() }

    fun load() {
        launchResult(block = { unified.listKnowledge() }, onSuccess = { _list.value = it ?: emptyList() })
        launchResult(block = { unified.knowledgeStats() }, onSuccess = { _stats.value = it })
    }

    fun create(req: KnowledgeDocumentRequest) =
        launchResult(block = { unified.createKnowledge(req) }, onSuccess = { load() })

    fun update(id: String, req: KnowledgeDocumentRequest) =
        launchResult(block = { unified.updateKnowledge(id, req) }, onSuccess = { load() })

    fun delete(id: String) =
        launchResult(block = { unified.deleteKnowledge(id) }, onSuccess = { load() })

    fun indexDoc(id: String) =
        launchResult(block = { unified.indexKnowledge(id) }, onSuccess = { showToast(string(R.string.knowledge_index_rebuilt)); load() })

    fun rebuildAll() =
        launchResult(block = { unified.rebuildKnowledge() }, onSuccess = { showToast(string(R.string.knowledge_all_index_rebuilt)); load() })

    fun importDocument(contentResolver: ContentResolver, uri: Uri) =
        launchResult(
            block = {
                val (name, mimeType, bytes) = withContext(Dispatchers.IO) {
                    val displayName = contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: string(R.string.knowledge_import_document)
                    val data = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(string(R.string.knowledge_read_file_failed))
                    Triple(displayName, contentResolver.getType(uri), data)
                }
                unified.importKnowledge(name, mimeType, bytes)
            },
            onSuccess = {
                showToast(string(R.string.knowledge_import_indexed))
                load()
            }
        )

    fun search(query: String) =
        launchResult(
            block = { unified.searchKnowledge(KnowledgeSearchRequest(query = query)) },
            onSuccess = { _searchResults.value = it ?: emptyList() }
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val vm: KnowledgeViewModel = viewModel()
    val context = LocalContext.current
    val list by vm.list.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var showForm by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var deleteTarget by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var query by remember { mutableStateOf("") }
    // 双栏布局状态
    val useTwoPane = rememberShouldUseTwoPane()
    // 双栏模式下选中的文档（用于在右侧显示详情）
    var selectedDoc by remember { mutableStateOf<KnowledgeDocument?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.importDocument(context.contentResolver, it) }
    }

    val scaffoldContent: @Composable () -> Unit = {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.knowledge_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // RAG 检索设置入口
                    IconButton(onClick = { onNavigate(Routes.RAG_SETTINGS) }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.rag_settings_title)
                        )
                    }
                    IconButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "text/*",
                                    "application/pdf",
                                    "application/epub+zip",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                )
                            )
                        }
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = "导入文档")
                    }
                    TextButton(onClick = { vm.rebuildAll() }) {
                        Text(stringResource(R.string.knowledge_rebuild_all), color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        editingItem = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.knowledge_new_doc))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (list.isEmpty() && !loading) {
                EmptyState(title = stringResource(R.string.knowledge_empty_title), hint = stringResource(R.string.knowledge_empty_hint))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorBanner(message = error!!, onRetry = {
                                vm.clearError()
                                vm.load()
                            })
                        }
                    }
                    // 统计卡片
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatChip(label = stringResource(R.string.knowledge_stat_total), value = "${stats?.total ?: 0}", modifier = Modifier.weight(1f))
                            StatChip(label = stringResource(R.string.knowledge_stat_indexed), value = "${stats?.indexed ?: 0}", modifier = Modifier.weight(1f))
                            StatChip(label = stringResource(R.string.knowledge_stat_pending), value = "${stats?.pending ?: 0}", modifier = Modifier.weight(1f))
                        }
                    }
                    // 搜索栏
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text(stringResource(R.string.knowledge_search_docs)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { if (query.isNotBlank()) vm.search(query) }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    // 搜索结果
                    if (searchResults.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(R.string.knowledge_search_results), subtitle = stringResource(R.string.knowledge_search_count, searchResults.size))
                        }
                        items(searchResults, key = { it.id ?: it.title ?: it.hashCode().toString() }) { result ->
                            SearchResultCard(result = result)
                        }
                        item {
                            SectionHeader(title = stringResource(R.string.knowledge_doc_list))
                        }
                    }
                    // 文档列表
                    items(list, key = { it.id ?: it.hashCode().toString() }) { doc ->
                        KnowledgeCard(
                            doc = doc,
                            onClick = if (useTwoPane) {
                                { selectedDoc = doc }
                            } else null,
                            onEdit = { editingItem = doc; showForm = true },
                            onDelete = { deleteTarget = doc },
                            onReindex = { doc.id?.let { vm.indexDoc(it) } }
                        )
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }
    } // 结束 scaffoldContent lambda

    // 双栏布局：大屏时左列列表 + 右列详情/编辑
    if (useTwoPane) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(0.4f)
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
                    .weight(0.6f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                if (showForm) {
                    // 双栏模式下内联显示编辑表单
                    KnowledgeInlineForm(
                        initial = editingItem,
                        onConfirm = { req ->
                            editingItem?.id?.let { vm.update(it, req) } ?: vm.create(req)
                            showForm = false
                            editingItem = null
                        },
                        onDismiss = {
                            showForm = false
                            editingItem = null
                        }
                    )
                } else {
                    selectedDoc?.let { doc ->
                        // 显示选中文档的详情
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                doc.displayName,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "来源：${doc.source ?: "—"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (doc.tags.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "标签：${doc.tags.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "创建时间：${doc.createdAt ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                doc.content ?: "无内容",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } ?: Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "选择一个文档查看详情",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    } else {
        scaffoldContent()
    }

    // 新建/编辑表单弹窗（仅单栏模式使用对话框）
    if (showForm && !useTwoPane) {
        KnowledgeFormDialog(
            initial = editingItem,
            onConfirm = { req ->
                editingItem?.id?.let { vm.update(it, req) } ?: vm.create(req)
                showForm = false
                editingItem = null
            },
            onDismiss = {
                showForm = false
                editingItem = null
            }
        )
    }

    // 删除确认弹窗
    deleteTarget?.let { target ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.knowledge_confirm_delete),
            message = stringResource(R.string.knowledge_confirm_delete_msg, target.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                target.id?.let { vm.delete(it) }
                deleteTarget = null
            }
        )
    }
}

/**
 * 知识库文档卡片
 */
@Composable
private fun KnowledgeCard(
    doc: KnowledgeDocument,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReindex: () -> Unit,
    onClick: (() -> Unit)? = null
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            )
            .listItemSemantics(
                stringResource(
                    R.string.knowledge_document_description,
                    doc.displayName,
                    doc.source ?: stringResource(R.string.common_unknown)
                )
            )
    ) {
        // 顶部行：标题 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = doc.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.knowledge_action), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.knowledge_reindex)) }, onClick = { menuExpanded = false; onReindex() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete), color = ErrorRed) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.knowledge_source, doc.source ?: "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (doc.tags.isNotEmpty()) {
            Text(stringResource(R.string.knowledge_tags, doc.tags.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.knowledge_created_at, doc.createdAt ?: "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 搜索结果卡片：展示标题、内容片段与相似度分数。
 */
@Composable
private fun SearchResultCard(result: KnowledgeSearchResult) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = result.title ?: stringResource(R.string.knowledge_unnamed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            result.score?.let {
                Box(
                    modifier = Modifier
                        .background(SuccessGreen.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.knowledge_similarity, "%.2f".format(it)), style = MaterialTheme.typography.labelSmall, color = SuccessGreen)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        result.content?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        }
        result.source?.let {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.knowledge_source, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 双栏模式下的内联文档编辑表单（不使用对话框，直接渲染在右栏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeInlineForm(
    initial: KnowledgeDocument?,
    onConfirm: (KnowledgeDocumentRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var source by remember { mutableStateOf(initial?.source ?: "") }
    var tags by remember { mutableStateOf(initial?.tags?.joinToString(", ") ?: "") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            if (initial == null) stringResource(R.string.knowledge_new_doc) else stringResource(R.string.knowledge_edit_doc),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.knowledge_title_required)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text(stringResource(R.string.knowledge_content_required)) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text(stringResource(R.string.knowledge_source_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            label = { Text(stringResource(R.string.knowledge_tags_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (title.isBlank() || content.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.knowledge_title_content_required), Toast.LENGTH_SHORT).show()
                } else {
                    val req = KnowledgeDocumentRequest(
                        title = title,
                        content = content,
                        source = source.ifBlank { null },
                        tags = tags.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
                    )
                    onConfirm(req)
                }
            }) {
                Text(stringResource(R.string.common_save))
            }
        }
    }
}

/**
 * 文档新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeFormDialog(
    initial: KnowledgeDocument?,
    onConfirm: (KnowledgeDocumentRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var source by remember { mutableStateOf(initial?.source ?: "") }
    var tags by remember { mutableStateOf(initial?.tags?.joinToString(", ") ?: "") }
    val context = LocalContext.current

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) stringResource(R.string.knowledge_new_doc) else stringResource(R.string.knowledge_edit_doc),
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (title.isBlank() || content.isBlank()) {
                Toast.makeText(context, context.getString(R.string.knowledge_title_content_required), Toast.LENGTH_SHORT).show()
            } else {
                val req = KnowledgeDocumentRequest(
                    title = title,
                    content = content,
                    source = source.ifBlank { null },
                    tags = tags.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
                )
                onConfirm(req)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.knowledge_title_required)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.knowledge_content_required)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                label = { Text(stringResource(R.string.knowledge_source_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text(stringResource(R.string.knowledge_tags_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
