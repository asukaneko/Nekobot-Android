package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class HostRolePolicyTest {
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
