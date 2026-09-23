package com.nuvio.tv.ui.screens.player

import kotlin.math.abs

internal object SubtitleAutoSyncProgressivePolicy {
    // A +/-8 s search can make an unrelated local maximum look significant. A holdout must
    // recover the nominated offset against distant competitors, not merely refine its vicinity.
    const val VALIDATION_COMPETITOR_RADIUS_MS = 240_000

    /** These are nominations, not lower automatic-acceptance thresholds. */
    fun canNominate(result: SubtitleAutoSyncResult): Boolean {
        if (result.shouldApply) return true
        if (result.rejection != SubtitleAutoSyncRejection.LOW_CONFIDENCE) return false
        if (result.evidenceWindows == 1) {
            return result.confidence >= 0.76 && result.scoreMargin >= 0.05 &&
                result.sigma >= 4.0 && result.windowAgreement >= 1.0
        }
        // Avoid spending new seeks on every mediocre peak during discovery. The existing final
        // rescue policy can still nominate a weaker candidate after the normal samples finish.
        return result.evidenceWindows >= 2 && result.confidence >= 0.62 &&
            result.scoreMargin >= 0.035 && result.sigma >= 2.5 && result.windowAgreement >= 0.4
    }

    fun confirms(candidate: SubtitleAutoSyncResult, result: SubtitleAutoSyncResult): Boolean {
        if (!SubtitleAutoSyncTargetedValidation.confirms(candidate.offsetMs, result)) return false
        // A fifteen-second discovery has less context: require stronger, tighter held-out matches.
        return candidate.evidenceWindows >= 2 ||
            (result.confidence >= 0.60 && result.scoreMargin >= 0.025 && result.sigma >= 2.5 &&
                result.windowAgreement >= 0.5 && abs(candidate.offsetMs.toLong() - result.offsetMs) <= 1_000L)
    }

    /** A failed offset is remembered, not an entire subtitle track. */
    fun wasTried(trackKey: String, offsetMs: Int, attempts: List<Pair<String, Int>>): Boolean =
        attempts.any { (key, offset) -> key == trackKey && abs(offset.toLong() - offsetMs) <= 3_000L }
}
