package com.nekobot.app.data.local

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CompanionTransferReplacementTest {
    private fun row(id: String, key: String, version: Int, current: String, locked: String = "0", valid: String = "1") =
        JsonParser.parseString("""{"id":"$id","scopeId":"s","factKey":"$key","version":$version,"current":$current,"locked":$locked,"valid":$valid,"status":"active"}""").asJsonObject

    @Test fun sqliteIntegerFlagsPreserveReplacedVersionsForRevisionHistory() {
        val rows = listOf(row("old", "job", 1, "0"), row("new", "job", 2, "1"), row("other", "hobby", 1, "1"))
        assertEquals(setOf("old", "new", "other"), retainedCompanionMemoryIds(rows))
    }

    @Test fun booleanFlagsAndManualProtectionRemainSupported() {
        val rows = listOf(row("old", "job", 1, "false", "true"), row("new", "job", 2, "true", valid = "true"))
        assertEquals(setOf("old", "new"), retainedCompanionMemoryIds(rows))
    }

    @Test fun invalidImportedSuccessorDoesNotRemoveAnOlderFact() {
        val rows = listOf(row("old", "job", 1, "1"), row("invalid", "job", 2, "1", valid = "0"))
        assertEquals(setOf("old", "invalid"), retainedCompanionMemoryIds(rows))
    }
}
