package com.nekobot.app.ui.screens.worldbook

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import com.nekobot.app.ui.components.BorderlessAssistChip as AssistChip
import com.nekobot.app.ui.components.BorderlessFilterChip as FilterChip
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.WorldBook
import com.nekobot.app.data.model.WorldBookEntry
import com.nekobot.app.data.model.WorldBookEntryRequest
import com.nekobot.app.data.model.WorldBookRequest
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

// 条目位置可选项
private val POSITION_OPTIONS = listOf("before_char", "after_char", "before_an", "after_an")
private val DEFAULT_TRIGGER_SOURCES =
    listOf("user", "assistant_recent", "history", "scene_state")

private fun formatStateTriggers(values: Map<String, List<String>>?): String =
    values.orEmpty().entries.joinToString("\n") { (key, items) ->
        "$key=${items.joinToString("|")}"
    }

private fun parseStateTriggers(text: String): Map<String, List<String>> =
    text.split('\n', ';')
        .mapNotNull { line ->
            val separator = line.indexOf('=').takeIf { it > 0 }
                ?: line.indexOf(':').takeIf { it > 0 }
                ?: return@mapNotNull null
            val key = line.substring(0, separator).trim().lowercase()
            val values = line.substring(separator + 1)
                .split('|', ',')
                .map(String::trim)
                .filter(String::isNotEmpty)
            key.takeIf(String::isNotEmpty)?.let { it to values }
        }
        .filter { it.second.isNotEmpty() }
        .toMap()

/**
 * 世界书详情/条目管理页 ViewModel：管理世界书信息与条目列表的增删改。
 */
class WorldBookViewModel(bookId: String) : com.nekobot.app.ui.BaseViewModel() {

    private val currentBookId = bookId

    private val _book = MutableStateFlow<WorldBook?>(null)
    val book: StateFlow<WorldBook?> = _book.asStateFlow()

    private val _entries = MutableStateFlow<List<WorldBookEntry>>(emptyList())
    val entries: StateFlow<List<WorldBookEntry>> = _entries.asStateFlow()

    /** 所有可选角色列表（用于绑定角色下拉框） */
    private val _characters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()

    // 条目编辑对话框状态
    private val _editingEntry = MutableStateFlow<WorldBookEntry?>(null)
    val editingEntry: StateFlow<WorldBookEntry?> = _editingEntry.asStateFlow()

    private val _showEntryDialog = MutableStateFlow(false)
    val showEntryDialog: StateFlow<Boolean> = _showEntryDialog.asStateFlow()

    // 条目编辑字段
    val entryKeys = MutableStateFlow("")
    val entryContent = MutableStateFlow("")
    val entryComment = MutableStateFlow("")
    val entryEnabled = MutableStateFlow(true)
    val entryConstant = MutableStateFlow(false)
    val entrySelective = MutableStateFlow(true)
    val entryPosition = MutableStateFlow(POSITION_OPTIONS.first())
    val entryPriority = MutableStateFlow(10)
    val entryCaseSensitive = MutableStateFlow(false)
    val entryTriggerSources = MutableStateFlow(DEFAULT_TRIGGER_SOURCES.joinToString(", "))
    val entryStateTriggers = MutableStateFlow("")
    val entryMatchMode = MutableStateFlow("any")
    val entryType = MutableStateFlow("lore")

    init { load(bookId) }

    /** 加载世界书信息与条目列表 */
    fun load(bookId: String) {
        launchResult(
            block = { unified.getWorldBook(bookId) },
            onSuccess = { _book.value = it }
        )
        launchResult(
            block = { unified.listEntries(bookId) },
            onSuccess = { _entries.value = it ?: emptyList() }
        )
        // 加载角色列表用于绑定下拉框
        launchResult(
            block = { unified.listCharacters() },
            onSuccess = { _characters.value = it ?: emptyList() }
        )
    }

