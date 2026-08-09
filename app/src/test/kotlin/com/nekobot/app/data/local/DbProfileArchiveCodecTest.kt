package com.nekobot.app.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DbProfileArchiveCodecTest {

    @Test
    fun databaseAndPortraitReferencesRoundTrip() {
        withTempDir { dir ->
            val main = File(dir, "source.db").apply { writeBytes("main-db".toByteArray()) }
            val wal = File(dir, "source.db-wal").apply { writeBytes("wal".toByteArray()) }
            val portrait = File(dir, "portrait.png").apply {
                writeBytes(ByteArray(2048) { index -> (index % 251).toByte() })
            }
            val firstReference = portrait.toURI().toString()
            val secondReference = "file://${portrait.absolutePath}"

            val archive = DbProfileArchiveCodec.createArchive(
                databaseFiles = listOf(main.name to main, wal.name to wal),
                portraitSources = listOf(
                    DbProfilePortraitSource(firstReference, portrait),
                    DbProfilePortraitSource(secondReference, portrait)
                )
            )
            val extracted = requireNotNull(DbProfileArchiveCodec.extractArchive(archive))

            assertArrayEquals(main.readBytes(), extracted.main)
            assertArrayEquals(wal.readBytes(), extracted.wal)
            assertEquals(1, extracted.portraits.size)
            assertEquals(setOf(firstReference, secondReference), extracted.portraitReferences.keys)
            assertEquals(
                extracted.portraitReferences[firstReference],
                extracted.portraitReferences[secondReference]
            )
            assertArrayEquals(
                portrait.readBytes(),
                extracted.portraits.getValue(extracted.portraitReferences.getValue(firstReference))
            )
        }
    }

    @Test
    fun oldDatabaseOnlyZipRemainsSupported() {
        val archive = zipOf(
            "nested/legacy.db" to "legacy-main".toByteArray(),
            "nested/legacy.db-wal" to "legacy-wal".toByteArray(),
            "README.txt" to "old backup".toByteArray()
        )

        val extracted = requireNotNull(DbProfileArchiveCodec.extractArchive(archive))

        assertArrayEquals("legacy-main".toByteArray(), extracted.main)
        assertArrayEquals("legacy-wal".toByteArray(), extracted.wal)
        assertTrue(extracted.portraits.isEmpty())
        assertTrue(extracted.portraitReferences.isEmpty())
        assertNull(extracted.story)
    }

    @Test
    fun realVersion22DatabaseAndStoryDataRoundTrip() {
        val source = realVersion22DatabaseFixture()
        val graphJson =
            """
            {
              "nodes": [
                {
                  "id": "root-node",
                  "conversation_id": "session-a",
                  "character_id": "character-a",
                  "title": "相遇",
                  "summary": "故事从这里开始",
                  "parent_node_id": null
                },
                {
                  "id": "branch-node",
                  "conversation_id": "session-a",
                  "character_id": "character-a",
                  "title": "新的选择",
                  "summary": "沿着选择继续前进",
                  "parent_node_id": "root-node"
                }
              ],
              "choices": [
                {
                  "id": "choice-a",
                  "node_id": "root-node",
                  "text": "继续前进",
                  "selected": true
                }
              ],
              "edges": [
                {
                  "id": "edge-a",
                  "from_node_id": "root-node",
                  "to_node_id": "branch-node",
                  "choice_id": "choice-a"
                }
              ],
              "active": {"session-a": "branch-node"}
            }
            """.trimIndent()
        val story = DbProfileStoryData(
            graphJson = graphJson,
            plotChoices = linkedMapOf(
                "session-a" to
                    """{"choices":[{"id":"choice-a","text":"继续前进","selected":true}]}"""
            )
        )

        withTempDir { dir ->
            val database = File(dir, "profile.db").apply { writeBytes(source.main) }
            val archive = DbProfileArchiveCodec.createArchive(
                databaseFiles = listOf(database.name to database),
                portraitSources = emptyList(),
                story = story
            )

            val extracted = requireNotNull(DbProfileArchiveCodec.extractArchive(archive))

            assertArrayEquals(source.main, extracted.main)
            assertArrayEquals(
                "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1),
                extracted.main.copyOfRange(0, 16)
            )
            assertEquals(
                22,
                ByteBuffer.wrap(extracted.main, 60, 4).order(ByteOrder.BIG_ENDIAN).int
            )
            assertEquals(story, extracted.story)
        }
    }

    @Test
    fun realVersion22DatabaseFixtureCanBeExtracted() {
        val extracted = realVersion22DatabaseFixture()

        assertArrayEquals(
            "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1),
            extracted.main.copyOfRange(0, 16)
        )
        assertEquals(
            22,
            ByteBuffer.wrap(extracted.main, 60, 4).order(ByteOrder.BIG_ENDIAN).int
        )
        assertNull(extracted.story)
    }

    @Test
    fun unsafePortraitManifestPathIsRejected() {
        val archive = zipOf(
            "profile.db" to "main".toByteArray(),
            "portraits/image.png" to "portrait".toByteArray(),
            "portraits/manifest.json" to
                """{"version":1,"references":{"file:///old.png":"portraits/../image.png"}}"""
                    .toByteArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            DbProfileArchiveCodec.extractArchive(archive)
        }
    }

    @Test
    fun ambiguousArchiveWithMultipleDatabasesIsRejected() {
        val archive = zipOf(
            "first.db" to "first".toByteArray(),
            "second.db" to "second".toByteArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            DbProfileArchiveCodec.extractArchive(archive)
        }
    }

    @Test
    fun unknownPortraitManifestVersionIsRejected() {
        val archive = zipOf(
            "profile.db" to "main".toByteArray(),
            "portraits/manifest.json" to
                """{"version":99,"references":{}}""".toByteArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            DbProfileArchiveCodec.extractArchive(archive)
        }
    }

    @Test
    fun malformedStoryJsonIsRejected() {
        val archive = zipOf(
            "profile.db" to "main".toByteArray(),
            "story/story.json" to "{".toByteArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            DbProfileArchiveCodec.extractArchive(archive)
        }
    }

    @Test
    fun invalidEmbeddedStoryGraphJsonIsRejected() {
        val archive = zipOf(
            "profile.db" to "main".toByteArray(),
            "story/story.json" to
                """{"version":1,"graphJson":"not-json","plotChoices":{}}""".toByteArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            DbProfileArchiveCodec.extractArchive(archive)
        }
    }

    @Test
    fun invalidEmbeddedPlotChoicesJsonIsRejected() {
        val archive = zipOf(
            "profile.db" to "main".toByteArray(),
            "story/story.json" to
                """{"version":1,"graphJson":"{}","plotChoices":{"session-a":"[]"}}"""
                    .toByteArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            DbProfileArchiveCodec.extractArchive(archive)
        }
    }

    @Test
    fun unknownStoryVersionIsRejected() {
        val archive = zipOf(
            "profile.db" to "main".toByteArray(),
            "story/story.json" to
                """{"version":99,"graphJson":"{}","plotChoices":{}}""".toByteArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            DbProfileArchiveCodec.extractArchive(archive)
        }
    }

    @Test
    fun sameFileNameWithDifferentContentStaysDistinct() {
        withTempDir { dir ->
            val main = File(dir, "profile.db").apply { writeBytes("db".toByteArray()) }
            val first = File(dir, "first/portrait.png").apply {
                parentFile?.mkdirs()
                writeBytes("first-image".toByteArray())
            }
            val second = File(dir, "second/portrait.png").apply {
                parentFile?.mkdirs()
                writeBytes("second-image".toByteArray())
            }
            val firstReference = first.toURI().toString()
            val secondReference = second.toURI().toString()

            val extracted = requireNotNull(
                DbProfileArchiveCodec.extractArchive(
                    DbProfileArchiveCodec.createArchive(
                        databaseFiles = listOf(main.name to main),
                        portraitSources = listOf(
                            DbProfilePortraitSource(firstReference, first),
                            DbProfilePortraitSource(secondReference, second)
                        )
                    )
                )
            )

            assertEquals(2, extracted.portraits.size)
            assertTrue(
                extracted.portraits.values.any { it.contentEquals(first.readBytes()) }
            )
            assertTrue(
                extracted.portraits.values.any { it.contentEquals(second.readBytes()) }
            )
            assertTrue(
                extracted.portraitReferences[firstReference] !=
                    extracted.portraitReferences[secondReference]
            )
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun realVersion22DatabaseFixture(): ExtractedDbProfileArchive {
        val fixture = listOf(
            File("docs/assets/nekobot_readme_demo_data.zip"),
            File("../docs/assets/nekobot_readme_demo_data.zip")
        ).first { it.isFile }
        return requireNotNull(DbProfileArchiveCodec.extractArchive(fixture.readBytes()))
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("db-profile-archive-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
