package com.nekobot.app.ui.screens.aiconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.ModelPricingCatalog
import com.nekobot.app.data.local.ai.ModelPricingSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelPricingCatalogUiState(
    val snapshot: ModelPricingSnapshot = ModelPricingCatalog.current(),
    val refreshing: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

class ModelPricingCatalogViewModel : ViewModel() {
    private val _state = MutableStateFlow(ModelPricingCatalogUiState())
    val state: StateFlow<ModelPricingCatalogUiState> = _state.asStateFlow()

    init {
        ServiceContainer.appContext?.let { context ->
            _state.update { it.copy(snapshot = ModelPricingCatalog.loadCached(context)) }
        }
    }

    fun refresh() {
        val context = ServiceContainer.appContext ?: run {
            _state.update { it.copy(error = ServiceContainer.getString(R.string.app_not_initialized)) }
            return
        }
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, message = null, error = null) }
            runCatching { ModelPricingCatalog.refresh(context) }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            snapshot = snapshot,
                            refreshing = false,
                            message = context.getString(
                                R.string.model_catalog_updated_count,
                                snapshot.entries.size
                            )
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            refreshing = false,
                            error = throwable.message
                                ?: ServiceContainer.getString(R.string.model_catalog_update_failed)
                        )
                    }
                }
        }
    }
}
