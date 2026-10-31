package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FusedReplyMemoryMappingTest {
    private fun memory(id: String) = CompanionMemoryEntity(id, "s", id, 1, "fact", "虚构角色", "内容$id", "active",
        createdAt = "now", updatedAt = "now")
    @Test fun numberingIsStableAcrossCoreRecallRetainedUncertainAndToolReads() {
        val refs = FusedMemoryReferences(existing = listOf("core", "recall", "retained", "uncertain").map(::memory))
        assertEquals("M1", refs.register("core")); assertEquals("M4", refs.register("uncertain"))
        val message = mapOf("role" to "tool", "name" to "agent_memory_read", "content" to Gson().toJson(mapOf("memories" to
            listOf(mapOf("id" to "new-real-id", "key" to "old-topic-key", "content" to "新查到的记忆")))))
        val sent = FusedReplyMemoryMapping.outbound(message, refs)
        assertTrue(sent["content"].toString().contains("[M5] 新查到的记忆"))
        assertFalse(sent["content"].toString().contains("new-real-id"))
        assertFalse(sent["content"].toString().contains("old-topic-key"))
        assertEquals("new-real-id", refs.memoryId("M5"))
        assertEquals("M6", refs.register("tool-add"))
        assertEquals(sent, FusedReplyMemoryMapping.outbound(message, refs))
        assertEquals("M1", FusedMemoryReferences().register("tool-add"))
    }
    @Test fun everyCoreEntryHasPrefixWithoutSixtyItemTruncation() {
        val core = (1..60).map { memory("core$it").copy(kind = "core") }
        val refs = FusedMemoryReferences(existing = core)
        val text = CompanionReferences.render(core, core, "", LocalDate.now()) { "[${refs.register(it)}] " }
        assertEquals(60, Regex("\\[M[0-9]+]").findAll(text).count())
        assertTrue(text.contains("[M60]"))
    }
    @Test fun ownToolUpdateRemovesOldReferenceButKeepsCurrentAndDynamicText() {
        val refs = FusedMemoryReferences(existing = listOf(memory("old"), memory("valid")))
        val original = mapOf("role" to "system", "content" to "[M1] 旧版本\n[M2] 保留版本\n当前状态",
            CompanionReferences.META to listOf("old", "valid"))
        val fresh = FusedReplyMemoryMapping.retireAuxiliary(listOf(original), setOf("valid"), refs).single()
        assertEquals("[M2] 保留版本\n当前状态", fresh["content"])
        assertEquals(listOf("valid"), fresh[CompanionReferences.META])
        assertEquals(listOf(fresh), CompanionReferences.filterToolResults(listOf(fresh), setOf("valid")))
    }
    @Test fun toolDefinitionAndPromptOnlyOfferNewOperationsAndProgramNumbers() {
        val definitions = FusedCompanionTools.definitions(listOf(mapOf("function" to mapOf("name" to "agent_memory_update"))))
        val json = JsonParser.parseString(Gson().toJson(definitions)).asJsonArray[0].asJsonObject["function"].asJsonObject
        val fields = json["parameters"].asJsonObject["properties"].asJsonObject
        assertFalse(fields.has("key")); assertFalse(fields.has("quote")); assertFalse(fields.has("operation"))
        assertEquals(listOf("add", "update", "cancel", "complete", "retract"), fields["op"].asJsonObject["enum"].asJsonArray.map { it.asString })
        assertTrue(FusedReplyMemoryMapping.RULE.contains("绝不把编号说出来"))
        assertTrue(FusedReplyMemoryMapping.RULE.contains("不得自己起编号"))
    }
    @Test fun replyToolsCannotUseHiddenNOrInventedReferences() {
        val row = memory("real")
        val source = LocalMessageEntity("u", "s", "user", "虚构原话", timestamp = "now", createdAt = "now")
        val refs = FusedMemoryReferences(listOf(source), listOf(row)); refs.bind("N1", row.id)
        for (ref in listOf("N1", "杭州-1", "M3a", "M99", row.id)) {
            val line = FusedMemoryValidation.parseLine("""{"op":"retract","target":"$ref","evidence":["S1"]}""") as FusedMemoryLine.Operation
            assertNull(refs.memoryId(ref).takeUnless { ref == "N1" || ref == "M1" })
            assertEquals("unknown_ref", FusedMemoryValidation.validate(line.value, refs, mapOf(row.id to row), tool = true).second?.reason)
        }
    }
}
