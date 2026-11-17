package com.nekobot.app.ui.screens.extensions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.data.model.MediaPlaygroundModel
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.BorderlessOutlinedTextField as OutlinedTextField
import com.nekobot.app.ui.components.GlassExposedDropdownMenu as ExposedDropdownMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 配置加载与生成分开管理，取消后旧任务不能覆盖新任务的状态。 */
abstract class MediaPlaygroundViewModel(private val purpose: String) : BaseViewModel() {
    private val _models = MutableStateFlow<List<MediaPlaygroundModel>>(emptyList())
    val models = _models.asStateFlow()
    private val _selectedModelId = MutableStateFlow("")
    val selectedModelId = _selectedModelId.asStateFlow()
    private val _configLoading = MutableStateFlow(false)
    val configLoading = _configLoading.asStateFlow()
    private var configJob: Job? = null
    private var configRevision = 0
    private var operationJob: Job? = null
    private var operationRevision = 0
    private var sourceKey: String? = null
    val currentModel get() = _models.value.firstOrNull { it.id == _selectedModelId.value }
        ?: _models.value.firstOrNull()
    protected val requestedModelId get() = _selectedModelId.value.takeIf(String::isNotBlank)

    fun updateSource(key: String) {
        val changed = sourceKey != key
        sourceKey = key
        refresh(reset = changed)
    }

    fun refresh(reset: Boolean = false) {
        if (loading.value && !reset) return
        val revision = ++configRevision
        configJob?.cancel()
        if (reset) {
            cancel()
            clearError()
            _models.value = emptyList()
            _selectedModelId.value = ""
            clearResult()
        }
        configJob = viewModelScope.launch {
            _configLoading.value = true
            try {
                when (val result = unified.getMediaPlaygroundModels(purpose)) {
                    is Resource.Success -> {
                        _models.value = result.data
                        if (result.data.none { it.id == _selectedModelId.value }) _selectedModelId.value = ""
                        loadModelDetails()
                    }
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            } finally {
                if (revision == configRevision) _configLoading.value = false
            }
        }
    }

    fun selectModel(id: String) {
        if (loading.value || configLoading.value || id == _selectedModelId.value) return
        _selectedModelId.value = id
        refresh()
    }

    protected open suspend fun loadModelDetails() {}
    protected suspend fun useDefaultModel() {
        _selectedModelId.value = ""
        loadModelDetails()
    }
    protected abstract fun clearResult()

    protected fun runOperation(block: suspend () -> Unit) {
        if (loading.value) return
        clearError()
        val revision = ++operationRevision
        setLoading(true)
        operationJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            } finally {
                if (revision == operationRevision) setLoading(false)
            }
        }
    }

    fun cancel() {
        ++operationRevision
        operationJob?.cancel()
        setLoading(false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaygroundScaffold(
    title: String,
    action: String,
    busy: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onConfigureModels: () -> Unit,
    onOpenHistory: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (onOpenHistory != null) {
                        IconButton(onClick = onOpenHistory, enabled = !busy) {
                            Icon(Icons.Default.History, stringResource(R.string.playground_history))
                        }
                    }
                    IconButton(onClick = onConfigureModels, enabled = !busy) {
                        Icon(Icons.Default.Tune, stringResource(R.string.playground_configure_models))
                    }
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.tts_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) {
                        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                    }
                    Button(onClick = onGenerate, enabled = enabled && !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) stringResource(R.string.playground_working) else action)
                    }
                }
            }
        }, content = content
    )
}

@Composable
internal fun PlaygroundSection(title: String, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            trailing()
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaygroundDropdown(
    label: String, selected: String, options: List<Pair<String, String>>,
    onSelect: (String) -> Unit, enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        @Suppress("DEPRECATION")
        OutlinedTextField(value = options.firstOrNull { it.first == selected }?.second ?: selected,
            onValueChange = {}, readOnly = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}

@Composable
internal fun PlaygroundModelPicker(models: List<MediaPlaygroundModel>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    PlaygroundDropdown(stringResource(R.string.playground_model), selected,
        listOf("" to stringResource(R.string.playground_auto_model)) + models.map { it.id to it.name }, onSelect, enabled)
    if (models.isEmpty()) {
        Text(stringResource(R.string.playground_no_model), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (selected.isBlank()) {
        Text(models.first().name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
