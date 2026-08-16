package com.nekobot.app.ui.screens.memory

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.nekobot.app.ui.components.BorderlessAssistChip as AssistChip
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.data.model.LegacyMemory
import com.nekobot.app.data.model.LegacyMemoryRequest
import com.nekobot.app.data.model.MemoryFile
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 记忆分类显示标签（按 categoryOrder 排序）
private val CATEGORY_LABELS = linkedMapOf(
    "user_persona" to R.string.memory_category_user_persona,
    "character_persona" to R.string.memory_category_character_persona,
    "important_event" to R.string.memory_category_important_event,
    "timeline" to R.string.memory_category_timeline,
    "life_sim" to R.string.memory_category_life_sim,
    "recent_digest" to R.string.memory_category_recent_digest,
    "legacy" to R.string.memory_category_legacy
)

private val PRIORITY_OPTIONS = listOf(
    "high" to R.string.memory_priority_high,
    "normal" to R.string.memory_priority_normal,
    "low" to R.string.memory_priority_low
)

/**
 * 角色记忆页 ViewModel：管理 MemoryFS 文件与旧版记忆的加载、删除、增改。
 */
class MemoryViewModel : com.nekobot.app.ui.BaseViewModel() {

    private val _files = MutableStateFlow<List<MemoryFile>>(emptyList())
    val files: StateFlow<List<MemoryFile>> = _files.asStateFlow()

    private val _legacy = MutableStateFlow<List<LegacyMemory>>(emptyList())
    val legacy: StateFlow<List<LegacyMemory>> = _legacy.asStateFlow()

    private val _selectedCharacterId = MutableStateFlow<String?>(null)
    val selectedCharacterId: StateFlow<String?> = _selectedCharacterId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _expandedPaths = MutableStateFlow<Set<String>>(emptySet())
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    private val _showAddDialog = MutableStateFlow(false)
    val showAddDialog: StateFlow<Boolean> = _showAddDialog.asStateFlow()

    private val _editingLegacy = MutableStateFlow<LegacyMemory?>(null)
    val editingLegacy: StateFlow<LegacyMemory?> = _editingLegacy.asStateFlow()

    init { load() }

    /** 加载 MemoryFS 与旧版记忆 */
    fun load() {
        if (isLocalMode) {
            // 本地模式：从 LocalRepository 加载所有角色的记忆（characterId=null），
            // 角色筛选由 UI 层根据 selectedCharacterId 完成，避免 DB 层过滤后 characterOptions 缺失
            launchResult(
                block = {
                    val memories = com.nekobot.app.ServiceContainer.localRepository
                        .listMemories(null)
                    com.nekobot.app.data.repository.Resource.Success(
                        com.nekobot.app.data.model.LegacyMemoryListResponse(memories = memories)
                    )
                },
                onSuccess = { resp ->
                    val all = resp?.memories
                        ?: (resp?.longTerm.orEmpty() + resp?.shortTerm.orEmpty())
                    _legacy.value = all
                    // 按 category 分组构建 MemoryFS 文件视图
                    _files.value = buildLocalMemoryFsFiles(all)
                }
            )
        } else {
            // 远程模式：从服务器加载
            launchResult(
                block = { repo.listMemoryFs(_selectedCharacterId.value) },
                onSuccess = { resp -> _files.value = resp?.files ?: emptyList() }
            )
            launchResult(
                block = { repo.listLegacyMemory() },
                onSuccess = { resp ->
                    _legacy.value = resp?.memories
                        ?: (resp?.longTerm.orEmpty() + resp?.shortTerm.orEmpty())
                }
            )
        }
    }

