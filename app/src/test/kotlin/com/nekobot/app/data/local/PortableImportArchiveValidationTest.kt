package com.nekobot.app.data.local

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortableImportArchiveValidationTest {
    @Test fun supportedIntegerVersionsAreAcceptedAndOthersRejectedBeforeWriting() {
        (1..3).forEach { assertEquals(it, PortableArchiveValidator.version(com.google.gson.JsonPrimitive(it), 1..3)) }
        listOf("4", "0", "-1", "3.2", "\"3\"", "true", "null", "999999999999999").forEach {
            try { PortableArchiveValidator.version(com.google.gson.JsonParser.parseString(it), 1..3); fail(it) }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun storyOnlyArchiveUsesStableConversationIdsFromItsOwnContents() {
        val sessions = PortableArchiveValidator.storySessions("""{"nodes":[{"id":"node","conversation_id":"existing-session"}],"choices":[],"edges":[]}""", setOf("choices-only-session"))
        assertEquals(setOf("existing-session", "choices-only-session"), sessions)
    }
    @Test(expected = IllegalArgumentException::class) fun storyNodeWithoutStableIdIsRejectedInsteadOfGeneratingANewId() {
        PortableArchiveValidator.storySessions("""{"nodes":[{"conversation_id":"session"}]}""", emptySet())
    }
    @Test fun credentialPreviewReturnsOnlyTargetIdsAndNeverValues() {
        val metadata = PortableArchiveValidator.credentialTargets("""{"version":1,"ai_models":[{"id":"model","api_key":"SYNTHETIC_NON_SECRET"}],"oauth_accounts":[{"id":"empty-account","credentials":""}]}""".toByteArray())
        assertEquals(setOf("model"), metadata["local_ai_models"])
        assertTrue(metadata["local_oauth_accounts"].orEmpty().isEmpty())
        assertFalse(metadata.toString().contains("SYNTHETIC_NON_SECRET"))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateCredentialTargetsAreRejected() {
        PortableArchiveValidator.credentialTargets("""{"version":1,"api_keys":[{"id":"one","key":"fixture"},{"id":"one","key":"other"}]}""".toByteArray())
    }
    @Test(expected = IllegalArgumentException::class) fun futureCredentialSchemaIsRejected() {
        PortableArchiveValidator.credentialTargets("""{"version":2,"ai_models":[]}""".toByteArray())
    }
    @Test(expected = IllegalArgumentException::class) fun unknownCredentialCategoryIsNotSilentlyIgnored() {
        PortableArchiveValidator.credentialTargets("""{"version":1,"unknown":[]}""".toByteArray())
    }
    @Test(expected = IllegalArgumentException::class) fun credentialFieldsCannotSmuggleNestedConfiguration() {
        PortableArchiveValidator.credentialTargets("""{"version":1,"ai_models":[{"id":"model","api_key":{"enabled":true}}]}""".toByteArray())
    }
    @get:Rule val temp = TemporaryFolder(File("build/portable-import-tests").apply { mkdirs() })
    private fun zip(vararg files: Pair<String, String>): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip -> files.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }
    private fun inventory(path: String, content: String) = JsonArray().apply { add(JsonObject().apply {
        addProperty("path", path); addProperty("size", content.toByteArray().size)
        addProperty("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) })
    }) }
    @Test fun rejectsAbsoluteDriveUncTraversalAndAmbiguousPaths() {
        listOf("/files/a", "\\\\server\\share", "C:/files/a", "../a", "files/../a", "files/./a", "files//a", "files/\u0000x").forEach { path ->
            try { PortableArchiveValidator.entryName(path); fail(path) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun acceptsUnicodeAndNormalizesRelativeBackslashes() {
        assertEquals("files/media/portraits/合成图.png", PortableArchiveValidator.entryName("files\\media\\portraits\\合成图.png"))
    }
    @Test fun validInventoryMatchesActualBytesAndAdditionalNotesAreNotRestored() {
        val path = "files/media/portraits/fixture.txt"
        val file = zip(path to "synthetic bytes", "README.txt" to "not an imported attachment")
        PortableArchiveValidator.validateStructure(file)
        PortableArchiveValidator.validateFiles(file, inventory(path, "synthetic bytes"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnlistedAttachments() {
        val path = "files/media/portraits/a"
        PortableArchiveValidator.validateFiles(zip(path to "a", "files/media/portraits/b" to "b"), inventory(path, "a"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingAttachment() {
        PortableArchiveValidator.validateFiles(zip("README.txt" to "fixture"), inventory("files/media/portraits/a", "a"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongHashEvenWhenSizeMatches() {
        val path = "files/media/portraits/a"
        PortableArchiveValidator.validateFiles(zip(path to "b"), inventory(path, "a"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongDeclaredLength() {
        val path = "files/media/portraits/a"
        PortableArchiveValidator.validateFiles(zip(path to "longer"), inventory(path, "a"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateInventoryPaths() {
        val path = "files/media/portraits/a"
        val data = inventory(path, "a").apply { add(get(0)) }
        PortableArchiveValidator.validateFiles(zip(path to "a"), data)
    }
    @Test(expected = IllegalArgumentException::class) fun structureRejectsTraversalBeforeOpeningContent() {
        PortableArchiveValidator.validateStructure(zip("files/media/../../outside" to "fixture"))
    }
}
