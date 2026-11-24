package com.nekobot.app

import android.content.Context
import app.lianxu.independent.ConfigRules
import app.lianxu.independent.HostModelBridge
import app.lianxu.independent.HostModelSnapshot
import com.nekobot.app.data.local.ai.OpenAIChatProtocol
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.NekobotDatabase

internal class NekobotModelBridge(private val context: Context) : HostModelBridge {
    override suspend fun listLocalModels(): List<HostModelSnapshot> {
        val prefs = ServiceContainer.prefs
        return localModelSnapshots(prefs.isLocalMode) { NekobotDatabase.get(context, prefs.activeDbName).aiModelDao().listAll() }
    }
}

internal suspend fun localModelSnapshots(local: Boolean, rows: suspend () -> List<LocalAiModelEntity>): List<HostModelSnapshot> =
    if (local) rows().mapNotNull { it.hostSnapshot() } else emptyList()

internal fun LocalAiModelEntity.hostSnapshot(): HostModelSnapshot? {
    val base = baseUrl.trimEnd('/')
    if (!enabled || !oauthAccountId.isNullOrBlank() || !appendBaseUrlPath ||
        apiKey.isBlank() || apiKey.any { it.isWhitespace() || it.isISOControl() } ||
        protocol != "openai_chat" || purpose !in listOf("chat", "embedding") ||
        model.isBlank() || ConfigRules.urlError(base) != null) return null
    val endpoint = if (purpose == "embedding") LocalAiClient.resolveEmbeddingUrl(base, appendBaseUrlPath)
        else OpenAIChatProtocol.resolveUrl(base, model, appendBaseUrlPath, false, apiKey)
    val path = if (purpose == "embedding") "/embeddings" else "/chat/completions"
    if (endpoint != base + path) return null
    return HostModelSnapshot(id, name.ifBlank { model }, base, model, apiKey, purpose, active, maxContextLength, maxTokens)
}
