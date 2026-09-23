package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class SubtitleFastAudioProbeRecoveryTest {
    private fun partial(termination: SubtitleFastAudioProbeTermination) = SubtitleFastAudioProbeResult(
        snapshot = SubtitleSpeechSnapshot(
            speechSpans = emptyList(),
            observedSpans = listOf(SubtitleSyncSpan(2_531_067, 2_533_280)),
            pcmAvailable = true
        ),
        decodedStartMs = 2_531_067,
        decodedEndMs = 2_533_280,
        termination = termination
    )

    @Test fun `two seconds of PCM followed by no progress triggers recovery at eight seconds`() {
        val watchdog = SubtitleProbeProgressWatchdog()
        assertFalse(watchdog.isStalled(2_213, 1_000))
        assertFalse(watchdog.isStalled(2_213, 8_999))
        assertTrue(watchdog.isStalled(2_213, 9_000))
    }

    @Test fun `startup without PCM uses its own timeout`() {
        val watchdog = SubtitleProbeProgressWatchdog()
        assertFalse(watchdog.isStalled(0, 0))
        assertFalse(watchdog.isStalled(0, 19_000))
        assertFalse(watchdog.isStalled(20, 19_100))
        assertFalse(watchdog.isStalled(20, 27_099))
        assertTrue(watchdog.isStalled(20, 27_100))
    }

    @Test fun `continuous PCM including silence resets watchdog without requiring speech`() {
        val watchdog = SubtitleProbeProgressWatchdog()
        for (step in 1L..60L) {
            assertFalse(watchdog.isStalled(step * 1_000, step * 2_000))
        }
    }

    @Test fun `scoring pauses do not consume the no progress budget`() {
        val watchdog = SubtitleProbeProgressWatchdog()
        assertFalse(watchdog.isStalled(15_000, 5_000))
        // 30 seconds spent scoring are excluded by the caller from the elapsed acquisition clock.
        assertFalse(watchdog.isStalled(15_000, 35_000 - 30_000))
        assertFalse(watchdog.isStalled(15_020, 35_040 - 30_000))
        assertTrue(watchdog.isStalled(15_020, 43_040 - 30_000))
    }

    @Test fun `partial PCM does not suppress fallback on stall or timeout`() {
        for (termination in listOf(SubtitleFastAudioProbeTermination.STALLED,
            SubtitleFastAudioProbeTermination.WALL_TIMEOUT, SubtitleFastAudioProbeTermination.ERROR)) {
            assertTrue(shouldRetrySubtitleProbeWithCompatibility(false, partial(termination), false))
        }
    }

    @Test fun `fallback is bounded and cannot bypass unsupported timelines or successful termination`() {
        val stalled = partial(SubtitleFastAudioProbeTermination.STALLED)
        assertFalse(shouldRetrySubtitleProbeWithCompatibility(true, stalled, false))
        assertFalse(shouldRetrySubtitleProbeWithCompatibility(false, stalled, true))
        for (termination in listOf(SubtitleFastAudioProbeTermination.TARGET_REACHED,
            SubtitleFastAudioProbeTermination.EOF)) {
            assertFalse(shouldRetrySubtitleProbeWithCompatibility(false, partial(termination), false))
        }
    }

    @Test fun `compatibility retry retains partial evidence and original request options`() {
        val request = SubtitleFastAudioProbeRequest("https://example.invalid/film", emptyMap(),
            2_531_000, 10_000_000, null, targetAudioDurationMs = 60_000)
        val result = partial(SubtitleFastAudioProbeTermination.STALLED)
        val retry = subtitleProbeCompatibilityRequest(request, result)
        assertSame(result.snapshot, retry.seedSnapshot)
        assertEquals(request, retry.copy(seedSnapshot = null))
        assertEquals(2_213L, result.observedDurationMs)
    }

    @Test fun `failed PCM does not poison a previously valid seed`() {
        val valid = partial(SubtitleFastAudioProbeTermination.STALLED)
        val request = SubtitleFastAudioProbeRequest("https://example.invalid/film", emptyMap(),
            2_531_000, 10_000_000, null, seedSnapshot = valid.snapshot)
        val failed = valid.copy(snapshot = valid.snapshot!!.copy(failureReason = "Unsupported PCM"))
        assertSame(valid.snapshot, subtitleProbeCompatibilityRequest(request, failed).seedSnapshot)
    }
}
