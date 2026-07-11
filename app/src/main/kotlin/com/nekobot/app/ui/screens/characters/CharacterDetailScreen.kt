package com.nekobot.app.ui.screens.characters

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

    // 六维初始关系状态（0-100）+ 初始心情
    val affection = MutableStateFlow(50)
    val trust = MutableStateFlow(50)
    val familiarity = MutableStateFlow(30)
    val dependency = MutableStateFlow(30)
    val security = MutableStateFlow(50)
    val jealousy = MutableStateFlow(0)
    val mood = MutableStateFlow("平静")

    init {
        if (!isNew) load(characterId)
    }

    /** 加载现有角色并填充编辑字段 */
    fun load(id: String) {
        launchResult(
            block = { unified.getCharacter(id) },
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
                // 解析六维初始状态 + 心情
                val st = c.state
                if (st != null && st.isJsonObject) {
                    val obj = st.asJsonObject
                    affection.value = obj.get("affection")?.takeIf { !it.isJsonNull }?.asInt ?: 50
                    trust.value = obj.get("trust")?.takeIf { !it.isJsonNull }?.asInt ?: 50
                    familiarity.value = obj.get("familiarity")?.takeIf { !it.isJsonNull }?.asInt ?: 30
                    dependency.value = obj.get("dependency")?.takeIf { !it.isJsonNull }?.asInt ?: 30
                    security.value = obj.get("security")?.takeIf { !it.isJsonNull }?.asInt ?: 50
                    jealousy.value = obj.get("jealousy")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                    mood.value = obj.get("mood")?.takeIf { !it.isJsonNull }?.asString ?: "平静"
                }
            }
        )
    }

    /**
     * 保存：
     *  - POST 不传 id / systemPrompt / greeting（后端自动管理 id / created_at / systemPrompt，greeting 已合并到 firstMessage）
     *  - PUT 传部分字段做合并更新，不传 updated_at（后端管理）
     *  - state 包含六维初始关系值 + 初始心情
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
            // 六维初始关系状态 + 心情 → state JSON 对象
            val stateObj = com.google.gson.JsonObject().apply {
                addProperty("affection", affection.value)
                addProperty("trust", trust.value)
                addProperty("familiarity", familiarity.value)
                addProperty("dependency", dependency.value)
                addProperty("security", security.value)
                addProperty("jealousy", jealousy.value)
                addProperty("mood", mood.value.trim().ifBlank { "平静" })
            }
            put("state", stateObj)
            // 显式不传：id / systemPrompt / greeting / created_at / updated_at
        }
        val json = com.nekobot.app.ServiceContainer.gson.toJsonTree(payload)
        if (isNew) {
            launchResult(
                block = { unified.createCharacter(json) },
                onSuccess = { onSuccess() }
            )
        } else {
            val id = _character.value?.id ?: return
            launchResult(
                block = { unified.updateCharacter(id, json) },
                onSuccess = { onSuccess() }
            )
        }
    }

    /** 删除当前角色 */
    fun delete(onSuccess: () -> Unit) {
        val id = _character.value?.id ?: return
        launchResult(
            block = { unified.deleteCharacter(id) },
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
    val affection by vm.affection.collectAsState()
    val trust by vm.trust.collectAsState()
    val familiarity by vm.familiarity.collectAsState()
    val dependency by vm.dependency.collectAsState()
    val security by vm.security.collectAsState()
    val jealousy by vm.jealousy.collectAsState()
    val mood by vm.mood.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }

    val isNew = characterId == "new"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 订阅全局 appMode Flow，模式切换时自动刷新
    val appMode by ServiceContainer.appModeFlow.collectAsState()
    val isLocalMode = appMode == AppMode.LOCAL

    // 立绘图片文件选择器
    val portraitLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) { saveImageAndGetPath(context, uri, isLocalMode, "portrait") }
                if (path != null) vm.portrait.value = path
                else vm.showToast("图片加载失败")
            }
        }
    }
    // 头像图片文件选择器
    val avatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) { saveImageAndGetPath(context, uri, isLocalMode, "avatar") }
                if (path != null) vm.avatar.value = path
                else vm.showToast("图片加载失败")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) "新建角色" else "编辑角色",
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
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.save(onBack) }) {
                        Icon(Icons.Filled.Save, contentDescription = "保存", tint = MaterialTheme.colorScheme.primary)
                    }
                    if (!isNew) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
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
                // 头像图标（font-awesome 类名，如 fas fa-cat）或上传图片
                LabeledFieldWithUpload(
                    label = "头像图标（fas fa-cat 等）或图片",
                    value = avatar,
                    onValueChange = { vm.avatar.value = it },
                    onUploadClick = {
                        avatarLauncher.launch(arrayOf("image/*"))
                    }
                )
                // 立绘路径/URL 或上传图片
                LabeledFieldWithUpload(
                    label = "立绘路径（portrait）或图片",
                    value = portrait,
                    onValueChange = { vm.portrait.value = it },
                    onUploadClick = {
                        portraitLauncher.launch(arrayOf("image/*"))
                    }
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
                // 六维初始关系状态
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "初始关系状态",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                DimSlider(label = "好感", value = affection, onValueChange = { vm.affection.value = it })
                DimSlider(label = "信任", value = trust, onValueChange = { vm.trust.value = it })
                DimSlider(label = "熟悉", value = familiarity, onValueChange = { vm.familiarity.value = it })
                DimSlider(label = "依赖", value = dependency, onValueChange = { vm.dependency.value = it })
                DimSlider(label = "安全感", value = security, onValueChange = { vm.security.value = it })
                DimSlider(label = "嫉妒", value = jealousy, onValueChange = { vm.jealousy.value = it })
                // 初始心情
                LabeledField(
                    label = "初始心情（如 平静/开心/害羞/愤怒）",
                    value = mood,
                    onValueChange = { vm.mood.value = it },
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

/** 六维滑块：标签 + 数值 + 滑块（0-100） */
@Composable
private fun DimSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..100f,
            steps = 99,
            modifier = Modifier.fillMaxWidth()
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            keyboardOptions = if (singleLine) KeyboardOptions(keyboardType = KeyboardType.Text) else KeyboardOptions.Default
        )
    }
}

/** 带上传按钮的标签输入框：单行文本 + 右侧上传图标按钮 */
@Composable
private fun LabeledFieldWithUpload(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onUploadClick: () -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                minLines = 1,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onUploadClick) {
                Icon(
                    Icons.Filled.Upload,
                    contentDescription = "上传图片",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 把 Uri 图片保存到本地（本地模式）或缓存目录（远程模式待上传）。
 * - 本地模式：保存到 filesDir/portraits，返回 file:// URI 字符串
 * - 远程模式：保存到 cacheDir 临时文件，返回 file:// URI 字符串
 *   （远程模式保存时会用此本地路径展示，提交时由后端处理 URL）
 */
private fun saveImageAndGetPath(
    context: Context,
    uri: Uri,
    isLocalMode: Boolean,
    prefix: String
): String? {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val ext = guessImageExt(context, uri)
        val dir = if (isLocalMode) {
            File(context.filesDir, "portraits")
        } else {
            File(context.cacheDir, "portraits")
        }
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${prefix}_${UUID.randomUUID().toString().take(16)}.$ext")
        file.writeBytes(bytes)
        android.net.Uri.fromFile(file).toString()
    } catch (e: Exception) {
        null
    }
}

/** 从 Uri 推断图片扩展名 */
private fun guessImageExt(context: Context, uri: Uri): String {
    val mime = context.contentResolver.getType(uri) ?: "image/png"
    return when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        else -> "png"
    }
}
