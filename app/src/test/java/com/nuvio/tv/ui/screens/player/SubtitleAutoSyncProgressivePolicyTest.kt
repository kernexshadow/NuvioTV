package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class SubtitleAutoSyncProgressivePolicyTest {
    private val strongShort = SubtitleAutoSyncResult(50_000, 0.81, 0.09, 5.0, 1.0, 1,
        SubtitleAutoSyncRejection.LOW_CONFIDENCE)

    @Test fun `short sample is nomination only with strict evidence requirements`() {
        assertTrue(SubtitleAutoSyncProgressivePolicy.canNominate(strongShort))
        assertFalse(strongShort.shouldApply)
        assertFalse(SubtitleAutoSyncProgressivePolicy.canNominate(strongShort.copy(scoreMargin = 0.01)))
        assertFalse(SubtitleAutoSyncProgressivePolicy.canNominate(strongShort.copy(confidence = 0.50)))
        assertFalse(SubtitleAutoSyncProgressivePolicy.canNominate(strongShort.copy(sigma = 2.0)))
    }

    @Test fun `short candidate needs longer held out matches and tighter timing`() {
        val confirmation = strongShort.copy(evidenceWindows = 2)
        assertFalse(SubtitleAutoSyncProgressivePolicy.confirms(strongShort, strongShort))
        assertTrue(SubtitleAutoSyncProgressivePolicy.confirms(strongShort, confirmation))
        assertFalse(SubtitleAutoSyncProgressivePolicy.confirms(strongShort, confirmation.copy(offsetMs = 52_000)))
        assertFalse(SubtitleAutoSyncProgressivePolicy.confirms(strongShort, confirmation.copy(confidence = 0.50)))
        assertNull(SubtitleAutoSyncTargetedValidation.confirmedResult(strongShort, listOf(confirmation)))
    }

    @Test fun `failed offset does not blacklist a different offset in the same subtitle`() {
        val attempts = listOf("selected" to 50_000)
        assertTrue(SubtitleAutoSyncProgressivePolicy.wasTried("selected", 50_300, attempts))
        assertFalse(SubtitleAutoSyncProgressivePolicy.wasTried("selected", -50_000, attempts))
        assertFalse(SubtitleAutoSyncProgressivePolicy.wasTried("alternative", 50_000, attempts))
    }

    @Test fun `mediocre longer sample does not start an early validation seek`() {
        assertFalse(SubtitleAutoSyncProgressivePolicy.canNominate(
            strongShort.copy(evidenceWindows = 4, confidence = 0.50)))
        assertTrue(SubtitleAutoSyncTargetedValidation.shouldStart(
            strongShort.copy(evidenceWindows = 4, confidence = 0.50), isLastStandardProbe = true))
    }
}
