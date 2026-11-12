package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingCompletionThresholdTest {

    @Test
    fun `no tracker bar keeps the local bar`() {
        assertNull(localCompletionThresholdOverride(null))
    }

    @Test
    fun `a tracker bar at or under the local bar keeps the local bar`() {
        assertNull(localCompletionThresholdOverride(80.0))
        assertNull(localCompletionThresholdOverride(90.0))
    }

    @Test
    fun `a higher tracker bar raises the local bar`() {
        assertEquals(0.96f, localCompletionThresholdOverride(96.0)!!, 0.0001f)
    }

    @Test
    fun `the raised bar never passes the whole scale`() {
        assertEquals(1f, localCompletionThresholdOverride(100.0)!!, 0f)
        assertEquals(1f, localCompletionThresholdOverride(140.0)!!, 0f)
    }

    @Test
    fun `a bar that is not a number is ignored`() {
        assertNull(localCompletionThresholdOverride(Double.NaN))
    }

    @Test
    fun `a stop between the local bar and the tracker bar stays in progress`() {
        val stoppedAt92 = progress(position = 92_000L, override = localCompletionThresholdOverride(96.0))

        assertFalse(stoppedAt92.isCompleted())
        assertTrue(stoppedAt92.isInProgress())
        assertTrue(stoppedAt92.copy(position = 96_000L).isCompleted())
    }

    @Test
    fun `without a tracker bar the local bar still completes at 90 percent`() {
        assertTrue(progress(position = 92_000L, override = localCompletionThresholdOverride(null)).isCompleted())
    }

    private fun progress(position: Long, override: Float?) = WatchProgress(
        contentId = "tt0944947",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt0944947:5:3",
        season = 5,
        episode = 3,
        episodeTitle = null,
        position = position,
        duration = 100_000L,
        lastWatched = 1L,
        completionThresholdOverride = override
    )
}
