package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionMemoryEntity
import java.time.Instant

/** 核心清单和发送内容共用规则；维护目标不用于聊天时静默裁掉核心。 */
internal object CompanionCoreMemory {
    const val maintenanceTokens = 1000

    fun contains(memory: CompanionMemoryEntity, now: Instant = Instant.now()): Boolean =
        memory.current && memory.valid && !memory.voided && !memory.pendingConfirm && memory.status in setOf("active", "completed") &&
            CompanionImportPolicy.usable(memory, now) &&
            (memory.alwaysProvide || (memory.kind == "core" && !memory.coreExcluded))

    fun rows(memories: List<CompanionMemoryEntity>): List<CompanionMemoryEntity> =
        memories.filter { contains(it) }.sortedBy { it.factKey }

    // 内部归属和来源仍存原库；发送时只提供有依据的日期与正文。
    fun render(memories: List<CompanionMemoryEntity>, today: java.time.LocalDate = java.time.LocalDate.now()): String =
        CompanionPromptLanguage.memories(memories, today)

    fun tokens(memories: List<CompanionMemoryEntity>): Int =
        if (memories.isEmpty()) 0 else estimateLocalTextTokens(render(memories))

    const val selectionRules = """
        核心(kind=core)只保存跨话题仍持续影响相处的少量稳定信息：常用称呼、明确关系、相处界限、长期重要偏好。
        临时情绪不能当稳定性格；日常细节、普通经历和短期待办通常不是核心。不能为了填满核心而凑内容。
        核心正文简洁且忠于原话，不重复角色卡，不输出总摘要。记不清的事不要编。
        对已有条目有新证据时，同时判断它现在是否仍适合作为核心；不适合就返回对应fact/task/episode等kind。
        若正文相同但核心归属需调整，也用update并附本批证据；merge仅用于补充原话出处。
        existing里的always_provide是用户固定、core_excluded是用户要求按需检索；不得绕过其key另建一条来改变用户选择。
        核心总量约1000 Token仅为维护建议，不因超过建议就降为普通记忆；是否精简、移出或删除由用户选择。无需为了挑选核心另发模型请求。
    """
}
