package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWelcomeSuggestionsTest {

    @Test
    fun `parses json array and removes duplicates`() {
        val raw = """["帮我梳理今天的待办", "检查项目构建环境", "帮我梳理今天的待办", "回顾长期约定"]"""
        assertEquals(
            listOf("帮我梳理今天的待办", "检查项目构建环境", "回顾长期约定"),
            AgentWelcomeSuggestions.parse(raw)
        )
    }

    @Test
    fun `parses wrapped json object`() {
        val raw = """{"suggestions": ["整理最近的记录", "检查环境与工具"]}"""
        assertEquals(
            listOf("整理最近的记录", "检查环境与工具"),
            AgentWelcomeSuggestions.parse(raw)
        )
    }

    @Test
    fun `falls back to plain lines when json is invalid`() {
        val raw = """
            1. 帮我梳理今天的待办
            - 检查项目构建环境
            「回顾长期约定」
        """.trimIndent()
        assertEquals(
            listOf("帮我梳理今天的待办", "检查项目构建环境", "回顾长期约定"),
            AgentWelcomeSuggestions.parse(raw)
        )
    }

    @Test
    fun `encode and decode keep four items`() {
        val items = listOf("第一条候选", "第二条候选", "第三条候选", "第四条候选", "第五条候选")
        val decoded = AgentWelcomeSuggestions.decode(AgentWelcomeSuggestions.encode(items))
        assertEquals(listOf("第一条候选", "第二条候选", "第三条候选", "第四条候选"), decoded)
    }
}
