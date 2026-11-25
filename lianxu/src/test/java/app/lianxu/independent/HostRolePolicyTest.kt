package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class HostRolePolicyTest {
    @Test fun remapUsesLocalIdsAndKeepsUnfinishedDeletesVerbatim() {
        val value = """{"link":{"scope":"A.db","friend":"old","host":"host"},"hostHash":"hh","localHash":"lh","hostAvatar":"","localAvatar":"","deleting":false}"""
        assertNull(remapRestoredRole(value, mapOf("unrelated" to "new")))
        val first = requireNotNull(remapRestoredRole(value, mapOf("old" to "new"))).second
        val second = requireNotNull(remapRestoredRole(first, mapOf("old" to "newer"))).second
        val link = com.google.gson.JsonParser.parseString(second).asJsonObject["link"].asJsonObject
        assertEquals("newer", link["friend"].asString)
        assertEquals("host", link["host"].asString)
        assertEquals("A.db", link["scope"].asString)
        val deleting = value.replace("false", "true")
        assertEquals(deleting, remapRestoredRole(deleting, mapOf("old" to "new"))?.second)
    }

    @Test fun detachedRoleStaysDetachedAcrossRepeatedReplace() {
        val value = """{"link":{"scope":"A.db","friend":"old","host":"id"},"hostHash":"hh","localHash":"lh","hostAvatar":"","localAvatar":"","detached":true}"""
        val first = requireNotNull(remapRestoredRole(value, mapOf("old" to "new")))
        val second = requireNotNull(remapRestoredRole(first.second, mapOf("old" to "newer")))
        assertEquals(ROLE_MAP + "detached:newer", second.first)
        val baseline = com.google.gson.JsonParser.parseString(second.second).asJsonObject
        assertTrue(baseline["detached"].asBoolean)
        assertEquals("newer", baseline["link"].asJsonObject["friend"].asString)
    }

    @Test fun requiredLocalFieldsFitEvenAtSurrogateBoundary() {
        val name = "名".repeat(29) + "🌸"
        val role = RoleBasics(name, "", "", "招呼", null).local()
        assertEquals("名".repeat(29), role.name)
        assertEquals(role.name, role.bio)
        assertEquals(role.name, role.definition)
        assertEquals("招呼", role.opening)
        assertTrue(RoleBasics("", "", "", "", null).local().name.isNotBlank())
    }

    @Test fun digestsDistinguishFieldBoundariesAndImageContents() {
        val first = RoleBasics("ab", "c", "d", "", byteArrayOf(1))
        assertNotEquals(first.digest, first.copy(name = "a", bio = "bc").digest)
        assertNotEquals(first.digest, first.copy(avatar = byteArrayOf(2)).digest)
        assertEquals(first.digest, first.copy(avatar = byteArrayOf(1)).digest)
    }

    @Test fun onlyChangedSideWritesAndCurrentActionResolvesConflicts() {
        assertEquals(RoleSource.NONE, roleSource(false, false, true))
        assertEquals(RoleSource.HOST, roleSource(true, false, false))
        assertEquals(RoleSource.LOCAL, roleSource(false, true, true))
        assertEquals(RoleSource.HOST, roleSource(true, true, true))
        assertEquals(RoleSource.LOCAL, roleSource(true, true, false))
    }
}
