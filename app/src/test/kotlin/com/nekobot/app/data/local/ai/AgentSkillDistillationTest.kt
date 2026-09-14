package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillDistillationTest {

    /** 构造一份符合标准格式、且满足最低结构要求的 SKILL.md。 */
    private fun standardSkillMd(name: String): String = """
        ---
        name: $name
        description: 构建并签名 release APK
        ---

        # $name

        ## 功能描述

        用 Gradle Wrapper 构建 release APK，并复用本机 keystore 完成签名，产物可直接安装验证。

        ## 适用场景

        需要出包给自己安装验证，并且希望一次跑通构建与签名流程时。

        ## 操作步骤

        1. 先确认 JAVA_HOME 指向 JDK 21，否则 Gradle 会挑到错误的 JDK。
        2. 执行 gradlew.bat --no-daemon --console=plain assembleRelease 完成构建。
        3. 从 app/build/outputs/apk/release 取走 APK 并安装验证。
        4. 构建失败时先看 Gradle 的报错行，再检查签名配置。

        ## 注意事项

        - release 会启用混淆与资源压缩，构建耗时会明显长于 debug 构建。
        - 签名沿用 debug keystore，不要用于正式发布渠道。
    """.trimIndent()

    /** 用 Gson 组装审查结果 JSON（避免手写转义）。 */
    private fun reviewJson(
        action: String,
        name: String,
        skillMd: String,
        description: String? = "构建并签名 release APK",
        aliases: List<String> = emptyList(),
        reason: String = "包含可复用的签名步骤"
    ): String {
        val payload = linkedMapOf<String, Any>()
        payload["action"] = action
        payload["name"] = name
        if (description != null) payload["description"] = description
        if (aliases.isNotEmpty()) payload["aliases"] = aliases
        payload["reason"] = reason
        payload["skill_md"] = skillMd
        return Gson().toJson(payload)
    }

    private val longUser = "帮我把 release 包构建出来并签名".padEnd(320, 'x')
    private val longAssistant = "已完成构建与签名。".padEnd(400, 'y')

    // ==================== 触发条件 ====================

    @Test
    fun complexTurnWithEnoughToolCallsTriggersReview() {
        assertTrue(
            AgentSkillExtractor.shouldReview(
                explicit = false,
                turnToolCalls = AgentSkillExtractor.COMPLEX_TURN_TOOL_CALLS,
                accumulatedToolCalls = 0,
                userMessage = longUser,
                assistantMessage = longAssistant
            )
        )
    }

    @Test
    fun moderateTurnNoLongerTriggersReview() {
        // 阈值提高后，5 次工具调用的"小任务"不再沉淀 Skill。
        assertFalse(
            AgentSkillExtractor.shouldReview(
                explicit = false,
                turnToolCalls = 5,
                accumulatedToolCalls = 5,
                userMessage = longUser,
                assistantMessage = longAssistant
            )
        )
    }

    @Test
    fun singleToolCallTurnNeverTriggersReview() {
        assertFalse(
            AgentSkillExtractor.shouldReview(
                explicit = false,
                turnToolCalls = 1,
                accumulatedToolCalls = 99,
                userMessage = longUser,
                assistantMessage = longAssistant
            )
        )
    }

    @Test
    fun shortTurnNeverTriggersReview() {
        assertFalse(
            AgentSkillExtractor.shouldReview(
                explicit = false,
                turnToolCalls = 12,
                accumulatedToolCalls = 40,
                userMessage = "在吗",
                assistantMessage = "在的"
            )
        )
    }

    @Test
    fun turnWithoutToolCallsDoesNotTriggerUnlessExplicit() {
        assertFalse(
            AgentSkillExtractor.shouldReview(false, 0, 50, longUser, longAssistant)
        )
        assertTrue(
            AgentSkillExtractor.shouldReview(true, 0, 0, longUser, longAssistant)
        )
    }

    @Test
    fun accumulatedToolCallsTriggerReviewAcrossTurns() {
        val user = "继续".padEnd(320, 'x')
        val assistant = "好的，已完成".padEnd(400, 'y')
        assertFalse(
            AgentSkillExtractor.shouldReview(
                explicit = false,
                turnToolCalls = 2,
                accumulatedToolCalls = AgentSkillExtractor.NUDGE_TOOL_CALL_INTERVAL - 1,
                userMessage = user,
                assistantMessage = assistant
            )
        )
        assertTrue(
            AgentSkillExtractor.shouldReview(
                explicit = false,
                turnToolCalls = 2,
                accumulatedToolCalls = AgentSkillExtractor.NUDGE_TOOL_CALL_INTERVAL,
                userMessage = user,
                assistantMessage = assistant
            )
        )
    }

    @Test
    fun blankAssistantMessageNeverTriggersReview() {
        assertFalse(AgentSkillExtractor.shouldReview(true, 9, 99, "记成 skill", "   "))
    }

    @Test
    fun explicitRequestStillNeedsEnoughContent() {
        assertFalse(AgentSkillExtractor.shouldReview(true, 0, 0, "记成 skill", "好的"))
        assertFalse(
            AgentSkillExtractor.shouldReview(
                true,
                0,
                0,
                "记成 skill",
                "已完成。".padEnd(AgentSkillExtractor.MIN_EXPLICIT_TURN_CHARS - 20, 'x')
            )
        )
    }

    // ==================== 显式沉淀意图识别 ====================

    @Test
    fun explicitLearnRequestsAreDetected() {
        listOf(
            "把这个流程记成 skill",
            "帮我总结成技能",
            "记住这个流程，下次还用",
            "以后都这么做",
            "please save this as a skill",
            "이 과정을 기억해"
        ).forEach { message ->
            assertTrue("应识别为显式沉淀要求: $message", AgentSkillExtractor.isExplicitLearnRequest(message))
        }
    }

    @Test
    fun ordinaryMessagesAreNotTreatedAsLearnRequests() {
        listOf(
            "帮我看下这个报错",
            "再跑一次测试",
            "这个按钮点了没反应"
        ).forEach { message ->
            assertFalse(AgentSkillExtractor.isExplicitLearnRequest(message))
        }
    }

    // ==================== 审查结果解析 ====================

    @Test
    fun createActionParsesIntoDraft() {
        val draft = AgentSkillExtractor.parseReview(
            reviewJson(
                action = "create",
                name = "android-release-build",
                skillMd = standardSkillMd("android-release-build"),
                aliases = listOf("release 构建")
            )
        )

        assertNotNull(draft)
        assertEquals("android-release-build", draft!!.name)
        assertEquals("构建并签名 release APK", draft.description)
        assertEquals(listOf("release 构建"), draft.aliases)
        assertTrue(draft.createNew)
        // 自动生成的 SKILL.md 必须是标准格式：YAML frontmatter + 一级标题 + 标准小节。
        assertTrue(draft.skillMd.startsWith("---\nname: android-release-build\n"))
        assertTrue(draft.skillMd.contains("description: \"构建并签名 release APK\""))
        assertTrue(draft.skillMd.contains("\n# android-release-build\n"))
        assertTrue(draft.skillMd.contains("## 操作步骤"))
    }

    @Test
    fun modelSuppliedFrontMatterIsReplacedByCanonicalOne() {
        val modelMd = standardSkillMd("android-release-build")
            .replaceFirst("name: android-release-build", "name: 打包发布")
        val draft = AgentSkillExtractor.parseReview(
            reviewJson("create", "android-release-build", modelMd, aliases = listOf("release 构建"))
        )

        assertNotNull(draft)
        assertTrue(draft!!.skillMd.startsWith("---\nname: android-release-build\n"))
        assertTrue(draft.skillMd.contains("aliases: [release 构建]"))
        assertFalse("模型自带的 frontmatter 字段不可信", draft.skillMd.contains("打包发布"))
        assertEquals(2, Regex("(?m)^---$").findAll(draft.skillMd).count())
    }

    @Test
    fun trivialSkillIsRejected() {
        val thin = "# quick-fix\n\n## 操作步骤\n\n1. 重启一下服务"
        assertNull(AgentSkillExtractor.parseReview(reviewJson("create", "quick-fix", thin)))
    }

    @Test
    fun skillWithoutDescriptionIsRejected() {
        assertNull(
            AgentSkillExtractor.parseReview(
                reviewJson("create", "android-release-build", standardSkillMd("android-release-build"), description = null)
            )
        )
    }

    @Test
    fun updateActionKeepsExistingNameAndMarksNotCreated() {
        val draft = AgentSkillExtractor.parseReview(
            reviewJson("update", "android-release-build", standardSkillMd("android-release-build"))
        )

        assertNotNull(draft)
        assertFalse(draft!!.createNew)
    }

    @Test
    fun skipActionProducesNoDraft() {
        assertNull(
            AgentSkillExtractor.parseReview("""{"action":"skip","reason":"本轮只是一次性排查"}""")
        )
    }

    @Test
    fun malformedJsonProducesNoDraft() {
        assertNull(AgentSkillExtractor.parseReview("这轮没有什么值得沉淀的。"))
        assertNull(AgentSkillExtractor.parseReview("{\"action\":\"create\"}"))
        assertNull(AgentSkillExtractor.parseReview(""))
    }

    @Test
    fun literalNewlinesInsideJsonStringAreTolerated() {
        // 部分模型会把 skill_md 的换行写成真实换行（非法 JSON，但常见），Gson 宽松模式仍应解析。
        val body = buildString {
            appendLine("# nginx-tls")
            appendLine()
            appendLine("## 功能描述")
            appendLine("为 Nginx 站点申请证书并配置自动续期，覆盖常见的验证失败与证书路径写错的场景，")
            appendLine("适用于自建反向代理服务器的首次部署与后续续期维护，也适用于把已有的 HTTP 站点")
            appendLine("整体切换到 HTTPS 的场景，避免每次续期都要重新查一遍文档。")
            appendLine()
            appendLine("## 操作步骤")
            appendLine("1. 先用 certbot 申请证书，并确认域名解析已经生效、80 端口没有被占用。")
            appendLine("2. 把证书路径写进 Nginx 站点配置，检查语法后 reload 使配置生效。")
            appendLine("3. 配置 systemd timer 定时续期，并手动触发一次验证续期链路可用。")
            appendLine()
            appendLine("## 注意事项")
            appendLine("- 续期失败通常是因为验证端口被防火墙拦截，需要先确认网络可达。")
        }
        val raw = "{\"action\":\"create\",\"name\":\"nginx-tls\",\"description\":\"申请并续期 TLS 证书\"," +
            "\"skill_md\":\"$body\"}"

        val draft = AgentSkillExtractor.parseReview(raw)
        assertNotNull(draft)
        assertTrue(draft!!.skillMd.contains("1. 先用 certbot 申请证书"))
        assertTrue(draft.skillMd.startsWith("---\nname: nginx-tls\n"))
    }

    // ==================== 名称 / 正文清洗 ====================

    @Test
    fun skillNameIsSanitizedIntoStorageSafeForm() {
        assertEquals("android-release-build", AgentSkillExtractor.sanitizeSkillName("Android Release Build"))
        assertEquals("a-b", AgentSkillExtractor.sanitizeSkillName("a/b"))
        assertEquals("a-b", AgentSkillExtractor.sanitizeSkillName("a\\b"))
        assertEquals("", AgentSkillExtractor.sanitizeSkillName("   "))
        assertEquals("", AgentSkillExtractor.sanitizeSkillName("/"))
        val long = AgentSkillExtractor.sanitizeSkillName("x".repeat(200))
        assertTrue(long.length <= AgentSkillExtractor.MAX_SKILL_NAME_CHARS)
    }

    @Test
    fun skillMdGetsStandardFrontMatterAndHeading() {
        val md = AgentSkillExtractor.sanitizeSkillMd(
            standardSkillMd("demo-skill"),
            "demo-skill",
            "构建并签名 release APK"
        )

        assertEquals(
            "---\nname: demo-skill\ndescription: \"构建并签名 release APK\"\n---",
            md.substringBefore("\n\n")
        )
        assertTrue(md.contains("# demo-skill"))
    }

    @Test
    fun skillMdGetsHeadingWhenMissing() {
        val body = standardSkillMd("demo-skill")
            .lineSequence()
            .filterNot { it.startsWith("# demo-skill") }
            .joinToString("\n")

        val md = AgentSkillExtractor.sanitizeSkillMd(body, "demo-skill", "构建并签名 release APK")

        assertTrue(md.contains("# demo-skill"))
        assertTrue(md.startsWith("---\nname: demo-skill"))
    }

    @Test
    fun thinOrUnstructuredSkillMdIsRejected() {
        assertEquals("", AgentSkillExtractor.sanitizeSkillMd("", "demo", "说明"))
        assertEquals("", AgentSkillExtractor.sanitizeSkillMd("## 操作步骤\n1. 先做这个", "demo", "说明"))
        // 有内容但只有一个二级小节、且没有可执行步骤。
        val flat = "# demo\n\n" + "这是一段很长的说明文字。".repeat(40)
        assertEquals("", AgentSkillExtractor.sanitizeSkillMd(flat, "demo", "说明"))
    }

    @Test
    fun skillMdUnwrapsOuterCodeFenceOnly() {
        val md = AgentSkillExtractor.sanitizeSkillMd(
            "```markdown\n${standardSkillMd("demo-skill")}\n```",
            "demo-skill",
            "构建并签名 release APK"
        )

        assertTrue(md.startsWith("---\nname: demo-skill"))
        assertTrue(md.contains("assembleRelease"))
        assertFalse(md.contains("```markdown"))
    }

    @Test
    fun oversizedSkillMdIsTruncated() {
        val body = buildString {
            appendLine("# demo")
            appendLine()
            appendLine("## 操作步骤")
            appendLine()
            appendLine("1. 第一步")
            appendLine("2. 第二步")
            appendLine("3. 第三步")
            appendLine()
            appendLine("## 注意事项")
            appendLine()
            append("- ")
            append("x".repeat(20_000))
        }
        val md = AgentSkillExtractor.sanitizeSkillMd(body, "demo", "示例技能")

        assertTrue(md.length <= AgentSkillExtractor.MAX_SKILL_MD_CHARS)
        assertTrue(md.startsWith("---\nname: demo"))
    }

    // ==================== 工具轨迹与已用 Skill 提取 ====================

    @Test
    fun trajectoryListsToolCallsAndResults() {
        val trace = listOf(
            mapOf(
                "role" to "assistant",
                "tool_calls" to listOf(
                    mapOf(
                        "id" to "1",
                        "function" to mapOf(
                            "name" to "exec_command",
                            "arguments" to "{\"command\":\"./gradlew.bat assembleRelease\"}"
                        )
                    )
                )
            ),
            mapOf("role" to "tool", "name" to "exec_command", "content" to "BUILD SUCCESSFUL")
        )

        val text = AgentSkillExtractor.buildToolTrajectory(trace)
        assertTrue(text.contains("exec_command"))
        assertTrue(text.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun emptyTrajectoryIsReportedAsNoToolCalls() {
        assertEquals("（本轮没有工具调用）", AgentSkillExtractor.buildToolTrajectory(emptyList()))
    }

    @Test
    fun usedSkillNamesAreExtractedFromSkillToolsOnly() {
        val trace = listOf(
            mapOf(
                "role" to "assistant",
                "tool_calls" to listOf(
                    mapOf("function" to mapOf("name" to "skill_read", "arguments" to "{\"skill_name\":\"android-release-build\"}")),
                    mapOf("function" to mapOf("name" to "exec_command", "arguments" to "{\"skill_name\":\"should-not-count\"}")),
                    mapOf("function" to mapOf("name" to "skill_view", "arguments" to "{\"skill_name\":\"nginx-tls\"}"))
                )
            )
        )

        assertEquals(listOf("android-release-build", "nginx-tls"), AgentSkillExtractor.usedSkillNames(trace))
    }

    // ==================== 审查提示词 ====================

    @Test
    fun reviewPromptCarriesPrioritiesAndContext() {
        val prompt = AgentSkillExtractor.buildReviewPrompt(
            userMessage = "帮我把 release 打包出来",
            assistantMessage = "已完成构建。",
            toolTrace = listOf(
                mapOf("role" to "tool", "name" to "exec_command", "content" to "BUILD SUCCESSFUL")
            ),
            existingSkills = listOf(AgentSkillBrief("android-release-build", "构建 release APK", true)),
            explicit = true
        )

        assertTrue(prompt.contains("android-release-build"))
        assertTrue(prompt.contains("BUILD SUCCESSFUL"))
        assertTrue(prompt.contains("默认 skip"))
        assertTrue(prompt.contains("action=skip"))
        assertTrue(prompt.contains("create|update|skip"))
        assertTrue(prompt.contains("明确要求"))
        // 标准 SKILL.md 格式要求必须出现在提示词里。
        assertTrue(prompt.contains("name: <与 name 字段一致的小写英文名>"))
        assertTrue(prompt.contains("## 操作步骤"))
    }
}
