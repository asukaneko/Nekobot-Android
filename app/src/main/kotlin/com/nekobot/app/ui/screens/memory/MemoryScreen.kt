package com.nekobot.app.ui.screens.memory

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.nekobot.app.ui.components.ErrorToast
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.delay
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

/** 各记忆类别对应的图标，便于在列表中快速识别 */
private fun categoryIcon(category: String): ImageVector = when (category) {
    "user_persona" -> Icons.Filled.Person
    "character_persona" -> Icons.Filled.Face
    "important_event" -> Icons.Filled.Star
    "timeline" -> Icons.Filled.Timeline
    "life_sim" -> Icons.Filled.Bedtime
    "recent_digest" -> Icons.Filled.History
    else -> Icons.Filled.Memory
}

/** 各记忆类别对应的强调色（取自主题，保证深浅色模式下协调） */
@Composable
private fun categoryAccent(category: String): Color = when (category) {
    "user_persona" -> MaterialTheme.colorScheme.tertiary
    "character_persona" -> MaterialTheme.colorScheme.primary
    "important_event" -> MaterialTheme.colorScheme.secondary
    "timeline" -> MaterialTheme.colorScheme.tertiary
    "life_sim" -> MaterialTheme.colorScheme.secondary
    "recent_digest" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 记忆列表的一行：标题 / MemoryFS 文件 / 单条记忆 / 空提示统一建模。
 *
 * 全局搜索跳转时按 [focusId] 找到对应行，滚动并在展开后短暂高亮，
 * 因此列表渲染与定位共用同一份行数据，避免下标与真实列表错位。
 */
private sealed class MemoryRow {
    abstract val key: String

    /** 与全局搜索的 memory id 对齐：MemoryFS 文件用 path，单条记忆用 id。 */
    open val focusId: String? get() = null

    /** 定位后需要自动展开的 expandedPaths key。 */
    open val expandKey: String? get() = null

    class Header(
        override val key: String,
        @StringRes val titleRes: Int?,
        val fallbackTitle: String,
        val count: Int
    ) : MemoryRow()

    class FileRow(val file: MemoryFile) : MemoryRow() {
        override val key get() = "fs_${file.path}"
        override val focusId get() = file.path
        override val expandKey get() = file.path
    }

    class EntryRow(
        val memory: LegacyMemory,
        override val key: String,
        override val expandKey: String,
        /** 本地模式的单条记忆可编辑标签；远程旧版记忆只读展示。 */
        val editableTags: Boolean
    ) : MemoryRow() {
        // 与全局搜索的 SearchableMemory 保持一致：服务端没给 id 时用角色/标题/内容哈希合成
        override val focusId get() = memory.id
            ?: "legacy:${memory.characterName}:${memory.title}:${memory.content.hashCode()}"
    }

    object EmptyRow : MemoryRow() {
        override val key get() = "empty"
    }

    object ErrorRow : MemoryRow() {
        override val key get() = "error"
    }
}

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

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    private val _editingTags = MutableStateFlow<LegacyMemory?>(null)
    val editingTags: StateFlow<LegacyMemory?> = _editingTags.asStateFlow()

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
                    if (_selectedTag.value != null && all.none { memory ->
                            memory.tags.orEmpty().any { tag ->
                                tag.equals(_selectedTag.value, ignoreCase = true)
                            }
                        }) {
                        _selectedTag.value = null
                    }
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
            val grouped = charMems.groupBy { mem -> memoryCategoryOf(mem) }
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

    /** 计算记忆条目归属的 memoryfs category（与 buildLocalMemoryFsFiles 分组逻辑一致） */
    private fun memoryCategoryOf(mem: LegacyMemory): String =
        mem.category?.takeIf { it.isNotBlank() } ?: when (mem.type) {
            "long" -> "important_event"
            "short" -> "recent_digest"
            else -> "legacy"
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

    fun selectTag(tag: String?) { _selectedTag.value = tag }

    fun startEditTags(memory: LegacyMemory) { _editingTags.value = memory }

    fun dismissTagEditor() { _editingTags.value = null }

    fun saveTags(tags: List<String>) {
        val id = _editingTags.value?.id ?: return
        launchResult(
            block = {
                val saved = com.nekobot.app.ServiceContainer.localRepository.updateMemoryTags(id, tags)
                com.nekobot.app.data.repository.Resource.Success(saved)
            },
            onSuccess = { saved ->
                if (saved == true) {
                    _editingTags.value = null
                    load()
                    showToast(string(R.string.memory_tags_saved))
                } else {
                    showError(string(R.string.memory_tags_missing))
                }
            }
        )
    }

    /** 展开/折叠文件内容 */
    fun toggleExpand(path: String) {
        _expandedPaths.value = _expandedPaths.value.toMutableSet().apply {
            if (contains(path)) remove(path) else add(path)
        }
    }

    /** 展开指定条目（全局搜索跳转定位时使用，已展开则不变）。 */
    fun expand(path: String) {
        if (_expandedPaths.value.contains(path)) return
        _expandedPaths.value = _expandedPaths.value + path
    }

    /** 删除 MemoryFS 文件 */
    fun deleteMemoryFile(path: String) {
        if (isLocalMode) {
            // 本地模式：MemoryFS 文件是"角色 × 类别"的聚合视图，
            // path 仅为展示路径，需找出该分组下所有底层记忆逐条删除
            val target = _files.value.find { it.path == path } ?: return
            val unknownLabel = string(R.string.memory_unknown_character)
            val ids = _legacy.value
                .filter { mem ->
                    val groupKey = mem.characterName.ifBlank { unknownLabel }
                    groupKey == target.characterId && memoryCategoryOf(mem) == target.category
                }
                .mapNotNull { it.id }
                .toSet()
            launchResult(
                block = {
                    ids.forEach { com.nekobot.app.ServiceContainer.localRepository.deleteMemory(it) }
                    com.nekobot.app.data.repository.Resource.Success(null)
                },
                onSuccess = {
                    _files.value = _files.value.filterNot { it.path == path }
                    _legacy.value = _legacy.value.filterNot { it.id in ids }
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
                    // 底层记忆变化后同步重建文件视图，保持两个列表一致
                    _files.value = buildLocalMemoryFsFiles(_legacy.value)
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
    /** 全局搜索跳转时携带的记忆 id（本地为记忆 id，远程 MemoryFS 文件为路径）。 */
    focusId: String? = null,
    viewModel: MemoryViewModel = viewModel()
) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val legacy by viewModel.legacy.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selectedChar by viewModel.selectedCharacterId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val editingTags by viewModel.editingTags.collectAsStateWithLifecycle()
    val expandedPaths by viewModel.expandedPaths.collectAsStateWithLifecycle()
    val showAddDialog by viewModel.showAddDialog.collectAsStateWithLifecycle()
    val editingLegacy by viewModel.editingLegacy.collectAsStateWithLifecycle()

    var showMenu by remember { mutableStateOf(false) }
    var deleteFile by remember { mutableStateOf<MemoryFile?>(null) }
    var deleteLegacyItem by remember { mutableStateOf<LegacyMemory?>(null) }
    val isLocalMode = com.nekobot.app.ServiceContainer.prefs.isLocalMode

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
    val filteredFiles = remember(files, searchQuery, selectedChar, selectedTag) {
        var result = if (selectedTag == null) files else emptyList()
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
    val filteredLegacy = remember(legacy, searchQuery, selectedChar, selectedTag) {
        var result = legacy
        if (selectedChar != null) {
            // 本地模式 LegacyMemory.characterName 即角色名，与 MemoryFile.characterId 对齐
            result = result.filter { it.characterName == selectedChar }
        }
        if (searchQuery.isNotBlank()) {
            result = result.filter {
                it.title.contains(searchQuery, true) ||
                it.content.contains(searchQuery, true) ||
                (it.summary?.contains(searchQuery, true) == true) ||
                it.tags.orEmpty().any { tag -> tag.contains(searchQuery, true) }
            }
        }
        if (selectedTag != null) {
            result = result.filter { memory ->
                memory.tags.orEmpty().any { it.equals(selectedTag, ignoreCase = true) }
            }
        }
        result
    }

    val tagOptions = remember(legacy, selectedChar) {
        legacy.asSequence()
            .filter { selectedChar == null || it.characterName == selectedChar }
            .flatMap { it.tags.orEmpty().asSequence() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
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
    val longTermLegacy = if (isLocalMode) emptyList() else
        filteredLegacy.filter { it.type == "long" && isLegacyCategory(it.category) }
    val shortTermLegacy = if (isLocalMode) emptyList() else
        filteredLegacy.filter { it.type == "short" && isLegacyCategory(it.category) }

    // 列表行：渲染与搜索定位共用，保证滚动下标和真实内容一致
    val rows: List<MemoryRow> = buildList {
        if (error != null) add(MemoryRow.ErrorRow)
        groupedFiles.forEach { (category, list) ->
            add(
                MemoryRow.Header(
                    key = "header:$category",
                    titleRes = CATEGORY_LABELS[category],
                    fallbackTitle = category,
                    count = list.size
                )
            )
            list.forEach { add(MemoryRow.FileRow(it)) }
        }
        if (isLocalMode && filteredLegacy.isNotEmpty()) {
            add(
                MemoryRow.Header(
                    key = "header:entries",
                    titleRes = R.string.memory_entries_and_tags,
                    fallbackTitle = "",
                    count = filteredLegacy.size
                )
            )
            filteredLegacy.forEach { mem ->
                val suffix = mem.id ?: mem.hashCode().toString()
                add(
                    MemoryRow.EntryRow(
                        memory = mem,
                        key = "entry_$suffix",
                        expandKey = "entry://$suffix",
                        editableTags = true
                    )
                )
            }
        }
        if (longTermLegacy.isNotEmpty()) {
            add(
                MemoryRow.Header(
                    key = "header:long",
                    titleRes = R.string.memory_long_term_legacy,
                    fallbackTitle = "",
                    count = longTermLegacy.size
                )
            )
            longTermLegacy.forEach { mem ->
                val suffix = mem.id ?: mem.hashCode().toString()
                add(
                    MemoryRow.EntryRow(
                        memory = mem,
                        key = "lt_$suffix",
                        expandKey = "legacy://$suffix",
                        editableTags = false
                    )
                )
            }
        }
        if (shortTermLegacy.isNotEmpty()) {
            add(
                MemoryRow.Header(
                    key = "header:short",
                    titleRes = R.string.memory_short_term_legacy,
                    fallbackTitle = "",
                    count = shortTermLegacy.size
                )
            )
            shortTermLegacy.forEach { mem ->
                val suffix = mem.id ?: mem.hashCode().toString()
                add(
                    MemoryRow.EntryRow(
                        memory = mem,
                        key = "st_$suffix",
                        expandKey = "legacy://$suffix",
                        editableTags = false
                    )
                )
            }
        }
        if (groupedFiles.isEmpty() &&
            (if (isLocalMode) filteredLegacy.isEmpty()
             else longTermLegacy.isEmpty() && shortTermLegacy.isEmpty())
        ) {
            add(MemoryRow.EmptyRow)
        }
    }

    // 从全局搜索进入：滚动到命中条目、自动展开并短暂高亮
    val listState = rememberLazyListState()
    var highlightKey by remember(focusId) { mutableStateOf<String?>(null) }
    var focusHandled by remember(focusId) { mutableStateOf(false) }
    // 命中行的下标（-1 = 尚未出现）；作为定位协程的 key，避免无关重组打断滚动动画
    val focusIndex = focusId?.let { target ->
        rows.indexOfFirst { it.focusId != null && it.focusId == target }
    } ?: -1
    LaunchedEffect(focusId, focusIndex, loading) {
        if (focusId.isNullOrBlank() || focusHandled) return@LaunchedEffect
        if (focusIndex < 0) {
            // 加载完成后仍找不到（条目已被删除等）：放弃定位，避免反复尝试
            if (!loading) focusHandled = true
            return@LaunchedEffect
        }
        focusHandled = true
        val row = rows[focusIndex]
        row.expandKey?.let(viewModel::expand)
        highlightKey = row.key
        listState.animateScrollToItem(focusIndex)
    }
    LaunchedEffect(highlightKey) {
        val key = highlightKey ?: return@LaunchedEffect
        delay(2600)
        if (highlightKey == key) highlightKey = null
    }

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
                    IconButton(onClick = { viewModel.startAddLegacy() }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.memory_add_legacy), tint = MaterialTheme.colorScheme.primary)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.memory_more), tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.memory_refresh)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.load()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.memory_export_legacy)) },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.exportLegacy()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 搜索 + 角色筛选统一面板：固定在顶部，不随列表滚动
            MemorySearchPanel(
                searchQuery = searchQuery,
                onQueryChange = { viewModel.setSearchQuery(it) },
                selectedChar = selectedChar,
                characterOptions = characterOptions,
                onSelectCharacter = { viewModel.selectCharacter(it) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
            )
            if (isLocalMode && tagOptions.isNotEmpty()) {
                MemoryTagFilterRow(
                    tags = tagOptions,
                    selectedTag = selectedTag,
                    onSelectTag = { viewModel.selectTag(it) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                )
            }
            Box(modifier = Modifier.weight(1f)) {
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
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                MemoryRow.ErrorRow -> ErrorToast(
                                    message = error.orEmpty(),
                                    onShown = { viewModel.clearError() }
                                )

                                is MemoryRow.Header -> SectionHeader(
                                    title = row.titleRes?.let { stringResource(it) } ?: row.fallbackTitle,
                                    subtitle = stringResource(R.string.memory_count_format, row.count)
                                )

                                is MemoryRow.FileRow -> MemoryFileItem(
                                    file = row.file,
                                    expanded = expandedPaths.contains(row.file.path),
                                    highlighted = highlightKey == row.key,
                                    onToggle = { viewModel.toggleExpand(row.file.path) },
                                    onDelete = { deleteFile = row.file }
                                )

                                is MemoryRow.EntryRow -> LegacyMemoryItem(
                                    memory = row.memory,
                                    expanded = expandedPaths.contains(row.expandKey),
                                    highlighted = highlightKey == row.key,
                                    onToggle = { viewModel.toggleExpand(row.expandKey) },
                                    onEdit = { viewModel.startEditLegacy(row.memory) },
                                    onDelete = { deleteLegacyItem = row.memory },
                                    onTagClick = { if (row.editableTags) viewModel.selectTag(it) },
                                    onEditTags = if (row.editableTags) {
                                        ({ viewModel.startEditTags(row.memory) })
                                    } else {
                                        null
                                    }
                                )

                                MemoryRow.EmptyRow -> Text(
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
    if (editingTags != null && isLocalMode) {
        MemoryTagsDialog(
            editing = editingTags!!,
            onDismiss = { viewModel.dismissTagEditor() },
            onSave = { viewModel.saveTags(it) }
        )
    }
}

/** 统一的搜索 + 角色筛选面板：搜索行与筛选 Chip 组合在同一玻璃卡片内 */
@Composable
private fun MemorySearchPanel(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    selectedChar: String?,
    characterOptions: Map<String, String>,
    onSelectCharacter: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
    ) {
        // 搜索行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                stringResource(R.string.memory_search_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        // 角色筛选 Chip 行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedChar == null,
                onClick = { onSelectCharacter(null) },
                label = { Text(stringResource(R.string.memory_filter_all)) }
            )
            characterOptions.forEach { (id, label) ->
                FilterChip(
                    selected = selectedChar == id,
                    onClick = { onSelectCharacter(id) },
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

@Composable
private fun MemoryTagFilterRow(
    tags: List<String>,
    selectedTag: String?,
    onSelectTag: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selectedTag == null,
            onClick = { onSelectTag(null) },
            label = { Text(stringResource(R.string.memory_tag_all)) }
        )
        tags.forEach { tag ->
            FilterChip(
                selected = selectedTag?.equals(tag, ignoreCase = true) == true,
                onClick = { onSelectTag(tag) },
                label = { Text(tag) }
            )
        }
    }
}

/** MemoryFS 文件卡片：折叠态仅展示标题与元信息，点击展开查看路径、完整内容与删除操作 */
@Composable
private fun MemoryFileItem(
    file: MemoryFile,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    highlighted: Boolean = false
) {
    val accent = categoryAccent(file.category)
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        cornerRadius = 16,
        containerColor = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        borderWidth = if (highlighted) 1 else 0,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 类别图标盒：按类别着色，便于快速识别
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    categoryIcon(file.category),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.title.ifBlank { file.path.substringAfterLast('/') },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 元信息行：角色 · 摘要（本地模式为「N 条记忆」）
                val meta = buildString {
                    if (file.characterId.isNotBlank()) append(file.characterId)
                    if (file.summary.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(file.summary)
                    }
                }
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (file.injectsToPrompt) {
                Spacer(Modifier.width(8.dp))
                InjectBadge()
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))
                // 展示路径：characters/{charName}/users/{targetId}/{category}.md
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (file.content.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = file.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (file.updatedAt.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.memory_updated_at, file.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 「注入」标记：紧凑的小徽章，表示该文件内容会注入提示词 */
@Composable
private fun InjectBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            stringResource(R.string.memory_inject),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 旧版记忆卡片：与 MemoryFS 卡片统一为「点击展开 → 展开区操作」的交互 */
@Composable
private fun LegacyMemoryItem(
    memory: LegacyMemory,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTagClick: (String) -> Unit = {},
    onEditTags: (() -> Unit)? = null,
    highlighted: Boolean = false
) {
    val highPriority = memory.priority == "high"
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        cornerRadius = 16,
        containerColor = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        borderWidth = if (highlighted) 1 else 0,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = memory.title.ifBlank { stringResource(R.string.memory_unnamed) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (highPriority) {
                        Spacer(Modifier.width(6.dp))
                        // 高优先级圆点提示
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }
                val preview = memory.summary?.takeIf { it.isNotBlank() } ?: memory.content
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))
                if (memory.content.isNotBlank()) {
                    Text(
                        text = memory.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (memory.tags.orEmpty().isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        memory.tags.orEmpty().forEach { tag ->
                            AssistChip(
                                onClick = { onTagClick(tag) },
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // 元信息 Chip 行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                            containerColor = if (highPriority)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = if (highPriority)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                // 操作行：编辑 / 删除
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onEditTags != null) {
                        TextButton(onClick = onEditTags) {
                            Icon(Icons.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.memory_edit_tags))
                        }
                    }
                    TextButton(onClick = onEdit) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_edit), color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTagsDialog(
    editing: LegacyMemory,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var draft by remember(editing.id) { mutableStateOf(editing.tags.orEmpty().joinToString("，")) }
    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.memory_edit_tags),
        confirmText = stringResource(R.string.common_save),
        cancelText = stringResource(R.string.common_cancel),
        onConfirm = {
            onSave(
                draft.split(',', '，', ';', '；', '\n')
                    .map { it.trim().replace(Regex("\\s+"), " ") }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
            )
        },
        onCancel = onDismiss
    ) {
        Text(
            text = editing.title.ifBlank { stringResource(R.string.memory_unnamed) },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        NekoTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = false,
            minLines = 2,
            maxLines = 4
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.memory_tags_separator_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
