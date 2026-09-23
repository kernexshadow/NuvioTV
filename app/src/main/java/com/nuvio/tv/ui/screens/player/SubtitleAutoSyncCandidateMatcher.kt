package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle
import kotlin.math.abs

internal data class SubtitleAutoSyncCandidateResult(
    val subtitle: Subtitle,
    val result: SubtitleAutoSyncResult
)

/** Pure selection policy for evaluating alternative external subtitles. */
internal object SubtitleAutoSyncCandidateMatcher {
    internal const val MAX_ALTERNATIVES = 8
    internal const val MIN_WINNER_CONFIDENCE = 0.76
    internal const val MIN_CURRENT_CONFIDENCE_LEAD = 0.08
    internal const val MIN_RUNNER_UP_CONFIDENCE_LEAD = 0.05
    private const val CONFIDENCE_EPSILON = 1e-9
    private const val STABLE_NEAR_MISS_OFFSET_TOLERANCE_MS = 2_000
    private const val STABLE_NEAR_MISS_MIN_CONFIDENCE = 0.62
    private const val STABLE_NEAR_MISS_MIN_MARGIN = 0.04
    private const val STABLE_NEAR_MISS_MIN_SIGMA = 3.0
    private const val STABLE_NEAR_MISS_MIN_AGREEMENT = 0.40
    private const val STABLE_NEAR_MISS_MIN_WINDOWS = 6

    /**
     * Keeps alternatives compatible with the selected subtitle language, preserving provider order.
     * Subtitle identity intentionally matches the player's id|url key.
     */
    fun alternatives(
        selected: Subtitle,
        available: List<Subtitle>,
        limit: Int = MAX_ALTERNATIVES
    ): List<Subtitle> {
        if (limit <= 0) return emptyList()

        val selectedKey = subtitleKey(selected)
        return available.asSequence()
            .filter { candidate ->
                subtitleKey(candidate) != selectedKey &&
                    PlayerSubtitleUtils.matchesLanguageCode(candidate.lang, selected.lang)
            }
            .distinctBy(::subtitleKey)
            .take(limit.coerceAtMost(MAX_ALTERNATIVES))
            .toList()
    }

    /** Successful results come first, followed by strongest confidence and evidence. */
    fun rank(results: List<SubtitleAutoSyncCandidateResult>): List<SubtitleAutoSyncCandidateResult> =
        results.sortedWith(
            compareByDescending<SubtitleAutoSyncCandidateResult> { it.result.shouldApply }
                .thenByDescending { it.result.confidence }
                .thenByDescending { it.result.scoreMargin }
                .thenByDescending { it.result.windowAgreement }
                .thenByDescending { it.result.evidenceWindows }
        )

    /**
     * Returns an alternative only when it is safe to apply and clearly beats both the currently
     * selected subtitle (when scored) and every other evaluated alternative.
     */
    fun clearWinner(
        results: List<SubtitleAutoSyncCandidateResult>,
        currentResult: SubtitleAutoSyncResult? = null
    ): SubtitleAutoSyncCandidateResult? {
        val winner = results.asSequence()
            .filter { it.result.shouldApply }
            .maxWithOrNull(
                compareBy<SubtitleAutoSyncCandidateResult> { it.result.confidence }
                    .thenBy { it.result.scoreMargin }
                    .thenBy { it.result.windowAgreement }
                    .thenBy { it.result.evidenceWindows }
            )
            ?: return null

        val winnerConfidence = winner.result.confidence
        if (winnerConfidence < MIN_WINNER_CONFIDENCE) return null

        if (
            currentResult != null &&
            winnerConfidence - currentResult.confidence + CONFIDENCE_EPSILON <
            MIN_CURRENT_CONFIDENCE_LEAD
        ) {
            return null
        }

        // Two different subtitle files can both align correctly, each with its own offset. Their
        // similar confidence corroborates the audio match rather than making the winner ambiguous.
        // Keep the lead requirement only for a close runner that failed the engine's own gates.
        val runnerUpConfidence = results.asSequence()
            .filter { it !== winner && !it.result.shouldApply }
            .maxOfOrNull { it.result.confidence }
        if (
            runnerUpConfidence != null &&
            winnerConfidence - runnerUpConfidence + CONFIDENCE_EPSILON <
            MIN_RUNNER_UP_CONFIDENCE_LEAD
        ) {
            return null
        }

        return winner
    }

    /**
     * Nominates, but never directly applies, an external subtitle whose same offset survived two
     * successive cumulative evidence rounds. Independent targeted probes remain mandatory.
     */
    fun stableNearMiss(
        evaluationRounds: List<List<SubtitleAutoSyncCandidateResult>>,
        excludedTrackKeys: Set<String> = emptySet()
    ): SubtitleAutoSyncCandidateResult? {
        if (evaluationRounds.size < 2) return null
        val previousByKey = evaluationRounds[evaluationRounds.lastIndex - 1]
            .associateBy { subtitleKey(it.subtitle) }

        data class StablePair(
            val latest: SubtitleAutoSyncCandidateResult,
            val previous: SubtitleAutoSyncCandidateResult
        )

        return evaluationRounds.last().asSequence()
            .filter { subtitleKey(it.subtitle) !in excludedTrackKeys }
            .mapNotNull { latest ->
                val previous = previousByKey[subtitleKey(latest.subtitle)] ?: return@mapNotNull null
                if (!isPlausibleNearMiss(latest.result) || !isPlausibleNearMiss(previous.result)) {
                    return@mapNotNull null
                }
                if (
                    abs(latest.result.offsetMs.toLong() - previous.result.offsetMs.toLong()) >
                    STABLE_NEAR_MISS_OFFSET_TOLERANCE_MS
                ) {
                    return@mapNotNull null
                }
                StablePair(latest, previous)
            }
            .maxWithOrNull(
                compareBy<StablePair> {
                    minOf(it.latest.result.confidence, it.previous.result.confidence)
                }
                    .thenBy {
                        (it.latest.result.confidence + it.previous.result.confidence) / 2.0
                    }
                    .thenBy {
                        minOf(it.latest.result.scoreMargin, it.previous.result.scoreMargin)
                    }
                    .thenBy {
                        minOf(it.latest.result.windowAgreement, it.previous.result.windowAgreement)
                    }
            )
            ?.latest
    }

    /** First round preserves fast external matches; the last two establish near-miss stability. */
    fun shouldEvaluateAlternativesAtProbe(probeIndex: Int, probeCount: Int): Boolean {
        if (probeIndex !in 0 until probeCount) return false
        return probeIndex == 0 || probeIndex >= (probeCount - 2).coerceAtLeast(0)
    }

    private fun isPlausibleNearMiss(result: SubtitleAutoSyncResult): Boolean =
        result.rejection == SubtitleAutoSyncRejection.NONE ||
            result.rejection == SubtitleAutoSyncRejection.LOW_CONFIDENCE &&
            result.confidence >= STABLE_NEAR_MISS_MIN_CONFIDENCE &&
            result.scoreMargin >= STABLE_NEAR_MISS_MIN_MARGIN &&
            result.sigma >= STABLE_NEAR_MISS_MIN_SIGMA &&
            result.windowAgreement >= STABLE_NEAR_MISS_MIN_AGREEMENT &&
            result.evidenceWindows >= STABLE_NEAR_MISS_MIN_WINDOWS

    private fun subtitleKey(subtitle: Subtitle): String = "${subtitle.id}|${subtitle.url}"
}
