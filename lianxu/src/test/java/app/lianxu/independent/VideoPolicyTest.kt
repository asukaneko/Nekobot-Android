package app.lianxu.independent

import org.junit.Test
import org.junit.Assert.*

class VideoPolicyTest {
    @Test fun missingVideoModeUsesFramesAndSavedModesAreRespected(){
        assertEquals(ToolboxPreferences.VideoMode.FRAMES,ToolboxPreferences.videoMode(""))
        assertEquals(ToolboxPreferences.VideoMode.ORIGINAL,ToolboxPreferences.videoMode("ORIGINAL"))
        assertEquals(ToolboxPreferences.VideoMode.FRAMES,ToolboxPreferences.videoMode("FRAMES"))
    }
    @Test fun decimalSeventyMegabytesIncludesTheBoundaryAndRejectsOneMoreByte(){
        VideoPolicy.requireSize(69_999_999);VideoPolicy.requireSize(70_000_000)
        try{VideoPolicy.requireSize(70_000_001);fail("oversized video accepted")}catch(e:IllegalArgumentException){assertEquals("暂不支持超过70 MB的视频",e.message)}
    }
    @Test fun everySecondIsKeptBeyondTwentyFrames(){
        val times=VideoPolicy.frameTimes(23_000).toList()
        assertEquals(23,times.size);assertEquals(0L,times.first());assertEquals(22_000L,times.last())
        assertTrue(times.zipWithNext().all{(a,b)->b-a==1000L})
    }
    @Test fun shortAndPartialLastSecondsAreNotSilentlyDropped(){
        assertEquals(listOf(0L),VideoPolicy.frameTimes(1).toList())
        assertEquals(listOf(0L),VideoPolicy.frameTimes(1000).toList())
        assertEquals(listOf(0L,1000L),VideoPolicy.frameTimes(1001).toList())
        assertEquals(24L,VideoPolicy.frameCount(23_001))
    }
    @Test fun uniformSamplesCoverLongVideosAndTheBoundary() {
        assertEquals(23_000L, VideoPolicy.frameTimes(24_000).last())
        for (duration in listOf(24_001L, 120_000L, 180_000L, Long.MAX_VALUE / 1000)) {
            val times = VideoPolicy.frameTimes(duration).toList()
            assertEquals(24L, VideoPolicy.frameCount(duration))
            assertEquals(24, times.size)
            assertEquals(0L, times.first())
            assertEquals(duration - 1, times.last())
            val gaps = times.zipWithNext { a, b -> b - a }
            assertTrue(gaps.min() > 0 && gaps.max() - gaps.min() <= 1)
        }
    }

    @Test fun timeLabelsIncludeHoursOnlyWhenNeeded() {
        assertEquals("0:00", VideoPolicy.formatTime(999))
        assertEquals("0:23", VideoPolicy.formatTime(23_000))
        assertEquals("2:35", VideoPolicy.formatTime(155_999))
        assertEquals("1:02:05", VideoPolicy.formatTime(3_725_000))
    }
}
