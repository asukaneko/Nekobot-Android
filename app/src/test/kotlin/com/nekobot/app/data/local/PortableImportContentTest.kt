package com.nekobot.app.data.local

import org.junit.Assert.*
import org.junit.Test

class PortableImportContentTest {
    private val catalog = listOf(
        PortableImportContentSpec("conversations", setOf("local_sessions", "local_messages", "companion_memories"),
            sidecars = mapOf("plot_story" to "data/conversations-story.json")),
        PortableImportContentSpec("characters", setOf("local_characters")),
        PortableImportContentSpec("extensions", setOf("local_skills"), fileRoots = setOf("skills")),
        PortableImportContentSpec("app_settings", singletonJson = true),
        PortableImportContentSpec("credentials", sidecars = mapOf("credentials_bundle" to "credentials/portable-credentials.json")),
        PortableImportContentSpec("media", fileRoots = setOf("portraits"))
    )

    private fun inspect(raw: String, category: String = "conversations") =
        PortableImportContentInspector.inspect(catalog, mapOf(category to raw.toByteArray()))

    @Test fun onlyActualNonEmptyContentsAppear() {
        val result = inspect("""{"tables":{"local_sessions":[],"local_messages":[],"companion_memories":[{"id":"synthetic","content":"fixture"}]}}""")
        assertEquals(setOf("conversations"), result.categoryIds)
        assertEquals(listOf(PortableImportContent("conversations", "table:companion_memories", rows = 1)), result.contents)
    }

    @Test fun absentChatAndEmptyArraysAreBothAbsentOptions() {
        assertEquals(emptyList<PortableImportContent>(), inspect("""{"tables":{}}""").contents)
        assertEquals(emptyList<PortableImportContent>(), inspect("""{"tables":{"local_messages":[]}}""").contents)
    }

    @Test fun countsRowsWithoutReturningAnySourceValues() {
        val result = inspect("""{"tables":{"local_messages":[{"id":"a","content":"SYNTHETIC_CHAT_SECRET_MARKER"},{"id":"b","content":null}]}}""")
        assertEquals(2, result.contents.single().rows)
        assertFalse(result.toString().contains("SYNTHETIC_CHAT_SECRET_MARKER"))
        assertFalse(result.toString().contains("content="))
    }

    @Test fun countsLargeSyntheticHistoryWithoutBuildingRowObjects() {
        val raw = buildString {
            append("{\"tables\":{\"local_messages\":[")
            repeat(62000) { if (it > 0) append(','); append("{\"id\":$it,\"content\":\"fixture\"}") }
            append("]}}")
        }
        assertEquals(62000, inspect(raw).contents.single().rows)
    }

    @Test fun fileOnlyCategoryShowsItsActualRootWithoutInventedDatabaseRows() {
        val result = PortableImportContentInspector.inspect(catalog, emptyMap(), listOf(
            PortableImportFileDescriptor("files/media/portraits/a.png", "media", "portraits"),
            PortableImportFileDescriptor("files/media/portraits/b.png", "media", "portraits")
        ))
        assertEquals(listOf(PortableImportContent("media", "root:portraits", files = 2)), result.contents)
    }

    @Test fun skillMetadataAndSkillFilesStaySeparatelyVisible() {
        val result = PortableImportContentInspector.inspect(catalog,
            mapOf("extensions" to """{"tables":{"local_skills":[{"id":"s","enabled":true}]}}""".toByteArray()),
            listOf(PortableImportFileDescriptor("files/extensions/skills/test/SKILL.md", "extensions", "skills")))
        assertEquals(listOf("table:local_skills", "root:skills"), result.contents.map { it.detailKey })
    }

    @Test fun emptySettingsAreAbsentAndNonEmptySettingsRemainValueFree() {
        assertTrue(inspect("{}", "app_settings").contents.isEmpty())
        val result = inspect("""{"language":"SYNTHETIC_SETTINGS_MARKER"}""", "app_settings")
        assertEquals(1, result.contents.single().rows)
        assertFalse(result.toString().contains("SYNTHETIC_SETTINGS_MARKER"))
    }

