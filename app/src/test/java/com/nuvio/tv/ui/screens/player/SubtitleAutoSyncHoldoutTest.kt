package com.nuvio.tv.ui.screens.player

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

/** Timing-only regression fixtures; these are not estimates of real audio/VAD accuracy. */
class SubtitleAutoSyncHoldoutTest {
    @Test fun `unrelated tracks cannot self confirm inside a narrow offset neighbourhood`() {
        var nominated = 0
        repeat(32) { seed ->
            val audio = cues(seed)
            val wrong = cues(seed + 100)
            val discovery = SubtitleAutoSyncEngine.findBestOffset(wrong, clip(audio, 600_000L, 30_000L))
            if (SubtitleAutoSyncProgressivePolicy.canNominate(discovery)) {
                nominated++
                assertNull("Unrelated candidate passed holdouts: seed=$seed, $discovery",
                    confirm(discovery, wrong, audio))
            }
        }
        // Exercise real nominated false peaks, not only candidates rejected before validation.
        assertTrue(nominated >= 10)
    }

    @Test fun `true fifty second offsets still survive independently searched holdouts`() {
        repeat(8) { seed ->
            val truth = cues(seed)
            val discovery = SubtitleAutoSyncEngine.findBestOffset(truth, clip(truth, 600_000L, 30_000L))
            assertTrue(SubtitleAutoSyncProgressivePolicy.canNominate(discovery))
            val confirmed = confirm(discovery, truth, truth)
            assertNotNull("True candidate lost: seed=$seed, $discovery", confirmed)
            assertTrue(abs(confirmed!!.offsetMs - 50_000) <= 200)
        }
    }

    private fun confirm(
        candidate: SubtitleAutoSyncResult,
        subtitles: List<SubtitleSyncCue>,
        audio: List<SubtitleSyncCue>
    ): SubtitleAutoSyncResult? {
        val positions = planSubtitleAutoSyncValidationPositions(subtitles, candidate.offsetMs,
            7_200_000L, listOf(SubtitleSyncSpan(600_000L, 660_000L)))
        if (positions.size != 2) return null
        val results = mutableListOf<SubtitleAutoSyncResult>()
        val profile = SubtitleAutoSyncEngine.prepareCues(subtitles)
        for (position in positions) {
            var confirmation: SubtitleAutoSyncResult? = null
            for (duration in listOf(30_000L, 45_000L, 60_000L)) {
                val result = SubtitleAutoSyncEngine.findBestOffset(profile,
                    clip(audio, (position - 5_000L).coerceIn(0L, 7_140_000L), duration),
                    candidate.offsetMs - SubtitleAutoSyncProgressivePolicy.VALIDATION_COMPETITOR_RADIUS_MS,
                    candidate.offsetMs + SubtitleAutoSyncProgressivePolicy.VALIDATION_COMPETITOR_RADIUS_MS)
                if (SubtitleAutoSyncProgressivePolicy.confirms(candidate, result)) {
                    confirmation = result
                    break
                }
            }
            results += confirmation ?: return null
        }
        return SubtitleAutoSyncTargetedValidation.confirmedResult(candidate, results)
    }

    private fun cues(seed: Int): List<SubtitleSyncCue> {
        val random = Random(seed.toLong())
        return buildList {
            var time = 0L
            while (time < 7_200_000L) {
                time += 300 + random.nextInt(3_600)
                val end = time + 500 + random.nextInt(3_700)
                add(SubtitleSyncCue(time, end, "Spoken dialogue line"))
                time = end
            }
        }
    }

    private fun clip(cues: List<SubtitleSyncCue>, start: Long, duration: Long) = SubtitleSpeechSnapshot(
        cues.mapNotNull { cue ->
            val a = maxOf(start, cue.startTimeMs + 50_000L)
            val b = minOf(start + duration, cue.endTimeMs + 50_000L)
            if (b > a) SubtitleSyncSpan(a, b) else null
        }, listOf(SubtitleSyncSpan(start, start + duration)), true)
}
