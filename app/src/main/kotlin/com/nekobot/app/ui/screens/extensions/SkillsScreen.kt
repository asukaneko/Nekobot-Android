package com.nekobot.app.ui.screens.extensions

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
import androidx.compose.material.icons.filled.MoreVert
import com.nekobot.app.ui.components.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.data.model.Skill
import com.nekobot.app.data.model.SkillRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.SuccessGreen
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Skills 配置 ViewModel：负责技能元数据的 CRUD 与启停切换。
 */
class SkillsViewModel : BaseViewModel() {
    private val _list = MutableStateFlow<List<Skill>>(emptyList())
    val list: StateFlow<List<Skill>> = _list.asStateFlow()

    init { load() }

    fun load() = launchResult(block = { unified.listSkills() }, onSuccess = { _list.value = it ?: emptyList() })

    fun create(req: SkillRequest) =
        launchResult(block = { unified.createSkill(req) }, onSuccess = { load() })

    fun update(id: String, req: SkillRequest) =
        launchResult(block = { unified.updateSkill(id, req) }, onSuccess = { load() })

    fun delete(id: String) =
        launchResult(block = { unified.deleteSkill(id) }, onSuccess = { load() })

    fun toggle(id: String) =
        launchResult(block = { unified.toggleSkill(id) }, onSuccess = { load() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val vm: SkillsViewModel = viewModel()
    val list by vm.list.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    var showForm by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<Skill?>(null) }
    var deleteTarget by remember { mutableStateOf<Skill?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills 配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingItem = null
                        showForm = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建 Skill")
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
                EmptyState(title = "暂无 Skill", hint = "点击右上角 + 添加")
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
                    items(list, key = { it.id ?: it.hashCode().toString() }) { skill ->
                        SkillCard(
                            skill = skill,
                            onEdit = { editingItem = skill; showForm = true },
                            onDelete = { deleteTarget = skill },
                            onToggle = { skill.id?.let { vm.toggle(it) } }
                        )
                    }
                }
            }

            LoadingOverlay(visible = loading)
        }
    }

    // 新建/编辑表单弹窗
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

    // 删除确认弹窗
    deleteTarget?.let { target ->
        NekoDialog(
            onDismiss = { deleteTarget = null },
            title = "确认删除",
            message = "确定删除 Skill「${target.displayName}」吗？",
            confirmText = "删除",
            onConfirm = {
                target.id?.let { vm.delete(it) }
                deleteTarget = null
            }
        )
    }
}

/**
 * Skill 卡片：展示名称、启停状态、描述、别名与操作菜单。
 */
@Composable
private fun SkillCard(
    skill: Skill,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        // 顶部行：名称 + 启停状态标记 + 操作菜单
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = skill.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            // 启停状态标记（颜色区分）
            val (statusText, statusColor) = if (skill.enabled) "已启用" to SuccessGreen else "已禁用" to WarningAmber
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
                    Icon(Icons.Filled.MoreVert, contentDescription = "操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text("切换启停") }, onClick = { menuExpanded = false; onToggle() })
                    DropdownMenuItem(text = { Text("删除", color = ErrorRed) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("描述: ${skill.description ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (skill.aliases.isNotEmpty()) {
            Text("别名: ${skill.aliases.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("创建时间: ${skill.createdAt ?: "—"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Skill 新建/编辑表单弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillFormDialog(
    initial: Skill?,
    onConfirm: (SkillRequest) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var aliases by remember { mutableStateOf(initial?.aliases?.joinToString(", ") ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    val context = LocalContext.current

    NekoDialog(
        onDismiss = onDismiss,
        title = if (initial == null) "新建 Skill" else "编辑 Skill",
        confirmText = "保存",
        onConfirm = {
            if (name.isBlank()) {
                Toast.makeText(context, "请填写名称", Toast.LENGTH_SHORT).show()
            } else {
                val req = SkillRequest(
                    name = name,
                    description = description.ifBlank { null },
                    aliases = aliases.split(",", "，").map { it.trim() }.filter { it.isNotBlank() },
                    enabled = enabled
                )
                onConfirm(req)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称（必填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("描述") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = aliases,
                onValueChange = { aliases = it },
                label = { Text("别名（逗号分隔）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }
    }
}
