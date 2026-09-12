package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillDistillationTest {

    // ==================== 触发条件 ====================

    @Test
    fun complexTurnWithEnoughToolCallsTriggersReview() {
        assertTrue(
            AgentSkillExtractor.shouldReview(
                explicit = false,
                turnToolCalls = AgentSkillExtractor.COMPLEX_TURN_TOOL_CALLS,
                accumulatedToolCalls = 0,
                userMessage = "帮我把项目部署上去",
                assistantMessage = "已完成部署，步骤如下：".padEnd(200, 'x')
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
        val longUser = "请解释一下这个概念".padEnd(120, 'x')
        val longAssistant = "这个概念是这样的".padEnd(200, 'y')
        assertFalse(
            AgentSkillExtractor.shouldReview(false, 0, 50, longUser, longAssistant)
        )
        assertTrue(
            AgentSkillExtractor.shouldReview(true, 0, 0, longUser, longAssistant)
        )
    }

    @Test
    fun accumulatedToolCallsTriggerReviewAcrossTurns() {
        val user = "继续".padEnd(120, 'x')
        val assistant = "好的，已完成".padEnd(200, 'y')
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
        val raw = """
            ```json
            {"action":"create","name":"android-release-build","description":"构建并签名 release APK",
             "aliases":["release 构建"],"reason":"包含可复用的签名步骤",
             "skill_md":"# android-release-build\n\n## 功能描述\n构建 release APK。\n## 操作步骤\n1. 设置 JAVA_HOME"}
            ```
        """.trimIndent()

        val draft = AgentSkillExtractor.parseReview(raw)
        assertNotNull(draft)
        assertEquals("android-release-build", draft!!.name)
        assertEquals("构建并签名 release APK", draft.description)
        assertEquals(listOf("release 构建"), draft.aliases)
        assertTrue(draft.createNew)
        assertTrue(draft.skillMd.startsWith("# android-release-build"))
    }

    @Test
    fun updateActionKeepsExistingNameAndMarksNotCreated() {
        val raw = """
            {"action":"update","name":"android-release-build","description":"构建 release APK",
             "skill_md":"# android-release-build\n\n## 注意事项\n签名沿用 debug keystore。"}
        """.trimIndent()

        val draft = AgentSkillExtractor.parseReview(raw)
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
        val raw = "{\"action\":\"create\",\"name\":\"nginx-tls\",\"skill_md\":\"# nginx-tls\n## 操作步骤\n1. 申请证书\"}"

        val draft = AgentSkillExtractor.parseReview(raw)
        assertNotNull(draft)
        assertTrue(draft!!.skillMd.contains("1. 申请证书"))
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
    fun skillMdGetsHeadingWhenMissing() {
        val md = AgentSkillExtractor.sanitizeSkillMd("## 操作步骤\n1. 先做这个", "demo-skill")
        assertTrue(md.startsWith("# demo-skill"))
    }

    @Test
    fun skillMdUnwrapsOuterCodeFenceOnly() {
        val md = AgentSkillExtractor.sanitizeSkillMd(
            "```markdown\n# demo\n\n```bash\nls -la\n```\n```",
            "demo"
        )
        assertEquals("# demo\n\n```bash\nls -la\n```", md)
    }

    @Test
    fun oversizedSkillMdIsTruncated() {
        val md = AgentSkillExtractor.sanitizeSkillMd("# demo\n" + "x".repeat(20_000), "demo")
        assertTrue(md.length <= AgentSkillExtractor.MAX_SKILL_MD_CHARS)
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
        assertTrue(prompt.contains("action=skip"))
        assertTrue(prompt.contains("create|update|skip"))
        assertTrue(prompt.contains("明确要求"))
    }
}
