package com.nekobot.app.data.local

import androidx.annotation.StringRes
import com.nekobot.app.R
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase

/** 创建页和事务提交共用的资格检查；不以消息数判断角色是否已使用。 */
internal class CompanionCreation(private val db: NekobotDatabase) {
    /**
     * 该角色仍有效的单角色窗口。
     *
     * 归属行指向的会话被删除后即为悬空：这里顺手释放，让角色回到普通角色卡状态。
     * 不释放的话角色页会读不到窗口而打不开，该角色也永远无法再建窗口。
     */
    suspend fun boundSession(characterId: String): LocalSessionEntity? {
        val owner = db.companionMemoryDao().owner(characterId) ?: return null
        val bound = db.sessionDao().getById(owner.sessionId)
        return when (CompanionOwnership.resolve(owner.sessionId, bound, characterId)) {
            CompanionOwnership.Bound -> bound
            CompanionOwnership.Dangling -> {
                db.companionMemoryDao().eraseOwner(characterId)
                null
            }
            CompanionOwnership.Unbound -> null
        }
    }

    /** 释放指向已不存在窗口的归属行（旧版本删除窗口后遗留），避免角色被永久隐藏。 */
    suspend fun releaseDanglingOwnership() {
        for (owner in db.companionMemoryDao().owners()) {
            val bound = db.sessionDao().getById(owner.sessionId)
            if (CompanionOwnership.resolve(owner.sessionId, bound, owner.characterId) == CompanionOwnership.Dangling) {
                db.companionMemoryDao().eraseOwner(owner.characterId)
            }
        }
    }

    suspend fun eligibility(characterId: String?): CompanionEligibility {
        if (characterId.isNullOrBlank() || db.characterDao().getById(characterId) == null) {
            return CompanionEligibility.MissingCharacter
        }
        boundSession(characterId)?.let { return CompanionEligibility.Existing(it.id) }
        val sessions = db.sessionDao().listForCompanionEligibility(characterId)
        if (sessions.isEmpty()) return CompanionEligibility.Available
        val candidates = sessions.filter { it.canTransferToCompanion() }
        return CompanionEligibility.Used(candidates)
    }

    /** 必须在与 INSERT 相同的事务内调用，避免预览后另一操作创建了会话。 */
    suspend fun requireNew(characterId: String?) {
        val state = eligibility(characterId)
        if (state != CompanionEligibility.Available) throw CompanionCreationException(state)
    }
}

fun LocalSessionEntity.canTransferToCompanion(): Boolean = !companionMode &&
    !characterId.isNullOrBlank() && characterIds.isNullOrBlank() &&
    (sessionMode.equals("character", true) || (sessionMode.equals("agent", true) && inheritCharacter))

sealed interface CompanionEligibility {
    data object Available : CompanionEligibility
    data object MissingCharacter : CompanionEligibility
    data class Existing(val sessionId: String) : CompanionEligibility
    data class Used(val candidates: List<LocalSessionEntity>) : CompanionEligibility
}

class CompanionCreationException(val eligibility: CompanionEligibility) : IllegalStateException() {
    @get:StringRes
    val messageRes: Int get() = when (eligibility) {
        CompanionEligibility.MissingCharacter -> R.string.companion_create_missing
        is CompanionEligibility.Existing -> R.string.companion_create_existing
        is CompanionEligibility.Used -> R.string.companion_create_used
        CompanionEligibility.Available -> R.string.companion_create_changed
    }
}