    /**
     * 本地模式：按 MemoryFS 类别元数据将扁平记忆列表分组为文件视图。
     *
     * 对齐原仓库 fs.py 的文件结构：以"角色 × 类别"为粒度，每个 MemoryFile 对应一个
     * 逻辑路径（如 `characters/森亚露露卡/users/asuka/character_persona.md`）。
     *
     * 优先使用 LegacyMemory.category（真实 memoryfs category），
     * 若为空则回退到 type → category 的旧映射（兼容历史数据）。
     */
    private fun buildLocalMemoryFsFiles(memories: List<LegacyMemory>): List<MemoryFile> {
        if (memories.isEmpty()) return emptyList()
        // 第一层：按角色分组（characterName 可能为空，统一归到 "未知角色"）
        val byCharacter = memories.groupBy {
            it.characterName.ifBlank { string(R.string.memory_unknown_character) }
        }
        val result = mutableListOf<MemoryFile>()
        for ((charName, charMems) in byCharacter) {
            // 第二层：按 category 分组
            val grouped = charMems.groupBy { mem ->
                mem.category?.takeIf { it.isNotBlank() } ?: when (mem.type) {
                    "long" -> "important_event"
                    "short" -> "recent_digest"
                    else -> "legacy"
                }
            }
            for (meta in com.nekobot.app.data.local.ai.MEMORY_CATEGORY_META) {
                val entries = grouped[meta.key] ?: continue
                if (entries.isEmpty()) continue
                val content = entries.joinToString("\n\n") { e ->
                    val title = e.title?.trim().orEmpty()
                    val body = e.content?.trim().orEmpty()
                    if (title.isNotEmpty()) "[$title]\n$body" else body
                }
                // 构造真实 memoryfs 路径（仅用于 UI 展示与区分角色）
                // 对齐原仓库路径规范：characters/{charName}/(users/{targetId}|events|life_sim|)/{category}.md
                val targetId = entries.firstOrNull()?.targetId?.takeIf { it.isNotBlank() } ?: "local-user"
                val path = buildLocalMemoryPath(meta.key, charName, targetId, entries.firstOrNull())
                MemoryFile(
                    path = path,
                    characterId = charName,  // 本地模式用角色名做 ID（远程模式用真实 ID）
                    targetId = targetId,
                    title = meta.label,
                    content = content,
                    summary = string(R.string.memory_count_summary, entries.size),
                    category = meta.key,
                    categoryLabel = meta.label,
                    categoryOrder = meta.order,
                    injectsToPrompt = meta.injectsToPrompt
                ).also { result.add(it) }
            }
        }
        // 按 categoryOrder 排序，同一 category 内按角色名排序
        return result.sortedWith(compareBy({ it.categoryOrder }, { it.characterId }))
    }

    /** 构造本地模式 MemoryFS 展示路径（对齐原仓库 fs.py 路径规范） */
    private fun buildLocalMemoryPath(
        category: String,
        charName: String,
        targetId: String,
        sample: LegacyMemory?
    ): String {
        // timeline / life_sim 是跨会话的，不按 targetId 隔离
        return when (category) {
            "user_persona" -> "characters/$charName/users/$targetId/user_persona.md"
            "character_persona" -> "characters/$charName/users/$targetId/character_persona.md"
            "important_event" -> "characters/$charName/events/${sample?.id ?: "general"}.md"
            "timeline" -> "characters/$charName/timeline.md"
            "life_sim" -> "characters/$charName/life_sim/${sample?.id ?: "general"}.md"
            "recent_digest" -> "characters/$charName/users/$targetId/recent_digest.md"
            else -> "characters/$charName/users/$targetId/legacy.md"
        }
    }

    /** 选择角色（null 表示全部） */
    fun selectCharacter(id: String?) {
        _selectedCharacterId.value = id
        load()
    }

    /** 更新搜索关键词 */
    fun setSearchQuery(q: String) { _searchQuery.value = q }

    /** 展开/折叠文件内容 */
    fun toggleExpand(path: String) {
        _expandedPaths.value = _expandedPaths.value.toMutableSet().apply {
            if (contains(path)) remove(path) else add(path)
        }
    }

    /** 删除 MemoryFS 文件 */
    fun deleteMemoryFile(path: String) {
        if (isLocalMode) {
            // 本地模式：MemoryFS 文件由记忆聚合而来，按 path 匹配删除
            val target = _files.value.find { it.path == path } ?: return
            // 本地 MemoryFS 文件的 path 即 memoryId
            launchResult(
                block = {
                    com.nekobot.app.ServiceContainer.localRepository.deleteMemory(path)
                    com.nekobot.app.data.repository.Resource.Success(null)
                },
                onSuccess = {
                    _files.value = _files.value.filterNot { it.path == path }
                    showToast(string(R.string.memory_deleted_file))
                }
            )
        } else {
            launchResult(
                block = { repo.deleteMemoryFs(path, _selectedCharacterId.value) },
                onSuccess = {
                    _files.value = _files.value.filterNot { it.path == path }
                    showToast(string(R.string.memory_deleted_file))
                }
            )
        }
    }

