package com.nekobot.app.ui.screens.settings

import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.knowledge.RagConfig
import com.nekobot.app.ui.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RAG 检索配置 ViewModel：管理语义权重、返回数量、MMR 系数、重排、阈值、引用标注等设置。
 * 通过 [ServiceContainer.prefs] 读写持久化值，并暴露 [StateFlow] 供 UI 订阅。
 */
class RagSettingsViewModel : BaseViewModel() {

    private val _config = MutableStateFlow(ServiceContainer.prefs.getRagConfig())
    val config: StateFlow<RagConfig> = _config.asStateFlow()

    /** 更新语义检索权重（0.0~1.0） */
    fun updateSemanticWeight(value: Float) {
        ServiceContainer.prefs.ragSemanticWeight = value
        _config.value = _config.value.copy(semanticWeight = value)
    }

    /** 更新最终返回结果数（1~20） */
    fun updateTopK(value: Int) {
        ServiceContainer.prefs.ragTopK = value
        _config.value = _config.value.copy(topK = value)
    }

    /** 更新 MMR 多样性系数（0.0~1.0） */
    fun updateMmrLambda(value: Float) {
        ServiceContainer.prefs.ragMmrLambda = value
        _config.value = _config.value.copy(mmrLambda = value)
    }

    /** 更新是否启用重排 */
    fun updateRerankEnabled(value: Boolean) {
        ServiceContainer.prefs.ragRerankEnabled = value
        _config.value = _config.value.copy(rerankEnabled = value)
    }

    /** 更新最低得分阈值（0.0~1.0） */
    fun updateScoreThreshold(value: Float) {
        ServiceContainer.prefs.ragScoreThreshold = value
        _config.value = _config.value.copy(scoreThreshold = value)
    }

    /** 更新是否启用引用标注 */
    fun updateCitationEnabled(value: Boolean) {
        ServiceContainer.prefs.ragCitationEnabled = value
        _config.value = _config.value.copy(citationEnabled = value)
    }
}
