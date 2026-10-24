package com.nekobot.app.data.local

import androidx.room.withTransaction
import com.nekobot.app.data.local.ai.CharacterThinkingSettings
import com.nekobot.app.data.local.ai.CompanionPromptSettings
import com.nekobot.app.data.local.ai.characterThinkingPromptToSave
import com.nekobot.app.data.local.ai.companionSpeakingPromptToSave
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class CompanionPromptStore(
    private val db: NekobotDatabase,
    private val legacy: () -> CharacterThinkingSettings
) {
    private val sessions = db.sessionDao()

    suspend fun get(sessionId: String): CompanionPromptSettings = withContext(Dispatchers.IO) {
        db.withTransaction {
            val previous = legacy()
            sessions.initializeCompanionPrompts(previous.customPrompt, previous.enabled)
            requireNotNull(sessions.getById(sessionId)) { "角色会话不存在" }.promptSettings()
        }
    }

    fun observe(sessionId: String) = flow {
        get(sessionId)
        emitAll(sessions.observeById(sessionId).map {
            requireNotNull(it) { "角色会话不存在" }.promptSettings()
        })
    }.flowOn(Dispatchers.IO)

    suspend fun saveSpeaking(sessionId: String, draft: String) = update(sessionId) {
        sessions.updateCompanionSpeakingPrompt(sessionId, companionSpeakingPromptToSave(draft))
    }

    suspend fun saveThinking(sessionId: String, draft: String) = update(sessionId) {
        sessions.updateCompanionThinkingPrompt(sessionId, characterThinkingPromptToSave(draft).takeUnless(String::isBlank).orEmpty())
    }

    suspend fun setThinkingEnabled(sessionId: String, enabled: Boolean) = update(sessionId) {
        sessions.updateCompanionThinkingEnabled(sessionId, enabled)
    }

    private suspend fun update(sessionId: String, write: suspend () -> Int) = withContext(Dispatchers.IO) {
        db.withTransaction {
            get(sessionId)
            check(write() == 1) { "角色设置未保存，请重新进入后重试" }
        }
    }

    private fun LocalSessionEntity.promptSettings(): CompanionPromptSettings {
        require(companionMode && !characterId.isNullOrBlank()) { "此设置仅适用于单会话角色" }
        return CompanionPromptSettings(companionSpeakingPrompt.orEmpty(), companionThinkingPrompt.orEmpty(),
            requireNotNull(companionThinkingEnabled) { "角色设置尚未初始化" })
    }
}
