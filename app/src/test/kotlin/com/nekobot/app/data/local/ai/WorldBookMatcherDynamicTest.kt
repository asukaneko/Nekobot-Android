package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookMatcherDynamicTest {
    private val gson = Gson()
    private val book = LocalWorldBookEntity(
        id = "book",
        name = "动态世界书",
        createdAt = "2026-07-30T00:00:00Z",
        updatedAt = "2026-07-30T00:00:00Z"
    )

    @Test
    fun matchesAffectionLocationAndPlotNode() {
        val entry = dynamicEntry(
            mapOf(
                "affection" to listOf(">=60"),
                "location" to listOf("白塔"),
                "plot_node" to listOf("node-7")
            ),
            matchMode = "all"
        )
        val results = match(
            entry,
            mapOf(
                "affection" to 72,
                "location" to "白塔",
                "plot_node" to "node-7"
            )
        )

        assertEquals(1, results.size)
        assertEquals(listOf("scene_state"), results.first().triggerSources)
    }

    @Test
    fun rejectsWhenOneRequiredConditionFails() {
        val entry = dynamicEntry(
            mapOf(
                "affection" to listOf(">=60"),
                "energy" to listOf("40..80")
            ),
            matchMode = "all"
        )
        assertTrue(match(entry, mapOf("affection" to 20, "energy" to 60)).isEmpty())
    }

    @Test
    fun respectsConfiguredTriggerSources() {
        val entry = LocalWorldBookEntryEntity(
            id = "entry",
            bookId = book.id,
            keys = gson.toJson(listOf("白塔")),
            content = "设定",
            triggerSourcesJson = gson.toJson(listOf("scene_state"))
        )
        val results = WorldBookMatcher.matchEntriesV2(
            context = WorldBookRecallContext(latestUserMessage = "前往白塔"),
            worldBooks = listOf(book),
            entriesByBook = mapOf(book.id to listOf(entry))
        )
        assertTrue(results.isEmpty())
    }

    private fun dynamicEntry(
        stateTriggers: Map<String, List<String>>,
        matchMode: String
    ) = LocalWorldBookEntryEntity(
        id = "entry",
        bookId = book.id,
        content = "动态设定",
        triggerSourcesJson = gson.toJson(listOf("scene_state")),
        stateTriggersJson = gson.toJson(stateTriggers),
        matchMode = matchMode,
        entryType = "relationship"
    )

    private fun match(
        entry: LocalWorldBookEntryEntity,
        scene: Map<String, Any>
    ) = WorldBookMatcher.matchEntriesV2(
        context = WorldBookRecallContext(scene = scene),
        worldBooks = listOf(book),
        entriesByBook = mapOf(book.id to listOf(entry))
    )
}
