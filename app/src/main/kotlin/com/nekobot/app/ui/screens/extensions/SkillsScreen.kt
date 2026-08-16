package com.nekobot.app.ui.screens.extensions

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nekobot.app.R
import com.nekobot.app.data.model.Skill
import com.nekobot.app.data.model.SkillFileInfo
import com.nekobot.app.data.model.SkillInstallRequest
import com.nekobot.app.data.model.SkillRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.GlassDropdownMenu
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.SuccessGreen
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Skills 元数据、目录存储和 URL 安装的统一管理。 */
class SkillsViewModel : BaseViewModel() {
    private val _list = MutableStateFlow<List<Skill>>(emptyList())
    val list: StateFlow<List<Skill>> = _list.asStateFlow()

    init {
        load()
    }

    fun load() = launchResult(
        block = { unified.listSkills() },
        onSuccess = { _list.value = it }
    )

    fun loadDetail(skill: Skill, onSuccess: (Skill) -> Unit) =
        launchResult(block = { unified.getSkillStorage(skill) }, onSuccess = onSuccess)

    fun readFile(skillName: String, relativePath: String, onSuccess: (String) -> Unit) =
        launchResult(
            block = { unified.readSkillFile(skillName, relativePath) },
            onSuccess = onSuccess
        )

    fun create(req: SkillRequest) =
        launchResult(block = { unified.createSkill(req) }, onSuccess = {
            showToast(string(R.string.skills_saved))
            load()
        })

    fun update(id: String, req: SkillRequest) =
        launchResult(block = { unified.updateSkill(id, req) }, onSuccess = {
            showToast(string(R.string.skills_saved))
            load()
        })

    fun install(req: SkillInstallRequest) =
        launchResult(block = { unified.installSkillFromUrl(req) }, onSuccess = {
            showToast(string(R.string.skills_installed_count, it.size))
            load()
        })

    fun delete(id: String) =
        launchResult(block = { unified.deleteSkill(id) }, onSuccess = { load() })

