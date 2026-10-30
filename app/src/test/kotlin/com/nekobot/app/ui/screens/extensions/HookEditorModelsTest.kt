package com.nekobot.app.ui.screens.extensions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hook 条件/动作「表单 ↔ JSON」转换测试。
 *
 * 关键契约：界面化编辑不能丢数据——未知键必须原样保留，已知键必须能往返转换。
 */
class HookEditorModelsTest {

    @Test
    fun `条件解析保留未知键`() {
        val draft = parseConditions(JsonParser.parseString("""{"affection_gte": 80, "custom_flag": true}"""))
        assertEquals(1, draft.rows.size)
        assertEquals("affection_gte", draft.rows[0].spec.key)
        assertEquals("80", draft.rows[0].value)
        assertEquals(true, draft.extras.has("custom_flag"))

        val json = draft.toJson()!!.asJsonObject
        assertEquals(80, json.get("affection_gte").asInt)
        assertEquals(true, json.get("custom_flag").asBoolean)
    }

    @Test
    fun `工具名列表条件往返为数组`() {
        val draft = parseConditions(
            JsonParser.parseString("""{"tool_name_in": ["shell_job", "exec_command"]}""")
        )
        assertEquals("shell_job,exec_command", draft.rows.single().value)

        val array = draft.toJson()!!.asJsonObject.getAsJsonArray("tool_name_in")
        assertEquals(2, array.size())
        assertEquals("exec_command", array[1].asString)
    }

    @Test
    fun `布尔条件序列化为布尔值`() {
        val spec = HookConditionCatalog.byKey("tool_success")!!
        val json = HookConditionsDraft(listOf(HookConditionDraft(spec, "true"))).toJson()!!
        assertTrue(json.asJsonObject.get("tool_success").asBoolean)
    }

    @Test
    fun `空条件返回 null`() {
        assertNull(HookConditionsDraft().toJson())
        assertTrue(HookConditionsDraft().isEmpty)
    }

    @Test
    fun `日志动作往返`() {
        val actions = parseActions(listOf(JsonParser.parseString("""{"type":"log","level":"warning","message":"hi"}""")))
        val draft = actions.single()
        assertEquals("log", draft.type)
        assertEquals("warning", draft.level)
        val json = draft.toJson()
        assertEquals("log", json.get("type").asString)
        assertEquals("warning", json.get("level").asString)
        assertEquals("hi", json.get("message").asString)
    }

    @Test
    fun `状态变化动作从 payload 读取并扁平化`() {
        val draft = parseActions(
            listOf(JsonParser.parseString("""{"type":"state_delta","payload":{"mood":"happy","energy":-5}}"""))
        ).single()
        assertEquals("happy", draft.mood)
        assertEquals("-5", draft.energyDelta)

        val json = draft.toJson()
        assertEquals("happy", json.get("mood").asString)
        assertEquals(-5, json.get("energy").asInt)
        assertTrue(!json.has("payload"))
    }

    @Test
    fun `世界书动作关键词数组往返`() {
        val draft = parseActions(
            listOf(JsonParser.parseString("""{"type":"world_book_add","name":"n","content":"c","keywords":["a","b"],"always_on":true}"""))
        ).single()
        assertEquals("a,b", draft.keywords)
        assertTrue(draft.alwaysOn)

        val json = draft.toJson()
        assertEquals(2, json.getAsJsonArray("keywords").size())
        assertTrue(json.get("always_on").asBoolean)
    }

    @Test
    fun `未知动作类型保留原始参数`() {
        val draft = parseActions(
            listOf(JsonParser.parseString("""{"type":"workflow","workflow_id":"w1","params":{"x":1}}"""))
        ).single()
        assertEquals("workflow", draft.type)
        assertNotNull(draft.extra)
        assertTrue(draft.extra!!.has("workflow_id"))

        val json = draft.toJson()
        assertEquals("workflow", json.get("type").asString)
        assertEquals("w1", json.get("workflow_id").asString)
        assertTrue(json.has("params"))
    }

    @Test
    fun `条件目录的键与字段一致`() {
        HookConditionCatalog.specs.forEach { spec ->
            val expected = when {
                spec.field == HookConditionField.USER_MESSAGE -> "message"
                spec.field == HookConditionField.ASSISTANT_MESSAGE -> "response"
                spec.field == HookConditionField.TOOL_SUCCESS -> "tool"
                spec.field == HookConditionField.SUBAGENT -> "subagent"
                else -> spec.key.substringBeforeLast('_')
            }
            assertTrue(
                "键 ${spec.key} 与字段 ${spec.field} 不匹配",
                spec.key.startsWith(expected)
            )
        }
        // 工具名比较方式必须齐全，否则界面上选不到
        val toolKeys = HookConditionCatalog.specs
            .filter { it.field == HookConditionField.TOOL_NAME }
            .map { it.key }
        assertEquals(listOf("tool_name_equals", "tool_name_in", "tool_name_contains"), toolKeys)
    }

    @Test
    fun `动作类型覆盖执行器支持的全部类型`() {
        val ids = HookActionType.entries.map { it.id }.toSet()
        assertEquals(
            setOf(
                "log", "notify", "memory_write", "prompt_inject", "state_delta",
                "relationship_delta", "message", "world_book_add", "tool_block"
            ),
            ids
        )
        HookActionType.entries.forEach { type ->
            val json: JsonObject = defaultActionDraft(type.id).toJson()
            assertEquals(type.id, json.get("type").asString)
        }
    }
}