    /** 切换启用状态：用当前书信息 + 新 enabled 调更新接口 */
    fun toggleEnabled(enabled: Boolean) {
        val b = _book.value ?: return
        val name = b.name ?: return
        launchResult(
            block = {
                unified.updateWorldBook(
                    currentBookId,
                    WorldBookRequest(
                        name = name,
                        description = b.description,
                        coverUrl = b.coverUrl,
                        characterIds = b.characterIds,
                        enabled = enabled
                    )
                )
            },
            onSuccess = { updated -> _book.value = updated }
        )
    }

    /** 更新世界书信息（名称、描述、绑定角色列表） */
    fun updateBook(name: String, description: String?, characterIds: List<String>?, coverUrl: String? = null) {
        val b = _book.value
        launchResult(
            block = {
                unified.updateWorldBook(
                    currentBookId,
                    WorldBookRequest(
                        name = name,
                        description = description,
                        coverUrl = coverUrl ?: b?.coverUrl,
                        characterIds = characterIds,
                        enabled = b?.enabled
                    )
                )
            },
            onSuccess = { updated ->
                _book.value = updated
                showToast(string(R.string.worldbook_updated))
            }
        )
    }

    /** 上传或保存封面：本地模式保存到应用私有目录，服务器模式上传到世界书封面接口。 */
    fun setCover(context: Context, source: Uri) {
        clearError()
        viewModelScope.launch {
            setLoading(true)
            try {
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(source)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取封面文件")
                }
                val mime = context.contentResolver.getType(source) ?: "image/png"
                val ext = when (mime.lowercase()) {
                    "image/jpeg" -> "jpg"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "png"
                }
                val coverUrl = if (isLocalMode) {
                    val dir = File(context.filesDir, "worldbook_covers").apply { mkdirs() }
                    val file = File(dir, "cover_${UUID.randomUUID().toString().take(16)}.$ext")
                    withContext(kotlinx.coroutines.Dispatchers.IO) { file.writeBytes(bytes) }
                    Uri.fromFile(file).toString()
                } else {
                    val part = MultipartBody.Part.createFormData(
                        "file",
                        "worldbook_cover_${UUID.randomUUID().toString().take(12)}.$ext",
                        bytes.toRequestBody(mime.toMediaTypeOrNull())
                    )
                    when (val result = unified.uploadWorldBookCover(currentBookId, part)) {
                        is com.nekobot.app.data.repository.Resource.Success -> result.data
                        is com.nekobot.app.data.repository.Resource.Error -> throw IllegalStateException(result.message)
                        is com.nekobot.app.data.repository.Resource.Loading -> throw IllegalStateException("封面上传中")
                    }
                }
                saveCoverUrl(coverUrl)
                showToast(string(R.string.worldbook_cover_updated))
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.worldbook_cover_update_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    fun generateCover(context: Context) {
        val current = _book.value ?: return
        clearError()
        val prompt = "为世界书《${current.displayName}》生成一张无文字、适合作为书籍封面的艺术插画。" +
            current.description.orEmpty().takeIf { it.isNotBlank() }?.let { "主题：$it" }.orEmpty()
        viewModelScope.launch {
            setLoading(true)
            try {
                val result = unified.generateImages(prompt = prompt, size = "1024x1024", n = 1)
                val image = when (result) {
                    is com.nekobot.app.data.repository.Resource.Success -> result.data?.firstOrNull()
                    is com.nekobot.app.data.repository.Resource.Error -> throw IllegalStateException(result.message)
                    is com.nekobot.app.data.repository.Resource.Loading -> null
                } ?: throw IllegalStateException("AI 未返回封面")
                setCoverFromGenerated(context, Uri.parse(image.cacheUri))
            } catch (e: Exception) {
                showError(e.message?.takeIf { it.isNotBlank() } ?: string(R.string.worldbook_cover_generate_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun setCoverFromGenerated(context: Context, source: Uri) {
        val file = source.path?.let(::File) ?: throw IllegalStateException("无法读取生成的封面")
        val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) { file.readBytes() }
        val coverUrl = if (isLocalMode) {
            val dir = File(context.filesDir, "worldbook_covers").apply { mkdirs() }
            val target = File(dir, "cover_${UUID.randomUUID().toString().take(16)}.png")
            withContext(kotlinx.coroutines.Dispatchers.IO) { target.writeBytes(bytes) }
            Uri.fromFile(target).toString()
        } else {
            val part = MultipartBody.Part.createFormData(
                "file",
                "worldbook_ai_cover_${UUID.randomUUID().toString().take(12)}.png",
                bytes.toRequestBody("image/png".toMediaTypeOrNull())
            )
            when (val result = unified.uploadWorldBookCover(currentBookId, part)) {
                is com.nekobot.app.data.repository.Resource.Success -> result.data
                is com.nekobot.app.data.repository.Resource.Error -> throw IllegalStateException(result.message)
                is com.nekobot.app.data.repository.Resource.Loading -> throw IllegalStateException("封面上传中")
            }
        }
        saveCoverUrl(coverUrl)
        clearError()
        showToast(string(R.string.worldbook_cover_generated))
    }

    private suspend fun saveCoverUrl(coverUrl: String) {
        val current = _book.value ?: return
        when (val result = unified.updateWorldBook(
            currentBookId,
            WorldBookRequest(
                name = current.name.orEmpty(),
                description = current.description,
                coverUrl = coverUrl,
                characterIds = current.characterIds,
                enabled = current.enabled
            )
        )) {
            is com.nekobot.app.data.repository.Resource.Success -> _book.value = result.data
            is com.nekobot.app.data.repository.Resource.Error -> throw IllegalStateException(result.message)
            is com.nekobot.app.data.repository.Resource.Loading -> Unit
        }
    }

    /** 删除世界书 */
    fun deleteBook(onSuccess: () -> Unit) {
        launchResult(
            block = { unified.deleteWorldBook(currentBookId) },
            onSuccess = { onSuccess() }
        )
    }

    /** 开始新建条目：重置字段并打开对话框 */
    fun startNewEntry() {
        _editingEntry.value = null
        entryKeys.value = ""
        entryContent.value = ""
        entryComment.value = ""
        entryEnabled.value = true
        entryConstant.value = false
        entrySelective.value = true
        entryPosition.value = POSITION_OPTIONS.first()
        entryPriority.value = 10
        entryCaseSensitive.value = false
        entryTriggerSources.value = DEFAULT_TRIGGER_SOURCES.joinToString(", ")
        entryStateTriggers.value = ""
        entryMatchMode.value = "any"
        entryType.value = "lore"
        _showEntryDialog.value = true
    }

    /** 开始编辑条目：填充字段并打开对话框 */
    fun startEditEntry(entry: WorldBookEntry) {
        _editingEntry.value = entry
        entryKeys.value = entry.keys?.joinToString(", ").orEmpty()
        entryContent.value = entry.content.orEmpty()
        entryComment.value = entry.comment.orEmpty()
        entryEnabled.value = entry.enabled ?: true
        entryConstant.value = entry.constant ?: false
        entrySelective.value = entry.selective ?: true
        entryPosition.value = entry.position ?: POSITION_OPTIONS.first()
        entryPriority.value = entry.priority ?: 10
        entryCaseSensitive.value = entry.caseSensitive ?: false
        entryTriggerSources.value =
            (entry.triggerSources ?: DEFAULT_TRIGGER_SOURCES).joinToString(", ")
        entryStateTriggers.value = formatStateTriggers(entry.stateTriggers)
        entryMatchMode.value = entry.matchMode ?: "any"
        entryType.value = entry.entryType ?: "lore"
        _showEntryDialog.value = true
    }

    /** 关闭条目对话框 */
    fun dismissEntryDialog() {
        _showEntryDialog.value = false
        _editingEntry.value = null
    }

    /** 保存条目：editingEntry 为空表示新建，否则更新 */
    fun saveEntry() {
        val keys = entryKeys.value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val req = WorldBookEntryRequest(
            keys = keys.takeIf { it.isNotEmpty() },
            content = entryContent.value.trim().takeIf { it.isNotBlank() },
            comment = entryComment.value.trim().takeIf { it.isNotBlank() },
            enabled = entryEnabled.value,
            constant = entryConstant.value,
            selective = entrySelective.value,
            position = entryPosition.value,
            priority = entryPriority.value,
            caseSensitive = entryCaseSensitive.value,
            triggerSources = entryTriggerSources.value
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .takeIf { it.isNotEmpty() },
            stateTriggers = parseStateTriggers(entryStateTriggers.value).takeIf { it.isNotEmpty() },
            matchMode = entryMatchMode.value,
            entryType = entryType.value.trim().lowercase().ifBlank { "lore" }
        )
        val editing = _editingEntry.value
        if (editing == null) {
            // 新建
            launchResult(
                block = { unified.createEntry(currentBookId, req) },
                onSuccess = {
                    _entries.value = _entries.value + it
                    _showEntryDialog.value = false
                    showToast(string(R.string.worldbook_entry_created))
                }
            )
        } else {
            val entryId = editing.id ?: return
            launchResult(
                block = { unified.updateEntry(currentBookId, entryId, req) },
                onSuccess = { updated ->
                    _entries.value = _entries.value.map { if (it.id == entryId) updated else it }
                    _showEntryDialog.value = false
                    _editingEntry.value = null
                    showToast(string(R.string.worldbook_entry_updated))
                }
            )
        }
    }

    /** 删除条目 */
    fun deleteEntry(id: String) {
        launchResult(
            block = { unified.deleteEntry(currentBookId, id) },
            onSuccess = {
                _entries.value = _entries.value.filterNot { it.id == id }
                showToast(string(R.string.worldbook_entry_deleted))
            }
        )
    }

    /**
     * AI 生成条目：按主题调用 AI 生成 5-10 个新条目，并追加到当前条目列表。
     * 本地模式由 LocalRepository 直接落库；远程模式由后端落库并返回条目列表。
     */
    fun aiGenerateEntries(topic: String?) {
        launchResult(
            block = { unified.aiGenerateWorldBookEntries(currentBookId, topic) },
            onSuccess = { newEntries ->
                _entries.value = _entries.value + (newEntries ?: emptyList())
                showToast(string(R.string.worldbook_entry_ai_generated, newEntries?.size ?: 0))
            }
        )
    }
}

/**
 * 世界书详情/条目管理页：展示书信息、管理条目增删改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookDetailScreen(
    bookId: String,
    onBack: () -> Unit
) {
    val vm: WorldBookViewModel = viewModel(
        key = "wb_$bookId",
        factory = viewModelFactory { initializer { WorldBookViewModel(bookId) } }
    )
    val book by vm.book.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val characters by vm.characters.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val showEntryDialog by vm.showEntryDialog.collectAsStateWithLifecycle()
    val editingEntry by vm.editingEntry.collectAsStateWithLifecycle()

    var showEditBookDialog by remember { mutableStateOf(false) }
    var showDeleteBookDialog by remember { mutableStateOf(false) }
    var deleteEntryId by remember { mutableStateOf<String?>(null) }
    var showAiEntriesDialog by remember { mutableStateOf(false) }
    var showBindCharacterDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.setCover(context, it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        book?.displayName ?: stringResource(R.string.worldbook_detail_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showEditBookDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.worldbook_edit_book), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showDeleteBookDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.worldbook_delete_book), tint = MaterialTheme.colorScheme.error)
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.takeIf { it.isNotBlank() }?.let { message ->
                    item {
                        ErrorBanner(message = message)
                    }
                }
                // 书信息卡片
                item {
                    // 根据当前 book.characterIds 和已加载的角色列表，解析出绑定角色名列表
                    // 注意：后端 character_ids 可能存的是角色名或预设 UUID，需同时匹配
                    val boundIds = book?.characterIds ?: emptyList()
                    val boundNames = characters
                        .filter { c ->
                            (c.id != null && c.id in boundIds) ||
                                (c.name != null && c.name in boundIds)
                        }
                        .mapNotNull { it.name }
                    BookInfoCard(
                        book = book,
                        boundCharacterNames = boundNames,
                        onToggleEnabled = { vm.toggleEnabled(it) },
                        onBindCharacter = { showBindCharacterDialog = true },
                        onUploadCover = { coverPicker.launch("image/*") },
                        onGenerateCover = { vm.generateCover(context) }
                    )
                }
                // 条目列表标题 + 新建按钮 + AI 生成按钮
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader(
                            title = stringResource(R.string.worldbook_entries_title),
                            subtitle = stringResource(R.string.worldbook_entries_subtitle, entries.size)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { vm.startNewEntry() }) {
                                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.worldbook_new_entry), color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { showAiEntriesDialog = true }) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.worldbook_ai_generate_entry), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                // 条目卡片
                items(entries, key = { it.id ?: it.hashCode().toString() }) { entry ->
                    EntryItem(
                        entry = entry,
                        onEdit = { vm.startEditEntry(entry) },
                        onDelete = { deleteEntryId = entry.id }
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.worldbook_entries_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 编辑书信息对话框
    if (showEditBookDialog) {
        EditBookDialog(
            initialName = book?.name.orEmpty(),
            initialDesc = book?.description.orEmpty(),
            initialCharacterIds = book?.characterIds ?: emptyList(),
            characters = vm.characters,
            onDismiss = { showEditBookDialog = false },
            onConfirm = { name, desc, charIds ->
                vm.updateBook(name, desc, charIds)
                showEditBookDialog = false
            }
        )
    }

    // 删除书确认
    if (showDeleteBookDialog) {
        NekoDialog(
            onDismiss = { showDeleteBookDialog = false },
            title = stringResource(R.string.worldbook_delete_book_title),
            message = stringResource(R.string.worldbook_delete_book_message),
            confirmText = stringResource(R.string.common_delete),
            cancelText = stringResource(R.string.common_cancel),
            onConfirm = {
                showDeleteBookDialog = false
                vm.deleteBook(onBack)
            },
            onCancel = { showDeleteBookDialog = false }
        )
    }

    // 删除条目确认
    if (deleteEntryId != null) {
        NekoDialog(
            onDismiss = { deleteEntryId = null },
            title = stringResource(R.string.worldbook_delete_entry_title),
            message = stringResource(R.string.worldbook_delete_entry_message),
            confirmText = stringResource(R.string.common_delete),
            cancelText = stringResource(R.string.common_cancel),
            onConfirm = {
                deleteEntryId?.let { vm.deleteEntry(it) }
                deleteEntryId = null
            },
            onCancel = { deleteEntryId = null }
        )
    }

    // 条目编辑对话框
    if (showEntryDialog) {
        EntryEditDialog(vm = vm, isEdit = editingEntry != null)
    }

    // AI 生成条目对话框
    if (showAiEntriesDialog) {
        AiGenerateEntriesDialog(
            onDismiss = { showAiEntriesDialog = false },
            onConfirm = { topic ->
                showAiEntriesDialog = false
                vm.aiGenerateEntries(topic)
            }
        )
    }

    // 绑定角色对话框（多选）
    if (showBindCharacterDialog) {
        BindCharacterDialog(
            initialCharacterIds = book?.characterIds ?: emptyList(),
            characters = vm.characters,
            onDismiss = { showBindCharacterDialog = false },
            onConfirm = { charIds ->
                // 仅更新绑定角色，其它字段保留
                vm.updateBook(
                    name = book?.name.orEmpty(),
                    description = book?.description,
                    characterIds = charIds
                )
                showBindCharacterDialog = false
            }
        )
    }
}

/** 书信息卡片 */
@Composable
private fun BookInfoCard(
    book: WorldBook?,
    boundCharacterNames: List<String>,
    onToggleEnabled: (Boolean) -> Unit,
    onBindCharacter: () -> Unit,
    onUploadCover: () -> Unit,
    onGenerateCover: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.wrapContentHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
            WorldBookCover(
                coverUrl = book?.coverUrl,
                contentDescription = book?.displayName,
                modifier = Modifier
                    .size(width = 76.dp, height = 100.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book?.displayName ?: stringResource(R.string.worldbook_unnamed),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Switch(
                        checked = book?.enabled == true,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.scale(0.82f),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
                if (!book?.description.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = book!!.description!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 绑定角色：点击可直接修改，显示所有已绑定角色名
                val boundNamesText = if (boundCharacterNames.isEmpty())
                    stringResource(R.string.worldbook_bound_characters_none)
                else
                    boundCharacterNames.joinToString(", ")
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBindCharacter)
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.worldbook_bound_characters, boundNamesText),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.worldbook_select_character),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
                    TextButton(
                        onClick = onUploadCover,
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.worldbook_upload_cover))
                    }
                    TextButton(
                        onClick = onGenerateCover,
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.worldbook_ai_generate_cover))
                    }
        }
        }
    }
}

