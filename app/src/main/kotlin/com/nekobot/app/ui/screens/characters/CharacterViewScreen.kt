package com.nekobot.app.ui.screens.characters

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.MarkdownText
import com.nekobot.app.ui.components.NekoDialog
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
    onEdit: (String) -> Unit
) {
    val vm: CharacterViewModelView = viewModel(
        key = "char_view_$characterId",
        factory = viewModelFactory { initializer { CharacterViewModelView(characterId) } }
    )
    val character by vm.character.collectAsState()
    val loading by vm.loading.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        character?.displayName ?: "角色详情",
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
                            contentDescription = "返回",
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
                            Icon(Icons.Filled.IosShare, contentDescription = "导出 JSON", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { character?.id?.let(onEdit) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
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
                    // 各字段分区
                    FieldSection("基础信息", c.basicInfo)
                    FieldSection("人格设定", c.personality)
                    FieldSection("场景", c.scenario)
                    FieldSection("首条消息", c.firstMessage)
                    FieldSection("对话示例", c.exampleDialogues)
                    FieldSection("回复格式", c.responseFormat)
                    if (!c.rules.isNullOrEmpty()) {
                        FieldSection("规则", c.rules.joinToString("\n") { "• $it" })
                    }
                    if (!c.systemPrompt.isNullOrBlank()) {
                        FieldSection("系统提示词（后端自动生成）", c.systemPrompt)
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

    // 导出结果提示
    exportResult?.let { name ->
        NekoDialog(
            onDismiss = { exportResult = null },
            title = if (name.isNotEmpty()) "导出成功" else "导出失败",
            message = if (name.isNotEmpty())
                "已导出到下载目录：\n$name"
            else
                "导出失败，请检查存储权限后重试。",
            confirmText = "知道了",
            cancelText = null,
            onConfirm = { exportResult = null },
            onCancel = null
        )
    }
}

/** 顶部头像/立绘 + 名称 + 标签卡片 */
@Composable
private fun HeaderCard(c: CharacterPreset) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val avatarUrl = c.avatarUrl?.let { resolveImageUrl(it) }
            AsyncImage(
                model = avatarUrl,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
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
            text = "初始关系状态",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        val rows = listOf(
            "好感" to "affection",
            "信任" to "trust",
            "熟悉" to "familiarity",
            "依赖" to "dependency",
            "安全感" to "security"
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
                text = "心情：$mood",
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
