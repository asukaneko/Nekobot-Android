package com.nekobot.app.ui.screens.sessions

import androidx.compose.ui.platform.LocalResources

import com.nekobot.app.R

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.nekobot.app.ui.screens.settings.AppearanceSettingRow
import com.nekobot.app.ui.screens.settings.AppearanceDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.CompanionConversionModel
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.CompanionRelationshipEventEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.model.userFacingTitle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionRelationshipScreen(sessionId: String, onBack: () -> Unit) {
    val textResources = LocalResources.current
    val repository = remember { ServiceContainer.localRepository }
    val service = remember { repository.companionRelationship }
    val worker = rememberCoroutineScope()
    val stored by remember(sessionId) { service.observe(sessionId) }.collectAsStateWithLifecycle(null)
    val running by service.running.collectAsStateWithLifecycle()
    var view by remember(sessionId) { mutableStateOf<CompanionRelationshipView?>(null) }
    var models by remember { mutableStateOf<List<CompanionConversionModel>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var modelPicker by remember { mutableStateOf(false) }
    var intervalEditor by remember { mutableStateOf(false) }
    var intervalText by remember { mutableStateOf("5") }
    var correction by remember { mutableStateOf<CompanionRelationshipEventEntity?>(null) }
    var claim by remember { mutableStateOf("") }
    var quotes by remember { mutableStateOf<List<LocalMessageEntity>?>(null) }
    var history by remember { mutableStateOf<List<CompanionRelationshipEventEntity>?>(null) }
    var detail by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var oldRecordsOpen by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var evidenceExpanded by rememberSaveable { mutableStateOf(false) }
    var background by remember { mutableStateOf(ServiceContainer.prefs.relationshipBackground) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        background = ServiceContainer.prefs.relationshipBackground
        refresh++
    }
    var characterName by remember(sessionId) { mutableStateOf<String?>(null) }
    var assessments by remember { mutableStateOf<List<com.nekobot.app.data.local.db.CompanionRelationshipAssessmentEntity>?>(null) }
    var showCount by remember { mutableIntStateOf(12) }
    val androidView = LocalView.current
    val systemBars = remember(androidView) {
        (androidView.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, androidView) }
    }
    DisposableEffect(systemBars) {
        val wasLight = systemBars?.isAppearanceLightStatusBars
        onDispose { if (wasLight != null) systemBars?.isAppearanceLightStatusBars = wasLight }
    }
    SideEffect { systemBars?.isAppearanceLightStatusBars = false }
    BackHandler(onBack = onBack)
    suspend fun reload() {
        view = service.view(sessionId)
        characterName = repository.getSession(sessionId)?.userFacingTitle()
        models = repository.companionConversionModels()
    }
    fun action(block: suspend () -> Unit) {
        if (working) return
        worker.launch {
            working = true; error = null
            try { block(); reload() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: textResources.getString(R.string.single_role_action_incomplete_please_try_again) }
            finally { working = false }
        }
    }
    LaunchedEffect(sessionId, stored, running, refresh) {
        try { reload() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: textResources.getString(R.string.single_role_could_not_load_relationship_state) }
    }
    LaunchedEffect(sessionId) {
        ServiceContainer.sessionChanged.collect { if (it == sessionId) refresh++ }
    }
    RelationshipSkinTheme(background) {
    RelationshipJournalBackdrop(background) {
    Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(title = { Text(textResources.getString(R.string.portability_detail_local_relationship_states), color = Color.White) }, navigationIcon = {
        Surface(shape = RoundedCornerShape(16.dp), color = journalPaperColor(), shadowElevation = 2.dp,
            modifier = Modifier.padding(start = 10.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, textResources.getString(R.string.common_back)) }
        }
    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            characterName?.let { name ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = CircleShape, color = journalPaperColor(), shadowElevation = 3.dp) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            Text(name.trim().take(1), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
            val v = view
            if (v == null) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else {
                val state = v.state
                RelationshipCurrentState(v) { title, text, sources -> detail = Triple(title, text, sources) }
                RelationshipSummary(v, working, onUpdate = { action { service.start(sessionId) } }) { title, text, sources ->
                    detail = Triple(title, text, sources)
                }
                if (state.enabled && state.awaitingHistory) {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.historyReviewRequired) textResources.getString(R.string.single_role_history_range_awaiting_confirmation_1_s_rounds, v.pending) else textResources.getString(R.string.single_role_1_s_rounds_have_not_been_assessed, v.pending), style = MaterialTheme.typography.titleMedium)
                        Text(if (state.historyReviewRequired) textResources.getString(R.string.single_role_an_older_version_may_have_treated_initial_imported_relationship_v) else textResources.getString(R.string.single_role_you_decide_whether_to_assess_earlier_chats_one_model_call_handles))
                        Button(onClick = { action { service.start(sessionId) } }, enabled = !working && !v.busy) { Text(textResources.getString(R.string.single_role_assess_these_conversations)) }
                        TextButton(onClick = { action { service.skipPending(sessionId) } }, enabled = !working && !v.busy) { Text(textResources.getString(R.string.single_role_skip_earlier_chats_continue_from_now)) }
                    } }
                }
                if (v.busy && !state.awaitingHistory) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (v.busy && state.awaitingHistory) RelationshipPaper {
                    Text(textResources.getString(R.string.single_role_assessing_this_entire_batch_progress_will_be_saved_together_when))
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (!v.busy) state.notice?.takeUnless(CompanionRelationshipRecovery::obsoleteInitializationNotice)?.let {
                    RelationshipPaper { Text(relationshipNoticeText(it, textResources::getString), color = MaterialTheme.colorScheme.error) }
                }
                state.correctionResult?.let { RelationshipPaper { Text(it, style = MaterialTheme.typography.bodySmall) } }
                v.sourceWarning?.let { warning ->
                    RelationshipPaper {
                        Text(warning, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { action { service.skipPending(sessionId) } }, enabled = !v.busy && !working) { Text(textResources.getString(R.string.single_role_keep_existing_progress_and_continue_from_now)) }
                    }
                }
                if (state.updatedAt > 0) Surface(shape = RoundedCornerShape(8.dp), color = journalPaperColor()) {
                    Text(textResources.getString(R.string.single_role_last_assessment_1_s, SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.updatedAt))),
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                }
                JournalAction(textResources.getString(R.string.single_role_assessment_settings), textResources.getString(R.string.single_role_model_and_update_frequency), Icons.Filled.Tune, onClick = { settingsExpanded = !settingsExpanded })
                if (settingsExpanded) RelationshipSection(textResources.getString(R.string.single_role_assessment_settings)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(textResources.getString(R.string.single_role_enable_relationship_state), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(state.enabled, enabled = !working && (state.enabled || state.modelId != null), onCheckedChange = { enabled ->
                                    action { service.configure(sessionId, enabled, state.automatic, state.modelId, state.interval) }
                                })
                            }
                            AppearanceDivider()
                            AppearanceSettingRow(textResources.getString(R.string.single_role_assessment_model), onClick = { if (!working) modelPicker = true }) {
                                val model = models.firstOrNull { it.id == state.modelId }
                                Text(model?.name ?: if (state.modelId == null) textResources.getString(R.string.dbprofile_please_select) else textResources.getString(R.string.single_role_unavailable), modifier = Modifier.widthIn(max = 140.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                            }
                            AppearanceDivider()
                            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(textResources.getString(R.string.single_role_update_automatically_by_turns), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(state.automatic, enabled = state.enabled && !working, onCheckedChange = { automatic ->
                                    action { service.configure(sessionId, state.enabled, automatic, state.modelId, state.interval) }
                                })
                            }
                            AppearanceDivider()
                            AppearanceSettingRow(textResources.getString(R.string.single_role_update_interval), onClick = { if (!working) { intervalText = state.interval.toString(); intervalEditor = true } }) {
                                Text(textResources.getString(R.string.single_role_every_1_s_turns, state.interval), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                            }
                        }
                    }
                    Text(textResources.getString(R.string.single_role_calls_the_selected_model_separately_and_incurs_its_fees), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    JournalAction(textResources.getString(R.string.single_role_evidence_for_changes), icon = Icons.Filled.History, onClick = { evidenceExpanded = !evidenceExpanded }, modifier = Modifier.weight(1f).fillMaxHeight())
                    JournalAction(textResources.getString(R.string.single_role_past_records), icon = Icons.Filled.Archive, onClick = { oldRecordsOpen = true }, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                if (evidenceExpanded) {
                RelationshipPaper { JournalHeading(textResources.getString(R.string.single_role_evidence_for_changes)) }
                if (v.events.isEmpty()) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(textResources.getString(R.string.single_role_no_new_assessment_records), Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                v.events.take(showCount).forEach { event ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(event.summary, style = MaterialTheme.typography.titleSmall)
                        Text(event.reason)
                        Text(if (event.corrected) textResources.getString(R.string.single_role_this_event_s_assessment_was_corrected) else textResources.getString(R.string.single_role_event_supported_by_original_messages), style = MaterialTheme.typography.labelSmall)
                        Row {
                            TextButton(onClick = { action { quotes = service.sourceQuotes(sessionId, event.sourcesJson) } }) { Text(textResources.getString(R.string.single_role_original_messages)) }
                            TextButton(onClick = { correction = event; claim = "" }, enabled = state.enabled && !working && !v.busy) { Text(textResources.getString(R.string.single_role_report_an_error)) }
                            TextButton(onClick = { action { history = service.eventHistory(sessionId, event.eventKey) } }) { Text(textResources.getString(R.string.single_role_revision_history)) }
                        }
                    } }
                }
                if (v.events.size > showCount) TextButton(onClick = { showCount += 12 }) { Text(textResources.getString(R.string.single_role_view_more_evidence)) }
                }
            }
            error?.let { RelationshipPaper {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }) { Text(textResources.getString(R.string.single_role_reload)) }
            } }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
    detail?.let { item -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(item.first) }, text = {
        Text(item.second, modifier = Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = { detail = null }) { Text(textResources.getString(R.string.common_close)) } }, dismissButton = {
        if (item.third != null) TextButton(onClick = {
            detail = null
            action { quotes = service.sourceQuotes(sessionId, item.third!!) }
        }) { Text(textResources.getString(R.string.single_role_view_original_messages)) }
    }) }
    if (oldRecordsOpen) AlertDialog(onDismissRequest = { oldRecordsOpen = false }, title = { Text(textResources.getString(R.string.single_role_past_records)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val old = view?.legacy
            if (old == null) Text(textResources.getString(R.string.single_role_no_past_records_yet)) else {
                Text(textResources.getString(R.string.single_role_earlier_relationships_and_feelings_are_kept_here_updating_the_cur), style = MaterialTheme.typography.bodySmall)
                old.observedAt?.let { Text(textResources.getString(R.string.single_role_original_message_time_1_s, relationshipTime(it, textResources.getString(R.string.common_unknown)))) }
                Text(textResources.getString(R.string.single_role_mood_1_s_energy_2_s_previous_intensity_3_s, old.mood, old.energy, old.intensity?.let { "${(it * 100).roundToInt()}%" } ?: textResources.getString(R.string.single_role_not_recorded)))
                if (old.reason.isNotBlank()) Text(old.reason)
                CompanionRelationshipPolicy.dimensions.keys.forEach { key ->
                    val label = relationshipDimensionLabel(key)
                    Text(textResources.getString(R.string.single_role_1_s_2_s_previous_value_3_s, label, old.descriptions[key] ?: textResources.getString(R.string.single_role_no_written_description_yet), old.totals[key] ?: textResources.getString(R.string.single_role_not_recorded)))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { oldRecordsOpen = false }) { Text(textResources.getString(R.string.common_close)) } }, dismissButton = {
        TextButton(onClick = { oldRecordsOpen = false; action { assessments = service.assessmentHistory(sessionId) } }) { Text(textResources.getString(R.string.single_role_written_assessment_history)) }
    })
    assessments?.let { items -> AlertDialog(onDismissRequest = { assessments = null }, title = { Text(textResources.getString(R.string.single_role_written_assessment_history)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (items.isEmpty()) Text(textResources.getString(R.string.single_role_no_new_format_written_assessments_yet))
            items.forEach { saved ->
                val snapshot = CompanionRelationshipNarrative.read(saved.narrativeJson)
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(saved.createdAt)), style = MaterialTheme.typography.titleSmall)
                if (snapshot == null) Text(textResources.getString(R.string.single_role_this_record_cannot_be_read_right_now)) else {
                    snapshot.feelings?.let { feeling -> Text(textResources.getString(R.string.single_role_feelings_then_1_s_mood_2_s_intensity_3_s_energy_4_s_5_s, relationshipTime(feeling.observedAt, textResources.getString(R.string.common_unknown)), feeling.mood ?: textResources.getString(R.string.common_unknown), feeling.intensity ?: textResources.getString(R.string.common_unknown), feeling.energy ?: textResources.getString(R.string.common_unknown), feeling.reason)) }
                    snapshot.relationships.forEach { (key, field) -> field?.let { Text("${relationshipDimensionLabel(key)}：${it.description}\n${it.reason}") } }
                }
            }
            if (items.size >= 50) Text(textResources.getString(R.string.single_role_showing_the_latest_50_written_assessments_older_records_remain_in), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { assessments = null }) { Text(textResources.getString(R.string.common_close)) } }) }
    if (modelPicker) AlertDialog(onDismissRequest = { modelPicker = false }, title = { Text(textResources.getString(R.string.single_role_choose_a_state_assessment_model)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(textResources.getString(R.string.single_role_turning_relationship_state_off_stops_updates_and_excludes_it_from), style = MaterialTheme.typography.bodySmall)
            if (models.isEmpty()) Text(textResources.getString(R.string.single_role_add_a_chat_or_auxiliary_model_in_api_settings_first))
            models.forEach { model -> TextButton(onClick = {
                modelPicker = false
                view?.state?.let { state -> action { service.configure(sessionId, state.enabled, state.automatic, model.id, state.interval) } }
            }, modifier = Modifier.fillMaxWidth()) { Text("${model.name}\n${model.provider} · ${model.model}") } }
        } }, confirmButton = { TextButton(onClick = { modelPicker = false }) { Text(textResources.getString(R.string.common_close)) } })
    if (intervalEditor) AlertDialog(onDismissRequest = { intervalEditor = false }, title = { Text(textResources.getString(R.string.single_role_automatic_update_interval)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(textResources.getString(R.string.single_role_a_turn_is_your_message_followed_by_one_reply_each_assessment_eval), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(intervalText, { intervalText = it }, label = { Text(textResources.getString(R.string.single_role_1_100_turns)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }, confirmButton = { TextButton(enabled = intervalText.toIntOrNull() in 1..100, onClick = {
        intervalEditor = false
        view?.state?.let { state -> action { service.configure(sessionId, state.enabled, state.automatic, state.modelId, intervalText.toInt()) } }
    }) { Text(textResources.getString(R.string.common_save)) } }, dismissButton = { TextButton(onClick = { intervalEditor = false }) { Text(textResources.getString(R.string.common_cancel)) } })
    correction?.let { event -> AlertDialog(onDismissRequest = { correction = null }, title = { Text(textResources.getString(R.string.single_role_check_this_event)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(event.summary)
            Text(textResources.getString(R.string.single_role_describe_the_incorrect_fact_or_judgment_the_assessment_model_chec), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(claim, { claim = it.take(2000) }, label = { Text(textResources.getString(R.string.single_role_for_example_we_agreed_to_cancel_in_advance_it_was_not_a_broken_pr)) }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = claim.isNotBlank(), onClick = {
        val reason = claim; correction = null
        action { service.start(sessionId, correctionKey = event.eventKey, claim = reason) }
    }) { Text(textResources.getString(R.string.single_role_request_verification)) } }, dismissButton = { TextButton(onClick = { correction = null }) { Text(textResources.getString(R.string.common_cancel)) } }) }
    quotes?.let { rows -> AlertDialog(onDismissRequest = { quotes = null }, title = { Text(textResources.getString(R.string.single_role_original_message_evidence)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (rows.isEmpty()) Text(textResources.getString(R.string.single_role_original_messages_are_no_longer_readable_memory_text_cannot_repla))
            rows.forEach { Text("${if (it.role == "user") textResources.getString(R.string.live_you) else textResources.getString(R.string.nav_characters)} · ${it.createdAt.take(19)}\n${it.content}") }
        }
    }, confirmButton = { TextButton(onClick = { quotes = null }) { Text(textResources.getString(R.string.common_close)) } }) }
    history?.let { items -> AlertDialog(onDismissRequest = { history = null }, title = { Text(textResources.getString(R.string.single_role_revision_history_for_this_event)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.forEach { Text("${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.createdAt))}\n${it.summary}\n${it.reason}") }
        }
    }, confirmButton = { TextButton(onClick = { history = null }) { Text(textResources.getString(R.string.common_close)) } }) }
    }
}
