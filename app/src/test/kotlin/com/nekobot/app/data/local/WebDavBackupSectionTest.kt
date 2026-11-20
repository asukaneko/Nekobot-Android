package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBackupSectionTest {

    @Test
    fun `legacy request without selection keeps full backup`() {
        assertEquals(WebDavBackupSection.entries.toSet(), WebDavBackupSection.resolve(null))
    }

    @Test
    fun `explicit selection always keeps database`() {
        val resolved = WebDavBackupSection.resolve(listOf("media", "audio"))

        assertEquals(
            setOf(WebDavBackupSection.DATABASE, WebDavBackupSection.MEDIA, WebDavBackupSection.AUDIO),
            resolved
        )
    }

    @Test
    fun `empty selection falls back to database only`() {
        assertEquals(setOf(WebDavBackupSection.DATABASE), WebDavBackupSection.resolve(emptyList()))
    }

    @Test
    fun `unknown ids are ignored`() {
        val resolved = WebDavBackupSection.resolve(listOf("not-a-section", "stats"))

        assertEquals(setOf(WebDavBackupSection.DATABASE, WebDavBackupSection.STATS), resolved)
        assertNull(WebDavBackupSection.fromId("not-a-section"))
    }

    @Test
    fun `default selection covers every section`() {
        val defaults = WebDavBackupSection.defaultSelection()

        assertEquals(WebDavBackupSection.entries.map { it.id }.toSet(), defaults)
        assertTrue(WebDavBackupSection.DATABASE.required)
        assertTrue(WebDavBackupSection.entries.filter { it != WebDavBackupSection.DATABASE }.none { it.required })
    }

    @Test
    fun `catalog roots map to file sections`() {
        assertEquals(
            WebDavBackupSection.WORKSPACE,
            WebDavBackupSection.sectionOfCatalogRoot(PortableDataCategory.WORKSPACE.id)
        )
        assertEquals(
            WebDavBackupSection.EXTENSIONS,
            WebDavBackupSection.sectionOfCatalogRoot(PortableDataCategory.EXTENSIONS.id)
        )
        assertEquals(
            WebDavBackupSection.MEDIA,
            WebDavBackupSection.sectionOfCatalogRoot(PortableDataCategory.MEDIA.id)
        )
    }
}
