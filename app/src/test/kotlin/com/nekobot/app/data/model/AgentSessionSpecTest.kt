package com.nekobot.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionSpecTest {

    @Test
    fun jsonRoundTripKeepsAllFields() {
        val spec = AgentSessionSpec(
            feature = "写一个 TODO 应用",
            path = "specs/todo-app-1a2b/spec.md",
            status = AgentSessionSpec.STATUS_APPROVED,
            createdAt = "2026-01-01 00:00:00"
        )
        val decoded = AgentSessionSpec.fromJson(AgentSessionSpec.encode(spec))

        assertNotNull(decoded)
        assertEquals(spec, decoded)
    }

    @Test
    fun fromJsonReturnsNullOrDraftOnInvalidInput() {
        assertNull(AgentSessionSpec.fromJson(null))
        assertNull(AgentSessionSpec.fromJson(""))
        assertNull(AgentSessionSpec.fromJson("not-json"))

        // 缺字段时按默认值解析为 draft，保证旧行为安全
        val partial = AgentSessionSpec.fromJson("""{"feature":"重构登录"}""")
        assertNotNull(partial)
        assertEquals("重构登录", partial?.feature)
        assertEquals(AgentSessionSpec.STATUS_DRAFT, partial?.status)
    }

    @Test
    fun normalizeStatusMapsKnownValues() {
        assertEquals(
            AgentSessionSpec.STATUS_APPROVED,
            AgentSessionSpec.normalizeStatus("Approved")
        )
        assertEquals(
            AgentSessionSpec.STATUS_APPROVED,
            AgentSessionSpec.normalizeStatus("implementing")
        )
        assertEquals(
            AgentSessionSpec.STATUS_DRAFT,
            AgentSessionSpec.normalizeStatus("draft")
        )
        assertEquals(
            AgentSessionSpec.STATUS_DRAFT,
            AgentSessionSpec.normalizeStatus(null)
        )
        assertEquals(
            AgentSessionSpec.STATUS_DRAFT,
            AgentSessionSpec.normalizeStatus("unknown")
        )
    }

    @Test
    fun slugifyStripsInvalidFilenameCharacters() {
        val slug = AgentSessionSpec.slugify("设计: A/B 测试? <v2>")
        assertTrue(
            "规格目录不能包含文件系统非法字符：$slug",
            Regex("[\\\\/:*?\"<>|]").containsMatchIn(slug).not()
        )
    }

    @Test
    fun slugifyCapsLengthAndAppendsUniqueSuffix() {
        val long = AgentSessionSpec.slugify("很长的功能描述".repeat(30))
        assertTrue(
            "slug 需截断：$long",
            long.substringBeforeLast('-').length <= 32
        )

        val first = AgentSessionSpec.slugify("TODO 应用")
        val second = AgentSessionSpec.slugify("TODO 应用")
        assertNotEquals("同名功能应生成不同目录避免覆盖", first, second)
    }

    @Test
    fun slugifyFallsBackWhenFeatureIsBlankOrInvalid() {
        // 纯空白/非法字符清洗后为空 → 回退到 spec 基名，且不能包含非法字符
        val blank = AgentSessionSpec.slugify("   ")
        assertTrue(blank.startsWith("spec-"))

        val invalid = AgentSessionSpec.slugify("///")
        assertTrue(invalid.startsWith("spec-"))
    }
}