/** 单个条目卡片 */
@Composable
private fun EntryItem(
    entry: WorldBookEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                // 内容预览（关键词上方）
                Text(
                    text = entry.content.orEmpty().ifBlank { stringResource(R.string.worldbook_no_content) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.comment.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.worldbook_comment, entry.comment),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!entry.stateTriggers.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.worldbook_dynamic_condition_summary,
                            formatStateTriggers(entry.stateTriggers).replace("\n", "；")
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 关键词：内容下方一排，可左右拖动
                val keys = entry.keys
                if (!keys.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        keys.forEach { k ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        k,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 开关行：常驻/选择/启用
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SwitchLabel(stringResource(R.string.worldbook_constant), entry.constant == true)
                    SwitchLabel(stringResource(R.string.worldbook_selective), entry.selective == true)
                    SwitchLabel(stringResource(R.string.worldbook_enabled), entry.enabled == true)
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

/** 小型只读开关标签 */
@Composable
private fun SwitchLabel(label: String, checked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.scale(0.8f)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 条目编辑对话框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditDialog(vm: WorldBookViewModel, isEdit: Boolean) {
    val keys by vm.entryKeys.collectAsStateWithLifecycle()
    val content by vm.entryContent.collectAsStateWithLifecycle()
    val comment by vm.entryComment.collectAsStateWithLifecycle()
    val enabled by vm.entryEnabled.collectAsStateWithLifecycle()
    val constant by vm.entryConstant.collectAsStateWithLifecycle()
    val selective by vm.entrySelective.collectAsStateWithLifecycle()
    val position by vm.entryPosition.collectAsStateWithLifecycle()
    val priority by vm.entryPriority.collectAsStateWithLifecycle()
    val caseSensitive by vm.entryCaseSensitive.collectAsStateWithLifecycle()
    val triggerSources by vm.entryTriggerSources.collectAsStateWithLifecycle()
    val stateTriggers by vm.entryStateTriggers.collectAsStateWithLifecycle()
    val matchMode by vm.entryMatchMode.collectAsStateWithLifecycle()
    val entryType by vm.entryType.collectAsStateWithLifecycle()

    var positionExpanded by remember { mutableStateOf(false) }

    NekoDialog(
        onDismiss = { vm.dismissEntryDialog() },
        title = if (isEdit) stringResource(R.string.worldbook_edit_entry) else stringResource(R.string.worldbook_new_entry),
        confirmText = stringResource(R.string.common_save),
        cancelText = stringResource(R.string.common_cancel),
        onConfirm = { vm.saveEntry() },
        onCancel = { vm.dismissEntryDialog() }
    ) {
        // 整体内容可滚动，防止超出屏幕
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 关键词（逗号分隔）
            Text(stringResource(R.string.worldbook_field_keys), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = keys,
                onValueChange = { vm.entryKeys.value = it },
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.worldbook_field_trigger_sources),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = triggerSources,
                onValueChange = { vm.entryTriggerSources.value = it },
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.worldbook_field_state_triggers),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = stateTriggers,
                onValueChange = { vm.entryStateTriggers.value = it },
                singleLine = false,
                minLines = 3,
                maxLines = 5
            )
            Text(
                stringResource(R.string.worldbook_state_triggers_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.worldbook_field_match_mode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("any", "all").forEach { mode ->
                    FilterChip(
                        selected = matchMode == mode,
                        onClick = { vm.entryMatchMode.value = mode },
                        label = {
                            Text(
                                stringResource(
                                    if (mode == "all") {
                                        R.string.worldbook_match_all
                                    } else {
                                        R.string.worldbook_match_any
                                    }
                                )
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.worldbook_field_entry_type),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = entryType,
                onValueChange = { vm.entryType.value = it },
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            // 内容（多行）
            Text(stringResource(R.string.worldbook_field_content), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = content,
                onValueChange = { vm.entryContent.value = it },
                singleLine = false,
                minLines = 3,
                maxLines = 6
            )
            Spacer(Modifier.height(10.dp))
            // 备注
            Text(stringResource(R.string.worldbook_field_comment), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = comment,
                onValueChange = { vm.entryComment.value = it },
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            // 位置下拉
            Text(stringResource(R.string.worldbook_field_position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            ExposedDropdownMenuBox(
                expanded = positionExpanded,
                onExpandedChange = { positionExpanded = it }
            ) {
                OutlinedTextField(
                    value = position,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = positionExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                ExposedDropdownMenu(
                    expanded = positionExpanded,
                    onDismissRequest = { positionExpanded = false }
                ) {
                    POSITION_OPTIONS.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt, color = MaterialTheme.colorScheme.onSurface) },
                            onClick = {
                                vm.entryPosition.value = opt
                                positionExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 优先级（数字）
            Text(stringResource(R.string.worldbook_field_priority), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = priority.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { vm.entryPriority.value = it } },
                singleLine = true,
                keyboardType = KeyboardType.Number
            )
            Spacer(Modifier.height(12.dp))
            // 开关组
            SwitchRow(stringResource(R.string.worldbook_enabled), enabled) { vm.entryEnabled.value = it }
            SwitchRow(stringResource(R.string.worldbook_constant), constant) { vm.entryConstant.value = it }
            SwitchRow(stringResource(R.string.worldbook_selective), selective) { vm.entrySelective.value = it }
            SwitchRow(stringResource(R.string.worldbook_case_sensitive), caseSensitive) { vm.entryCaseSensitive.value = it }
        }
    }
}

/** 开关行 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

/** 编辑书信息对话框（含绑定角色多选） */
@Composable
private fun EditBookDialog(
    initialName: String,
    initialDesc: String,
    initialCharacterIds: List<String>,
    characters: StateFlow<List<CharacterPreset>>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String?, characterIds: List<String>) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }
    val selectedIds = remember { mutableStateListOf<String>().apply { addAll(initialCharacterIds) } }
    val charList by characters.collectAsStateWithLifecycle()

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.worldbook_edit_book_title),
        confirmText = stringResource(R.string.common_save),
        cancelText = stringResource(R.string.common_cancel),
        onConfirm = {
            if (name.isBlank()) return@NekoDialog
            onConfirm(name.trim(), desc.trim().takeIf { it.isNotBlank() }, selectedIds.toList())
        },
        onCancel = onDismiss
    ) {
        // 整体内容可滚动，防止超出屏幕
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.worldbook_field_name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.worldbook_field_description), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            NekoTextField(
                value = desc,
                onValueChange = { desc = it },
                singleLine = false,
                minLines = 2,
                maxLines = 5
            )
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.worldbook_bind_characters_multi), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (charList.isEmpty()) {
                Text(
                    stringResource(R.string.worldbook_no_characters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    stringResource(R.string.worldbook_selected_count, selectedIds.size, charList.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                // 角色 Checkbox 列表（嵌入外层滚动中，不单独滚动）
                charList.forEach { c ->
                    // 后端 character_ids 可能存的是角色名或 UUID，需同时匹配
                    val checked = (c.id != null && c.id in selectedIds) ||
                        (c.name != null && c.name in selectedIds)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val id = c.id ?: return@clickable
                                if (checked) {
                                    selectedIds.remove(id)
                                    c.name?.let { selectedIds.remove(it) }
                                } else {
                                    selectedIds.add(id)
                                }
                            }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                val id = c.id ?: return@Checkbox
                                if (checked) {
                                    selectedIds.remove(id)
                                    c.name?.let { selectedIds.remove(it) }
                                } else {
                                    selectedIds.add(id)
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = c.name ?: stringResource(R.string.worldbook_unnamed_character),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 绑定角色对话框：支持多选角色，其它字段保留不变。
 *
 * 使用 Checkbox 列表代替下拉菜单：
 * 1. 所有角色直接渲染出来，无需点击展开
 * 2. 支持多选绑定
 * 3. 角色列表为空时显示明确提示
 */
@Composable
private fun BindCharacterDialog(
    initialCharacterIds: List<String>,
    characters: StateFlow<List<CharacterPreset>>,
    onDismiss: () -> Unit,
    onConfirm: (characterIds: List<String>) -> Unit
) {
    // 用可变 Set 维护已选角色 ID
    val selectedIds = remember { mutableStateListOf<String>().apply { addAll(initialCharacterIds) } }
    val charList by characters.collectAsStateWithLifecycle()

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.worldbook_bind_characters_multi),
        confirmText = stringResource(R.string.common_save),
        cancelText = stringResource(R.string.common_cancel),
        onConfirm = { onConfirm(selectedIds.toList()) },
        onCancel = onDismiss
    ) {
        Text(
            stringResource(R.string.worldbook_bind_characters_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (charList.isEmpty()) {
            // 角色列表为空时明确提示
            Text(
                stringResource(R.string.worldbook_no_characters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            Text(
                stringResource(R.string.worldbook_selected_count, selectedIds.size, charList.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            // 可滚动的角色 Checkbox 列表
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                charList.forEach { c ->
                    // 后端 character_ids 可能存的是角色名或 UUID，需同时匹配
                    val checked = (c.id != null && c.id in selectedIds) ||
                        (c.name != null && c.name in selectedIds)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val id = c.id ?: return@clickable
                                if (checked) {
                                    selectedIds.remove(id)
                                    c.name?.let { selectedIds.remove(it) }
                                } else {
                                    selectedIds.add(id)
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                val id = c.id ?: return@Checkbox
                                if (checked) {
                                    selectedIds.remove(id)
                                    c.name?.let { selectedIds.remove(it) }
                                } else {
                                    selectedIds.add(id)
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = c.name ?: stringResource(R.string.worldbook_unnamed_character),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
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

/**
 * AI 生成条目对话框：用户可选填生成主题，AI 会围绕主题生成 5-10 条新条目并追加到列表。
 *
 * 参考原仓库 nbot/web/routes/world_book.py 的 ai-generate 接口。
 */
@Composable
private fun AiGenerateEntriesDialog(
    onDismiss: () -> Unit,
    onConfirm: (topic: String?) -> Unit
) {
    var topic by remember { mutableStateOf("") }

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.worldbook_ai_generate_entry),
        confirmText = stringResource(R.string.worldbook_generate),
        cancelText = stringResource(R.string.common_cancel),
        onConfirm = {
            onConfirm(topic.trim().takeIf { it.isNotBlank() })
        },
        onCancel = onDismiss
    ) {
        Text(stringResource(R.string.worldbook_field_topic_optional), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            singleLine = false,
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    stringResource(R.string.worldbook_entry_topic_placeholder),
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
            stringResource(R.string.worldbook_entry_topic_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