    fun toggle(id: String) =
        launchResult(block = { unified.toggleSkill(id) }, onSuccess = { load() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(onBack: () -> Unit, onOpenStorage: (Skill) -> Unit = {}) {
    val vm: SkillsViewModel = viewModel()
    val list by vm.list.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showForm by remember { mutableStateOf(false) }
    var showInstaller by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<Skill?>(null) }
    var deleteTarget by remember { mutableStateOf<Skill?>(null) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skills_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showInstaller = true }) {
                        Icon(
                            Icons.Filled.CloudDownload,
                            contentDescription = stringResource(R.string.skills_install_url)
                        )
                    }
                    IconButton(onClick = {
                        editingItem = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.skills_new))
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { SkillInfoCard() }
                error?.let { message ->
                    item {
                        ErrorBanner(message = message, onRetry = {
                            vm.clearError()
                            vm.load()
                        })
                    }
                }
                if (list.isEmpty() && !loading) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.skills_empty_title),
                            hint = stringResource(R.string.skills_empty_hint)
                        )
                    }
                } else {
                    items(list, key = { it.id ?: it.name }) { skill ->
                        SkillCard(
                            skill = skill,
                            onEdit = {
                                vm.loadDetail(skill) { detail ->
                                    editingItem = detail
                                    showForm = true
                                }
                            },
                            onDelete = { deleteTarget = skill },
                            onToggle = { skill.id?.let(vm::toggle) },
                            onOpenStorage = { onOpenStorage(skill) }
                        )
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    if (showForm) {
        SkillFormDialog(
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

    if (showInstaller) {
        SkillInstallDialog(
            onConfirm = {
                vm.install(it)
                showInstaller = false
            },
            onDismiss = { showInstaller = false }
        )
    }

    deleteTarget?.let { target ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = stringResource(R.string.skills_confirm_delete),
            message = stringResource(R.string.skills_delete_message, target.displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                target.id?.let(vm::delete)
                deleteTarget = null
            }
        )
    }
}

@Composable
private fun SkillInfoCard() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.skills_storage_standard),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.skills_storage_standard_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onOpenStorage: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                if (skill.hasStorage) {
                    Text(
                        text = stringResource(R.string.skills_storage_file_count, skill.files.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            val (statusText, statusColor) = if (skill.enabled) {
                stringResource(R.string.skills_status_enabled) to SuccessGreen
            } else {
                stringResource(R.string.skills_status_disabled) to WarningAmber
            }
            Box(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            Spacer(Modifier.width(8.dp))
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.skills_action),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (skill.hasStorage) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.skill_open_storage)) },
                            onClick = { menuExpanded = false; onOpenStorage() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        onClick = { menuExpanded = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.skills_toggle)) },
                        onClick = { menuExpanded = false; onToggle() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = ErrorRed) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.skills_description, skill.description ?: "—"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (skill.aliases.isNotEmpty()) {
            Text(
                stringResource(R.string.skills_aliases, skill.aliases.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        skill.sourceUrl?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.skills_source, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun SkillFormDialog(
    initial: Skill?,
    onConfirm: (SkillRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var description by remember(initial) { mutableStateOf(initial?.description ?: "") }
    var aliases by remember(initial) { mutableStateOf(initial?.aliases?.joinToString(", ") ?: "") }
    var parameters by remember(initial) {
        mutableStateOf(initial?.parameters?.toString()?.takeUnless { it == "null" } ?: "{}")
    }
    var skillMd by remember(initial) { mutableStateOf(initial?.skillMd ?: "") }
    var referenceMd by remember(initial) { mutableStateOf(initial?.referenceMd ?: "") }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    val context = LocalContext.current

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) {
            stringResource(R.string.skills_new)
        } else {
            stringResource(R.string.skills_edit)
        },
        confirmText = stringResource(R.string.common_save),
        onConfirm = {
            if (name.isBlank()) {
                Toast.makeText(context, context.getString(R.string.skills_name_required), Toast.LENGTH_SHORT).show()
                return@NekoDialog
            }
            val parsedParameters: JsonElement? = try {
                parameters.takeIf { it.isNotBlank() }?.let(JsonParser::parseString)
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.skills_parameters_invalid), Toast.LENGTH_SHORT).show()
                return@NekoDialog
            }
            onConfirm(
                SkillRequest(
                    name = name.trim(),
                    description = description.ifBlank { null },
                    aliases = aliases.split(',', '，').map { it.trim() }.filter { it.isNotBlank() },
                    enabled = enabled,
                    parameters = parsedParameters,
                    skillMd = skillMd.ifBlank { null },
                    referenceMd = referenceMd
                )
            )
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.skills_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.skills_description_label)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = aliases,
                onValueChange = { aliases = it },
                label = { Text(stringResource(R.string.skills_aliases_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = skillMd,
                onValueChange = { skillMd = it },
                label = { Text(stringResource(R.string.skills_markdown_label)) },
                supportingText = { Text(stringResource(R.string.skills_markdown_hint)) },
                minLines = 7,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = referenceMd,
                onValueChange = { referenceMd = it },
                label = { Text(stringResource(R.string.skills_reference_label)) },
                minLines = 3,
                maxLines = 10,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = parameters,
                onValueChange = { parameters = it },
                label = { Text(stringResource(R.string.skills_parameters_label)) },
                minLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.skills_enabled_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }
    }
}

@Composable
private fun SkillInstallDialog(
    onConfirm: (SkillInstallRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var overwrite by remember { mutableStateOf(false) }
    val context = LocalContext.current

    NekoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.skills_install_url),
        confirmText = stringResource(R.string.skills_install),
        onConfirm = {
            val normalized = url.trim()
            if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
                Toast.makeText(context, context.getString(R.string.skills_url_invalid), Toast.LENGTH_SHORT).show()
            } else {
                onConfirm(SkillInstallRequest(normalized, enabled, overwrite))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.skills_install_formats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.skills_url_label)) },
                placeholder = { Text("https://github.com/owner/repo") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.skills_enabled_label))
                    Text(
                        stringResource(R.string.skills_install_enabled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.skills_overwrite))
                    Text(
                        stringResource(R.string.skills_overwrite_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = overwrite, onCheckedChange = { overwrite = it })
            }
        }
    }
}

