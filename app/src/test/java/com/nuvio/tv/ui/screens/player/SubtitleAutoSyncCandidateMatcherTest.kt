package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoSyncCandidateMatcherTest {
    @Test
    fun `alternatives match language exclude selected deduplicate and cap at eight`() {
        val selected = subtitle(id = "selected", url = "https://subs/selected.srt", lang = "en")
        val duplicateOfSelected = selected.copy(addonName = "Another addon")
        val candidates = (1..10).map { index ->
            subtitle(id = "en-$index", url = "https://subs/$index.srt", lang = "en-US")
        }
        val duplicateCandidate = candidates.first().copy(addonName = "Duplicate addon")
        val wrongLanguage = subtitle(id = "pt", url = "https://subs/pt.srt", lang = "pt")

        val result = SubtitleAutoSyncCandidateMatcher.alternatives(
            selected = selected,
            available = listOf(duplicateOfSelected, wrongLanguage) +
                candidates.take(2) + duplicateCandidate + candidates.drop(2)
        )

        assertEquals(8, result.size)
        assertEquals((1..8).map { "en-$it" }, result.map { it.id })
    }

    @Test
    fun `alternatives preserve exact regional language matching rules`() {
        val selected = subtitle(id = "pt", url = "https://subs/pt.srt", lang = "pt")
        val portugal = subtitle(id = "pt-2", url = "https://subs/pt-2.srt", lang = "por")
        val brazil = subtitle(id = "br", url = "https://subs/br.srt", lang = "pt-BR")

        val result = SubtitleAutoSyncCandidateMatcher.alternatives(
            selected = selected,
            available = listOf(brazil, portugal)
        )

        assertEquals(listOf(portugal), result)
    }

    @Test
    fun `rank puts successful strongest results first`() {
        val failedHighConfidence = candidate("failed", result(confidence = 0.95, success = false))
        val weakerSuccess = candidate("weaker", result(confidence = 0.81, success = true))
        val strongerSuccess = candidate("stronger", result(confidence = 0.88, success = true))

        val ranked = SubtitleAutoSyncCandidateMatcher.rank(
            listOf(failedHighConfidence, weakerSuccess, strongerSuccess)
        )

        assertEquals(listOf("stronger", "weaker", "failed"), ranked.map { it.subtitle.id })
    }

    @Test
    fun `clear winner accepts successful candidate with all required leads`() {
        val winner = candidate("winner", result(confidence = 0.86, success = true))
        val runner = candidate("runner", result(confidence = 0.80, success = true))

        val actual = SubtitleAutoSyncCandidateMatcher.clearWinner(
            results = listOf(runner, winner),
            currentResult = result(confidence = 0.77, success = false)
        )

        assertSame(winner, actual)
    }

    @Test
    fun `clear winner rejects result below minimum confidence or without success`() {
        val belowMinimum = candidate("low", result(confidence = 0.759, success = true))
        val unsuccessful = candidate("failed", result(confidence = 0.96, success = false))

        assertNull(SubtitleAutoSyncCandidateMatcher.clearWinner(listOf(belowMinimum)))
        assertNull(SubtitleAutoSyncCandidateMatcher.clearWinner(listOf(unsuccessful)))
    }

    @Test
    fun `clear winner rejects insufficient lead over current result`() {
        val winner = candidate("winner", result(confidence = 0.86, success = true))

        val actual = SubtitleAutoSyncCandidateMatcher.clearWinner(
            results = listOf(winner),
            currentResult = result(confidence = 0.79, success = false)
        )

        assertNull(actual)
    }

    @Test
    fun `clear winner rejects ambiguous runner even when runner failed`() {
        val winner = candidate("winner", result(confidence = 0.86, success = true))
        val closeFailedRunner = candidate("runner", result(confidence = 0.82, success = false))

        val actual = SubtitleAutoSyncCandidateMatcher.clearWinner(
            results = listOf(winner, closeFailedRunner),
            currentResult = result(confidence = 0.70, success = false)
        )

        assertNull(actual)
    }

    @Test
    fun `clear winner accepts close runner when both subtitle tracks independently align`() {
        val winner = candidate("winner", result(confidence = 0.806, success = true))
        val closeSuccessfulRunner = candidate(
            "runner",
            result(confidence = 0.796, success = true)
        )

        val actual = SubtitleAutoSyncCandidateMatcher.clearWinner(
            results = listOf(winner, closeSuccessfulRunner),
            currentResult = result(confidence = 0.51, success = false)
        )

        assertSame(winner, actual)
    }

    @Test
    fun `clear winner does not require current lead when current result is absent`() {
        val winner = candidate("winner", result(confidence = 0.76, success = true))

        assertSame(winner, SubtitleAutoSyncCandidateMatcher.clearWinner(listOf(winner)))
    }

    @Test
    fun `clear winner accepts exact current and runner lead boundaries`() {
        val winner = candidate("winner", result(confidence = 0.88, success = true))
        val runner = candidate("runner", result(confidence = 0.83, success = true))

        val actual = SubtitleAutoSyncCandidateMatcher.clearWinner(
            results = listOf(runner, winner),
            currentResult = result(confidence = 0.80, success = false)
        )

        assertSame(winner, actual)
    }

    @Test
    fun `stable strong near miss is nominated for independent validation`() {
        val previous = candidate(
            "stable",
            result(
                confidence = 0.721,
                success = false,
                offsetMs = 2_200,
                margin = 0.119,
                sigma = 3.53,
                agreement = 0.50,
                windows = 12
            )
        )
        val latest = candidate(
            "stable",
            result(
                confidence = 0.689,
                success = false,
                offsetMs = 2_200,
                margin = 0.108,
                sigma = 3.16,
                agreement = 0.43,
                windows = 14
            )
        )

        val actual = SubtitleAutoSyncCandidateMatcher.stableNearMiss(
            listOf(listOf(previous), listOf(latest))
        )

        assertSame(latest, actual)
    }

    @Test
    fun `near miss must repeat the same offset and pass quality floors`() {
        val previous = candidate(
            "unstable",
            result(confidence = 0.72, success = false, offsetMs = -196_800)
        )
        val jumped = candidate(
            "unstable",
            result(confidence = 0.72, success = false, offsetMs = 2_200)
        )
        val weakPrevious = candidate(
            "weak",
            result(confidence = 0.61, success = false, offsetMs = 58_600)
        )
        val weakLatest = candidate(
            "weak",
            result(confidence = 0.68, success = false, offsetMs = 58_600)
        )

        assertNull(
            SubtitleAutoSyncCandidateMatcher.stableNearMiss(
                listOf(listOf(previous), listOf(jumped))
            )
        )
        assertNull(
            SubtitleAutoSyncCandidateMatcher.stableNearMiss(
                listOf(listOf(weakPrevious), listOf(weakLatest))
            )
        )
    }

    @Test
    fun `excluded stable winner allows next stable subtitle to be validated`() {
        val firstPrevious = candidate(
            "first",
            result(confidence = 0.72, success = false, offsetMs = 2_200, windows = 12)
        )
        val firstLatest = candidate(
            "first",
            result(confidence = 0.69, success = false, offsetMs = 2_200, windows = 14)
        )
        val secondPrevious = candidate(
            "second",
            result(confidence = 0.68, success = false, offsetMs = 58_600, windows = 12)
        )
        val secondLatest = candidate(
            "second",
            result(confidence = 0.67, success = false, offsetMs = 58_600, windows = 14)
        )

        val actual = SubtitleAutoSyncCandidateMatcher.stableNearMiss(
            evaluationRounds = listOf(
                listOf(firstPrevious, secondPrevious),
                listOf(firstLatest, secondLatest)
            ),
            excludedTrackKeys = setOf("first|https://subs/first.srt")
        )

        assertSame(secondLatest, actual)
    }

    @Test
    fun `alternatives are evaluated on first and final two probes`() {
        assertTrue(SubtitleAutoSyncCandidateMatcher.shouldEvaluateAlternativesAtProbe(0, 5))
        assertFalse(SubtitleAutoSyncCandidateMatcher.shouldEvaluateAlternativesAtProbe(1, 5))
        assertFalse(SubtitleAutoSyncCandidateMatcher.shouldEvaluateAlternativesAtProbe(2, 5))
        assertTrue(SubtitleAutoSyncCandidateMatcher.shouldEvaluateAlternativesAtProbe(3, 5))
        assertTrue(SubtitleAutoSyncCandidateMatcher.shouldEvaluateAlternativesAtProbe(4, 5))
    }

    private fun subtitle(id: String, url: String, lang: String = "en") = Subtitle(
        id = id,
        url = url,
        lang = lang,
        addonName = "Test addon",
        addonLogo = null
    )

    private fun candidate(id: String, result: SubtitleAutoSyncResult) =
        SubtitleAutoSyncCandidateResult(
            subtitle = subtitle(id, "https://subs/$id.srt"),
            result = result
        )

    private fun result(
        confidence: Double,
        success: Boolean,
        offsetMs: Int = 0,
        margin: Double = 0.10,
        sigma: Double = 6.0,
        agreement: Double = 0.80,
        windows: Int = 3
    ) = SubtitleAutoSyncResult(
        offsetMs = offsetMs,
        confidence = confidence,
        scoreMargin = margin,
        sigma = sigma,
        windowAgreement = agreement,
        evidenceWindows = windows,
        rejection = if (success) SubtitleAutoSyncRejection.NONE else SubtitleAutoSyncRejection.LOW_CONFIDENCE
    )
}
