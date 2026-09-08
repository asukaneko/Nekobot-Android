package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandCapsuleTest {

    @Test
    fun noMatchForPlainText() {
        assertNull(matchCommandCapsule(""))
        assertNull(matchCommandCapsule("你好"))
        assertNull(matchCommandCapsule("帮我写代码"))
        assertNull(matchCommandCapsule("  /goal 前面有空格"))
    }

    @Test
    fun noMatchForOtherCommandsAndTypos() {
        assertNull(matchCommandCapsule("/help"))
        assertNull(matchCommandCapsule("/goalx"))
        assertNull(matchCommandCapsule("/goa"))
        assertNull(matchCommandCapsule("/specify"))
        assertNull(matchCommandCapsule("text /goal 中间出现"))
    }

    @Test
    fun matchesGoalWithAndWithoutArgs() {
        val bare = matchCommandCapsule("/goal")
        assertEquals(CommandCapsuleKind.GOAL, bare?.kind)
        assertEquals("/goal", bare?.token)

        val withArgs = matchCommandCapsule("/goal 学会做饭")
        assertEquals(CommandCapsuleKind.GOAL, withArgs?.kind)
        assertEquals("/goal", withArgs?.token)
        assertEquals(0 until 5, withArgs?.tokenRange)
    }

    @Test
    fun matchesSpecWithAndWithoutArgs() {
        val bare = matchCommandCapsule("/spec")
        assertEquals(CommandCapsuleKind.SPEC, bare?.kind)
        assertEquals("/spec", bare?.token)

        val withArgs = matchCommandCapsule("/spec 登录模块")
        assertEquals(CommandCapsuleKind.SPEC, withArgs?.kind)
        assertEquals("/spec", withArgs?.token)
    }

    @Test
    fun matchesCaseInsensitiveAndNewlineTerminated() {
        val upper = matchCommandCapsule("/GOAL 跑步")
        assertEquals(CommandCapsuleKind.GOAL, upper?.kind)
        // token 保留用户原始写法
        assertEquals("/GOAL", upper?.token)

        val newline = matchCommandCapsule("/spec\n先看文档")
        assertEquals(CommandCapsuleKind.SPEC, newline?.kind)
        assertEquals("/spec", newline?.token)
    }

    @Test
    fun visualTransformationHidesTokenAndTrailingSpace() {
        val transformation = commandCapsuleVisualTransformation()

        val plain = transformation.filter(androidx.compose.ui.text.AnnotatedString("帮我做事"))
        assertEquals("帮我做事", plain.text.text)

        val cmd = transformation.filter(androidx.compose.ui.text.AnnotatedString("/goal 学会做饭"))
        assertEquals("学会做饭", cmd.text.text)
        // 命中区域（含空格）折叠为起点 0
        assertEquals(0, cmd.offsetMapping.originalToTransformed(0))
        assertEquals(0, cmd.offsetMapping.originalToTransformed(6))
        assertEquals(0, cmd.offsetMapping.transformedToOriginal(0))
        assertEquals("/goal 学会做饭".length, cmd.offsetMapping.transformedToOriginal(cmd.text.text.length))
    }

    @Test
    fun visualTransformationKeepsPlainTextForNonCommands() {
        val transformation = commandCapsuleVisualTransformation()
        val result = transformation.filter(androidx.compose.ui.text.AnnotatedString("/help"))
        assertEquals("/help", result.text.text)
    }
}