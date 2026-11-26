package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class UpdateVersionMatchTest {
    @Test fun testCounterDoesNotSelectAnUnrelatedFormalRelease() {
        val build=InstalledBuild("test","1.0.6-test.26+fixture",26)
        val current=UpdatesPresentation.current(build)
        assertEquals(LocalReleaseNotes.forVersion(65),current)
        assertEquals("v1.0.6 · 测试版 26",build.versionLabel)
        assertNull(UpdatesPresentation.current(build.copy(versionName="9.9.9-test.26+fixture")))
    }
}
