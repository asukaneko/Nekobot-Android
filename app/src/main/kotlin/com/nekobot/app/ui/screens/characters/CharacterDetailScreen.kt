package com.nekobot.app.ui.screens.characters

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val _generatedPortrait = MutableStateFlow<String?>(null)
    val generatedPortrait: StateFlow<String?> = _generatedPortrait.asStateFlow()
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
    val mood = MutableStateFlow(string(R.string.character_default_mood))

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
                    mood.value = obj.get("mood")?.takeIf { !it.isJsonNull }?.asString ?: string(R.string.character_default_mood)
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
            showToast(string(R.string.character_name_required))
            return
        }
        val rulesList = rulesText.value.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val tagsList = tagsText.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val portraitToSave = generatedPortrait.value ?: portrait.value

        val payload = buildMap<String, Any?> {
            put("name", nameVal)
            description.value.trim().takeIf { it.isNotBlank() }?.let { put("description", it) }
            avatar.value.trim().takeIf { it.isNotBlank() }?.let { put("avatar", it) }
            portraitToSave.trim().takeIf { it.isNotBlank() }?.let { put("portrait", it) }
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
                addProperty("mood", mood.value.trim().ifBlank { string(R.string.character_default_mood) })
            }
            put("state", stateObj)
            // 显式不传：id / systemPrompt / greeting / created_at / updated_at
        }
        val json = com.nekobot.app.ServiceContainer.gson.toJsonTree(payload)
        if (isNew) {
            launchResult(
                block = { unified.createCharacter(json) },
                onSuccess = {
                    portrait.value = portraitToSave
                    _generatedPortrait.value = null
                    onSuccess()
                }
            )
        } else {
            val id = _character.value?.id ?: return
            launchResult(
                block = { unified.updateCharacter(id, json) },
                onSuccess = {
                    portrait.value = portraitToSave
                    _generatedPortrait.value = null
                    onSuccess()
                }
            )
        }
    }

    /** 用户手动修改立绘时，以用户选择的结果为准。 */
    fun updatePortrait(value: String) {
        portrait.value = value
        _generatedPortrait.value = null
    }

    /** 调用 AI 翻译角色卡并回填编辑字段，翻译结果由用户点击保存后持久化。 */
    fun translate(targetLanguage: String) {
        if (isNew) return
        val source = (_character.value ?: CharacterPreset()).copy(
            name = name.value,
            description = description.value,
            avatar = avatar.value,
            portrait = portrait.value,
            basicInfo = basicInfo.value,
            personality = personality.value,
            firstMessage = firstMessage.value,
            scenario = scenario.value,
            exampleDialogues = dialogExamples.value,
            responseFormat = responseFormat.value,
            rules = rulesText.value.lines().map { it.trim() }.filter { it.isNotEmpty() },
            tags = tagsText.value.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            state = com.google.gson.JsonObject().apply {
                addProperty("affection", affection.value)
                addProperty("trust", trust.value)
                addProperty("familiarity", familiarity.value)
                addProperty("dependency", dependency.value)
                addProperty("security", security.value)
                addProperty("jealousy", jealousy.value)
                addProperty("mood", mood.value)
            },
            systemPrompt = systemPrompt.value
        )
        launchResult(
            block = { unified.aiTranslateCharacter(source, targetLanguage) },
            onSuccess = { translated ->
                _character.value = translated
                name.value = translated.name.orEmpty()
                description.value = translated.description.orEmpty()
                basicInfo.value = translated.basicInfo.orEmpty()
                personality.value = translated.personality.orEmpty()
                firstMessage.value = translated.firstMessage.orEmpty()
                scenario.value = translated.scenario.orEmpty()
                dialogExamples.value = translated.exampleDialogues.orEmpty()
                responseFormat.value = translated.responseFormat.orEmpty()
                rulesText.value = translated.rules.orEmpty().joinToString("\n")
                tagsText.value = translated.tags.orEmpty().joinToString(", ")
                systemPrompt.value = translated.systemPrompt.orEmpty()
                showToast(string(R.string.character_translate_success))
            }
        )
    }

    /** 删除当前角色 */
    fun delete(onSuccess: () -> Unit) {
        val id = _character.value?.id ?: return
        launchResult(
            block = { unified.deleteCharacter(id) },
            onSuccess = { onSuccess() }
        )
    }

    /**
     * AI 生成立绘：远程模式提交异步任务并轮询直到完成；本地模式同步生成直接返回结果。
     * 成功后将 URL 写入 portrait 字段。需先填写角色名。
     */
    fun generatePortraitAI() {
        val characterName = name.value.trim()
        if (characterName.isBlank()) {
            showToast(string(R.string.character_name_empty))
            return
        }
        launchResult(
            block = {
                unified.generatePortrait(
                    characterName = characterName,
                    description = description.value.trim(),
                    basicInfo = basicInfo.value.trim(),
                    personality = personality.value.trim()
                )
            },
            onSuccess = { result ->
                val obj = result?.takeIf { it.isJsonObject }?.asJsonObject
                val success = obj?.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                if (!success) {
                    val needConfig = obj?.get("need_config")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    val error = obj?.get("error")?.takeIf { !it.isJsonNull }?.asString
                    showToast(if (needConfig) string(R.string.character_portrait_no_model) else (error ?: string(R.string.character_portrait_generate_failed)))
                    return@launchResult
                }
                // 本地模式：同步生成直接返回 completed + portrait_url，无需轮询
                val status = obj?.get("status")?.takeIf { !it.isJsonNull }?.asString
                val portraitUrl = obj?.get("portrait_url")?.takeIf { !it.isJsonNull }?.asString
                if (status == "completed" && !portraitUrl.isNullOrBlank()) {
                    applyGeneratedPortrait(portraitUrl)
                    return@launchResult
                }
                // 远程模式：取 task_id 进入轮询
                val taskId = obj?.get("task_id")?.takeIf { !it.isJsonNull }?.asString
                if (taskId.isNullOrBlank()) {
                    showToast(string(R.string.character_portrait_submit_failed))
                    return@launchResult
                }
                showToast(string(R.string.character_portrait_generating))
                pollPortraitTask(taskId)
            }
        )
    }

    /** 保留 AI 生成结果供预览，保存角色卡时才会替换当前立绘。 */
    private fun applyGeneratedPortrait(portraitUrl: String) {
        _generatedPortrait.value = portraitUrl
        showToast(string(R.string.character_portrait_save_manually))
    }

    /** 轮询 AI 立绘生成任务状态，完成或失败时结束。 */
    private fun pollPortraitTask(taskId: String) {
        viewModelScope.launch {
            setLoading(true)
            try {
                var attempts = 0
                val maxAttempts = 60  // 最多轮询 60 次（约 2 分钟）
                var resultUrl: String? = null
                while (attempts < maxAttempts) {
                    delay(2000)
                    when (val res = unified.getPortraitGenerationStatus(taskId)) {
                        is Resource.Success -> {
                            val obj = res.data?.takeIf { it.isJsonObject }?.asJsonObject
                            when (obj?.get("status")?.takeIf { !it.isJsonNull }?.asString) {
                                "completed" -> {
                                    resultUrl = obj?.get("portrait_url")?.takeIf { !it.isJsonNull }?.asString
                                    if (!resultUrl.isNullOrBlank()) break
                                }
                                "failed" -> {
                                    val error = obj?.get("error")?.takeIf { !it.isJsonNull }?.asString ?: string(R.string.character_portrait_generate_failed)
                                    throw Exception(error)
                                }
                            }
                        }
                        is Resource.Error -> throw Exception(res.message)
                        else -> {}
                    }
                    attempts++
                }
                if (!resultUrl.isNullOrBlank()) {
                    applyGeneratedPortrait(resultUrl)
                } else {
                    showToast(string(R.string.character_portrait_timeout))
                }
            } catch (e: Exception) {
                showToast(e.message ?: string(R.string.character_portrait_generate_failed))
            } finally {
                setLoading(false)
            }
        }
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
    val loading by vm.loading.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val description by vm.description.collectAsStateWithLifecycle()
    val avatar by vm.avatar.collectAsStateWithLifecycle()
    val portrait by vm.portrait.collectAsStateWithLifecycle()
    val generatedPortrait by vm.generatedPortrait.collectAsStateWithLifecycle()
    val basicInfo by vm.basicInfo.collectAsStateWithLifecycle()
    val personality by vm.personality.collectAsStateWithLifecycle()
    val firstMessage by vm.firstMessage.collectAsStateWithLifecycle()
    val scenario by vm.scenario.collectAsStateWithLifecycle()
    val dialogExamples by vm.dialogExamples.collectAsStateWithLifecycle()
    val responseFormat by vm.responseFormat.collectAsStateWithLifecycle()
    val rulesText by vm.rulesText.collectAsStateWithLifecycle()
    val systemPrompt by vm.systemPrompt.collectAsStateWithLifecycle()
    val tagsText by vm.tagsText.collectAsStateWithLifecycle()
    val affection by vm.affection.collectAsStateWithLifecycle()
    val trust by vm.trust.collectAsStateWithLifecycle()
    val familiarity by vm.familiarity.collectAsStateWithLifecycle()
    val dependency by vm.dependency.collectAsStateWithLifecycle()
    val security by vm.security.collectAsStateWithLifecycle()
    val jealousy by vm.jealousy.collectAsStateWithLifecycle()
    val mood by vm.mood.collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf("en") }

    val isNew = characterId == "new"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 订阅全局 appMode Flow，模式切换时自动刷新
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val isLocalMode = appMode == AppMode.LOCAL
    val toast by vm.toast.collectAsStateWithLifecycle()
    var fullscreenPortrait by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) {
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    // 立绘图片文件选择器
    val portraitLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                vm.setLoading(true)
                try {
                    val path = withContext(Dispatchers.IO) { saveImageAndGetPath(context, uri, isLocalMode, "portrait") }
                    if (path != null) vm.updatePortrait(path)
                    else vm.showToast(if (isLocalMode) context.getString(R.string.character_image_load_failed) else context.getString(R.string.character_portrait_upload_failed))
                } finally {
                    vm.setLoading(false)
                }
            }
        }
    }
    // 头像图片文件选择器
    val avatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                vm.setLoading(true)
                try {
                    val path = withContext(Dispatchers.IO) { saveImageAndGetPath(context, uri, isLocalMode, "avatar") }
                    if (path != null) vm.avatar.value = path
                    else vm.showToast(if (isLocalMode) context.getString(R.string.character_image_load_failed) else context.getString(R.string.character_avatar_upload_failed))
                } finally {
                    vm.setLoading(false)
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isNew) stringResource(R.string.characters_new) else stringResource(R.string.character_edit_title),
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
                    if (!isNew) {
                        IconButton(onClick = { showTranslateDialog = true }) {
                            Icon(
                                Icons.Filled.Language,
                                contentDescription = stringResource(R.string.character_translate),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = { vm.save(onBack) }) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.common_save), tint = MaterialTheme.colorScheme.primary)
                    }
                    if (!isNew) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
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
                LabeledField(label = stringResource(R.string.character_name_label), value = name, onValueChange = { vm.name.value = it }, singleLine = true)
                // 描述
                LabeledField(
                    label = stringResource(R.string.character_description_field), value = description, onValueChange = { vm.description.value = it },
                    singleLine = false
                )
                // 头像图标（font-awesome 类名，如 fas fa-cat）或上传图片
                LabeledFieldWithUpload(
                    label = stringResource(R.string.character_avatar_icon_label),
                    value = avatar,
                    onValueChange = { vm.avatar.value = it },
                    onUploadClick = {
                        avatarLauncher.launch(arrayOf("image/*"))
                    }
                )
                // 立绘路径/URL 或上传图片
                LabeledFieldWithUpload(
                    label = stringResource(R.string.character_portrait_path_label),
                    value = portrait,
                    onValueChange = vm::updatePortrait,
                    onUploadClick = {
                        portraitLauncher.launch(arrayOf("image/*"))
                    },
                    onAiGenerateClick = { vm.generatePortraitAI() }
                )
                // AI 立绘只作未保存预览，返回编辑页不会改变原立绘。
                PortraitPreview(
                    portrait = generatedPortrait ?: portrait,
                    hasUnsavedGeneratedPortrait = generatedPortrait != null,
                    onClick = { fullscreenPortrait = it }
                )
                // 基础信息（多行）
                LabeledField(
                    label = stringResource(R.string.character_basic_info_label),
                    value = basicInfo,
                    onValueChange = { vm.basicInfo.value = it },
                    singleLine = false
                )
                // 人格设定
                LabeledField(
                    label = stringResource(R.string.character_personality), value = personality, onValueChange = { vm.personality.value = it },
                    singleLine = false
                )
                // 首条消息
                LabeledField(
                    label = stringResource(R.string.character_first_message_label),
                    value = firstMessage, onValueChange = { vm.firstMessage.value = it },
                    singleLine = false
                )
                // 场景
                LabeledField(
                    label = stringResource(R.string.character_scenario), value = scenario, onValueChange = { vm.scenario.value = it },
                    singleLine = false
                )
                // 对话示例
                LabeledField(
                    label = stringResource(R.string.character_dialog_examples_label),
                    value = dialogExamples, onValueChange = { vm.dialogExamples.value = it },
                    singleLine = false
                )
                // 回复格式
                LabeledField(
                    label = stringResource(R.string.character_response_format_label),
                    value = responseFormat, onValueChange = { vm.responseFormat.value = it },
                    singleLine = false
                )
                // 规则（每行一条）
                LabeledField(
                    label = stringResource(R.string.character_rules_label), value = rulesText, onValueChange = { vm.rulesText.value = it },
                    singleLine = false
                )
                // 系统提示词（只读展示，后端自动生成）
                if (systemPrompt.isNotBlank()) {
                    LabeledField(
                        label = stringResource(R.string.character_system_prompt_label),
                        value = systemPrompt,
                        onValueChange = { vm.systemPrompt.value = it },
                        singleLine = false
                    )
                }
                // 标签（逗号分隔）
                LabeledField(
                    label = stringResource(R.string.character_tags_label), value = tagsText, onValueChange = { vm.tagsText.value = it },
                    singleLine = true
                )
                // 六维初始关系状态
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.character_initial_state),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                DimSlider(label = stringResource(R.string.character_dim_affection), value = affection, onValueChange = { vm.affection.value = it })
                DimSlider(label = stringResource(R.string.character_dim_trust), value = trust, onValueChange = { vm.trust.value = it })
                DimSlider(label = stringResource(R.string.character_dim_familiarity), value = familiarity, onValueChange = { vm.familiarity.value = it })
                DimSlider(label = stringResource(R.string.character_dim_dependency), value = dependency, onValueChange = { vm.dependency.value = it })
                DimSlider(label = stringResource(R.string.character_dim_security), value = security, onValueChange = { vm.security.value = it })
                DimSlider(label = stringResource(R.string.character_dim_jealousy), value = jealousy, onValueChange = { vm.jealousy.value = it })
                // 初始心情
                LabeledField(
                    label = stringResource(R.string.character_initial_mood_label),
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
            title = stringResource(R.string.character_delete_title),
            message = stringResource(R.string.character_delete_confirm),
            confirmText = stringResource(R.string.common_delete),
            cancelText = stringResource(R.string.common_cancel),
            onConfirm = {
                showDeleteDialog = false
                vm.delete(onBack)
            },
            onCancel = { showDeleteDialog = false }
        )
    }

    if (showTranslateDialog) {
        val languages = listOf(
            "zh" to stringResource(R.string.character_translate_language_zh),
            "en" to stringResource(R.string.character_translate_language_en),
            "ja" to stringResource(R.string.character_translate_language_ja),
            "ko" to stringResource(R.string.character_translate_language_ko)
        )
        AlertDialog(
            onDismissRequest = { showTranslateDialog = false },
            title = { Text(stringResource(R.string.character_translate_target_language)) },
            text = {
                Column {
                    languages.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedLanguage = code },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLanguage == code,
                                onClick = { selectedLanguage = code }
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTranslateDialog = false
                    vm.translate(selectedLanguage)
                }) {
                    Text(stringResource(R.string.character_translate_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTranslateDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // 全屏立绘查看
    val fsUrl = fullscreenPortrait
    if (fsUrl != null) {
        FullscreenPortraitDialog(url = fsUrl, onDismiss = { fullscreenPortrait = null })
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

/** 带上传按钮的标签输入框：单行文本 + 右侧上传图标按钮（可选 AI 生成按钮） */
@Composable
private fun LabeledFieldWithUpload(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onUploadClick: () -> Unit,
    onAiGenerateClick: (() -> Unit)? = null
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
                    contentDescription = stringResource(R.string.character_upload_image),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (onAiGenerateClick != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onAiGenerateClick) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = stringResource(R.string.character_ai_generate_portrait),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

/**
 * 把 Uri 图片保存到本地（本地模式）或上传到服务器（远程模式）。
 * - 本地模式：保存到 filesDir/portraits，返回 file:// URI 字符串
 * - 远程模式：上传至 /api/personality/portrait，返回服务器相对 URL 字符串
 *   （保存时 portrait/avatar 字段直接存 URL，由后端静态服务提供图片）
 */
private suspend fun saveImageAndGetPath(
    context: Context,
    uri: Uri,
    isLocalMode: Boolean,
    prefix: String
): String? {
    return try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val ext = guessImageExt(context, uri, bytes)
        if (isLocalMode) {
            // 本地模式：保存到 filesDir/portraits，返回 file:// URI
            val dir = File(context.filesDir, "portraits")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${prefix}_${UUID.randomUUID().toString().take(16)}.$ext")
            file.writeBytes(bytes)
            android.net.Uri.fromFile(file).toString()
        } else {
            // 远程模式：上传到服务器，返回 URL 字符串
            val fileName = "${prefix}_${UUID.randomUUID().toString().take(16)}.$ext"
            val mime = context.contentResolver.getType(uri) ?: "image/$ext"
            val mediaType = mime.toMediaTypeOrNull() ?: "image/png".toMediaTypeOrNull()
            val body = bytes.toRequestBody(mediaType)
            val part = okhttp3.MultipartBody.Part.createFormData("file", fileName, body)
            when (val res = com.nekobot.app.ServiceContainer.unified.uploadPortrait(part)) {
                is com.nekobot.app.data.repository.Resource.Success -> res.data
                is com.nekobot.app.data.repository.Resource.Error -> {
                    android.util.Log.e("CharacterDetail", "立绘上传失败: ${res.message}")
                    null
                }
                is com.nekobot.app.data.repository.Resource.Loading -> null
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("CharacterDetail", "saveImageAndGetPath error", e)
        null
    }
}

/** 从 Uri、文件名和文件头推断图片扩展名，避免 WebP 被内容提供商误报成 png。 */
private fun guessImageExt(context: Context, uri: Uri, bytes: ByteArray): String {
    val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
    val displayName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull().orEmpty().lowercase()
    val nameExt = displayName.substringAfterLast('.', "").trim()
    val isWebpHeader = bytes.size >= 12 &&
        String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    return when {
        mime.contains("webp") || nameExt == "webp" || isWebpHeader -> "webp"
        mime.contains("png") || nameExt == "png" -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("gif") -> "gif"
        nameExt == "jpg" || nameExt == "jpeg" -> "jpg"
        nameExt == "gif" -> "gif"
        else -> "png"
    }
}

/** 把相对路径图片地址拼成完整 URL（兼容本地 file: 路径） */
private fun resolveImageUrl(path: String): String {
    if (path.startsWith("file:") || path.startsWith("content://")) return path
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = ServiceContainer.network.baseUrl().trimEnd('/')
    return base + "/" + path.trimStart('/')
}

/**
 * 立绘预览卡片：非空时展示缩略图，点击进入全屏查看。
 */
@Composable
private fun PortraitPreview(
    portrait: String,
    hasUnsavedGeneratedPortrait: Boolean,
    onClick: (String) -> Unit
) {
    if (portrait.isBlank()) return
    val url = remember(portrait) { resolveImageUrl(portrait) }
    Column {
        Text(
            text = stringResource(R.string.character_portrait_preview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasUnsavedGeneratedPortrait) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.character_portrait_save_manually),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick(url) },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.character_portrait_preview_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 全屏立绘查看：支持双指缩放与拖动，单击或点击关闭按钮退出。
 */
@Composable
private fun FullscreenPortraitDialog(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
        ) {
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.character_portrait_large),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
                    .clickable { onDismiss() }
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { onDismiss() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.common_close),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
