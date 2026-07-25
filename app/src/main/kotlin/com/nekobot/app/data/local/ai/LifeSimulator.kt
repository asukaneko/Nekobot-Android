package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterMemoryEntity
import com.nekobot.app.data.local.db.MemoryDao
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 角色生活片段生成器（静默心跳的"懒触发"版）。
 *
 * 对齐原仓库 nbot/web/server.py 的 _build_life_sim_prompt / _persist_life_sim_to_memory /
 * _extract_activity_from_life_sim / _update_heartbeat_activity。
 *
 * 与原仓库的区别：
 * - 原仓库依赖 SessionHeartbeatManager 后台定时调度（服务器进程持续运行）
 * - Android 端无后台常驻进程，改为"懒触发"：用户下次发消息时检查距离上次生成的时间，
 *   超过阈值则补生成一次 life_sim 内容并写入 MemoryFS。
 * - 用户体验等价：用户打开会话时 AI 已"知道"角色在用户不在时做了什么。
 */
object LifeSimulator {
    private const val TAG = "LifeSimulator"

    /** life_sim 生成间隔（分钟），对齐原仓库默认 60 分钟 */
    const val INTERVAL_MINUTES = 60L

    /** life_sim 单会话最大持久化条目数（对齐原仓库 _MAX_LIFE_SIM_ENTRIES） */
    private const val MAX_LIFE_SIM_ENTRIES = 10

    /**
     * 检查是否需要触发生成。
     *
     * @param lastRunIso 上次生成时间（ISO 字符串），空表示从未生成
     * @param now 当前时间
     * @return true 表示需要触发
     */
    fun shouldTrigger(lastRunIso: String?, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (lastRunIso.isNullOrBlank()) return true
        val last = parseDateTime(lastRunIso) ?: return true
        val elapsedMinutes = java.time.Duration.between(last, now).toMinutes()
        return elapsedMinutes >= INTERVAL_MINUTES
    }

    /**
     * 构建生成角色生活片段的 prompt（对齐原仓库 _build_life_sim_prompt）。
     *
     * 关键约束（避免 AI 把生活片段写成"和用户对话"）：
     * - 用户当前不在场，这是角色独处时的真实生活经历
     * - 不要写任何对话、互动、用户出现
     * - sleeping 阶段：写"在睡觉/做梦/被吵醒前的休息"类内容
     * - 第一行用短语概括活动（用于更新 current_activity）
     */
    fun buildLifeSimPrompt(
        profileText: String,
        circadianText: String,
        timelineText: String,
        recentText: String,
        phase: String = ""
    ): List<Map<String, String>> {
        // 基础系统提示：明确"独处"语境
        var system = (
            "你是一个角色生活模拟器。根据角色设定、当前时段、近期经历，" +
            "生成一段角色独处时真实经历的生活片段。\n\n" +
            "【关键约束】\n" +
            "1. 用户当前不在场——这是角色一个人独处时发生的事，" +
            "不要写任何与用户对话、互动、回应用户的内容\n" +
            "2. 不要出现'用户'、'TA'、'我们'、'和你聊天'、'你刚才说'等" +
            "暗示有对话的措辞\n" +
            "3. 50-100 字，第一人称或第三人称均可（与角色卡风格一致）\n" +
            "4. 内容必须贴合角色性格、当前昼夜时段（如深夜不会去散步）\n" +
            "5. 可以是日常琐事、情绪流动、小插曲，但不要重大剧情转折\n" +
            "6. 不要提到'系统'、'心跳'、'模拟'等元信息\n" +
            "7. 第一行用一个短语概括角色正在做什么（如：在厨房煮咖啡）"
        )

        // sleeping 阶段：追加休息保护
        if (phase == "sleeping") {
            system += (
                "\n\n【夜间睡眠保护】\n" +
                "现在是深夜/凌晨时段，角色正在睡觉休息中。" +
                "请生成角色在睡眠中或睡前的状态（如：正在熟睡、做了个梦、" +
                "在浅睡中翻身、被窗外的声响短暂吵醒又睡过去等）。" +
                "不要写角色深夜外出、看书、运动、喝咖啡等任何清醒活动。"
            )
        }

        val userParts = mutableListOf<String>()
        if (profileText.isNotBlank()) userParts.add(profileText)
        if (circadianText.isNotBlank()) userParts.add("【当前时段】\n$circadianText")
        userParts.add("【近期经历】\n$timelineText")
        userParts.add(
            "【参考信息】用户最近的对话历史（仅用于了解用户身份，" +
            "不要让角色在生活片段中与用户互动）：\n$recentText"
        )
        userParts.add("请生成角色独处时的一段生活片段：")

        return listOf(
            mapOf("role" to "system", "content" to system),
            mapOf("role" to "user", "content" to userParts.joinToString("\n\n"))
        )
    }

    /**
     * 从 AI 生成的生活片段提取活动标签（第一行）。
     * 对齐原仓库 _extract_activity_from_life_sim。
     */
    fun extractActivity(content: String): String {
        val firstLine = content.trim().split("\n").firstOrNull()?.trim() ?: return ""
        // 去掉可能的前缀符号（如"1. "、"· "等）
        var result = firstLine
        for (prefix in listOf("1.", "2.", "3.", "·", "•", "- ", "* ")) {
            if (result.startsWith(prefix)) {
                result = result.removePrefix(prefix).trim()
            }
        }
        return result.take(60)
    }

