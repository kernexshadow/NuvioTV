package com.nuvio.tv.ui.screens.player

/** Uses decoded PCM (including silence), never speech activity or absolute media timestamps. */
internal class SubtitleProbeProgressWatchdog(
    private val noProgressTimeoutMs: Long = 8_000L
) {
    private var greatestObservedMs = 0L
    private var lastProgressAtMs: Long? = null

    /** [activeElapsedMs] excludes time deliberately spent scoring at a checkpoint. */
    fun isStalled(observedDurationMs: Long, activeElapsedMs: Long): Boolean {
        if (observedDurationMs > greatestObservedMs) {
            greatestObservedMs = observedDurationMs
            lastProgressAtMs = activeElapsedMs
        }
        // Opening/index/decoder startup has its own, longer timeout.
        val lastProgress = lastProgressAtMs ?: return false
        return activeElapsedMs - lastProgress >= noProgressTimeoutMs
    }
}

internal fun shouldRetrySubtitleProbeWithCompatibility(
    useLegacySink: Boolean,
    result: SubtitleFastAudioProbeResult,
    unsupportedTimeline: Boolean
): Boolean = !useLegacySink && !unsupportedTimeline && when (result.termination) {
    SubtitleFastAudioProbeTermination.STALLED,
    SubtitleFastAudioProbeTermination.WALL_TIMEOUT,
    SubtitleFastAudioProbeTermination.ERROR -> true
    SubtitleFastAudioProbeTermination.TARGET_REACHED,
    SubtitleFastAudioProbeTermination.EOF -> false
}

/** Preserve valid partial evidence and continue after it; do not redownload the same sample. */
internal fun subtitleProbeCompatibilityRequest(
    request: SubtitleFastAudioProbeRequest,
    result: SubtitleFastAudioProbeResult
): SubtitleFastAudioProbeRequest = request.copy(
    seedSnapshot = result.snapshot?.takeIf {
        it.pcmAvailable && it.failureReason == null && it.observedSpans.isNotEmpty()
    } ?: request.seedSnapshot
)
