package com.nekobot.app.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
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
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.ErrorRed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 会话详情 ViewModel：加载单个会话、编辑名称 / 标签 / 置顶 / 收藏，
 * 支持保存、删除、进入对话。
 */
class SessionDetailViewModel : BaseViewModel() {

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    val name = MutableStateFlow("")
    val tagsText = MutableStateFlow("")
    val pinned = MutableStateFlow(false)
    val favorite = MutableStateFlow(false)

    fun init(id: String) {
        load(id)
    }

    fun load(id: String) {
        launchResult(
            block = { repo.getSession(id) },
            onSuccess = { s ->
                _session.value = s
                name.value = s.name.orEmpty()
                tagsText.value = s.tags?.joinToString(", ").orEmpty()
                pinned.value = s.pinned == true
                favorite.value = s.favorite == true
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
                        favorite = favorite.value
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
 * 会话详情页：展示并编辑会话元信息（名称、标签、置顶、收藏），
 * 顶部提供「进入对话」「保存」「删除」操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        vm.init(sessionId)
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("会话详情", color = OnSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface,
                    titleContentColor = OnSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnSurface)
                    }
                },
                actions = {
                    // 进入对话
                    IconButton(onClick = { session?.id?.let(onOpenChat) }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "进入对话", tint = Primary)
                    }
                    // 保存
                    IconButton(onClick = { vm.save(onBack) }) {
                        Icon(Icons.Filled.Save, contentDescription = "保存", tint = Primary)
                    }
                    // 删除
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = ErrorRed)
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
            if (loading && session == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = Primary)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    session?.let { s ->
                        // 顶部：立绘 + 会话基本信息
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val portraitUrl = resolveAvatarUrl(s.portraitUrl)
                                Box(
                                    modifier = Modifier
                                        .size(width = 80.dp, height = 104.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(BgSurface),
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
                                        Icon(
                                            Icons.AutoMirrored.Filled.Chat,
                                            contentDescription = null,
                                            tint = OnSurfaceVariant,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.size(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = s.displayName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = OnSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!s.characterName.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "角色：${s.characterName}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Primary
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    s.messageCount?.let { count ->
                                        Text(
                                            text = "消息数：$count",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceVariant
                                        )
                                    }
                                    s.updatedAt?.let { time ->
                                        Text(
                                            text = "更新：$time",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // 编辑区
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
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

                        // 只读信息
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader(title = "其它信息")
                            Spacer(Modifier.height(8.dp))
                            DetailLine(label = "会话 ID", value = s.id.orEmpty())
                            DetailLine(label = "类型", value = s.type ?: "—")
                            DetailLine(label = "模式", value = s.sessionMode ?: "—")
                            DetailLine(label = "角色 ID", value = s.characterId ?: "—")
                            DetailLine(label = "创建时间", value = s.createdAt ?: "—")
                            DetailLine(label = "公开", value = if (s.isPublic == true) "是" else "否")
                            DetailLine(label = "归档", value = if (s.archived == true) "是" else "否")
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

    // 删除确认弹窗
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

/** 详情页只读信息行 */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant,
            modifier = Modifier.size(width = 88.dp, height = 20.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 可切换的胶囊按钮 */
@Composable
private fun ToggleChipRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        cornerRadius = 12,
        containerColor = if (selected) Primary.copy(alpha = 0.2f) else BgSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = if (selected) Primary else OnSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