    @Test fun credentialPresenceDoesNotParseOrExposeCredentialBundle() {
        val result = PortableImportContentInspector.inspect(catalog, emptyMap(), presentSidecars = setOf("credentials/portable-credentials.json"))
        assertEquals(listOf(PortableImportContent("credentials", "credentials_bundle", rows = 1)), result.contents)
    }

    @Test fun storyPresenceDoesNotImplyMessagesArePresent() {
        val result = PortableImportContentInspector.inspect(catalog, emptyMap(), presentSidecars = setOf("data/conversations-story.json"))
        assertEquals(listOf("plot_story"), result.contents.map { it.detailKey })
    }

    @Test fun rejectsDuplicateRootTableAndNestedFields() {
        listOf(
            """{"tables":{},"tables":{}}""",
            """{"tables":{"local_messages":[],"local_messages":[]}}""",
            """{"tables":{"local_messages":[{"id":"a","id":"b"}]}}""",
            """{"tables":{"local_messages":[{"nested":{"key":1,"key":2}}]}}"""
        ).forEach { raw -> assertRejected { inspect(raw) } }
    }

    @Test fun rejectsUnknownDataInsteadOfShowingMisleadingPartialPreview() {
        assertRejected { inspect("""{"tables":{"unknown_table":[]}}""") }
        assertRejected { inspect("""{"tables":{}}""", "unknown_category") }
        assertRejected { PortableImportContentInspector.inspect(catalog, emptyMap(),
            listOf(PortableImportFileDescriptor("fixture", "media", "unknown_root"))) }
        assertRejected { PortableImportContentInspector.inspect(catalog, emptyMap(), presentSidecars = setOf("unknown")) }
    }

    @Test fun rejectsMalformedRowsAndTrailingJson() {
        listOf("{}", """{"tables":{"local_messages":[null]}}""",
            """{"tables":{"local_messages":[1]}}""", """{"tables":{}} {}""").forEach { raw ->
            assertRejected { inspect(raw) }
        }
    }

    @Test fun rejectsDuplicateArchiveFileDescriptors() {
        val file = PortableImportFileDescriptor("files/media/portraits/a.png", "media", "portraits")
        assertRejected { PortableImportContentInspector.inspect(catalog, emptyMap(), listOf(file, file)) }
    }

    @Test fun acceptsTheExistingFourteenCategoryCatalogAndAllPortableTables() {
        val completeCatalog = PortableDataCategory.entries.map { category ->
            PortableImportContentSpec(category.id, category.tables.toSet(),
                fileRoots = if (category.tables.isEmpty() && category != PortableDataCategory.APP_SETTINGS) setOf("synthetic_root") else emptySet(),
                singletonJson = category == PortableDataCategory.APP_SETTINGS)
        }
        val data = completeCatalog.filter { it.tables.isNotEmpty() }.associate { spec ->
            spec.categoryId to ("{\"tables\":{" + spec.tables.joinToString(",") { "\"$it\":[{}]" } + "}}").toByteArray()
        } + ("app_settings" to """{"language":"fixture"}""".toByteArray())
        val files = completeCatalog.filter { it.fileRoots.isNotEmpty() }.map { spec ->
            PortableImportFileDescriptor("files/${spec.categoryId}/synthetic_root/fixture", spec.categoryId, "synthetic_root")
        }
        val result = PortableImportContentInspector.inspect(completeCatalog, data, files)
        assertEquals(14, result.categoryIds.size)
        assertEquals(51, result.contents.count { it.detailKey.startsWith("table:") })
        assertEquals(52, result.contents.sumOf { it.rows }) // 51张表及设置对象。
    }

    private fun assertRejected(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Invalid input was accepted")
    }
}
