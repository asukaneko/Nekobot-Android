package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalSessionEntity

/**
 * `companion_owners` 归属行与实际会话的对应关系。
 *
 * 归属行只说明「哪个会话曾是这个角色的单角色窗口」。会话被删除、被导入流程漏掉，
 * 或不再属于该角色时归属就会悬空。悬空归属必须按「未绑定」处理并清理，
 * 否则角色页读取会失败，该角色也永远无法再建窗口。
 */
internal enum class CompanionOwnership {
    /** 没有归属行：角色就是普通角色卡。 */
    Unbound,

    /** 归属行指向仍然有效的单角色窗口。 */
    Bound,

    /** 归属行指向的窗口已经不存在或不再属于该角色：需要释放后按未绑定处理。 */
    Dangling;

    companion object {
        fun resolve(ownerSessionId: String?, bound: LocalSessionEntity?, characterId: String): CompanionOwnership = when {
            ownerSessionId.isNullOrBlank() -> Unbound
            bound == null -> Dangling
            !bound.companionMode -> Dangling
            bound.characterId != characterId -> Dangling
            else -> Bound
        }
    }
}
