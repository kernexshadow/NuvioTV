package com.nuvio.tv.ui.screens.player

import kotlin.math.ceil

internal data class SubtitleFastAudioProbePlan(
    val playbackSpeed: Float,
    val activeDecodeTimeoutMs: Long,
    val estimatedFileBitrateBps: Int?,
    val isUpshiftTrial: Boolean = false
)

/** Keeps fast probes bounded without asking a high-bitrate remux to stream at an unrealistic 8x. */
internal object SubtitleFastAudioProbePolicy {
    private const val TARGET_AUDIO_MS = 60_000L
    private const val MIN_ACTIVE_TIMEOUT_MS = 20_000L
    private const val ACTIVE_TIMEOUT_GRACE_MS = 10_000L
    private const val MIN_SHORT_PROBE_TIMEOUT_MS = 6_000L
    private const val SHORT_PROBE_GRACE_MS = 4_000L

    private const val EIGHT_X_MAX_BITRATE_BPS = 15_000_000
    private const val FOUR_X_MAX_BITRATE_BPS = 35_000_000
    private const val TWO_X_MAX_BITRATE_BPS = 120_000_000

    fun plan(fileSizeBytes: Long?, durationMs: Long): SubtitleFastAudioProbePlan {
        val bitrateBps = PlayerBitrateEstimator.fileBitrateBps(fileSizeBytes, durationMs)
        val speed = when {
            // Unknown size/throughput is the least safe case for 8x. Start at 4x and let observed
            // decode progress decide whether a slower tier is needed.
            bitrateBps == null -> 4f
            bitrateBps <= EIGHT_X_MAX_BITRATE_BPS -> 8f
            bitrateBps <= FOUR_X_MAX_BITRATE_BPS -> 4f
            bitrateBps <= TWO_X_MAX_BITRATE_BPS -> 2f
            else -> 1f
        }
        return planForSpeed(speed, bitrateBps)
    }

    fun planForSpeed(
        playbackSpeed: Float,
        estimatedFileBitrateBps: Int? = null
    ): SubtitleFastAudioProbePlan {
        val speed = playbackSpeed.takeIf { it == 1f || it == 2f || it == 4f || it == 8f } ?: 1f
        val targetWallMs = ceil(TARGET_AUDIO_MS / speed.toDouble()).toLong()
        return SubtitleFastAudioProbePlan(
            playbackSpeed = speed,
            activeDecodeTimeoutMs = (targetWallMs + ACTIVE_TIMEOUT_GRACE_MS)
                .coerceAtLeast(MIN_ACTIVE_TIMEOUT_MS),
            estimatedFileBitrateBps = estimatedFileBitrateBps
        )
    }

    /**
     * Gives dialogue scouts a timeout proportional to their much smaller PCM target. Keeping the
     * regular 20-40 second timeout would make a failed 12-second scout as expensive as a full
     * discovery probe.
     */
    fun timeoutForTarget(
        plan: SubtitleFastAudioProbePlan,
        targetAudioMs: Long
    ): Long {
        val targetWallMs = ceil(
            targetAudioMs.coerceAtLeast(1L) / plan.playbackSpeed.coerceAtLeast(1f).toDouble()
        ).toLong()
        return (targetWallMs + SHORT_PROBE_GRACE_MS)
            .coerceAtLeast(MIN_SHORT_PROBE_TIMEOUT_MS)
            .coerceAtMost(plan.activeDecodeTimeoutMs)
    }

    /** Back off one tier when a probe timed out before decoding even half of its audio target. */
    fun afterProbe(
        current: SubtitleFastAudioProbePlan,
        result: SubtitleFastAudioProbeResult
    ): SubtitleFastAudioProbePlan {
        if (current.isUpshiftTrial) {
            val normalAtTrialSpeed = planForSpeed(
                playbackSpeed = current.playbackSpeed,
                estimatedFileBitrateBps = current.estimatedFileBitrateBps
            )
            val trialSucceededWithinNormalBudget =
                result.termination == SubtitleFastAudioProbeTermination.TARGET_REACHED &&
                    result.observedDurationMs >= TARGET_AUDIO_MS &&
                    result.activeDecodeDurationMs in
                    1L..normalAtTrialSpeed.activeDecodeTimeoutMs
            return if (trialSucceededWithinNormalBudget) {
                normalAtTrialSpeed
            } else {
                planForSpeed(
                    playbackSpeed = current.playbackSpeed / 2f,
                    estimatedFileBitrateBps = current.estimatedFileBitrateBps
                )
            }
        }

        if (
            result.termination == SubtitleFastAudioProbeTermination.WALL_TIMEOUT &&
            result.observedDurationMs < TARGET_AUDIO_MS / 2L &&
            current.playbackSpeed > 1f
        ) {
            return planForSpeed(
                playbackSpeed = current.playbackSpeed / 2f,
                estimatedFileBitrateBps = current.estimatedFileBitrateBps
            )
        }

        val canTryFaster =
            result.termination == SubtitleFastAudioProbeTermination.TARGET_REACHED &&
                result.observedDurationMs >= TARGET_AUDIO_MS &&
                result.activeDecodeDurationMs in 1L..current.activeDecodeTimeoutMs &&
                current.playbackSpeed < 4f
        if (!canTryFaster) return current

        val faster = planForSpeed(
            playbackSpeed = current.playbackSpeed * 2f,
            estimatedFileBitrateBps = current.estimatedFileBitrateBps
        )
        return faster.copy(
            // A trial gets the previous tier's time budget. If the source cannot go faster it can
            // still deliver the same 60 seconds before we fall back on the following probe.
            activeDecodeTimeoutMs = maxOf(
                faster.activeDecodeTimeoutMs,
                current.activeDecodeTimeoutMs
            ),
            isUpshiftTrial = true
        )
    }
}
