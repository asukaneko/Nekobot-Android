package com.nekobot.app.ui.screens.characters

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import com.nekobot.app.ui.components.BorderlessAssistChip as AssistChip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.CreateSessionRequest
import com.nekobot.app.data.model.RELATIONSHIP_STATE_SOURCE_INHERIT
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.MarkdownText
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.RelationshipStateSourceSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 角色详情只读视图 ViewModel：仅加载展示，不维护编辑字段。
 */
class CharacterViewModelView(characterId: String) : com.nekobot.app.ui.BaseViewModel() {

    private val _character = MutableStateFlow<CharacterPreset?>(null)
    val character: StateFlow<CharacterPreset?> = _character.asStateFlow()

    init { load(characterId) }

    fun load(id: String) {
        launchResult(
            block = { unified.getCharacter(id) },
            onSuccess = { _character.value = it }
        )
    }

    fun delete(onSuccess: () -> Unit) {
        val id = _character.value?.id ?: return
        launchResult(
            block = { unified.deleteCharacter(id) },
            onSuccess = { onSuccess() }
        )
    }

    /**
     * 用当前角色直接创建一个名为"新会话"的会话，成功后回调 [onSuccess] 传入新会话 ID。
     * 复用与 SessionsScreen 一致的请求结构（character 模式）。
     */
    fun createSessionWithCharacter(
        relationshipStateSource: String,
        onSuccess: (String) -> Unit,
        onError: () -> Unit = {}
    ) {
        val c = _character.value ?: return
        val req = CreateSessionRequest(
            name = string(R.string.character_new_session),
            sessionMode = "character",
            characterId = c.id,
            systemPrompt = c.systemPrompt?.takeIf { it.isNotBlank() },
            firstMessage = c.firstMessage?.takeIf { it.isNotBlank() },
            scenario = c.scenario?.takeIf { it.isNotBlank() },
            senderName = c.displayName,
            senderAvatar = c.avatar,
            senderPortrait = c.portrait,
            userId = ServiceContainer.prefs.username.takeIf { it.isNotBlank() },
            relationshipStateSource = relationshipStateSource
        )
        launchResult(
            block = { unified.createSession(req) },
            onSuccess = { session ->
                val id = session?.id
                if (id != null) onSuccess(id) else onError()
            },
            onError = {
                showError(it)
                onError()
            }
        )
    }

