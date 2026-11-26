package app.lianxu.independent

import android.media.MediaMetadataRetriever
import org.junit.Assert.*
import org.junit.Test

class ChatVideosAndroidTest {
    @Test fun longVideoRetriesNearbyTimesThenSyncWithoutDiscardingOtherFrames() {
        val times = VideoPolicy.frameTimes(180_000).toList()
        val attempts = mutableListOf<Pair<Long, Int>>()
        val read = times.mapNotNull { at ->
            ChatVideos.readAround(at, 180_000) { time, option ->
                attempts += time to option
                when (at) {
                    times.first() -> time.takeIf { it == 250L }
                    times[1] -> null
                    times.last() -> time.takeIf { option == MediaMetadataRetriever.OPTION_CLOSEST_SYNC }
                    else -> time
                }
            }
        }
        assertEquals(23, read.size)
        assertEquals(250L, read.first())
        assertEquals(179_999L, read.last())
        assertEquals(0L to MediaMetadataRetriever.OPTION_CLOSEST, attempts.first())
        assertEquals(179_999L to MediaMetadataRetriever.OPTION_CLOSEST_SYNC, attempts.last())
        assertTrue(attempts.all { it.first in 0 until 180_000L })
    }
}
