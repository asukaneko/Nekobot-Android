package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotGraphManagerTest {

    @Test
    fun selectChoice_keepsOnlyTheFinalChoiceSelectedWithinNode() {
        val manager = PlotGraphManager()
        val first = PlotChoice(id = "first", nodeId = "node")
        val second = PlotChoice(id = "second", nodeId = "node")
        val third = PlotChoice(id = "third", nodeId = "node")
        manager.addChoice(first)
        manager.addChoice(second)
        manager.addChoice(third)

        manager.selectChoice("first")
        manager.selectChoice("second")
        manager.selectChoice("third")

        assertFalse(first.selected)
        assertFalse(second.selected)
        assertTrue(third.selected)
    }

    @Test
    fun toJsonForConversationsExportsOnlyRequestedConversationAndKeepsTopology() {
        val source = PlotGraphManager().apply {
            addBranchedConversation(CONVERSATION_A, "a")
            addBranchedConversation(CONVERSATION_B, "b")
            addEdge(
                PlotEdge(
                    id = "cross-profile-edge",
                    fromNodeId = "a-child",
                    toNodeId = "b-root"
                )
            )
        }

        val exported = source.toJsonForConversations(setOf(CONVERSATION_A))
        val restored = PlotGraphManager().apply {
            replaceConversationsFromJson(
                conversationIdsToReplace = setOf(CONVERSATION_A),
                graphJson = exported,
                allowedConversationIds = setOf(CONVERSATION_A)
            )
        }

        assertCompleteConversation(restored, CONVERSATION_A, "a")
        assertTrue(graphItems(restored, CONVERSATION_B, "nodes").isEmpty())
        assertTrue(graphItems(restored, CONVERSATION_B, "choices").isEmpty())
        assertTrue(graphItems(restored, CONVERSATION_B, "edges").isEmpty())
        assertNull(restored.getActiveNodeId(CONVERSATION_B))
        assertNull(restored.getNode("b-root"))
        assertNull(restored.getNode("b-child"))
    }

    @Test
    fun replaceConversationsFromJsonReplacesAAndPreservesB() {
        val imported = PlotGraphManager().apply {
            addBranchedConversation(CONVERSATION_A, "new-a")
        }.toJsonForConversations(setOf(CONVERSATION_A))
        val target = PlotGraphManager().apply {
            addBranchedConversation(CONVERSATION_A, "old-a")
            addBranchedConversation(CONVERSATION_B, "b")
        }

        target.replaceConversationsFromJson(
            conversationIdsToReplace = setOf(CONVERSATION_A),
            graphJson = imported,
            allowedConversationIds = setOf(CONVERSATION_A)
        )

        assertNull(target.getNode("old-a-root"))
        assertNull(target.getNode("old-a-child"))
        assertCompleteConversation(target, CONVERSATION_A, "new-a")
        assertCompleteConversation(target, CONVERSATION_B, "b")
    }

    @Test
    fun replaceConversationsFromJsonRejectsExternalConversationWithoutChangingOriginalGraph() {
        val target = PlotGraphManager().apply {
            addBranchedConversation(CONVERSATION_A, "old-a")
            addBranchedConversation(CONVERSATION_B, "b")
        }
        val before = target.toJson()
        val externalGraph = PlotGraphManager().apply {
            addBranchedConversation("external-session", "external")
        }.toJsonForConversations(setOf("external-session"))

        assertThrows(IllegalArgumentException::class.java) {
            target.replaceConversationsFromJson(
                conversationIdsToReplace = setOf(CONVERSATION_A),
                graphJson = externalGraph,
                allowedConversationIds = setOf(CONVERSATION_A)
            )
        }

        assertEquals(before, target.toJson())
        assertCompleteConversation(target, CONVERSATION_A, "old-a")
        assertCompleteConversation(target, CONVERSATION_B, "b")
        assertNull(target.getNode("external-root"))
    }

    @Test
    fun replaceConversationsFromJsonRejectsParentCycleWithoutChangingOriginalGraph() {
        val cyclicGraph = PlotGraphManager().apply {
            addNode(
                PlotNode(
                    id = "cycle-first",
                    conversationId = CONVERSATION_A,
                    parentNodeId = "cycle-second"
                )
            )
            addNode(
                PlotNode(
                    id = "cycle-second",
                    conversationId = CONVERSATION_A,
                    parentNodeId = "cycle-first"
                )
            )
        }.toJsonForConversations(setOf(CONVERSATION_A))

        assertRejectedWithoutChangingOriginalGraph(
            graphJson = cyclicGraph,
            conversationIdsToReplace = setOf(CONVERSATION_A),
            allowedConversationIds = setOf(CONVERSATION_A)
        )
    }

    @Test
    fun replaceConversationsFromJsonAcceptsDeepParentChainWithoutStackOverflow() {
        val source = PlotGraphManager()
        var parentId: String? = null
        repeat(8_000) { index ->
            val nodeId = "deep-$index"
            source.addNode(
                PlotNode(
                    id = nodeId,
                    conversationId = CONVERSATION_A,
                    parentNodeId = parentId
                )
            )
            parentId = nodeId
        }

        val restored = PlotGraphManager().apply {
            replaceConversationsFromJson(
                conversationIdsToReplace = setOf(CONVERSATION_A),
                graphJson = source.toJsonForConversations(setOf(CONVERSATION_A)),
                allowedConversationIds = setOf(CONVERSATION_A)
            )
        }

        assertEquals("deep-7998", requireNotNull(restored.getNode("deep-7999")).parentNodeId)
    }

    @Test
    fun replaceConversationsFromJsonRejectsDanglingAndCrossConversationParentsAtomically() {
        val danglingParent = PlotGraphManager().apply {
            addNode(
                PlotNode(
                    id = "dangling-parent-node",
                    conversationId = CONVERSATION_A,
                    parentNodeId = "missing-parent"
                )
            )
        }.toJsonForConversations(setOf(CONVERSATION_A))
        assertRejectedWithoutChangingOriginalGraph(
            graphJson = danglingParent,
            conversationIdsToReplace = setOf(CONVERSATION_A),
            allowedConversationIds = setOf(CONVERSATION_A)
        )

        val crossConversationParent = PlotGraphManager().apply {
            addNode(
                PlotNode(
                    id = "cross-parent",
                    conversationId = CONVERSATION_B
                )
            )
            addNode(
                PlotNode(
                    id = "cross-child",
                    conversationId = CONVERSATION_A,
                    parentNodeId = "cross-parent"
                )
            )
        }.toJsonForConversations(setOf(CONVERSATION_A, CONVERSATION_B))
        assertRejectedWithoutChangingOriginalGraph(
            graphJson = crossConversationParent,
            conversationIdsToReplace = setOf(CONVERSATION_A, CONVERSATION_B),
            allowedConversationIds = setOf(CONVERSATION_A, CONVERSATION_B)
        )
    }

    @Test
    fun replaceConversationsFromJsonRejectsDanglingAndCrossNodeSelectedChoicesAtomically() {
        val danglingChoice = PlotGraphManager().apply {
            addNode(
                PlotNode(
                    id = "selected-dangling-root",
                    conversationId = CONVERSATION_A,
                    selectedChoiceId = "missing-choice"
                )
            )
        }.toJsonForConversations(setOf(CONVERSATION_A))
        assertRejectedWithoutChangingOriginalGraph(
            graphJson = danglingChoice,
            conversationIdsToReplace = setOf(CONVERSATION_A),
            allowedConversationIds = setOf(CONVERSATION_A)
        )

        val crossNodeChoice = PlotGraphManager().apply {
            addNode(
                PlotNode(
                    id = "selected-cross-root",
                    conversationId = CONVERSATION_A,
                    selectedChoiceId = "selected-cross-choice"
                )
            )
            addNode(
                PlotNode(
                    id = "selected-cross-child",
                    conversationId = CONVERSATION_A,
                    parentNodeId = "selected-cross-root"
                )
            )
            addChoice(
                PlotChoice(
                    id = "selected-cross-choice",
                    nodeId = "selected-cross-child"
                )
            )
        }.toJsonForConversations(setOf(CONVERSATION_A))
        assertRejectedWithoutChangingOriginalGraph(
            graphJson = crossNodeChoice,
            conversationIdsToReplace = setOf(CONVERSATION_A),
            allowedConversationIds = setOf(CONVERSATION_A)
        )
    }

    @Test
    fun replaceConversationsFromJsonRejectsDanglingAndCrossNodeEdgeChoicesAtomically() {
        val danglingEdgeChoice = PlotGraphManager().apply {
            addNode(PlotNode(id = "edge-dangling-root", conversationId = CONVERSATION_A))
            addNode(
                PlotNode(
                    id = "edge-dangling-child",
                    conversationId = CONVERSATION_A,
                    parentNodeId = "edge-dangling-root"
                )
            )
            addEdge(
                PlotEdge(
                    id = "edge-dangling",
                    fromNodeId = "edge-dangling-root",
                    toNodeId = "edge-dangling-child",
                    choiceId = "missing-choice"
                )
            )
        }.toJsonForConversations(setOf(CONVERSATION_A))
        assertRejectedWithoutChangingOriginalGraph(
            graphJson = danglingEdgeChoice,
            conversationIdsToReplace = setOf(CONVERSATION_A),
            allowedConversationIds = setOf(CONVERSATION_A)
        )

        val crossNodeEdgeChoice = PlotGraphManager().apply {
            addNode(PlotNode(id = "edge-cross-root", conversationId = CONVERSATION_A))
            addNode(
                PlotNode(
                    id = "edge-cross-child",
                    conversationId = CONVERSATION_A,
                    parentNodeId = "edge-cross-root"
                )
            )
            addChoice(
                PlotChoice(
                    id = "edge-cross-choice",
                    nodeId = "edge-cross-child"
                )
            )
            addEdge(
                PlotEdge(
                    id = "edge-cross",
                    fromNodeId = "edge-cross-root",
                    toNodeId = "edge-cross-child",
                    choiceId = "edge-cross-choice"
                )
            )
        }.toJsonForConversations(setOf(CONVERSATION_A))
        assertRejectedWithoutChangingOriginalGraph(
            graphJson = crossNodeEdgeChoice,
            conversationIdsToReplace = setOf(CONVERSATION_A),
            allowedConversationIds = setOf(CONVERSATION_A)
        )
    }

    private fun assertRejectedWithoutChangingOriginalGraph(
        graphJson: String,
        conversationIdsToReplace: Set<String>,
        allowedConversationIds: Set<String>
    ) {
        val target = PlotGraphManager().apply {
            addBranchedConversation(CONVERSATION_A, "old-a")
            addBranchedConversation(CONVERSATION_B, "b")
        }
        val before = target.toJson()

        assertThrows(IllegalArgumentException::class.java) {
            target.replaceConversationsFromJson(
                conversationIdsToReplace = conversationIdsToReplace,
                graphJson = graphJson,
                allowedConversationIds = allowedConversationIds
            )
        }

        assertEquals(before, target.toJson())
        assertCompleteConversation(target, CONVERSATION_A, "old-a")
        assertCompleteConversation(target, CONVERSATION_B, "b")
    }

    private fun PlotGraphManager.addBranchedConversation(
        conversationId: String,
        prefix: String
    ) {
        val rootId = "$prefix-root"
        val childId = "$prefix-child"
        val choiceId = "$prefix-choice"
        addNode(
            PlotNode(
                id = rootId,
                conversationId = conversationId,
                characterId = "$prefix-character",
                title = "$prefix root",
                summary = "$prefix root summary",
                userMessage = "$prefix root user",
                assistantMessage = "$prefix root assistant"
            )
        )
        addChoice(
            PlotChoice(
                id = choiceId,
                nodeId = rootId,
                text = "$prefix choice",
                selected = false
            )
        )
        assertTrue(selectChoice(choiceId))
        addNode(
            PlotNode(
                id = childId,
                conversationId = conversationId,
                characterId = "$prefix-character",
                title = "$prefix child",
                summary = "$prefix child summary",
                parentNodeId = rootId,
                userMessage = "$prefix child user",
                assistantMessage = "$prefix child assistant"
            )
        )
        addEdge(
            PlotEdge(
                id = "$prefix-edge",
                fromNodeId = rootId,
                toNodeId = childId,
                choiceId = choiceId,
                label = "$prefix choice"
            )
        )
        setActiveNode(conversationId, childId)
    }

    private fun assertCompleteConversation(
        manager: PlotGraphManager,
        conversationId: String,
        prefix: String
    ) {
        assertEquals(
            setOf("$prefix-root", "$prefix-child"),
            graphItems(manager, conversationId, "nodes").map { it["id"] }.toSet()
        )
        assertEquals(
            setOf("$prefix-choice"),
            graphItems(manager, conversationId, "choices").map { it["id"] }.toSet()
        )
        assertEquals(
            setOf("$prefix-edge"),
            graphItems(manager, conversationId, "edges").map { it["id"] }.toSet()
        )
        assertEquals("$prefix-child", manager.getActiveNodeId(conversationId))
        assertEquals(
            listOf("$prefix-root", "$prefix-child"),
            manager.pathToNode("$prefix-child").map { it.id }
        )
        assertTrue(requireNotNull(manager.getChoice("$prefix-choice")).selected)
        assertEquals("$prefix root user", requireNotNull(manager.getNode("$prefix-root")).userMessage)
        assertEquals(
            "$prefix child assistant",
            requireNotNull(manager.getNode("$prefix-child")).assistantMessage
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun graphItems(
        manager: PlotGraphManager,
        conversationId: String,
        key: String
    ): List<Map<String, Any>> =
        manager.getGraph(conversationId).getValue(key) as List<Map<String, Any>>

    private companion object {
        const val CONVERSATION_A = "session-a"
        const val CONVERSATION_B = "session-b"
    }
}
