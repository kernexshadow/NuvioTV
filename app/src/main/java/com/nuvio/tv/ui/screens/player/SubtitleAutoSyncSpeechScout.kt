package com.nuvio.tv.ui.screens.player

internal data class SubtitleAutoSyncSpeechScoutSample(
    val positionMs: Long,
    val result: SubtitleFastAudioProbeResult
)

internal data class SubtitleAutoSyncSpeechScoutQuality(
    val observedMs: Long,
    val speechMs: Long,
    val speechRatioPermille: Int,
    val speechBursts: Int,
    val activityBoundaries: Int,
    val informationScore: Long,
    val dialogueRich: Boolean
)

/** Ranks short audio probes before Auto Sync spends a full probe on a mostly silent scene. */
internal object SubtitleAutoSyncSpeechScout {
    private const val MIN_OBSERVED_MS = 8_000L
    private const val MIN_SPEECH_MS = 2_400L
    private const val MIN_SPEECH_RATIO_PERMILLE = 180
    private const val MAX_SPEECH_RATIO_PERMILLE = 920
    private const val MIN_INFORMATION_SCORE = 2_500L
    private const val BOUNDARY_VALUE_MS = 500L

    fun quality(result: SubtitleFastAudioProbeResult): SubtitleAutoSyncSpeechScoutQuality {
        val observedMs = result.observedDurationMs
        val observedSpans = result.snapshot
            ?.observedSpans
            ?.let { spans -> SubtitleAutoSyncEngine.mergeSpans(spans, allowedGapMs = 120L) }
            .orEmpty()
        val speechSpans = result.snapshot
            ?.speechSpans
            ?.let { spans -> SubtitleAutoSyncEngine.mergeSpans(spans, allowedGapMs = 300L) }
            .orEmpty()
        val speechMs = speechSpans.sumOf { speech ->
            observedSpans.sumOf { observed ->
                (minOf(speech.endMs, observed.endMs) -
                    maxOf(speech.startMs, observed.startMs)).coerceAtLeast(0L)
            }
        }.coerceAtMost(observedMs)
        val speechRatioPermille = if (observedMs > 0L) {
            ((speechMs * 1_000L) / observedMs).toInt()
        } else {
            0
        }
        val activityBoundaries = speechSpans.sumOf { speech ->
            observedSpans.sumOf { observed ->
                var boundaries = 0
                if (speech.startMs > observed.startMs && speech.startMs < observed.endMs) boundaries++
                if (speech.endMs > observed.startMs && speech.endMs < observed.endMs) boundaries++
                boundaries
            }
        }
        val balancedActivityMs = minOf(speechMs, (observedMs - speechMs).coerceAtLeast(0L))
        val informationScore = balancedActivityMs + activityBoundaries * BOUNDARY_VALUE_MS
        return SubtitleAutoSyncSpeechScoutQuality(
            observedMs = observedMs,
            speechMs = speechMs,
            speechRatioPermille = speechRatioPermille,
            speechBursts = speechSpans.size,
            activityBoundaries = activityBoundaries,
            informationScore = informationScore,
            dialogueRich = observedMs >= MIN_OBSERVED_MS &&
                speechMs >= MIN_SPEECH_MS &&
                speechRatioPermille in
                MIN_SPEECH_RATIO_PERMILLE..MAX_SPEECH_RATIO_PERMILLE &&
                informationScore >= MIN_INFORMATION_SCORE
        )
    }

    /**
     * Puts measured dialogue-rich positions first, then unexplored positions, and leaves known
     * low-information positions for last. Original order is preserved inside equal-quality groups.
     */
    fun rankPositions(
        samples: List<SubtitleAutoSyncSpeechScoutSample>,
        originalPositions: List<Long>
    ): List<Long> {
        data class RankedScout(
            val index: Int,
            val sample: SubtitleAutoSyncSpeechScoutSample,
            val quality: SubtitleAutoSyncSpeechScoutQuality
        )

        val rankedScouts = samples
            .withIndex()
            .map { indexed ->
                RankedScout(indexed.index, indexed.value, quality(indexed.value.result))
            }
            .sortedWith(
                compareByDescending<RankedScout> { it.quality.dialogueRich }
                    .thenByDescending { it.quality.informationScore }
                    .thenByDescending { it.quality.activityBoundaries }
                    .thenByDescending { it.quality.speechMs }
                    .thenByDescending { it.quality.observedMs }
                    .thenBy { it.index }
            )
        val richPositions = rankedScouts
            .filter { it.quality.dialogueRich }
            .map { it.sample.positionMs }
        val poorPositions = rankedScouts
            .filterNot { it.quality.dialogueRich }
            .map { it.sample.positionMs }
        val scoutedPositions = samples.mapTo(mutableSetOf()) { it.positionMs }
        val unscoutedPositions = originalPositions.filterNot(scoutedPositions::contains)
        return (richPositions + unscoutedPositions + poorPositions).distinct()
    }
}
