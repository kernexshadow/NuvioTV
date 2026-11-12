package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress

/**
 * The completion threshold a locally saved progress row carries, as the fraction [WatchProgress]
 * reads, when a connected tracker records a watch only from a higher point than the local bar.
 *
 * It only ever raises the bar. Below it the local row and the tracker already agree, and a lower
 * tracker bar is that tracker's own business.
 */
fun localCompletionThresholdOverride(trackerThresholdPercent: Double?): Float? {
    val fraction = trackerThresholdPercent
        ?.takeIf { percent -> percent.isFinite() }
        ?.let { percent -> (percent / 100.0).toFloat() }
        ?: return null
    if (fraction <= WatchProgress.COMPLETED_THRESHOLD) return null
    return fraction.coerceAtMost(1f)
}
