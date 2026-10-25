package com.nekobot.app.ui.screens.aiconfig

import com.nekobot.app.R
import com.nekobot.app.data.local.ai.parseModelProxyUrl
import com.nekobot.app.data.local.ai.usesFixedEmbeddingDimensions
import com.nekobot.app.data.local.ai.validEmbeddingDimensionsInput
import java.net.URI

internal data class ModelEditorIssue(val field: String, val step: Int, val message: Int)

/** 按步骤验证，空白的可选参数继续使用原有默认值。 */
internal fun AiModelEditorState.validationIssues(showProxy: Boolean, customDimensions: Boolean): List<ModelEditorIssue> = buildList {
    fun issue(field: String, step: Int, message: Int) { add(ModelEditorIssue(field, step, message)) }
    if (name.isBlank()) issue("name", 0, R.string.aimodel_editor_validation_name)
    val localSpeech = purpose == "stt" && sttProvider == "local"
    if (!localSpeech) {
        if (baseUrl.isBlank()) issue("baseUrl", 1, R.string.aimodel_editor_validation_base_url)
        else if (!runCatching { URI(baseUrl.trim()).let { it.scheme in setOf("http", "https", "ws", "wss") && !it.host.isNullOrBlank() } }.getOrDefault(false)) {
            issue("baseUrl", 1, R.string.model_setup_invalid_url)
        }
        if (apiKey.isBlank()) issue("apiKey", 1, R.string.aimodel_editor_validation_api_key)
        if (model.isBlank()) issue("model", 1, R.string.aimodel_editor_validation_model)
        if (showProxy && proxyUrl.isNotBlank() && runCatching { parseModelProxyUrl(proxyUrl, resolveHost = false) }.isFailure) {
            issue("proxyUrl", 1, R.string.model_setup_invalid_proxy)
        }
    }
    if (purpose == "embedding" && !usesFixedEmbeddingDimensions(model) && (customDimensions || dimensions.isNotBlank()) && !validEmbeddingDimensionsInput(dimensions)) {
        issue("dimensions", 2, R.string.aimodel_editor_embedding_dimensions_invalid)
    }
    if (purpose in setOf("chat", "vision", "video", "live")) {
        listOf("maxTokens" to maxTokens, "maxContextLength" to maxContextLength).forEach { (field, value) ->
            if (value.isNotBlank() && (value.toIntOrNull() ?: 0) <= 0) issue(field, 2, R.string.model_setup_positive_integer)
        }
        listOf("temperature" to temperature, "topP" to topP).forEach { (field, value) ->
            val number = value.toDoubleOrNull()
            if (value.isNotBlank() && (number == null || !number.isFinite() || number < 0 || number > if (field == "topP") 1 else 2)) {
                issue(field, 2, if (field == "topP") R.string.model_setup_top_p_range else R.string.model_setup_temperature_range)
            }
        }
    }
    listOf("inputPrice" to inputPrice, "outputPrice" to outputPrice, "cacheReadPrice" to cacheReadPrice, "cacheWritePrice" to cacheWritePrice).forEach { (field, value) ->
        val number = value.toDoubleOrNull()
        if (value.isNotBlank() && (number == null || !number.isFinite() || number < 0)) issue(field, 2, R.string.model_setup_nonnegative_number)
    }
    if (priority.isNotBlank() && priority.toIntOrNull() == null) issue("priority", 2, R.string.model_setup_integer)
}
