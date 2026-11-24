package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class HostModelPolicyTest {
    private fun model(id: String, active: Boolean, purpose: String = "chat") =
        HostModelSnapshot(id,id,"https://example.test/v1","sample","synthetic",purpose,active,16384,2048)

    @Test fun stableIdsAndUnchangedRecordNeedsNoWrite() {
        val config = model("id:/ 空格",false).config()
        assertTrue(config.id.matches(Regex("host-[a-f0-9]{24}")))
        assertEquals(config, model("id:/ 空格",true).config())
        val unchanged = HostModelChanges(listOf(model("id:/ 空格",true)),listOf(config)) { "synthetic" }
        assertTrue(unchanged.writes.isEmpty())
        assertTrue(unchanged.removed.isEmpty())
        val changed = HostModelChanges(listOf(model("id:/ 空格",true)),listOf(config)) { "previous-fixture" }
        assertEquals(setOf(config),changed.writes.keys)
        val local = config.copy(id="local")
        val cleared = HostModelChanges(emptyList(),listOf(config,local)) { error("unexpected credential read") }
        assertEquals(setOf(config.id),cleared.removed)
        assertNotEquals(config.id,model("other",false).config().id)
    }

    @Test fun followsActiveChatOrFirstChatAndRespectsOverride() {
        val first=model("a",false)
        val active=model("b",true)
        val rows=listOf(first,active,model("vector",false,"embedding"))
        assertEquals(active.config().id,followedModel(ModelPurpose.CHAT,rows,"",true))
        assertEquals(first.config().id,followedModel(ModelPurpose.CHAT,listOf(first),"",true))
        assertEquals("local",followedModel(ModelPurpose.CHAT,rows,"local",false))
        assertEquals("",followedModel(ModelPurpose.EMBEDDING,rows,"host-old",true))
        assertEquals("local",followedModel(ModelPurpose.EMBEDDING,rows,"local",true))
        assertEquals("",followedModel(ModelPurpose.MEMORY,rows,"",true))
    }
}
