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

    @Test fun mirroredOutputLeavesPromptRoomAndHonorsExplicitLimits() {
        fun output(context: Int, configured: Int?) =
            HostModelSnapshot("id", "name", "https://example.test/v1", "sample", "fixture",
                "chat", true, context, configured).config().outputTokens
        assertEquals(8192, output(32768, null))
        assertEquals(1024, output(4096, 0))
        assertEquals(65536, output(1048576, null))
        assertEquals(16000, output(32768, 16000))
        assertEquals(4095, output(4096, 12000))
    }

    @Test fun streamToggleChangesOnlyOnce() {
        val enabled = model("a", true).config()
        val disabled = HostModelSnapshot("a","a","https://example.test/v1","sample","synthetic",
            "chat",true,16384,2048,supportsStream = false)
        assertTrue(enabled.streamReplies)
        assertFalse(disabled.config().streamReplies)
        assertEquals(setOf(disabled.config()), HostModelChanges(listOf(disabled), listOf(enabled)) { "synthetic" }.writes.keys)
        assertTrue(HostModelChanges(listOf(disabled), listOf(disabled.config())) { "synthetic" }.writes.isEmpty())
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
    @Test fun reviewUsesChatMirrorsAndOmitsHostSelectionsFromBackup() {
        val chat = model("chat", true).config()
        val embedding = model("vector", false, "embedding").config()
        val local = chat.copy(id = "local")
        val kinds = mapOf(chat.id to "chat", embedding.id to "embedding")
        val options = purposeOptions(listOf(chat, embedding, local), ModelPurpose.REVIEW, embedding.id, false, kinds)
        assertEquals(setOf(chat.id, local.id), options.visible.map { it.id }.toSet())
        assertEquals("", backupPurposeIds { chat.id }[ModelPurpose.REVIEW])
        assertEquals(local.id, backupPurposeIds { local.id }[ModelPurpose.REVIEW])
    }
}
