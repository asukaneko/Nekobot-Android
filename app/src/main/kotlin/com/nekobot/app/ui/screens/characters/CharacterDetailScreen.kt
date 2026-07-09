package com.nekobot.app.ui.screens.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 角色详情/编辑页 ViewModel：负责加载角色、编辑各字段、保存与删除。
 * 当 characterId == "new" 时为新建模式，不加载现有数据。
 */
class CharacterViewModel(characterId: String) : com.nekobot.app.ui.BaseViewModel() {

    private val isNew = characterId == "new"

    private val _character = MutableStateFlow<CharacterPreset?>(null)
    val character: StateFlow<CharacterPreset?> = _character.asStateFlow()

    // 各编辑字段（与服务端 custom-presets 完整字段对应）
    val name = MutableStateFlow("")
    val description = MutableStateFlow("")
    val avatar = MutableStateFlow("")
    val portrait = MutableStateFlow("")
    val basicInfo = MutableStateFlow("")
    val personality = MutableStateFlow("")
    val firstMessage = MutableStateFlow("")
    val scenario = MutableStateFlow("")
    val dialogExamples = MutableStateFlow("")
    val responseFormat = MutableStateFlow("")
    val rulesText = MutableStateFlow("")
    val systemPrompt = MutableStateFlow("")
    val tagsText = MutableStateFlow("")

    init {
        if (!isNew) load(characterId)
    }

    /** 加载现有角色并填充编辑字段 */
    fun load(id: String) {
        launchResult(
            block = { repo.getCharacter(id) },
            onSuccess = { c ->
                _character.value = c
                name.value = c.name.orEmpty()
                description.value = c.description.orEmpty()
                avatar.value = c.avatar.orEmpty()
                portrait.value = c.portrait.orEmpty()
                basicInfo.value = c.basicInfo.orEmpty()
                personality.value = c.personality.orEmpty()
                firstMessage.value = c.firstMessage.orEmpty()
                scenario.value = c.scenario.orEmpty()
                dialogExamples.value = c.exampleDialogues.orEmpty()
                responseFormat.value = c.responseFormat.orEmpty()
                rulesText.value = c.rules?.joinToString("\n").orEmpty()
                systemPrompt.value = c.systemPrompt.orEmpty()
                tagsText.value = c.tags?.joinToString(", ").orEmpty()
            }
        )
    }

    /**
     * 保存：
     *  - POST 不传 id / systemPrompt / greeting（后端自动管理 id / created_at / systemPrompt，greeting 已合并到 firstMessage）
     *  - PUT 传部分字段做合并更新，不传 updated_at（后端管理）
     *  - state 不在 UI 中编辑，保留服务端默认值
     */
    fun save(onSuccess: () -> Unit) {
        val nameVal = name.value.trim()
        if (nameVal.isBlank()) {
            showToast("角色名不能为空")
            return
        }
        val rulesList = rulesText.value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val tagsList = tagsText.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val payload = buildMap<String, Any?> {
            put("name", nameVal)
            description.value.trim().takeIf { it.isNotBlank() }?.let { put("description", it) }
            avatar.value.trim().takeIf { it.isNotBlank() }?.let { put("avatar", it) }
            portrait.value.trim().takeIf { it.isNotBlank() }?.let { put("portrait", it) }
            basicInfo.value.trim().takeIf { it.isNotBlank() }?.let { put("basicInfo", it) }
            personality.value.trim().takeIf { it.isNotBlank() }?.let { put("personality", it) }
            firstMessage.value.trim().takeIf { it.isNotBlank() }?.let { put("firstMessage", it) }
            scenario.value.trim().takeIf { it.isNotBlank() }?.let { put("scenario", it) }
            dialogExamples.value.trim().takeIf { it.isNotBlank() }?.let { put("exampleDialogues", it) }
            responseFormat.value.trim().takeIf { it.isNotBlank() }?.let { put("responseFormat", it) }
            if (rulesList.isNotEmpty()) put("rules", rulesList)
            if (tagsList.isNotEmpty()) put("tags", tagsList)
            // 显式不传：id / systemPrompt / greeting / created_at / updated_at
        }
        val json = com.nekobot.app.ServiceContainer.gson.toJsonTree(payload)
        if (isNew) {
            launchResult(
                block = { repo.createCharacter(json) },
                onSuccess = { onSuccess() }
            )
        } else {
            val id = _character.value?.id ?: return
            launchResult(
                block = { repo.updateCharacter(id, json) },
                onSuccess = { onSuccess() }
            )
        }
    }

    /** 删除当前角色 */
    fun delete(onSuccess: () -> Unit) {
        val id = _character.value?.id ?: return
        launchResult(
            block = { repo.deleteCharacter(id) },
            onSuccess = { onSuccess() }
        )
    }
}