    /**
     * 完整的生成 + 持久化流程。
     *
     * @param aiClient AI 客户端
     * @param activeModel 激活模型
     * @param memoryDao 记忆 DAO
     * @param characterId 角色 ID
     * @param conversationId 会话 ID
     * @param targetId 目标用户 ID
     * @param profileText 角色卡文本（name + description + personality + scenario）
     * @param circadianState 昼夜状态（来自 TimeContext.buildCircadianState）
     * @param recentMessages 最近用户消息列表（用于参考信息）
     * @param onTokenRecorded token 用量回调
     *        参数：input, output, model（配置名）, actualModel（实际模型标识，用于排行榜聚合）
     * @return 生成的活动标签（如"在厨房煮咖啡"），空字符串表示生成失败
     */
    suspend fun generateAndPersist(
        aiClient: LocalAiClient,
        activeModel: LocalAiModelEntity,
        memoryDao: MemoryDao,
        characterId: String,
        conversationId: String,
        targetId: String,
        profileText: String,
        circadianState: Map<String, Any>,
        recentMessages: List<String>,
        onTokenRecorded: (input: Int, output: Int, model: String, actualModel: String) -> Unit = { _, _, _, _ -> }
    ): String {
        if (characterId.isBlank() || conversationId.isBlank()) return ""

        try {
            val phase = (circadianState["phase"] as? String).orEmpty()
            val circadianText = TimeContext.formatCircadianPrompt(circadianState)

            // 收集近期经历：优先读本会话之前的 life_sim（最近 5 条），兜底 timeline
            val timelineText = collectTimeline(memoryDao, characterId, conversationId)

            // 用户最近对话历史（仅用于了解用户身份）
            val recentText = recentMessages.takeLast(5).joinToString("\n").ifBlank { "（暂无对话历史）" }

            // 构建 prompt 并调用 LLM
            val messages = buildLifeSimPrompt(profileText, circadianText, timelineText, recentText, phase)
            val result = aiClient.chatOnce(activeModel, messages)
            if (result.error != null) {
                LocalLogger.w(TAG, "life_sim 生成失败: ${result.error}")
                return ""
            }

            val content = result.content.trim()
            if (content.isBlank()) {
                LocalLogger.w(TAG, "life_sim 生成内容为空")
                return ""
            }

            // 提取活动标签
            val activity = extractActivity(content)

            // 持久化到 MemoryFS（life_sim 路径，按 conversationId 隔离）
            val now = LocalDateTime.now()
            val timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            val entry = "[$timestamp] $content"
            val lifeSimPath = "characters/$characterId/life_sim/$conversationId.md"

            val version = try {
                (memoryDao.listByPath(lifeSimPath).maxOfOrNull { it.version } ?: 0) + 1
            } catch (e: Exception) { 1 }

            val entity = LocalCharacterMemoryEntity(
                id = UUID.randomUUID().toString(),
                characterId = characterId,
                targetId = targetId,
                type = "long",
                category = "life_sim",
                title = "角色生活片段",
                summary = "心跳生成的角色自我生活经历",
                content = entry,
                importance = 5,  // 0.5 * 10
                memoryPath = lifeSimPath,
                version = version,
                createdAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                updatedAt = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
            memoryDao.upsert(entity)

            // 截断：保留最新 MAX_LIFE_SIM_ENTRIES 条
            try {
                memoryDao.trimByPath(lifeSimPath, keep = MAX_LIFE_SIM_ENTRIES)
            } catch (e: Exception) {
                LocalLogger.w(TAG, "life_sim 截断失败: ${e.message}")
            }

            // 记录 token 用量
            val usage = result.usage
            if (usage.isNotEmpty()) {
                val input = (usage["prompt_tokens"] ?: usage["input_tokens"] ?: 0) as Int
                val output = (usage["completion_tokens"] ?: usage["output_tokens"] ?: 0) as Int
                onTokenRecorded(input, output, activeModel.name, activeModel.model)
            }

            LocalLogger.i(TAG, "life_sim 生成成功 | char=$characterId | conv=$conversationId | activity=$activity | contentLen=${content.length}")
            return activity
        } catch (e: Exception) {
            LocalLogger.w(TAG, "life_sim 生成异常: ${e.message}", e)
            return ""
        }
    }

    /**
     * 收集近期经历（对齐原仓库 _collect_heartbeat_timeline）。
     * 优先读本会话之前的 life_sim（最近 5 条），兜底 timeline。
     */
    private suspend fun collectTimeline(
        memoryDao: MemoryDao,
        characterId: String,
        conversationId: String
    ): String {
        try {
            // 优先读本会话的 life_sim
            val lifeSimPath = "characters/$characterId/life_sim/$conversationId.md"
            val lifeSimEntries = memoryDao.listByPath(lifeSimPath)
            if (lifeSimEntries.isNotEmpty()) {
                val lines = lifeSimEntries
                    .sortedByDescending { it.updatedAt ?: it.createdAt }
                    .take(5)
                    .map { it.content.trim() }
                if (lines.isNotEmpty()) return lines.joinToString("\n")
            }

            // 兜底：跨会话 timeline
            val timelinePath = "characters/$characterId/timeline.md"
            val timelineEntries = memoryDao.listByPath(timelinePath)
            if (timelineEntries.isNotEmpty()) {
                val lines = timelineEntries
                    .sortedByDescending { it.updatedAt ?: it.createdAt }
                    .take(5)
                    .map { it.content.trim() }
                if (lines.isNotEmpty()) return lines.joinToString("\n")
            }

            return "（暂无经历）"
        } catch (e: Exception) {
            return "（暂无经历）"
        }
    }

    /** 解析时间字符串 */
    private fun parseDateTime(value: String): LocalDateTime? {
        return try {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: Exception) {
            try {
                val cleaned = value.replace("Z", "").substringBefore("+").substringBeforeLast("-")
                LocalDateTime.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } catch (e2: Exception) { null }
        }
    }
}