    /**
     * 导出当前角色卡为 JSON 文件到下载目录。
     * - Android 10+ 走 MediaStore.Downloads（作用域存储）
     * - Android 9 及以下走 Environment.DIRECTORY_DOWNLOADS 公共目录
     *
     * 返回写入的文件名，失败返回 null。
     */
    suspend fun exportToJson(context: Context): String? = withContext(Dispatchers.IO) {
        val c = _character.value ?: return@withContext null
        // 使用 setPrettyPrinting 让 JSON 更易读
        val pretty = com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()
        val json = pretty.toJson(c)
        val safeName = (c.name ?: "character").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val fileName = "${safeName}_${System.currentTimeMillis()}.json"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+：通过 MediaStore.Downloads 写入
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri: Uri = resolver.insert(collection, values)
                    ?: return@withContext null
                resolver.openOutputStream(uri)?.use { os: OutputStream ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                } ?: return@withContext null
            } else {
                // Android 9 及以下：直接写公共 Downloads 目录
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val target = File(downloadsDir, fileName)
                FileOutputStream(target).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            fileName
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 角色详情只读展示页：点击角色卡片进入；右上角"编辑"按钮进入 [CharacterDetailScreen]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterViewScreen(
    characterId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenChat: (String) -> Unit = {}
) {
    val vm: CharacterViewModelView = viewModel(
        key = "char_view_$characterId",
        factory = viewModelFactory { initializer { CharacterViewModelView(characterId) } }
    )
    val character by vm.character.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var creatingSession by remember { mutableStateOf(false) }
    var showRelationshipSourceDialog by remember { mutableStateOf(false) }
    var relationshipStateSource by remember { mutableStateOf(RELATIONSHIP_STATE_SOURCE_INHERIT) }
    val appMode by ServiceContainer.appModeFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun createCharacterSession(source: String) {
        if (creatingSession) return
        creatingSession = true
        vm.createSessionWithCharacter(
            relationshipStateSource = source,
            onSuccess = { sessionId ->
                creatingSession = false
                onOpenChat(sessionId)
            },
            onError = { creatingSession = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        character?.displayName ?: stringResource(R.string.character_detail_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                    // 导出 JSON：将当前角色卡保存到下载目录
                    IconButton(
                        onClick = {
                            if (!exporting) {
                                exporting = true
                                scope.launch {
                                    val name = vm.exportToJson(context)
                                    exporting = false
                                    exportResult = name
                                }
                            }
                        },
                        enabled = !exporting && character != null
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Filled.IosShare, contentDescription = stringResource(R.string.character_export_json), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { character?.id?.let(onEdit) }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.common_edit), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
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
                val c = character
                if (c != null) {
                    // 顶部：头像/立绘 + 名称 + 标签
                    HeaderCard(c)
                    // 用此角色新建会话
                    Button(
                        onClick = {
                            if (appMode == AppMode.LOCAL) showRelationshipSourceDialog = true
                            else createCharacterSession(RELATIONSHIP_STATE_SOURCE_INHERIT)
                        },
                        enabled = !creatingSession,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (creatingSession) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(R.string.character_new_session_button))
                    }
                    // 各字段分区
                    FieldSection(stringResource(R.string.character_field_basic_info), c.basicInfo)
                    FieldSection(stringResource(R.string.character_personality), c.personality)
                    FieldSection(stringResource(R.string.character_scenario), c.scenario)
                    FieldSection(stringResource(R.string.character_field_first_message), c.firstMessage)
                    FieldSection(stringResource(R.string.character_field_dialog_examples), c.exampleDialogues)
                    FieldSection(stringResource(R.string.character_field_response_format), c.responseFormat)
                    if (!c.rules.isNullOrEmpty()) {
                        FieldSection(stringResource(R.string.character_field_rules), c.rules.joinToString("\n") { "• $it" })
                    }
                    if (!c.systemPrompt.isNullOrBlank()) {
                        FieldSection(stringResource(R.string.character_field_system_prompt), c.systemPrompt)
                    }
                    // 六维关系状态
                    val st = c.state
                    if (st != null && st.isJsonObject) {
                        val obj = st.asJsonObject
                        StateCard(obj)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            LoadingOverlay(visible = loading)
        }
    }

    if (showRelationshipSourceDialog) {
        NekoDialog(
            onDismiss = { showRelationshipSourceDialog = false },
            title = stringResource(R.string.sessions_relationship_state_source),
            confirmText = stringResource(R.string.common_create),
            onConfirm = {
                showRelationshipSourceDialog = false
                createCharacterSession(relationshipStateSource)
            },
            onCancel = { showRelationshipSourceDialog = false },
            content = {
                RelationshipStateSourceSelector(
                    selectedSource = relationshipStateSource,
                    onSourceSelected = { relationshipStateSource = it },
                    initialState = character?.state
                )
            }
        )
    }

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

    // 导出结果提示
    exportResult?.let { name ->
        NekoDialog(
            onDismiss = { exportResult = null },
            title = if (name.isNotEmpty()) stringResource(R.string.character_export_success) else stringResource(R.string.character_export_failed),
            message = if (name.isNotEmpty())
                stringResource(R.string.character_export_success_msg, name)
            else
                stringResource(R.string.character_export_failed_msg),
            confirmText = stringResource(R.string.character_got_it),
            cancelText = null,
            onConfirm = { exportResult = null },
            onCancel = null
        )
    }
}

/** 顶部头像/立绘 + 名称 + 标签卡片 */
@Composable
private fun HeaderCard(c: CharacterPreset) {
    var fullscreenUrl by remember { mutableStateOf<String?>(null) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val avatarUrl = c.avatarUrl?.let { resolveImageUrl(it) }
            AsyncImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.character_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        if (!avatarUrl.isNullOrBlank()) fullscreenUrl = avatarUrl
                    }
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = c.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!c.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = c.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        val tags = c.tags
        if (!tags.isNullOrEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                tags.forEach { tag ->
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            labelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }

    // 全屏立绘预览：支持双指缩放与拖动，单击或点击关闭按钮退出
    val url = fullscreenUrl
    if (url != null) {
        Dialog(onDismissRequest = { fullscreenUrl = null }) {
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
                    contentDescription = stringResource(R.string.character_portrait),
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
                        .clickable { fullscreenUrl = null }
                )
                // 顶部关闭按钮
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { fullscreenUrl = null }
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
}

/** 单个字段分区：标题 + Markdown 渲染内容（为空则不展示） */
@Composable
private fun FieldSection(title: String, content: String?) {
    if (content.isNullOrBlank()) return
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        MarkdownText(
            text = content,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 六维关系状态展示卡片 */
@Composable
private fun StateCard(state: com.google.gson.JsonObject) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.character_initial_state),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        val rows = listOf(
            stringResource(R.string.character_dim_affection) to "affection",
            stringResource(R.string.character_dim_trust) to "trust",
            stringResource(R.string.character_dim_familiarity) to "familiarity",
            stringResource(R.string.character_dim_dependency) to "dependency",
            stringResource(R.string.character_dim_security) to "security"
        )
        rows.forEach { (label, key) ->
            val v = state.get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(60.dp)
                )
                LinearProgressIndicator(
                    progress = { (v.coerceIn(0, 100) / 100f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = v.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(32.dp)
                )
            }
        }
        val mood = state.get("mood")?.takeIf { !it.isJsonNull }?.asString
        if (!mood.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.character_mood_label, mood),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 把相对路径图片地址拼成完整 URL（兼容本地 file: 路径） */
private fun resolveImageUrl(path: String): String {
    if (path.startsWith("file:") || path.startsWith("content://")) return path
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = ServiceContainer.network.baseUrl().trimEnd('/')
    return base + "/" + path.trimStart('/')
}
