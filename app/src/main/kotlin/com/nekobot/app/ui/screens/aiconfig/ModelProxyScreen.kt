package com.nekobot.app.ui.screens.aiconfig

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.parseModelProxyUrl
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.ui.BaseViewModel
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.LoadingOverlay
import com.nekobot.app.ui.components.ProviderLogo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ModelProxyViewModel : BaseViewModel() {

    private val _models = MutableStateFlow<List<LocalAiModelEntity>>(emptyList())
    val models: StateFlow<List<LocalAiModelEntity>> = _models.asStateFlow()

    /**
     * Map 中存在模型 ID 表示该模型启用代理；值允许暂时为空，便于用户打开开关后输入。
     */
    private val _proxyDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val proxyDrafts: StateFlow<Map<String, String>> = _proxyDrafts.asStateFlow()

    private var initialized = false

    init {
        viewModelScope.launch {
            unified.observeLocalAiModels()?.collect { list ->
                _models.value = list
                if (!initialized && list.isNotEmpty()) {
                    _proxyDrafts.value = list
                        .filter { it.proxyUrl.isNotBlank() }
                        .associate { it.id to it.proxyUrl }
                    initialized = true
                }
            }
        }
    }

    fun setProxyEnabled(model: LocalAiModelEntity, enabled: Boolean) {
        _proxyDrafts.value = _proxyDrafts.value.toMutableMap().apply {
            if (enabled) {
                put(model.id, model.proxyUrl)
            } else {
                remove(model.id)
            }
        }
        clearError()
    }

    fun updateProxyUrl(modelId: String, value: String) {
        if (modelId !in _proxyDrafts.value) return
        _proxyDrafts.value = _proxyDrafts.value.toMutableMap().apply {
            put(modelId, value)
        }
        clearError()
    }

    fun save() {
        val drafts = _proxyDrafts.value
        val models = _models.value
        for (model in models) {
            val proxyUrl = drafts[model.id] ?: continue
            if (proxyUrl.isBlank()) {
                showError(string(R.string.model_proxy_url_required, model.name))
                return
            }
            runCatching { parseModelProxyUrl(proxyUrl) }.exceptionOrNull()?.let {
                showError("${model.name}: ${it.message ?: "代理链接格式无效"}")
                return
            }
        }

        viewModelScope.launch {
            setLoading(true)
            try {
                models.forEach { model ->
                    val proxyUrl = drafts[model.id]?.trim().orEmpty()
                    if (model.proxyUrl != proxyUrl) {
                        unified.upsertLocalAiModel(model.copy(proxyUrl = proxyUrl))
                    }
                }
                showToast(string(R.string.model_proxy_saved))
            } catch (error: Exception) {
                showError(error.message ?: string(R.string.common_unknown_error))
            } finally {
                setLoading(false)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelProxyScreen(onBack: () -> Unit) {
    val vm: ModelProxyViewModel = viewModel()
    val models by vm.models.collectAsState()
    val proxyDrafts by vm.proxyDrafts.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_proxy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.model_proxy_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (models.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.model_proxy_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp)
                    )
                }
            } else {
                items(models, key = { it.id }) { model ->
                    val enabled = model.id in proxyDrafts
                    ModelProxyCard(
                        model = model,
                        enabled = enabled,
                        proxyUrl = proxyDrafts[model.id].orEmpty(),
                        onEnabledChange = { vm.setProxyEnabled(model, it) },
                        onProxyUrlChange = { vm.updateProxyUrl(model.id, it) }
                    )
                }
            }

            error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (models.isNotEmpty()) {
                item {
                    Button(
                        onClick = vm::save,
                        enabled = !loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.model_proxy_save))
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }

        LoadingOverlay(visible = loading)
    }
}

@Composable
private fun ModelProxyCard(
    model: LocalAiModelEntity,
    enabled: Boolean,
    proxyUrl: String,
    onEnabledChange: (Boolean) -> Unit,
    onProxyUrlChange: (String) -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderLogo(
                provider = model.provider,
                baseUrl = model.baseUrl,
                model = model.model,
                size = 42.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = model.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (enabled) R.string.model_proxy_enabled else R.string.model_proxy_direct
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        if (enabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = onProxyUrlChange,
                label = { Text(stringResource(R.string.model_proxy_url)) },
                placeholder = { Text(stringResource(R.string.model_proxy_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
