package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class SubtitleSpeechAlignerTest {
    private data class Burst(val startMs: Long, val endMs: Long)

    @Test
    fun `recovers a small delay from one minute of audio`() {
        val speech = dialogue(seed = 1, fromMs = 600_000L, untilMs = 660_000L)
        val result = align(speech, cuesFor(speech, delayMs = 2_300L))

        assertTrue(result.toString(), result is SubtitleSpeechAlignment.Synced)
        assertEquals(2_300.0, (result as SubtitleSpeechAlignment.Synced).offsetMs.toDouble(), 64.0)
        assertEquals(15_000, result.searchRadiusMs)
    }

    @Test
    fun `recovers a large negative delay with the full search`() {
        val speech = dialogue(seed = 2, fromMs = 1_200_000L, untilMs = 1_320_000L)
        val result = align(speech, cuesFor(speech, delayMs = -47_000L, extraBeforeMs = 60_000L))

        assertTrue(result.toString(), result is SubtitleSpeechAlignment.Synced)
        assertEquals(-47_000.0, (result as SubtitleSpeechAlignment.Synced).offsetMs.toDouble(), 64.0)
        assertEquals(90_000, result.searchRadiusMs)
    }

    @Test
    fun `unrelated subtitle is not synced`() {
        val speech = dialogue(seed = 3, fromMs = 300_000L, untilMs = 420_000L)
        val unrelated = dialogue(seed = 99, fromMs = 200_000L, untilMs = 520_000L)
        val result = align(speech, cuesFor(unrelated, delayMs = 0L))

        assertFalse(result.toString(), result is SubtitleSpeechAlignment.Synced)
    }

    @Test
    fun `too little audio is reported instead of guessed`() {
        val speech = dialogue(seed = 4, fromMs = 60_000L, untilMs = 70_000L)
        val result = align(speech, cuesFor(dialogue(4, 0L, 200_000L), delayMs = 0L))

        assertTrue(result.toString(), result is SubtitleSpeechAlignment.NotEnoughAudio)
    }

    @Test
    fun `frame rate mismatch is reported as drift and never synced`() {
        val scale = 25.0 / 23.976
        val speech = dialogue(seed = 5, fromMs = 1_800_000L, untilMs = 2_000_000L)
        // The file was timed for another frame rate: media = file * scale.
        val cues = speech.bursts.map { burst ->
            SubtitleSyncCue((burst.startMs / scale).toLong(), (burst.endMs / scale).toLong(), text(burst))
        }
        val result = align(speech, cues)

        assertTrue(result.toString(), result is SubtitleSpeechAlignment.Drift)
        assertEquals(scale, (result as SubtitleSpeechAlignment.Drift).scale, 1e-9)
    }

    @Test
    fun `music cues are excluded from the dialogue profile`() {
        val cues = listOf(
            SubtitleSyncCue(1_000L, 3_000L, "Where were you last night?"),
            SubtitleSyncCue(4_000L, 6_000L, "♪ la la la, singing along ♪")
        )

        assertEquals(1, SubtitleSpeechAligner.prepareCues(cues).size)
    }

    private class Speech(val segment: SubtitleSpeechFeatureSegment, val bursts: List<Burst>)

    private fun align(speech: Speech, cues: List<SubtitleSyncCue>) =
        SubtitleSpeechAligner.align(listOf(speech.segment), SubtitleSpeechAligner.prepareCues(cues))

    /** Alternating speech bursts (modulated speech-band energy) and near-silence. */
    private fun dialogue(seed: Int, fromMs: Long, untilMs: Long): Speech {
        val random = Random(seed)
        val bursts = mutableListOf<Burst>()
        var t = fromMs + random.nextLong(200L, 1_500L)
        while (t < untilMs) {
            val end = (t + random.nextLong(800L, 3_200L)).coerceAtMost(untilMs)
            bursts += Burst(t, end)
            t = end + random.nextLong(400L, 3_000L)
        }
        val frameUs = 32_000.0
        val frames = ((untilMs - fromMs) * 1_000L / frameUs).toInt()
        val band = FloatArray(frames)
        val broadband = FloatArray(frames)
        var burstIndex = 0
        for (frame in 0 until frames) {
            val timeMs = fromMs + (frame * frameUs / 1_000.0).toLong()
            while (burstIndex < bursts.size && bursts[burstIndex].endMs <= timeMs) burstIndex++
            val speaking = burstIndex < bursts.size && bursts[burstIndex].startMs <= timeMs
            val level = if (speaking) {
                0.08 * (0.35 + 0.65 * abs(sin(frame * 0.9))) * (0.8 + 0.4 * random.nextDouble())
            } else {
                0.002 * (0.8 + 0.4 * random.nextDouble())
            }
            band[frame] = level.toFloat()
            broadband[frame] = (level * 1.3 + 0.001).toFloat()
        }
        return Speech(SubtitleSpeechFeatureSegment(fromMs * 1_000L, frameUs, band, broadband), bursts)
    }

    /** A cue per burst, timed in the file so that `file + delayMs = media`. */
    private fun cuesFor(speech: Speech, delayMs: Long, extraBeforeMs: Long = 0L): List<SubtitleSyncCue> {
        val cues = speech.bursts.map { burst ->
            SubtitleSyncCue(burst.startMs - delayMs, burst.endMs - delayMs + 600L, text(burst))
        }
        if (extraBeforeMs <= 0L) return cues
        // Dialogue outside the heard audio, as in a real file.
        val earlier = dialogue(seed = 77, fromMs = cues.first().startTimeMs - extraBeforeMs,
            untilMs = cues.first().startTimeMs - 1_000L).bursts
            .map { SubtitleSyncCue(it.startMs, it.endMs + 600L, text(it)) }
        return earlier + cues
    }

    private fun text(burst: Burst): String = "w".repeat(((burst.endMs - burst.startMs) / 70L).toInt().coerceAtLeast(2))
}
