package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoSyncSpeechScoutTest {
    @Test
    fun `dialogue-rich position is tried before a mostly silent position`() {
        val quiet = sample(
            positionMs = 100_000L,
            observedMs = 12_000L,
            speechSpans = listOf(SubtitleSyncSpan(101_000L, 101_500L))
        )
        val dialogue = sample(
            positionMs = 200_000L,
            observedMs = 12_000L,
            speechSpans = listOf(
                SubtitleSyncSpan(201_000L, 203_000L),
                SubtitleSyncSpan(205_000L, 208_000L)
            )
        )

        val ranked = SubtitleAutoSyncSpeechScout.rankPositions(
            samples = listOf(quiet, dialogue),
            originalPositions = listOf(100_000L, 200_000L, 300_000L)
        )

        assertEquals(listOf(200_000L, 300_000L, 100_000L), ranked)
        assertTrue(SubtitleAutoSyncSpeechScout.quality(dialogue.result).dialogueRich)
        assertFalse(SubtitleAutoSyncSpeechScout.quality(quiet.result).dialogueRich)
    }

    @Test
    fun `varied dialogue outranks continuous activity with no useful boundaries`() {
        val continuous = sample(
            positionMs = 100_000L,
            observedMs = 12_000L,
            speechSpans = listOf(SubtitleSyncSpan(100_000L, 112_000L))
        )
        val varied = sample(
            positionMs = 200_000L,
            observedMs = 12_000L,
            speechSpans = listOf(
                SubtitleSyncSpan(200_800L, 203_200L),
                SubtitleSyncSpan(205_000L, 207_400L),
                SubtitleSyncSpan(209_000L, 211_000L)
            )
        )

        val continuousQuality = SubtitleAutoSyncSpeechScout.quality(continuous.result)
        val variedQuality = SubtitleAutoSyncSpeechScout.quality(varied.result)
        val ranked = SubtitleAutoSyncSpeechScout.rankPositions(
            samples = listOf(continuous, varied),
            originalPositions = listOf(100_000L, 200_000L)
        )

        assertFalse(continuousQuality.dialogueRich)
        assertTrue(variedQuality.dialogueRich)
        assertTrue(variedQuality.informationScore > continuousQuality.informationScore)
        assertEquals(listOf(200_000L, 100_000L), ranked)
    }

    @Test
    fun `tiny partial decode is not treated as dialogue-rich`() {
        val partial = sample(
            positionMs = 100_000L,
            observedMs = 2_000L,
            speechSpans = listOf(SubtitleSyncSpan(100_000L, 102_000L))
        )

        val quality = SubtitleAutoSyncSpeechScout.quality(partial.result)

        assertEquals(1_000, quality.speechRatioPermille)
        assertFalse(quality.dialogueRich)
    }

    private fun sample(
        positionMs: Long,
        observedMs: Long,
        speechSpans: List<SubtitleSyncSpan>
    ): SubtitleAutoSyncSpeechScoutSample = SubtitleAutoSyncSpeechScoutSample(
        positionMs = positionMs,
        result = SubtitleFastAudioProbeResult(
            snapshot = SubtitleSpeechSnapshot(
                speechSpans = speechSpans,
                observedSpans = listOf(
                    SubtitleSyncSpan(positionMs, positionMs + observedMs)
                ),
                pcmAvailable = true
            ),
            decodedStartMs = positionMs,
            decodedEndMs = positionMs + observedMs,
            termination = SubtitleFastAudioProbeTermination.TARGET_REACHED
        )
    )
}