    /** 删除旧版记忆 */
    fun deleteLegacy(id: String) {
        if (isLocalMode) {
            launchResult(
                block = {
                    com.nekobot.app.ServiceContainer.localRepository.deleteMemory(id)
                    com.nekobot.app.data.repository.Resource.Success(null)
                },
                onSuccess = {
                    _legacy.value = _legacy.value.filterNot { it.id == id }
                    showToast(string(R.string.memory_deleted))
                }
            )
        } else {
            launchResult(
                block = { repo.deleteLegacyMemory(id) },
                onSuccess = {
                    _legacy.value = _legacy.value.filterNot { it.id == id }
                    showToast(string(R.string.memory_deleted))
                }
            )
        }
    }

    /** 打开新增对话框 */
    fun startAddLegacy() {
        _editingLegacy.value = null
        _showAddDialog.value = true
    }

    /** 打开编辑对话框 */
    fun startEditLegacy(memory: LegacyMemory) {
        _editingLegacy.value = memory
        _showAddDialog.value = true
    }

    /** 关闭对话框 */
    fun dismissDialog() {
        _showAddDialog.value = false
        _editingLegacy.value = null
    }

    /** 保存（新增/更新）旧版记忆 */
    fun saveLegacy(req: LegacyMemoryRequest) {
        val editing = _editingLegacy.value
        if (isLocalMode) {
            launchResult(
                block = {
                    com.nekobot.app.ServiceContainer.localRepository.saveMemory(
                        id = editing?.id,
                        title = req.title,
                        content = req.content,
                        summary = req.summary ?: "",
                        type = req.type ?: "long",
                        priority = req.priority ?: "normal",
                        characterId = _selectedCharacterId.value
                    )
                    com.nekobot.app.data.repository.Resource.Success(null)
                },
                onSuccess = {
                    load()
                    _showAddDialog.value = false
                    _editingLegacy.value = null
                    showToast(if (editing != null) string(R.string.memory_updated) else string(R.string.memory_added))
                }
            )
        } else {
            if (editing?.id != null) {
                launchResult(
                    block = {
                        val body = com.google.gson.JsonParser.parseString(
                            com.google.gson.Gson().toJson(req)
                        )
                        repo.updateLegacyMemory(editing.id, body)
                    },
                    onSuccess = {
                        load()
                        _showAddDialog.value = false
                        _editingLegacy.value = null
                        showToast(string(R.string.memory_updated))
                    }
                )
            } else {
                launchResult(
                    block = { repo.createLegacyMemory(req) },
                    onSuccess = {
                        load()
                        _showAddDialog.value = false
                        showToast(string(R.string.memory_added))
                    }
                )
            }
        }
    }

    /** 导出旧版记忆（重新拉取列表后展示） */
    fun exportLegacy() {
        if (isLocalMode) {
            // 本地模式：重新从本地加载
            load()
            showToast(string(R.string.memory_refreshed))
        } else {
            launchResult(
                block = { repo.exportLegacyMemory() },
                onSuccess = {
                    _legacy.value = it?.memories
                        ?: (it?.longTerm.orEmpty() + it?.shortTerm.orEmpty())
                    showToast(string(R.string.memory_refreshed))
                }
            )
        }
    }
}

