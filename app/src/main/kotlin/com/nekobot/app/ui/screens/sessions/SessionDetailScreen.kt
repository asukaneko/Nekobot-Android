package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.UpdateSessionRequest
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoDialog
import com.nekobot.app.ui.components.SectionHeader
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.ErrorRed
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.Secondary
import com.nekobot.app.ui.theme.SuccessGreen
import com.nekobot.app.ui.theme.Tertiary
import com.nekobot.app.ui.theme.WarningAmber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话详情 ViewModel：加载单个会话、编辑名称 / 标签 / 置顶 / 收藏 / 系统提示词 /
 * 自动状态间隔 / 剧情模式等，支持保存、删除。
 */
class SessionDetailViewModel : BaseViewModel() {

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    val name = MutableStateFlow("")
    val tagsText = MutableStateFlow("")
    val pinned = MutableStateFlow(false)
    val favorite = MutableStateFlow(false)
    val systemPrompt = MutableStateFlow("")
    val autoStateInterval = MutableStateFlow<Int?>(null)
    val plotMode = MutableStateFlow(false)
    val plotRealTimeSync = MutableStateFlow(false)

    fun init(id: String) { load(id) }

    fun load(id: String) {
        launchResult(
            block = { repo.getSession(id) },
            onSuccess = { s ->
                _session.value = s
                name.value = s.name.orEmpty()
                tagsText.value = s.tags?.joinToString(", ").orEmpty()
                pinned.value = s.pinned == true
                favorite.value = s.favorite == true
                systemPrompt.value = s.systemPrompt.orEmpty()
                autoStateInterval.value = s.autoStateInterval
                plotMode.value = s.plotMode == true
                plotRealTimeSync.value = s.plotRealTimeSync == true
            }
        )
    }

    fun save(onSuccess: () -> Unit) {
        val s = _session.value ?: return
        val nameVal = name.value.trim()
        if (nameVal.isBlank()) {
            showToast("会话名不能为空")
            return
        }
        val tagsList = tagsText.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        launchResult(
            block = {
                repo.updateSession(
                    s.id.orEmpty(),
                    UpdateSessionRequest(
                        name = nameVal,
                        tags = tagsList,
                        pinned = pinned.value,
                        favorite = favorite.value,
                        systemPrompt = systemPrompt.value.ifBlank { null },
                        autoStateInterval = autoStateInterval.value,
                        plotMode = plotMode.value,
                        plotRealTimeSync = plotRealTimeSync.value
                    )
                )
            },
            onSuccess = {
                showToast("已保存")
                load(s.id.orEmpty())
                onSuccess()
            }
        )
    }

    fun delete(onSuccess: () -> Unit) {
        val s = _session.value ?: return
        launchResult(
            block = { repo.deleteSession(s.id.orEmpty()) },
            onSuccess = {
                showToast("已删除")
                onSuccess()
            }
        )
    }
}