/**
 * 角色详情/编辑页：可编辑角色各字段，保存或删除。
 * characterId == "new" 表示新建模式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterDetailScreen(
    characterId: String,
    onBack: () -> Unit
) {
    // 用工厂传入 characterId，key 区分不同角色实例
    val vm: CharacterViewModel = viewModel(
        key = "char_$characterId",
        factory = viewModelFactory { initializer { CharacterViewModel(characterId) } }
    )
    val loading by vm.loading.collectAsState()
    val name by vm.name.collectAsState()
    val description by vm.description.collectAsState()
    val avatar by vm.avatar.collectAsState()
    val portrait by vm.portrait.collectAsState()
    val basicInfo by vm.basicInfo.collectAsState()
    val personality by vm.personality.collectAsState()
    val firstMessage by vm.firstMessage.collectAsState()
    val scenario by vm.scenario.collectAsState()
    val dialogExamples by vm.dialogExamples.collectAsState()
    val responseFormat by vm.responseFormat.collectAsState()
    val rulesText by vm.rulesText.collectAsState()
    val systemPrompt by vm.systemPrompt.collectAsState()
    val tagsText by vm.tagsText.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }

    val isNew = characterId == "new"

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) "新建角色" else "编辑角色",
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface,
                    titleContentColor = OnSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = OnSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.save(onBack) }) {
                        Icon(Icons.Filled.Save, contentDescription = "保存", tint = Primary)
                    }
                    if (!isNew) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = ErrorRed)
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
                .background(BgDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 角色名（必填）
                LabeledField(label = "角色名 *", value = name, onValueChange = { vm.name.value = it }, singleLine = true)
                // 描述
                LabeledField(
                    label = "描述", value = description, onValueChange = { vm.description.value = it },
                    singleLine = false
                )
                // 头像图标（font-awesome 类名，如 fas fa-cat）
                LabeledField(
                    label = "头像图标（fas fa-cat 等）", value = avatar, onValueChange = { vm.avatar.value = it },
                    singleLine = true
                )
                // 立绘路径/URL
                LabeledField(
                    label = "立绘路径（portrait）", value = portrait, onValueChange = { vm.portrait.value = it },
                    singleLine = true
                )
                // 基础信息（多行）
                LabeledField(
                    label = "基础信息（basicInfo，身高/年龄/职业等）",
                    value = basicInfo,
                    onValueChange = { vm.basicInfo.value = it },
                    singleLine = false
                )
                // 人格设定
                LabeledField(
                    label = "人格设定", value = personality, onValueChange = { vm.personality.value = it },
                    singleLine = false
                )
                // 首条消息
                LabeledField(
                    label = "首条消息（firstMessage）",
                    value = firstMessage, onValueChange = { vm.firstMessage.value = it },
                    singleLine = false
                )
                // 场景
                LabeledField(
                    label = "场景", value = scenario, onValueChange = { vm.scenario.value = it },
                    singleLine = false
                )
                // 对话示例
                LabeledField(
                    label = "对话示例（exampleDialogues）",
                    value = dialogExamples, onValueChange = { vm.dialogExamples.value = it },
                    singleLine = false
                )
                // 回复格式
                LabeledField(
                    label = "回复格式（responseFormat）",
                    value = responseFormat, onValueChange = { vm.responseFormat.value = it },
                    singleLine = false
                )
                // 规则（每行一条）
                LabeledField(
                    label = "规则（rules，每行一条）", value = rulesText, onValueChange = { vm.rulesText.value = it },
                    singleLine = false
                )
                // 系统提示词（只读展示，后端自动生成）
                if (systemPrompt.isNotBlank()) {
                    LabeledField(
                        label = "系统提示词（后端自动生成，只读）",
                        value = systemPrompt,
                        onValueChange = { vm.systemPrompt.value = it },
                        singleLine = false
                    )
                }
                // 标签（逗号分隔）
                LabeledField(
                    label = "标签（逗号分隔）", value = tagsText, onValueChange = { vm.tagsText.value = it },
                    singleLine = true
                )
                Spacer(Modifier.height(24.dp))
            }
            LoadingOverlay(visible = loading)
        }
    }

    // 删除确认弹窗
    if (showDeleteDialog) {
        NekoDialog(
            onDismiss = { showDeleteDialog = false },
            title = "删除角色",
            message = "确定要删除该角色吗？此操作不可撤销。",
            confirmText = "删除",
            cancelText = "取消",
            onConfirm = {
                showDeleteDialog = false
                vm.delete(onBack)
            },
            onCancel = { showDeleteDialog = false }
        )
    }
}

/** 带标签的输入框：支持单行/多行 */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 8,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = OnSurface,
                unfocusedTextColor = OnSurface,
                focusedBorderColor = Primary,
                unfocusedBorderColor = Outline,
                cursorColor = Primary,
                focusedContainerColor = BgSurfaceVariant,
                unfocusedContainerColor = BgSurfaceVariant
            ),
            keyboardOptions = if (singleLine) KeyboardOptions(keyboardType = KeyboardType.Text) else KeyboardOptions.Default
        )
    }
}