/**
 * 角色记忆页：展示 MemoryFS 文件与旧版记忆，支持按角色筛选、搜索、增删改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    viewModel: MemoryViewModel = viewModel()
) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val legacy by viewModel.legacy.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selectedChar by viewModel.selectedCharacterId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val expandedPaths by viewModel.expandedPaths.collectAsStateWithLifecycle()
    val showAddDialog by viewModel.showAddDialog.collectAsStateWithLifecycle()
    val editingLegacy by viewModel.editingLegacy.collectAsStateWithLifecycle()

    var showExportMenu by remember { mutableStateOf(false) }
    var deleteFile by remember { mutableStateOf<MemoryFile?>(null) }
    var deleteLegacyItem by remember { mutableStateOf<LegacyMemory?>(null) }

    // 角色选项：从 MemoryFS 文件中提取（本地模式 characterId 即角色名）
    val characterOptions = remember(files) {
        val map = linkedMapOf<String, String>()
        files.forEach { f ->
            val id = f.characterId
            if (!id.isNullOrBlank()) {
                // 本地模式 characterId 直接是角色名，显示更友好
                map[id] = id
            }
        }
        map
    }

    // 搜索 + 角色筛选
    val filteredFiles = remember(files, searchQuery, selectedChar) {
        var result = files
        if (selectedChar != null) {
            result = result.filter { it.characterId == selectedChar }
        }
        if (searchQuery.isNotBlank()) {
            result = result.filter {
                it.title.contains(searchQuery, true) ||
                it.summary.contains(searchQuery, true) ||
                it.content.contains(searchQuery, true) ||
                it.path.contains(searchQuery, true)
            }
        }
        result
    }
    val filteredLegacy = remember(legacy, searchQuery, selectedChar) {
        var result = legacy
        if (selectedChar != null) {
            // 本地模式 LegacyMemory.characterName 即角色名，与 MemoryFile.characterId 对齐
            result = result.filter { it.characterName == selectedChar }
        }
        if (searchQuery.isNotBlank()) {
            result = result.filter {
                it.title.contains(searchQuery, true) ||
                it.content.contains(searchQuery, true) ||
                (it.summary?.contains(searchQuery, true) == true)
            }
        }
        result
    }

    // MemoryFS 文件按 category 分组
    val groupedFiles = remember(filteredFiles) {
        filteredFiles.groupBy { it.category }
            .toSortedMap(compareBy { cat ->
                filteredFiles.firstOrNull { it.category == cat }?.categoryOrder ?: 99
            })
    }
    // 旧版记忆按 type 分组
    // 注意：已被分类到 MemoryFS 文件视图的记忆（category=user_persona/character_persona/important_event/timeline/life_sim/recent_digest）
    // 不在"旧版记忆"区重复展示，只显示真正的旧版（category 为空/null/"legacy"）
    val isLegacyCategory = { cat: String? -> cat.isNullOrBlank() || cat == "legacy" }
    val longTermLegacy = filteredLegacy.filter { it.type == "long" && isLegacyCategory(it.category) }
    val shortTermLegacy = filteredLegacy.filter { it.type == "short" && isLegacyCategory(it.category) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memory_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.memory_refresh), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { viewModel.startAddLegacy() }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.memory_add_legacy), tint = MaterialTheme.colorScheme.primary)
                    }
                    Box {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.memory_more), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.memory_export_legacy)) },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportLegacy()
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
            if (files.isEmpty() && legacy.isEmpty() && !loading) {
                EmptyState(
                    title = stringResource(R.string.memory_empty_title),
                    hint = stringResource(R.string.memory_empty_hint),
                    icon = {
                        Icon(
                            Icons.Filled.Psychology,
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

                    // 搜索框
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text(stringResource(R.string.memory_search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
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

                    // 角色筛选 Chip 行
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedChar == null,
                                onClick = { viewModel.selectCharacter(null) },
                                label = { Text(stringResource(R.string.memory_filter_all)) }
                            )
                            characterOptions.forEach { (id, label) ->
                                FilterChip(
                                    selected = selectedChar == id,
                                    onClick = { viewModel.selectCharacter(id) },
                                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }

                    // MemoryFS 分组
                    groupedFiles.forEach { (category, list) ->
                        item {
                            val labelRes = CATEGORY_LABELS[category]
                            val label = if (labelRes != null) stringResource(labelRes) else category
                            SectionHeader(
                                title = label,
                                subtitle = stringResource(R.string.memory_count_format, list.size)
                            )
                        }
                        items(list, key = { "fs_${it.path}" }) { file ->
                            MemoryFileItem(
                                file = file,
                                expanded = expandedPaths.contains(file.path),
                                onToggle = { viewModel.toggleExpand(file.path) },
                                onDelete = { deleteFile = file }
                            )
                        }
                    }

                    // 旧版记忆 - 长期
                    if (longTermLegacy.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(R.string.memory_long_term_legacy), subtitle = stringResource(R.string.memory_count_format, longTermLegacy.size))
                        }
                        items(longTermLegacy, key = { "lt_${it.id ?: it.hashCode()}" }) { mem ->
                            LegacyMemoryItem(
                                memory = mem,
                                onEdit = { viewModel.startEditLegacy(mem) },
                                onDelete = { deleteLegacyItem = mem }
                            )
                        }
                    }

                    // 旧版记忆 - 短期
                    if (shortTermLegacy.isNotEmpty()) {
                        item {
                            SectionHeader(title = stringResource(R.string.memory_short_term_legacy), subtitle = stringResource(R.string.memory_count_format, shortTermLegacy.size))
                        }
                        items(shortTermLegacy, key = { "st_${it.id ?: it.hashCode()}" }) { mem ->
                            LegacyMemoryItem(
                                memory = mem,
                                onEdit = { viewModel.startEditLegacy(mem) },
                                onDelete = { deleteLegacyItem = mem }
                            )
                        }
                    }

                    if (groupedFiles.isEmpty() && longTermLegacy.isEmpty() && shortTermLegacy.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.memory_no_match),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 删除 MemoryFS 文件确认
    if (deleteFile != null) {
        val unnamed = stringResource(R.string.memory_delete_file_unnamed)
        NekoDialog(
            onDismiss = { deleteFile = null },
            title = stringResource(R.string.memory_delete_file_title),
            message = stringResource(R.string.memory_delete_file_msg, deleteFile?.title?.ifBlank { deleteFile?.path } ?: unnamed),
            confirmText = stringResource(R.string.common_delete),
            cancelText = stringResource(R.string.common_cancel),
            onConfirm = {
                deleteFile?.path?.let { viewModel.deleteMemoryFile(it) }
                deleteFile = null
            },
            onCancel = { deleteFile = null }
        )
    }

    // 删除旧版记忆确认
    if (deleteLegacyItem != null) {
        NekoDialog(
            onDismiss = { deleteLegacyItem = null },
            title = stringResource(R.string.memory_delete_memory_title),
            message = stringResource(R.string.memory_delete_memory_msg, deleteLegacyItem?.title ?: ""),
            confirmText = stringResource(R.string.common_delete),
            cancelText = stringResource(R.string.common_cancel),
            onConfirm = {
                deleteLegacyItem?.id?.let { viewModel.deleteLegacy(it) }
                deleteLegacyItem = null
            },
            onCancel = { deleteLegacyItem = null }
        )
    }

    // 新增/编辑旧版记忆对话框
    if (showAddDialog) {
        LegacyMemoryDialog(
            editing = editingLegacy,
            onDismiss = { viewModel.dismissDialog() },
            onSave = { viewModel.saveLegacy(it) }
        )
    }
}

/** MemoryFS 文件卡片 */
@Composable
private fun MemoryFileItem(
    file: MemoryFile,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = file.title.ifBlank { file.path.substringAfterLast('/') },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (file.injectsToPrompt) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.memory_inject), style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                labelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
                // 角色名 + 路径：模仿原仓库展示 characters/{charName}/users/{targetId}/{category}.md
                // 让用户能直观区分属于哪个角色
                Spacer(Modifier.height(4.dp))
                if (file.characterId.isNotBlank()) {
                    AssistChip(
                        onClick = {},
                        label = { Text(file.characterId, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = RoundedCornerShape(8.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (file.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = file.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    if (file.content.isNotBlank()) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = file.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                if (file.updatedAt.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.memory_updated_at, file.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.markdown_collapse) else stringResource(R.string.markdown_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 旧版记忆卡片 */
@Composable
private fun LegacyMemoryItem(
    memory: LegacyMemory,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = memory.title.ifBlank { stringResource(R.string.memory_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!memory.summary.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = memory.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (memory.characterName.isNotBlank()) {
                        AssistChip(
                            onClick = {},
                            label = { Text(memory.characterName, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    }
                    if (memory.targetId.isNotBlank()) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.memory_target_label, memory.targetId), style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    val priorityRes = PRIORITY_OPTIONS.firstOrNull { it.first == memory.priority }?.second
                    val priorityLabel = if (priorityRes != null) stringResource(priorityRes) else memory.priority
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.memory_priority_label, priorityLabel), style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (memory.priority == "high")
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = if (memory.priority == "high")
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.common_edit), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 新增/编辑旧版记忆对话框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyMemoryDialog(
    editing: LegacyMemory?,
    onDismiss: () -> Unit,
    onSave: (LegacyMemoryRequest) -> Unit
) {
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var content by remember { mutableStateOf(editing?.content ?: "") }
    var summary by remember { mutableStateOf(editing?.summary ?: "") }
    var type by remember { mutableStateOf(editing?.type ?: "long") }
    var priority by remember { mutableStateOf(editing?.priority ?: "normal") }
    var expireDays by remember { mutableStateOf((editing?.expireDays ?: 7).toString()) }
    var characterName by remember { mutableStateOf(editing?.characterName ?: "") }
    var targetId by remember { mutableStateOf(editing?.targetId ?: "") }

    var typeExpanded by remember { mutableStateOf(false) }
    var priorityExpanded by remember { mutableStateOf(false) }

    NekoDialog(
        onDismiss = onDismiss,
        title = if (editing == null) stringResource(R.string.memory_add_title) else stringResource(R.string.memory_edit_title),
        confirmText = stringResource(R.string.common_save),
        cancelText = stringResource(R.string.common_cancel),
        onConfirm = {
            if (title.isBlank() || content.isBlank()) return@NekoDialog
            onSave(
                LegacyMemoryRequest(
                    title = title.trim(),
                    content = content.trim(),
                    summary = summary.trim().takeIf { it.isNotBlank() },
                    type = type,
                    priority = priority,
                    expireDays = expireDays.toIntOrNull() ?: 7,
                    targetId = targetId.trim(),
                    characterName = characterName.trim()
                )
            )
        },
        onCancel = onDismiss
    ) {
        // 类型选择
        Text(stringResource(R.string.memory_field_type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = it }
        ) {
            OutlinedTextField(
                value = if (type == "long") stringResource(R.string.memory_type_long) else stringResource(R.string.memory_type_short),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                colors = fieldColors()
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false }
            ) {
                DropdownMenuItem(text = { Text(stringResource(R.string.memory_type_long)) }, onClick = { type = "long"; typeExpanded = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.memory_type_short)) }, onClick = { type = "short"; typeExpanded = false })
            }
        }
        Spacer(Modifier.height(10.dp))

        // 角色名
        Text(stringResource(R.string.memory_field_character_name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(value = characterName, onValueChange = { characterName = it }, singleLine = true)
        Spacer(Modifier.height(10.dp))

        // 关联对象
        Text(stringResource(R.string.memory_field_target_id), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(value = targetId, onValueChange = { targetId = it }, singleLine = true)
        Spacer(Modifier.height(10.dp))

        // 标题
        Text(stringResource(R.string.memory_field_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(value = title, onValueChange = { title = it }, singleLine = true)
        Spacer(Modifier.height(10.dp))

        // 摘要
        Text(stringResource(R.string.memory_field_summary), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(value = summary, onValueChange = { summary = it }, singleLine = false, minLines = 2, maxLines = 4)
        Spacer(Modifier.height(10.dp))

        // 内容
        Text(stringResource(R.string.memory_field_content), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(value = content, onValueChange = { content = it }, singleLine = false, minLines = 3, maxLines = 8)
        Spacer(Modifier.height(10.dp))

        // 优先级
        Text(stringResource(R.string.memory_field_priority), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = priorityExpanded,
            onExpandedChange = { priorityExpanded = it }
        ) {
            val priorityRes = PRIORITY_OPTIONS.firstOrNull { it.first == priority }?.second
            OutlinedTextField(
                value = if (priorityRes != null) stringResource(priorityRes) else priority,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = priorityExpanded) },
                colors = fieldColors()
            )
            ExposedDropdownMenu(
                expanded = priorityExpanded,
                onDismissRequest = { priorityExpanded = false }
            ) {
                PRIORITY_OPTIONS.forEach { (v, res) ->
                    DropdownMenuItem(text = { Text(stringResource(res)) }, onClick = { priority = v; priorityExpanded = false })
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // 有效期天数
        Text(stringResource(R.string.memory_field_expire_days), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        NekoTextField(
            value = expireDays,
            onValueChange = { expireDays = it.filter { c -> c.isDigit() } },
            singleLine = true,
            keyboardType = KeyboardType.Number
        )
    }
}

/** 统一样式的输入框 */
@Composable
private fun NekoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = fieldColors()
    )
}

/** 统一 OutlinedTextField 配色 */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
)
