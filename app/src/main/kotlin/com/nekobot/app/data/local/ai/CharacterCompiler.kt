package com.nekobot.app.data.local.ai

/**
 * 角色卡→系统提示词编译器，对应原仓库 nbot/character/compiler.py。
 *
 * 将 CharacterProfile 编译为完整的系统提示词，包含：
 * 角色名称/基本信息/性格/背景/回复格式/规则/示例对话 + 运行时状态 + 关系状态。
 */
object CharacterCompiler {

    /**
     * 编译角色卡为系统提示词。
     *
     * @param profile 角色卡
     * @param state 运行时状态（可选）
     * @param relationship 关系状态（可选）
     * @param sessionContext 会话上下文（可选，含 session_name/current_time/user_info/recent_messages）
     * @param userName 用户名（用于 {{user}} 模板替换）
     * @return 系统提示词文本
     */
    fun compileProfilePrompt(
        profile: CharacterProfile,
        state: CharacterState? = null,
        relationship: RelationshipState? = null,
        sessionContext: Map<String, Any>? = null,
        userName: String? = null
    ): String {
        val parts = mutableListOf<String>()

        // 角色名称
        if (profile.name.isNotEmpty()) {
            parts.add("【角色名称】\n${profile.name}")
        }

        // 基本信息
        if (profile.basicInfo.isNotEmpty()) {
            parts.add("【基本信息】\n${profile.basicInfo}")
        }

        // 性格特点
        if (profile.personality.isNotEmpty()) {
            parts.add("【性格特点】\n${profile.personality}")
        }

        // 背景设定
        if (profile.scenario.isNotEmpty()) {
            parts.add("【背景设定】\n${profile.scenario}")
        }

        // 回复格式
        if (profile.responseFormat.isNotEmpty()) {
            parts.add("【回复格式】\n${profile.responseFormat}")
        }

        // 行为规则
        if (profile.rules.isNotEmpty()) {
            val rulesText = profile.rules.mapIndexed { idx, rule -> "${idx + 1}. $rule" }.joinToString("\n")
            parts.add("【行为规则】\n$rulesText")
        }

        // 示例对话
        if (profile.exampleDialogues.isNotEmpty()) {
            parts.add("【示例对话】\n${profile.exampleDialogues}")
        }

        // 会话上下文
        if (sessionContext != null && sessionContext.isNotEmpty()) {
            val ctxParts = mutableListOf<String>()
            (sessionContext["session_name"] as? String)?.let { if (it.isNotEmpty()) ctxParts.add("会话名称: $it") }
            (sessionContext["current_time"] as? String)?.let { if (it.isNotEmpty()) ctxParts.add("当前时间: $it") }
            (sessionContext["user_info"] as? String)?.let { if (it.isNotEmpty()) ctxParts.add("用户信息: $it") }
            @Suppress("UNCHECKED_CAST")
            val recentMessages = sessionContext["recent_messages"] as? List<Map<String, Any>>
            if (!recentMessages.isNullOrEmpty()) {
                val msgText = recentMessages.takeLast(6).joinToString("\n") { msg ->
                    val role = msg["role"] as? String ?: ""
                    val content = msg["content"] as? String ?: ""
                    "  ${role}: ${content.take(200)}"
                }
                ctxParts.add("最近对话:\n$msgText")
            }
            if (ctxParts.isNotEmpty()) {
                parts.add("【当前会话上下文】\n${ctxParts.joinToString("\n")}")
            }
        }

        // 角色运行时状态
        if (state != null) {
            val stateParts = mutableListOf<String>()
            stateParts.add("心情: ${state.mood}")
            stateParts.add("情绪强度: ${(state.moodIntensity * 100).toInt()}/100")
            stateParts.add("精力: ${state.energy}/100")
            parts.add("【角色当前状态】\n${stateParts.joinToString("\n")}")
        }

        // 关系状态
        if (relationship != null) {
            val relParts = mutableListOf<String>()
            relParts.add("好感: ${relationship.affection}/100")
            relParts.add("信任: ${relationship.trust}/100")
            relParts.add("熟悉: ${relationship.familiarity}/100")
            relParts.add("依赖: ${relationship.dependency}/100")
            relParts.add("安全感: ${relationship.security}/100")
            if (relationship.jealousy > 0) {
                relParts.add("嫉妒: ${relationship.jealousy}/100")
            }
            parts.add("【与用户的关系】\n${relParts.joinToString("\n")}")
        }

        var prompt = if (parts.isNotEmpty()) {
            val header = "你是角色 \"${profile.name.ifEmpty { "未命名" }}\"。\n\n"
            header + parts.joinToString("\n\n")
        } else {
            "请定义你的角色设定。"
        }

        // 模板变量替换
        if (userName != null) {
            prompt = prompt.replace("{{user}}", userName)
        }
        prompt = prompt.replace("{{char}}", profile.name)

        return prompt
    }

    /**
     * 从旧 personality dict 编译系统提示词（兼容入口）。
     */
    fun compilePersonalityPrompt(
        personalityData: Map<String, Any>,
        sessionContext: Map<String, Any>? = null,
        userName: String? = null
    ): String {
        val profile = CharacterProfile.fromPersonalityDict(personalityData)
        return compileProfilePrompt(profile, sessionContext = sessionContext, userName = userName)
    }
}
