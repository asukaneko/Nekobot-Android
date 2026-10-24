package com.nekobot.app.ui.screens.memory

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nekobot.app.data.local.ai.CompanionRecallSettings
import com.nekobot.app.data.local.ai.CompanionConsolidationSettings
import com.nekobot.app.data.local.CompanionEmbeddingSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionRecallSettingsScreen(
    sessionId: String, ownerName: String, ownerAvatar: String?, initial: CompanionRecallSettings,
    busy: Boolean, error: String?, onBack: () -> Unit, onSave: (Int, Int, Int, Boolean, String?, CompanionConsolidationSettings) -> Unit,
    embedding: CompanionEmbeddingSettings, onAddModel: () -> Unit, addedModelId: String? = null,
    initialConsolidation: CompanionConsolidationSettings = CompanionConsolidationSettings()
) {
    val textResources = LocalResources.current
    var rounds by rememberSaveable(sessionId) { mutableIntStateOf(initial.rounds) }
    var tokens by rememberSaveable(sessionId) { mutableIntStateOf(initial.tokens) }
    var perCall by rememberSaveable(sessionId) { mutableIntStateOf(initial.effectivePerCall) }
    var summaryVector by rememberSaveable(sessionId) { mutableStateOf(initialConsolidation.vectorEnabled) }
    var recentLimit by rememberSaveable(sessionId) { mutableIntStateOf(initialConsolidation.recent) }
    var triggerLimit by rememberSaveable(sessionId) { mutableIntStateOf(initialConsolidation.trigger) }
    var existingLimit by rememberSaveable(sessionId) { mutableIntStateOf(initialConsolidation.existing) }
    var vectorEnabled by rememberSaveable(sessionId) { mutableStateOf(embedding.enabled) }
    var embeddingId by rememberSaveable(sessionId) { mutableStateOf(embedding.selectedId) }
    var choosingModel by rememberSaveable { mutableStateOf(false) }
    var editingLimit by rememberSaveable { mutableStateOf<String?>(null) }
    var draftLimit by rememberSaveable { mutableStateOf("") }
    var quotaInfo by rememberSaveable { mutableStateOf(false) }
    var chooseFirst by rememberSaveable { mutableStateOf(false) }
    val chosen = embedding.options.firstOrNull { it.id == embeddingId }
    val summaryVectorReady = vectorEnabled && chosen != null
    val summaryVectorState = summaryVectorSwitchState(summaryVector, summaryVectorReady)
    LaunchedEffect(addedModelId) { if (addedModelId != null) { embeddingId = addedModelId; chooseFirst = false } }
    BackHandler { if (!busy) onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text(textResources.getString(R.string.single_role_memory_settings)) }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, textResources.getString(R.string.common_back)) }
    }) }, bottomBar = {
        Surface {
            Button(onClick = { onSave(rounds, tokens, perCall.coerceAtMost(tokens), vectorEnabled, embeddingId,
                summaryVectorState.settings(recentLimit, triggerLimit, existingLimit)) },
                enabled = !busy && (!vectorEnabled || chosen != null), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp).heightIn(min = 52.dp)) {
                Text(if (busy) textResources.getString(R.string.single_role_saving) else textResources.getString(R.string.system_save_settings))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(textResources.getString(R.string.single_role_retrieval_settings_all_single_session_characters), style = MaterialTheme.typography.titleSmall)
            RetrievalCard {
                RetrievalHeading(Icons.AutoMirrored.Outlined.MenuBook, textResources.getString(R.string.single_role_keyword_search), textResources.getString(R.string.single_role_search_locally_by_words_and_text)) {
                    Text(textResources.getString(R.string.single_role_always_available), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            RetrievalCard {
                RetrievalHeading(Icons.Outlined.Search, textResources.getString(R.string.single_role_vector_search), textResources.getString(R.string.single_role_find_memories_by_meaning_even_with_different_wording)) {
                    Switch(vectorEnabled, onCheckedChange = { requested ->
                        if (requested && chosen == null) { chooseFirst = true; choosingModel = true }
                        else { vectorEnabled = requested; chooseFirst = false }
                    }, enabled = !busy, modifier = Modifier.testTag("companion-vector-switch"))
                }
                Text(if (vectorEnabled) textResources.getString(R.string.single_role_using_keywords_vectors) else textResources.getString(R.string.single_role_using_keywords_only),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (chooseFirst) Text(textResources.getString(R.string.single_role_choose_a_model_first_then_enable_vector_search_yourself), style = MaterialTheme.typography.bodySmall)
            }
            RetrievalCard {
                RetrievalHeading(Icons.Outlined.Hub, textResources.getString(R.string.single_role_embedding_model), when {
                    chosen != null -> embeddingModelSubtitle(chosen.name, chosen.model)
                    !embeddingId.isNullOrBlank() -> textResources.getString(R.string.single_role_previously_selected_model_is_unavailable)
                    else -> textResources.getString(R.string.single_role_not_selected)
                }) {}
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choosingModel = true }, enabled = !busy, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp), shape = RoundedCornerShape(12.dp)) { Text(textResources.getString(R.string.single_role_choose_an_existing_model)) }
                    OutlinedButton(onClick = onAddModel, enabled = !busy, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp), shape = RoundedCornerShape(12.dp)) { Text(textResources.getString(R.string.single_role_add_embedding_model)) }
                }
                Text(textResources.getString(R.string.single_role_embedding_calls_are_billed_by_the_selected_provider), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vectorEnabled || !embeddingId.isNullOrBlank() && chosen == null) Text(textResources.getString(R.string.single_role_uses_keywords_while_the_model_is_unavailable_or_the_index_is_not),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(textResources.getString(R.string.single_role_the_model_is_shared_memories_remain_separate_for_each_character), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(textResources.getString(R.string.single_role_recall_quota_1_s, ownerName.ifBlank { textResources.getString(R.string.single_role_current_character) }), style = MaterialTheme.typography.titleSmall)
                Box {
                    IconButton(onClick = { quotaInfo = true }, modifier = Modifier.size(32.dp).testTag("recall-quota-info")) {
                        Icon(Icons.Outlined.Info, textResources.getString(R.string.single_role_about_recall_quotas), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (quotaInfo) Popup(onDismissRequest = { quotaInfo = false }, offset = IntOffset(0, 96),
                        properties = PopupProperties(focusable = true)) {
                        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 280.dp).testTag("recall-quota-popover")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                recallQuotaExplanation(rounds, tokens, perCall, textResources::getString).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    LimitRow(textResources.getString(R.string.single_role_maximum_recall_rounds), textResources.getString(R.string.single_role_1_s_rounds, rounds), !busy) { draftLimit = rounds.toString(); editingLimit = "rounds" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow(textResources.getString(R.string.single_role_total_material_limit), "$tokens Token", !busy) { draftLimit = tokens.toString(); editingLimit = "tokens" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow(textResources.getString(R.string.single_role_per_read_limit), "${perCall.coerceAtMost(tokens)} Token", !busy) { draftLimit = perCall.coerceAtMost(tokens).toString(); editingLimit = "perCall" }
                }
            }
            Text(textResources.getString(R.string.single_role_quotas_only_apply_to_this_character), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            // 显示和保存用户偏好；向量是否就绪只影响可操作性，运行时仍可回退关键词。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(textResources.getString(R.string.memory_v2_batch_title), style = MaterialTheme.typography.titleSmall)
                InfoPopoverButton(textResources.getString(R.string.memory_v2_batch_title), "consolidation-info", listOf(
                    textResources.getString(R.string.memory_v2_batch_description)))
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(textResources.getString(R.string.single_role_semantic_matching), style = MaterialTheme.typography.bodyMedium)
                        InfoPopoverButton(textResources.getString(R.string.single_role_about_vectors_in_summarization), "summary-vector-info", listOf(
                            textResources.getString(R.string.single_role_keywords_are_already_used_by_default_enable_this_to_also_match_by)))
                        Spacer(Modifier.weight(1f))
                        Switch(summaryVectorState.checked, onCheckedChange = { summaryVector = it },
                            enabled = !busy && summaryVectorState.enabled, modifier = Modifier.testTag("summary-vector-switch"))
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow(textResources.getString(R.string.single_role_keep_recent_original_messages), "$recentLimit Token", !busy) { draftLimit = recentLimit.toString(); editingLimit = "recent" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow(textResources.getString(R.string.memory_v2_batch_title), "$triggerLimit Token", !busy) { draftLimit = triggerLimit.toString(); editingLimit = "trigger" }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LimitRow(textResources.getString(R.string.single_role_previous_memory_reference_limit), "$existingLimit Token", !busy) { draftLimit = existingLimit.toString(); editingLimit = "existing" }
                }
            }
            Text(textResources.getString(R.string.memory_v2_batch_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { CompanionMemoryNotice(textResources.getString(R.string.single_role_settings_not_saved_yet), it) }
        }
    }
    if (choosingModel) ModalBottomSheet(onDismissRequest = { choosingModel = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(textResources.getString(R.string.single_role_choose_an_existing_embedding_model), style = MaterialTheme.typography.titleLarge)
            Text(textResources.getString(R.string.single_role_selecting_does_not_enable_retrieval_automatically_changes_apply_a), style = MaterialTheme.typography.bodySmall)
            embedding.options.forEach { option ->
                OutlinedButton(onClick = { embeddingId = option.id; choosingModel = false; chooseFirst = false },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(option.name, style = MaterialTheme.typography.titleSmall)
                        Text("${option.provider.ifBlank { textResources.getString(R.string.single_role_custom_provider) }} · ${option.model}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (option.id == embeddingId) Text(textResources.getString(R.string.story_selected))
                }
            }
            if (embedding.options.isEmpty()) Text(textResources.getString(R.string.single_role_no_embedding_models_available_yet), Modifier.padding(vertical = 16.dp))
            TextButton(onClick = { choosingModel = false; onAddModel() }) { Text(textResources.getString(R.string.single_role_add_embedding_model)) }
        }
    }
    editingLimit?.let { kind ->
        val range = when (kind) {
            "rounds" -> 1..100
            "perCall" -> CompanionRecallSettings.MIN_PER_CALL..tokens
            "recent" -> 1000..64000
            "trigger", "existing" -> 256..64000
            else -> 256..64000
        }
        val number = draftLimit.toIntOrNull()
        AlertDialog(onDismissRequest = { editingLimit = null }, title = { Text(when (kind) { "rounds" -> textResources.getString(R.string.single_role_maximum_recall_rounds); "perCall" -> textResources.getString(R.string.single_role_per_read_limit); "recent" -> textResources.getString(R.string.single_role_keep_recent_original_messages); "trigger" -> textResources.getString(R.string.memory_v2_batch_title); "existing" -> textResources.getString(R.string.single_role_previous_memory_reference_limit); else -> textResources.getString(R.string.single_role_total_material_limit) }) },
            text = { OutlinedTextField(draftLimit, { if (it.length <= 5 && it.all { c -> c in '0'..'9' }) draftLimit = it },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = number == null || number !in range, supportingText = { Text(textResources.getString(R.string.single_role_enter_an_integer_from_1_s_to_2_s, range.first, range.last)) }) },
            confirmButton = { TextButton(onClick = {
                when (kind) {
                    "rounds" -> rounds = requireNotNull(number)
                    "perCall" -> perCall = requireNotNull(number)
                    "recent" -> recentLimit = requireNotNull(number)
                    "trigger" -> triggerLimit = requireNotNull(number)
                    "existing" -> existingLimit = requireNotNull(number)
                    else -> { tokens = requireNotNull(number); perCall = perCall.coerceAtMost(tokens) }
                }
                editingLimit = null
            }, enabled = number != null && number in range) { Text(textResources.getString(R.string.single_role_confirm)) } },
            dismissButton = { TextButton(onClick = { editingLimit = null }) { Text(textResources.getString(R.string.common_cancel)) } })
    }
}

@Composable private fun RetrievalCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun RetrievalHeading(icon: ImageVector, title: String, description: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** 与“回查额度”说明相同样式的 i 图标气泡。 */
@Composable private fun InfoPopoverButton(description: String, tag: String, lines: List<String>) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp).testTag(tag)) {
            Icon(Icons.Outlined.Info, description, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Popup(onDismissRequest = { open = false }, offset = IntOffset(0, 96),
            properties = PopupProperties(focusable = true)) {
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 3.dp, shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 280.dp).testTag("$tag-popover")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }
}

@Composable private fun LimitRow(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 向量暂不可用不覆盖已保存的语义对照偏好。 */
internal data class SummaryVectorSwitchState(val checked: Boolean, val enabled: Boolean) {
    fun settings(recent: Int, trigger: Int, existing: Int) =
        CompanionConsolidationSettings(checked, recent, trigger, existing)
}

internal fun summaryVectorSwitchState(requested: Boolean, vectorReady: Boolean) =
    SummaryVectorSwitchState(checked = requested, enabled = vectorReady)

/** 回查额度说明，数字跟随当前设置。 */
internal fun recallQuotaExplanation(
    rounds: Int, tokens: Int,
    perCallSetting: Int = com.nekobot.app.data.local.ai.CompanionRecallBudget.PER_CALL_TOKENS,
    text: (Int) -> String
): List<String> {
    fun getString(id: Int, vararg args: Any): String =
        if (args.isEmpty()) text(id) else String.format(java.util.Locale.getDefault(), text(id), *args)
    val perCall = minOf(tokens, perCallSetting)
    val items = if (perCall == 1500) getString(R.string.single_role_7_8) else getString(R.string.single_role_about_1_s, (perCall / 200).coerceAtLeast(1))
    val essay = if (tokens in 2500..6000) getString(R.string.single_role_about_a_short_essay) else ""
    return listOf(
        getString(R.string.single_role_recall_rounds_1_s_how_many_times_the_character_can_look_up_memori, rounds),
        getString(R.string.single_role_total_budget_1_s_2_s_3_s_chinese_characters_4_s, tokens, tokens * 3 / 4, tokens, essay),
        getString(R.string.single_role_per_read_1_s_2_s_ordinary_memories, perCall, items),
        getString(R.string.single_role_only_retrieved_material_counts_not_the_character_s_reply)
    )
}

/** 显示名与模型 ID 相同时只显示一行，避免重复。 */
internal fun embeddingModelSubtitle(name: String, model: String): String {
    val n = name.trim(); val m = model.trim()
    return when {
        n.isEmpty() -> m
        m.isEmpty() || n.equals(m, ignoreCase = true) -> n
        else -> n + "\n" + m
    }
}
