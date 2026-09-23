package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFastAudioProbePolicyTest {
    @Test
    fun `unknown bitrate starts conservatively at four times speed`() {
        val plan = SubtitleFastAudioProbePolicy.plan(
            fileSizeBytes = null,
            durationMs = 0L
        )

        assertEquals(4f, plan.playbackSpeed)
        assertEquals(25_000L, plan.activeDecodeTimeoutMs)
    }

    @Test
    fun `hundred gigabyte long movie starts at two times speed`() {
        val plan = SubtitleFastAudioProbePolicy.plan(
            fileSizeBytes = 102_682_361_361L,
            durationMs = 10_144_224L
        )

        assertEquals(2f, plan.playbackSpeed)
        assertEquals(40_000L, plan.activeDecodeTimeoutMs)
    }

    @Test
    fun `ordinary compressed stream can use eight times speed`() {
        val plan = SubtitleFastAudioProbePolicy.plan(
            fileSizeBytes = 4_000_000_000L,
            durationMs = 7_200_000L
        )

        assertEquals(8f, plan.playbackSpeed)
        assertEquals(20_000L, plan.activeDecodeTimeoutMs)
    }

    @Test
    fun `short scout gets a timeout proportional to its target`() {
        val plan = SubtitleFastAudioProbePolicy.planForSpeed(4f)

        assertEquals(7_000L, SubtitleFastAudioProbePolicy.timeoutForTarget(plan, 12_000L))
    }

    @Test
    fun `short timed out probe backs off and extends active timeout`() {
        val initial = SubtitleFastAudioProbePolicy.planForSpeed(8f)
        val result = SubtitleFastAudioProbeResult(
            snapshot = null,
            decodedStartMs = 0L,
            decodedEndMs = 15_000L,
            termination = SubtitleFastAudioProbeTermination.WALL_TIMEOUT
        )

        val next = SubtitleFastAudioProbePolicy.afterProbe(initial, result)

        assertEquals(4f, next.playbackSpeed)
        assertEquals(25_000L, next.activeDecodeTimeoutMs)
    }

    @Test
    fun `useful timed out probe keeps its speed`() {
        val initial = SubtitleFastAudioProbePolicy.planForSpeed(2f)
        val result = SubtitleFastAudioProbeResult(
            snapshot = SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(SubtitleSyncSpan(0L, 47_000L)),
                pcmAvailable = true
            ),
            decodedStartMs = 0L,
            decodedEndMs = 47_000L,
            termination = SubtitleFastAudioProbeTermination.WALL_TIMEOUT
        )

        assertEquals(initial, SubtitleFastAudioProbePolicy.afterProbe(initial, result))
    }

    @Test
    fun `disconnected pcm range uses observed union when deciding backoff`() {
        val initial = SubtitleFastAudioProbePolicy.planForSpeed(4f)
        val result = SubtitleFastAudioProbeResult(
            snapshot = SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(
                    SubtitleSyncSpan(0L, 5_000L),
                    SubtitleSyncSpan(45_000L, 50_000L)
                ),
                pcmAvailable = true
            ),
            decodedStartMs = 0L,
            decodedEndMs = 50_000L,
            termination = SubtitleFastAudioProbeTermination.WALL_TIMEOUT
        )

        assertEquals(2f, SubtitleFastAudioProbePolicy.afterProbe(initial, result).playbackSpeed)
    }

    @Test
    fun `successful two times probe trials four times with the proven timeout budget`() {
        val initial = SubtitleFastAudioProbePolicy.planForSpeed(2f)
        val result = completedProbe(activeDecodeDurationMs = 30_000L)

        val next = SubtitleFastAudioProbePolicy.afterProbe(initial, result)

        assertEquals(4f, next.playbackSpeed)
        assertEquals(40_000L, next.activeDecodeTimeoutMs)
        assertTrue(next.isUpshiftTrial)
    }

    @Test
    fun `fast successful trial becomes the normal four times plan`() {
        val trial = SubtitleFastAudioProbePolicy.planForSpeed(4f).copy(
            activeDecodeTimeoutMs = 40_000L,
            isUpshiftTrial = true
        )

        val next = SubtitleFastAudioProbePolicy.afterProbe(
            trial,
            completedProbe(activeDecodeDurationMs = 15_000L)
        )

        assertEquals(4f, next.playbackSpeed)
        assertEquals(25_000L, next.activeDecodeTimeoutMs)
        assertFalse(next.isUpshiftTrial)
    }

    @Test
    fun `slow successful trial returns to two times`() {
        val trial = SubtitleFastAudioProbePolicy.planForSpeed(4f).copy(
            activeDecodeTimeoutMs = 40_000L,
            isUpshiftTrial = true
        )

        val next = SubtitleFastAudioProbePolicy.afterProbe(
            trial,
            completedProbe(activeDecodeDurationMs = 30_000L)
        )

        assertEquals(2f, next.playbackSpeed)
        assertFalse(next.isUpshiftTrial)
    }

    @Test
    fun `incomplete speed trial returns to the previous tier`() {
        val trial = SubtitleFastAudioProbePolicy.planForSpeed(4f).copy(
            activeDecodeTimeoutMs = 40_000L,
            isUpshiftTrial = true
        )
        val result = SubtitleFastAudioProbeResult(
            snapshot = SubtitleSpeechSnapshot(
                speechSpans = emptyList(),
                observedSpans = listOf(SubtitleSyncSpan(0L, 20_000L)),
                pcmAvailable = true
            ),
            decodedStartMs = 0L,
            decodedEndMs = 20_000L,
            termination = SubtitleFastAudioProbeTermination.WALL_TIMEOUT,
            activeDecodeDurationMs = 40_000L
        )

        val next = SubtitleFastAudioProbePolicy.afterProbe(trial, result)

        assertEquals(2f, next.playbackSpeed)
        assertFalse(next.isUpshiftTrial)
    }

    private fun completedProbe(activeDecodeDurationMs: Long) = SubtitleFastAudioProbeResult(
        snapshot = SubtitleSpeechSnapshot(
            speechSpans = emptyList(),
            observedSpans = listOf(SubtitleSyncSpan(0L, 60_000L)),
            pcmAvailable = true
        ),
        decodedStartMs = 0L,
        decodedEndMs = 60_000L,
        termination = SubtitleFastAudioProbeTermination.TARGET_REACHED,
        activeDecodeDurationMs = activeDecodeDurationMs
    )
}
