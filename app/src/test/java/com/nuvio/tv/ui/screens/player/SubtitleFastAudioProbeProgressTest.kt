package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFastAudioProbeProgressTest {
    @Test
    fun `absolute timestamp beyond target does not count as decoded duration`() {
        val spans = listOf(
            SubtitleSyncSpan(
                startMs = 3_500_000L,
                endMs = 3_500_031L
            )
        )

        assertFalse(
            hasReachedSubtitleFastAudioTarget(
                observedSpans = spans,
                targetDurationMs = 60_000L
            )
        )
    }

    @Test
    fun `sixty seconds of observed PCM reaches target regardless of absolute position`() {
        val spans = listOf(
            SubtitleSyncSpan(
                startMs = 3_500_000L,
                endMs = 3_560_000L
            )
        )

        assertTrue(
            hasReachedSubtitleFastAudioTarget(
                observedSpans = spans
            )
        )
    }

    @Test
    fun `gaps between observed spans are not counted as decoded PCM`() {
        val spans = listOf(
            SubtitleSyncSpan(startMs = 1_000_000L, endMs = 1_020_000L),
            SubtitleSyncSpan(startMs = 1_120_000L, endMs = 1_140_000L)
        )

        assertFalse(
            hasReachedSubtitleFastAudioTarget(
                observedSpans = spans,
                targetDurationMs = 60_000L
            )
        )
    }

    @Test
    fun `overlapping spans are not counted twice`() {
        val spans = listOf(
            SubtitleSyncSpan(startMs = 2_000_000L, endMs = 2_040_000L),
            SubtitleSyncSpan(startMs = 2_020_000L, endMs = 2_050_000L)
        )

        assertFalse(
            hasReachedSubtitleFastAudioTarget(
                observedSpans = spans,
                targetDurationMs = 60_000L
            )
        )
    }

    @Test
    fun `scout seed and continuation form one full probe without double counting`() {
        val seed = SubtitleSpeechSnapshot(
            speechSpans = listOf(SubtitleSyncSpan(10_000L, 16_000L)),
            observedSpans = listOf(SubtitleSyncSpan(10_000L, 22_000L)),
            pcmAvailable = true
        )
        val continuation = SubtitleSpeechSnapshot(
            speechSpans = listOf(SubtitleSyncSpan(23_000L, 42_000L)),
            observedSpans = listOf(SubtitleSyncSpan(22_000L, 70_000L)),
            pcmAvailable = true
        )

        val merged = mergeSubtitleFastAudioProbeSnapshots(seed, continuation)

        assertEquals(60_000L, mergedObservedDuration(merged))
        assertTrue(hasReachedSubtitleFastAudioTarget(merged.observedSpans))
        assertEquals(2, merged.speechSpans.size)
    }

    private fun mergedObservedDuration(snapshot: SubtitleSpeechSnapshot): Long =
        SubtitleAutoSyncEngine.mergeSpans(snapshot.observedSpans, allowedGapMs = 120L)
            .sumOf { it.endMs - it.startMs }
}
