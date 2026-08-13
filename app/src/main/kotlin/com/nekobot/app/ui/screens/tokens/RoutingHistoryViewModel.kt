package com.nekobot.app.ui.screens.tokens

import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.RoutingAbTestStats
import com.nekobot.app.data.local.ai.RoutingModelStats
import com.nekobot.app.data.local.ai.aggregateRoutingAbTestStats
import com.nekobot.app.data.local.ai.aggregateRoutingModelStats
import com.nekobot.app.data.local.db.RoutingDecisionLogEntity
import com.nekobot.app.ui.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 路由决策历史 ViewModel。
 *
 * 通过 LocalRepository.routingDecisionLogger 查询/评分/清空决策日志，
 * 为 RoutingHistoryScreen 提供数据源。
 */
class RoutingHistoryViewModel : BaseViewModel() {

    private val logger get() = ServiceContainer.localRepository.routingDecisionLogger

    private val _logs = MutableStateFlow<List<RoutingDecisionLogEntity>>(emptyList())
    val logs: StateFlow<List<RoutingDecisionLogEntity>> = _logs.asStateFlow()

    private val _modelStats = MutableStateFlow<List<RoutingModelStats>>(emptyList())
    val modelStats: StateFlow<List<RoutingModelStats>> = _modelStats.asStateFlow()

    private val _abTestStats = MutableStateFlow<List<RoutingAbTestStats>>(emptyList())
    val abTestStats: StateFlow<List<RoutingAbTestStats>> = _abTestStats.asStateFlow()

    /**
     * 加载路由决策历史。
     *
     * @param sessionId 会话 ID（为 null 时查询全部）
     * @param limit 返回条数上限
     */
    fun loadLogs(sessionId: String? = null, limit: Int = 50) {
        viewModelScope.launch {
            setLoading(true)
            clearError()
            try {
                val history = logger.queryHistory(sessionId, limit)
                _logs.value = history

                // 总览使用更大的样本窗口；单条历史仍按页面 limit 展示。
                val analyticsHistory = if (sessionId == null) {
                    logger.queryHistory(limit = ANALYTICS_LIMIT)
                } else {
                    history
                }
                val aggregated = aggregateRoutingModelStats(analyticsHistory)
                val configuredModels = if (sessionId == null) {
                    ServiceContainer.localRepository.listModelsByPurpose("chat")
                } else {
                    emptyList()
                }
                val statsById = aggregated.associateBy { it.modelId }
                val configuredStats = configuredModels.map { model ->
                    statsById[model.id] ?: RoutingModelStats(
                        modelId = model.id,
                        modelName = model.name.ifBlank { model.model }
                    )
                }
                _modelStats.value = (configuredStats + aggregated.filter { stat ->
                    configuredModels.none { it.id == stat.modelId }
                }).sortedWith(
                    compareBy<RoutingModelStats> { it.latestScore == null }
                        .thenBy { it.latestScore ?: Double.MAX_VALUE }
                        .thenByDescending { it.selectedCount }
                        .thenBy { it.modelName }
                )
                _abTestStats.value = aggregateRoutingAbTestStats(analyticsHistory)
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * 记录用户对回复质量的主观评分。
     *
     * @param logId 日志 ID
     * @param score 质量评分（-1=差, 1=好）
     */
    fun recordQuality(logId: String, score: Int) {
        viewModelScope.launch {
            try {
                logger.recordQualityScore(logId, score)
                // 乐观更新本地列表，避免整页重载
                _logs.value = _logs.value.map { log ->
                    if (log.id == logId) log.copy(qualityScore = score) else log
                }
                val analyticsHistory = logger.queryHistory(limit = ANALYTICS_LIMIT)
                _modelStats.value = aggregateRoutingModelStats(analyticsHistory)
                _abTestStats.value = aggregateRoutingAbTestStats(analyticsHistory)
                showToast(
                    string(
                        if (score > 0) R.string.routing_rating_positive
                        else R.string.routing_rating_negative
                    )
                )
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            }
        }
    }

    /**
     * 清空全部路由决策日志。
     */
    fun clearLogs() {
        viewModelScope.launch {
            setLoading(true)
            try {
                logger.clearAll()
                _logs.value = emptyList()
                _modelStats.value = emptyList()
                _abTestStats.value = emptyList()
                showToast(string(R.string.routing_history_cleared))
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.common_unknown_error))
            } finally {
                setLoading(false)
            }
        }
    }

    private companion object {
        const val ANALYTICS_LIMIT = 200
    }
}
