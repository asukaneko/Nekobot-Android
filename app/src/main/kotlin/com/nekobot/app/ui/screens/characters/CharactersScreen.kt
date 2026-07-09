package com.nekobot.app.ui.screens.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.ErrorBanner
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 角色列表页 ViewModel：管理角色列表的加载、删除。
 */
class CharactersViewModel : com.nekobot.app.ui.BaseViewModel() {

    private val _characters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()

    init {
        load()
    }

    /** 加载角色列表 */
    fun load() {
        launchResult(
            block = { repo.listCharacters() },
            onSuccess = { _characters.value = it ?: emptyList() }
        )
    }

    /** 删除指定角色 */
    fun delete(id: String) {
        launchResult(
            block = { repo.deleteCharacter(id) },
            onSuccess = {
                _characters.value = _characters.value.filterNot { it.id == id }
                showToast("已删除角色")
            }
        )
    }
}

/**
 * 角色列表页：展示所有角色，支持刷新、新建、点击进入详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(
    onOpenCharacter: (String) -> Unit,
    viewModel: CharactersViewModel = viewModel()
) {
    val characters by viewModel.characters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("角色", color = OnSurface, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface,
                    titleContentColor = OnSurface
                ),
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = OnSurface)
                    }
                    IconButton(onClick = { onOpenCharacter("new") }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建角色", tint = Primary)
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
            if (characters.isEmpty() && !loading) {
                EmptyState(
                    title = "暂无角色",
                    hint = "点击右上角新建一个角色",
                    icon = {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = OnSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (error != null) {
                        item {
                            ErrorBanner(message = error!!, onRetry = {
                                viewModel.clearError()
                                viewModel.load()
                            })
                        }
                    }
                    items(characters, key = { it.id ?: it.name ?: it.hashCode().toString() }) { character ->
                        CharacterItem(character = character, onClick = {
                            character.id?.let { onOpenCharacter(it) }
                        })
                    }
                }
            }
            LoadingOverlay(visible = loading && characters.isEmpty())
        }
    }
}

/** 单个角色卡片 */
@Composable
private fun CharacterItem(character: CharacterPreset, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像：圆形，相对路径拼接 baseUrl
            val avatarUrl = character.avatarUrl?.let { resolveImageUrl(it) }
            AsyncImage(
                model = avatarUrl,
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(BgSurfaceVariant)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!character.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = character.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 标签 Chip 行
                val tags = character.tags
                if (!tags.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tags.take(4).forEach { tag ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        tag,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OnSurfaceVariant
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Primary.copy(alpha = 0.12f),
                                    leadingIconContentColor = Primary
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 把相对路径图片地址拼成完整 URL */
private fun resolveImageUrl(path: String): String {
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = ServiceContainer.network.baseUrl().trimEnd('/')
    return base + "/" + path.trimStart('/')
}
