package com.nekobot.app.ui.screens.worldbook

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.data.model.WorldBookMatchDiagnostic
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.EmptyState
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 召回来源的本地化名称。 */
internal fun triggerSourceLabelRes(source: String): Int = when (source) {
    "always" -> R.string.worldbook_source_always
    "user" -> R.string.worldbook_source_user
    "assistant_recent" -> R.string.worldbook_source_assistant
    "history" -> R.string.worldbook_source_history
    "scene_state" -> R.string.worldbook_source_scene
    else -> R.string.worldbook_source_unknown
}

/**
 * 世界书命中调试二级页 ViewModel。
 *
 * 独立于单本世界书：命中的是「当前角色可见的全部世界书」，所以入口放在列表页而不是某本书里。
 */
class WorldBookMatchDebugViewModel : com.nekobot.app.ui.BaseViewModel() {

    private val _characters = MutableStateFlow<List<CharacterPreset>>(emptyList())
    val characters: StateFlow<List<CharacterPreset>> = _characters.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _characterId = MutableStateFlow("")
    val characterId: StateFlow<String> = _characterId.asStateFlow()

    private val _results = MutableStateFlow<List<WorldBookMatchDiagnostic>>(emptyList())
    val results: StateFlow<List<WorldBookMatchDiagnostic>> = _results.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 是否已经跑过一次（用于区分「未测试」与「测试后范围内没有条目」）。 */
    private val _ran = MutableStateFlow(false)
    val ran: StateFlow<Boolean> = _ran.asStateFlow()

    init {
        launchResult(
            block = { unified.listCharacters() },
            onSuccess = { _characters.value = it ?: emptyList() }
        )
    }

    fun setMessage(value: String) {
        _message.value = value
    }

    fun setCharacterId(value: String) {
        _characterId.value = value
    }

    /**
     * 跑一次命中调试。
     *
     * 本地模式返回全部条目（含未命中原因）；服务器模式只返回命中项（后端不提供未命中诊断）。
     */
    fun run() {
        val text = _message.value.trim()
        if (text.isEmpty()) {
            showToast(string(R.string.worldbook_debug_need_message))
            return
        }
        _running.value = true
        viewModelScope.launch {
            try {
                when (
                    val result = unified.testWorldBookMatch(
                        message = text,
                        characterId = _characterId.value.takeIf { it.isNotBlank() }
                    )
                ) {
                    is Resource.Success -> {
                        _results.value = result.data
                        _ran.value = true
                    }
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.worldbook_debug_failed))
            } finally {
                _running.value = false
            }
        }
    }
}

/**
 * 世界书命中调试页：输入一段测试消息，查看每条条目为什么注入 / 为什么不注入。
 *
 * 命中项展示得分、召回来源与命中关键词；未命中的条目（本地模式）展示具体原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookMatchDebugScreen(
    onBack: () -> Unit,
    viewModel: WorldBookMatchDebugViewModel = viewModel()
) {
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val characterId by viewModel.characterId.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val ran by viewModel.ran.collectAsStateWithLifecycle()
    var characterExpanded by remember { mutableStateOf(false) }

    val matched = results.count { it.matched }
    val skipped = results.size - matched

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.worldbook_debug_title),
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
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.worldbook_debug_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.worldbook_debug_message_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = message,
                                onValueChange = { viewModel.setMessage(it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 5
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.worldbook_debug_character_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            ExposedDropdownMenuBox(
                                expanded = characterExpanded,
                                onExpandedChange = { characterExpanded = it }
                            ) {
                                val selectedLabel = characters
                                    .firstOrNull { it.id == characterId || it.name == characterId }
                                    ?.name
                                    ?: stringResource(R.string.worldbook_debug_character_none)
                                OutlinedTextField(
                                    value = selectedLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    shape = RoundedCornerShape(12.dp),
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = characterExpanded)
                                    }
                                )
                                ExposedDropdownMenu(
                                    expanded = characterExpanded,
                                    onDismissRequest = { characterExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.worldbook_debug_character_none)) },
                                        onClick = {
                                            viewModel.setCharacterId("")
                                            characterExpanded = false
                                        }
                                    )
                                    characters.forEach { character ->
                                        DropdownMenuItem(
                                            text = { Text(character.name ?: character.id.orEmpty()) },
                                            onClick = {
                                                viewModel.setCharacterId(character.id.orEmpty())
                                                characterExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.run() },
                                enabled = !running && message.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (running) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.worldbook_debug_run))
                            }
                        }
                    }
                }

                if (results.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.worldbook_debug_matched_count, matched, skipped),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                items(results, key = { "${it.matched}_${it.entryId}_${it.entryName}" }) { item ->
                    WorldBookDebugRow(item)
                }

                if (ran && results.isEmpty() && !running) {
                    item {
                        EmptyState(title = stringResource(R.string.worldbook_debug_no_result))
                    }
                }
            }
        }
    }
}

/** 单条命中 / 未命中结果的卡片。 */
@Composable
private fun WorldBookDebugRow(item: WorldBookMatchDiagnostic) {
    val matched = item.matched
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.entryName ?: stringResource(R.string.worldbook_unnamed),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (matched) {
                        stringResource(R.string.worldbook_debug_score, item.score ?: 0)
                    } else {
                        stringResource(R.string.worldbook_debug_skipped)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (matched) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            item.worldBookName?.takeIf { it.isNotBlank() }?.let { bookName ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = bookName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (matched) {
                val sources = item.triggerSources.orEmpty().map { stringResource(triggerSourceLabelRes(it)) }
                if (sources.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.worldbook_debug_sources) + " " + sources.joinToString(" / "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val keywords = item.matchedKeywords.orEmpty()
                if (keywords.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.worldbook_debug_keywords) + " " + keywords.joinToString(" / "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item.skipReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