/**
 * 会话详情页：参考 webui 会话详情弹窗，展示并编辑会话的全部元信息。
 * 分区：基本信息 / 标记 / 系统提示词 / 角色绑定 / 运行时状态 / 剧情模式 / 自动状态 / 只读信息。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val vm: SessionDetailViewModel = viewModel(key = "session_detail_$sessionId")
    val session by vm.session.collectAsState()
    val name by vm.name.collectAsState()
    val tagsText by vm.tagsText.collectAsState()
    val pinned by vm.pinned.collectAsState()
    val favorite by vm.favorite.collectAsState()
    val systemPrompt by vm.systemPrompt.collectAsState()
    val autoStateInterval by vm.autoStateInterval.collectAsState()
    val plotMode by vm.plotMode.collectAsState()
    val plotRealTimeSync by vm.plotRealTimeSync.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(sessionId) { vm.init(sessionId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("会话详情", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { session?.id?.let(onOpenChat) }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "进入对话", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { vm.save(onBack) }) {
                        Icon(Icons.Filled.Save, contentDescription = "保存", tint = MaterialTheme.colorScheme.primary)
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
            if (loading && session == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    session?.let { s ->
                        // === 1. 顶部：立绘 + 基本信息 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val portraitUrl = resolveAvatarUrl(s.portraitUrl)
                                Box(
                                    modifier = Modifier
                                        .size(width = 80.dp, height = 104.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!portraitUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = portraitUrl,
                                            contentDescription = s.characterName ?: "立绘",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                                    }
                                }
                                Spacer(Modifier.size(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(s.displayName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (!s.characterName.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("角色：${s.characterName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    // 类型 / 模式 badge
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        s.type?.let { BadgeChip(it) }
                                        s.sessionMode?.let { BadgeChip(it) }
                                        if (s.pinned == true) BadgeChip("置顶", MaterialTheme.colorScheme.primary)
                                        if (s.favorite == true) BadgeChip("收藏", WarningAmber)
                                        if (s.archived == true) BadgeChip("已归档", MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (s.readOnly == true) BadgeChip("只读", MaterialTheme.colorScheme.error)
                                    }
                                    s.messageCount?.let { count ->
                                        Spacer(Modifier.height(4.dp))
                                        Text("消息数：$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    s.updatedAt?.let { time ->
                                        Text("更新：${time.take(19)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        // === 2. 编辑基本信息 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = "基本信息")
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = name,
                                onValueChange = { vm.name.value = it },
                                label = { Text("会话名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = tagsText,
                                onValueChange = { vm.tagsText.value = it },
                                label = { Text("标签（逗号分隔）") },
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ToggleChipRow(
                                    label = if (pinned) "已置顶" else "置顶",
                                    selected = pinned,
                                    onClick = { vm.pinned.value = !pinned },
                                    modifier = Modifier.weight(1f)
                                )
                                ToggleChipRow(
                                    label = if (favorite) "已收藏" else "收藏",
                                    selected = favorite,
                                    onClick = { vm.favorite.value = !favorite },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // === 3. 系统提示词 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(
                                title = "系统提示词",
                                trailing = {
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(systemPrompt)) }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = systemPrompt,
                                onValueChange = { vm.systemPrompt.value = it },
                                label = { Text("System Prompt") },
                                minLines = 3,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // === 4. 角色绑定信息 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = "角色绑定")
                            Spacer(Modifier.height(8.dp))
                            DetailLine(label = "角色 ID", value = s.characterId ?: "—")
                            DetailLine(label = "角色名", value = s.characterName ?: "—")
                            DetailLine(label = "发送者名", value = s.senderName ?: "—")
                            DetailLine(label = "场景", value = s.scenario?.take(60) ?: "—")
                            s.characterIds?.takeIf { it.isNotEmpty() }?.let {
                                DetailLine(label = "群聊角色", value = it.joinToString(", "))
                            }
                        }

                        // === 5. 角色运行时状态 ===
                        s.characterRuntimeSnapshot?.takeIf { it.isJsonObject }?.asJsonObject?.let { snap ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                SectionHeader(title = "角色运行时状态")
                                Spacer(Modifier.height(8.dp))
                                // 心情 / 能量
                                val mood = snap.get("mood")?.asString
                                val intensity = snap.get("mood_intensity")?.let { if (it.isJsonPrimitive) it.asFloat else null }
                                val energy = snap.get("energy")?.let { if (it.isJsonPrimitive) it.asInt else null }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StateMiniCard("心情", mood, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                    StateMiniCard("强度", intensity?.let { "${(it * 100).toInt()}%" }, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                                    StateMiniCard("能量", energy?.toString(), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(8.dp))
                                // 关系数值
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    RelBar("好感", snap.get("affection")?.asInt, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                                    RelBar("信任", snap.get("trust")?.asInt, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                                    RelBar("熟悉", snap.get("familiarity")?.asInt, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                                    RelBar("依赖", snap.get("dependency")?.asInt, WarningAmber, Modifier.weight(1f))
                                    RelBar("安全", snap.get("security")?.asInt, SuccessGreen, Modifier.weight(1f))
                                    RelBar("嫉妒", snap.get("jealousy")?.asInt, MaterialTheme.colorScheme.error, Modifier.weight(1f))
                                }
                                // 表情
                                snap.get("visible_emotion")?.asString?.let {
                                    Spacer(Modifier.height(8.dp))
                                    DetailLine(label = "表象情绪", value = it)
                                }
                                snap.get("hidden_emotion")?.asString?.let {
                                    DetailLine(label = "内在情绪", value = it)
                                }
                            }
                        }

                        // === 6. 剧情模式 / 自动状态 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = "高级设置")
                            Spacer(Modifier.height(8.dp))
                            ToggleChipRow(
                                label = if (plotMode) "剧情模式：开" else "剧情模式：关",
                                selected = plotMode,
                                onClick = { vm.plotMode.value = !plotMode },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (plotMode) {
                                Spacer(Modifier.height(8.dp))
                                ToggleChipRow(
                                    label = if (plotRealTimeSync) "现实时间同步：开" else "现实时间同步：关",
                                    selected = plotRealTimeSync,
                                    onClick = { vm.plotRealTimeSync.value = !plotRealTimeSync },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            // 自动状态间隔下拉
                            Text("自动状态评估间隔", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(4.dp))
                            AutoStateIntervalSelector(
                                value = autoStateInterval,
                                onChange = { vm.autoStateInterval.value = it }
                            )
                        }

                        // === 7. 只读元信息 ===
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = "元信息")
                            Spacer(Modifier.height(8.dp))
                            DetailLine(label = "会话 ID", value = s.id.orEmpty())
                            DetailLine(label = "用户 ID", value = s.userId ?: "—")
                            DetailLine(label = "创建时间", value = s.createdAt?.take(19) ?: "—")
                            DetailLine(label = "更新时间", value = s.updatedAt?.take(19) ?: "—")
                            DetailLine(label = "公开", value = if (s.isPublic == true) "是" else "否")
                            DetailLine(label = "归档", value = if (s.archived == true) "是" else "否")
                            DetailLine(label = "只读", value = if (s.readOnly == true) "是" else "否")
                            s.lastMessage?.let {
                                DetailLine(label = "最后消息", value = it.take(50))
                            }
                        }

                        error?.let {
                            ErrorBanner(message = it, onRetry = {
                                vm.clearError()
                                vm.load(sessionId)
                            })
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        NekoDialog(
            onDismiss = { showDeleteDialog = false },
            title = "删除会话",
            message = "确定删除「${session?.displayName ?: ""}」吗？此操作不可撤销。",
            confirmText = "删除",
            onConfirm = {
                showDeleteDialog = false
                vm.delete(onBack)
            }
        )
    }
}

// ==================== 辅助组件 ====================

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgeChip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToggleChipRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}

@Composable
private fun StateMiniCard(
    label: String,
    value: String?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value ?: "—", style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RelBar(
    label: String,
    value: Int?,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value?.toString() ?: "—", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoStateIntervalSelector(
    value: Int?,
    onChange: (Int?) -> Unit
) {
    val options = listOf(
        null to "全局默认",
        0 to "关闭",
        1 to "每 1 轮",
        2 to "每 2 轮",
        3 to "每 3 轮",
        5 to "每 5 轮",
        8 to "每 8 轮",
        10 to "每 10 轮"
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (interval, label) ->
            FilterChip(
                selected = value == interval,
                onClick = { onChange(interval) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