// ==================== Skill 文件夹浏览页 ====================

private val FILE_GROUP_ORDER = listOf("core", "reference", "license", "script", "resource", "other")

private fun fileTypeLabel(type: String): Int = when (type) {
    "core" -> R.string.skill_file_group_core
    "reference" -> R.string.skill_file_group_reference
    "license" -> R.string.skill_file_group_license
    "script" -> R.string.skill_file_group_script
    "resource" -> R.string.skill_file_group_resource
    else -> R.string.skill_file_group_other
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024.0)
    else -> String.format("%.2fMB", bytes / (1024.0 * 1024.0))
}

/** 是否为可直接预览的文本文件 */
private fun isTextPreviewable(file: SkillFileInfo): Boolean {
    if (file.size > 12L * 1024 * 1024) return false
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val textExt = setOf(
        "md", "txt", "json", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "py", "js", "ts", "tsx", "jsx", "kt", "java", "c", "cpp", "h", "hpp",
        "go", "rs", "rb", "php", "sh", "bat", "ps1", "sql", "csv", "log",
        "html", "htm", "css", "scss", "less", "xml", "env", "gitignore",
        "license", "readme", "lock", "gradle", "swift", "lua", "r", "scala"
    )
    return ext in textExt || file.type in listOf("core", "reference", "license")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillStorageScreen(skillName: String, onBack: () -> Unit) {
    val vm: SkillsViewModel = viewModel()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var skill by remember { mutableStateOf<Skill?>(null) }
    var previewFile by remember { mutableStateOf<SkillFileInfo?>(null) }
    var previewContent by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(skillName) {
        // 加载最新 Skill 详情，确保 files 列表完整
        val initial = Skill(name = skillName, hasStorage = true)
        vm.loadDetail(initial) { skill = it }
    }

    val current = skill
    val files = current?.files.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skill_detail_title) + " · " + skillName) },
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
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (files.isEmpty() && !loading) {
                EmptyState(
                    title = stringResource(R.string.skill_file_no_files)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = skillName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.skill_files_section) +
                                            " · ${files.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    error?.let { message ->
                        item {
                            ErrorBanner(message = message, onRetry = {
                                vm.clearError()
                                vm.loadDetail(Skill(name = skillName, hasStorage = true)) { skill = it }
                            })
                        }
                    }
                    // 按文件类型分组展示
                    val grouped = files.groupBy { it.type }
                        .toSortedMap(compareBy { FILE_GROUP_ORDER.indexOf(it) })
                    grouped.forEach { (type, fileList) ->
                        item(key = "group_$type") {
                            Text(
                                text = stringResource(fileTypeLabel(type)) +
                                    " (${fileList.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                            )
                        }
                        items(fileList, key = { "file_${it.path}" }) { file ->
                            SkillFileRow(
                                file = file,
                                onOpen = {
                                    if (!isTextPreviewable(file)) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.skill_file_binary),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        previewFile = file
                                        previewContent = null
                                        previewError = null
                                        vm.readFile(skillName, file.path) { content ->
                                            previewContent = content
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 文件预览弹窗
    previewFile?.let { file ->
        NekoDialog(
            onDismiss = {
                previewFile = null
                previewContent = null
                previewError = null
            },
            title = stringResource(R.string.skill_file_preview_title) + " · " + file.name,
            confirmText = stringResource(R.string.common_close),
            onConfirm = {
                previewFile = null
                previewContent = null
                previewError = null
            },
            cancelText = null,
            onCancel = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                when {
                    previewContent != null -> Text(
                        text = previewContent!!,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    previewError != null -> Text(
                        text = previewError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed
                    )
                    else -> Text(
                        text = stringResource(R.string.skill_file_read_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillFileRow(file: SkillFileInfo, onOpen: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.path + " · " + formatFileSize(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.skill_file_open),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.skill_file_open),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
