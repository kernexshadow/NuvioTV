/*
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Derived from Debrify's subtitle auto-sync (SubtitleAligner.kt and SpeechFeatureTap.kt),
 * https://github.com/varunsalian/debrify, copyright the Debrify contributors, licensed under the
 * GNU Affero General Public License v3.0. Reimplemented for NuvioTV; the approach and tuning
 * constants follow the original.
 *
 * Unlike the rest of NuvioTV (GPL-3.0), this file is licensed under the GNU AGPL v3.0.
 * Section 13 of both licenses permits combining the two in one program.
 */
package com.nuvio.tv.ui.screens.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of aligning a subtitle file with speech the player has already played. */
internal sealed class SubtitleSpeechAlignment {
    abstract val analyzedMs: Long
    abstract val usableCues: Int

    /** Constant delay to apply: a cue at file time t is shown at `t + offsetMs`. */
    data class Synced(
        val offsetMs: Int,
        val zScore: Double,
        val peakToSidelobe: Double,
        /** Heuristic 0.5-1.0 display value for passing matches, not a probability. */
        val confidence: Double,
        val searchRadiusMs: Int,
        override val analyzedMs: Long,
        override val usableCues: Int
    ) : SubtitleSpeechAlignment()

    /** A frame-rate-scaled timeline won clearly; a constant delay cannot fix it. Never applied. */
    data class Drift(
        val scale: Double,
        val offsetMs: Int,
        val zScore: Double,
        val peakToSidelobe: Double,
        override val analyzedMs: Long,
        override val usableCues: Int
    ) : SubtitleSpeechAlignment()

    /** Enough audio, but no peak passed the gates. The closest candidate is kept for logs. */
    data class NoMatch(
        val bestOffsetMs: Int?,
        val bestZScore: Double,
        val bestPeakToSidelobe: Double,
        override val analyzedMs: Long,
        override val usableCues: Int
    ) : SubtitleSpeechAlignment()

    /** Too little captured audio, or too few cues inside it, to judge any offset. */
    data class NotEnoughAudio(
        val requiredMs: Long,
        override val analyzedMs: Long,
        override val usableCues: Int
    ) : SubtitleSpeechAlignment()
}

/**
 * Cross-correlates a subtitle's speech schedule with a speech score computed from
 * [SubtitleSpeechFeatureTap] features, for every candidate delay at once via FFT.
 *
 * Every lag gets a z-like statistic: the mean centred speech score while cues are shown at that
 * lag, in standard errors. A result is only returned as [SubtitleSpeechAlignment.Synced] when the
 * peak is both tall (z) and isolated from the rest of the lag landscape (peak-to-sidelobe ratio).
 */
internal object SubtitleSpeechAligner {
    const val GRID_MS = 32.0

    /** Bounds FFT size and cost; twenty minutes of speech is far more than a match needs. */
    private const val MAX_ANALYSIS_SPAN_MS = 20 * 60_000.0
    private const val MAX_SANE_START_MS = 100L * 3_600_000L
    private const val MIN_SEGMENT_MS = 2_000.0
    private const val PEAK_EXCLUSION_MS = 2_000.0
    private const val MIN_BACKGROUND_LAGS = 50

    /** A scaled timeline must beat a plain delay by this factor; the simpler model wins ties. */
    private const val DRIFT_PARSIMONY = 1.10

    /** Excludes music/lyrics cues, which the speech score deliberately rates low. */
    private const val MIN_DIALOGUE_WEIGHT = 0.6

    /** Cues stay on screen for reading time; cap them at an estimate of the spoken duration. */
    private const val SPEECH_MS_PER_CHAR = 70.0
    private const val MIN_SPEECH_SPAN_MS = 700.0
    private const val MAX_SPEECH_SPAN_MS = 7_000.0

    // Speech score, in natural-log energy units.
    private const val ACTIVITY_MARGIN = 0.35
    private const val ACTIVITY_RANGE = 1.2
    private const val NOISE_FLOOR_CHUNK_MS = 5_000.0
    private const val NOISE_FLOOR_PERCENTILE = 0.10
    /** Speech modulates energy at syllable rate; sustained music and effects do not. */
    private const val MODULATION_WINDOW_MS = 1_000.0
    private const val MODULATION_LOW = 0.15
    private const val MODULATION_RANGE = 0.45
    private const val MODULATION_MIN_WEIGHT = 0.15
    private const val BAND_SHARE_LOW = 0.25
    private const val BAND_SHARE_RANGE = 0.5
    private const val BAND_SHARE_MIN_WEIGHT = 0.2

    /** Common frame-rate conversions between releases. */
    private val FRAME_RATE_SCALES = doubleArrayOf(
        1.0,
        25.0 / 23.976, 23.976 / 25.0,
        25.0 / 24.0, 24.0 / 25.0,
        24.0 / 23.976, 23.976 / 24.0
    )

    internal class Tier(
        val searchMs: Double,
        val minAudioMs: Double,
        val minCueOverlapMs: Double,
        val minCues: Int,
        val minZScore: Double,
        val minPeakToSidelobe: Double,
        val scales: DoubleArray
    )

    /**
     * Most real offsets are a few seconds. A +/-15 s search has far fewer lags, so a much taller
     * z-score is reachable honestly with 20 s of audio; its short window depresses the sidelobe
     * ratio (dialogue repeats every few seconds), so that gate is lower.
     */
    internal val NARROW = Tier(
        searchMs = 15_000.0, minAudioMs = 20_000.0, minCueOverlapMs = 10_000.0, minCues = 8,
        minZScore = 8.0, minPeakToSidelobe = 2.5, scales = doubleArrayOf(1.0)
    )
    internal val FULL = Tier(
        searchMs = 90_000.0, minAudioMs = 45_000.0, minCueOverlapMs = 30_000.0, minCues = 20,
        minZScore = 4.0, minPeakToSidelobe = 5.0, scales = FRAME_RATE_SCALES
    )

    internal class DialogueCue(val startMs: Long, val endMs: Long, val speechCapMs: Double)

    fun prepareCues(cues: List<SubtitleSyncCue>): List<DialogueCue> =
        SubtitleAutoSyncCueProfile.features(cues)
            .filter { it.weight >= MIN_DIALOGUE_WEIGHT }
            .map { cue ->
                DialogueCue(
                    startMs = cue.startMs,
                    endMs = cue.endMs,
                    speechCapMs = (cue.normalizedText.length * SPEECH_MS_PER_CHAR)
                        .coerceIn(MIN_SPEECH_SPAN_MS, MAX_SPEECH_SPAN_MS)
                )
            }

    /** Narrow search first; the full search (with frame-rate check) only if it finds nothing. */
    fun align(
        segments: List<SubtitleSpeechFeatureSegment>,
        cues: List<DialogueCue>
    ): SubtitleSpeechAlignment {
        val narrow = align(segments, cues, NARROW)
        if (narrow is SubtitleSpeechAlignment.Synced) return narrow
        return align(segments, cues, FULL)
    }

    fun align(
        segments: List<SubtitleSpeechFeatureSegment>,
        cues: List<DialogueCue>,
        tier: Tier
    ): SubtitleSpeechAlignment {
        // Timestamps outside a plausible media range are a clock-domain error, never evidence.
        val usable = segments.filter {
            it.startUs / 1_000L in -60_000L..MAX_SANE_START_MS && it.durationMs >= MIN_SEGMENT_MS
        }
        val analyzedMs = usable.sumOf { it.durationMs }.toLong()
        fun notEnough() = SubtitleSpeechAlignment.NotEnoughAudio(tier.minAudioMs.toLong(), analyzedMs, cues.size)
        if (usable.isEmpty() || analyzedMs < tier.minAudioMs || cues.size < tier.minCues) return notEnough()

        // Keep the most recently captured audio that fits in one analysis span: it is what the
        // viewer is hearing now, and an old peek elsewhere must not evict it.
        val picked = ArrayList<SubtitleSpeechFeatureSegment>()
        var spanStartMs = Double.POSITIVE_INFINITY
        var spanEndMs = Double.NEGATIVE_INFINITY
        for (segment in usable.asReversed()) {
            val start = min(spanStartMs, segment.startMs)
            val end = max(spanEndMs, segment.startMs + segment.durationMs)
            if (end - start > MAX_ANALYSIS_SPAN_MS) continue
            picked += segment
            spanStartMs = start
            spanEndMs = end
        }
        val gridCount = ((spanEndMs - spanStartMs) / GRID_MS).toInt() + 1
        val score = DoubleArray(gridCount)
        val mask = DoubleArray(gridCount)
        // Oldest capture first, so a re-watched span keeps its newest features.
        for (segment in picked.asReversed()) {
            val speech = speechScore(segment)
            val frameMs = segment.frameDurationUs / 1_000.0
            for (frame in speech.indices) {
                val grid = ((segment.startMs + frame * frameMs - spanStartMs) / GRID_MS).roundToInt()
                if (grid in 0 until gridCount) {
                    score[grid] = speech[frame]
                    mask[grid] = 1.0
                }
            }
        }

        val observedGrids = mask.sum()
        if (observedGrids * GRID_MS < tier.minAudioMs) return notEnough()
        var mean = 0.0
        for (grid in 0 until gridCount) mean += score[grid] * mask[grid]
        mean /= observedGrids
        var variance = 0.0
        for (grid in 0 until gridCount) {
            if (mask[grid] > 0.0) variance += (score[grid] - mean) * (score[grid] - mean)
        }
        val sigma = sqrt(variance / observedGrids)
        if (sigma < 1e-4) return notEnough() // silence or a flat signal

        val lagRadius = (tier.searchMs / GRID_MS).toInt()
        val extendedCount = gridCount + 2 * lagRadius
        val fft = Fft(nextPowerOfTwo(extendedCount))
        val centred = fft.forward(DoubleArray(gridCount) { (score[it] - mean) * mask[it] })
        val coverage = fft.forward(mask)
        val minOverlapGrids = tier.minCueOverlapMs / GRID_MS

        var best: Peak? = null
        var bestUnscaled: Peak? = null
        for (scale in tier.scales) {
            val cueRaster = rasterizeCues(cues, scale, spanStartMs, extendedCount, lagRadius)
            if (cueRaster == null) continue
            val cueSpectrum = fft.forward(cueRaster)
            val speechDuringCues = fft.correlate(centred, cueSpectrum)
            val cueCoverage = fft.correlate(coverage, cueSpectrum)
            val peak = findPeak(speechDuringCues, cueCoverage, sigma, lagRadius, minOverlapGrids, scale)
                ?: continue
            if (scale == 1.0) bestUnscaled = peak
            if (best == null || peak.zScore > best.zScore) best = peak
        }
        val chosen = best ?: return notEnough()
        val effective = if (
            chosen.scale != 1.0 && bestUnscaled != null &&
            bestUnscaled.zScore * DRIFT_PARSIMONY >= chosen.zScore
        ) bestUnscaled else chosen

        val offsetMs = (effective.lagGrids * GRID_MS).roundToInt()
        if (effective.zScore < tier.minZScore || effective.peakToSidelobe < tier.minPeakToSidelobe) {
            return SubtitleSpeechAlignment.NoMatch(
                offsetMs, effective.zScore, effective.peakToSidelobe, analyzedMs, cues.size
            )
        }
        if (effective.scale != 1.0) {
            return SubtitleSpeechAlignment.Drift(
                effective.scale, offsetMs, effective.zScore, effective.peakToSidelobe, analyzedMs, cues.size
            )
        }
        val confidence = 0.25 * min(effective.zScore / tier.minZScore, 2.0) +
            0.25 * min(effective.peakToSidelobe / tier.minPeakToSidelobe, 2.0)
        return SubtitleSpeechAlignment.Synced(
            offsetMs = offsetMs,
            zScore = effective.zScore,
            peakToSidelobe = effective.peakToSidelobe,
            confidence = confidence,
            searchRadiusMs = tier.searchMs.toInt(),
            analyzedMs = analyzedMs,
            usableCues = cues.size
        )
    }

    /** Per-frame speech likelihood in [0, 1]: level above the noise floor, modulation, band share. */
    internal fun speechScore(segment: SubtitleSpeechFeatureSegment): DoubleArray {
        val count = segment.frameCount
        if (count == 0) return DoubleArray(0)
        val frameMs = segment.frameDurationUs / 1_000.0
        val logEnergy = DoubleArray(count) { ln(segment.band[it] + 1e-6) }

        // Rolling noise floor: a low percentile per chunk, interpolated between chunk centres.
        val chunk = max(1, (NOISE_FLOOR_CHUNK_MS / frameMs).toInt())
        val chunkCount = (count + chunk - 1) / chunk
        val floors = DoubleArray(chunkCount)
        val scratch = DoubleArray(chunk)
        for (index in 0 until chunkCount) {
            val from = index * chunk
            val length = min(chunk, count - from)
            System.arraycopy(logEnergy, from, scratch, 0, length)
            java.util.Arrays.sort(scratch, 0, length)
            floors[index] = scratch[(length * NOISE_FLOOR_PERCENTILE).toInt().coerceAtMost(length - 1)]
        }

        val window = max(3, (MODULATION_WINDOW_MS / frameMs).toInt()) or 1
        val half = window / 2
        val sum = DoubleArray(count + 1)
        val sumSquares = DoubleArray(count + 1)
        for (index in 0 until count) {
            sum[index + 1] = sum[index] + logEnergy[index]
            sumSquares[index + 1] = sumSquares[index] + logEnergy[index] * logEnergy[index]
        }

        return DoubleArray(count) { index ->
            val floor = if (chunkCount == 1) floors[0] else {
                val position = index.toDouble() / chunk - 0.5
                val lower = position.toInt().coerceIn(0, chunkCount - 1)
                val upper = (lower + 1).coerceAtMost(chunkCount - 1)
                val fraction = (position - lower).coerceIn(0.0, 1.0)
                floors[lower] * (1.0 - fraction) + floors[upper] * fraction
            }
            val activity = ((logEnergy[index] - floor - ACTIVITY_MARGIN) / ACTIVITY_RANGE).coerceIn(0.0, 1.0)
            val from = max(0, index - half)
            val until = min(count, index + half + 1)
            val n = (until - from).toDouble()
            val localMean = (sum[until] - sum[from]) / n
            val deviation = sqrt(max(0.0, (sumSquares[until] - sumSquares[from]) / n - localMean * localMean))
            val modulation = ((deviation - MODULATION_LOW) / MODULATION_RANGE).coerceIn(MODULATION_MIN_WEIGHT, 1.0)
            val bandShare = segment.band[index] / (segment.broadband[index] + 1e-6)
            val bandWeight = ((bandShare - BAND_SHARE_LOW) / BAND_SHARE_RANGE).coerceIn(BAND_SHARE_MIN_WEIGHT, 1.0)
            activity * modulation * bandWeight
        }
    }

    /**
     * Index j of the raster represents grid (j - lagRadius) from [spanStartMs], so every lag in
     * +/-lagRadius stays inside the array. Returns null when no cue falls inside it.
     */
    private fun rasterizeCues(
        cues: List<DialogueCue>,
        scale: Double,
        spanStartMs: Double,
        extendedCount: Int,
        lagRadius: Int
    ): DoubleArray? {
        val raster = DoubleArray(extendedCount)
        var any = false
        for (cue in cues) {
            val startMs = cue.startMs * scale
            val endMs = startMs + min((cue.endMs - cue.startMs) * scale, cue.speechCapMs)
            val first = (((startMs - spanStartMs) / GRID_MS).toInt() + lagRadius).coerceAtLeast(0)
            val last = (((endMs - spanStartMs) / GRID_MS).toInt() + lagRadius).coerceAtMost(extendedCount - 1)
            for (index in first..last) {
                raster[index] = 1.0
                any = true
            }
        }
        return raster.takeIf { any }
    }

    private class Peak(val lagGrids: Double, val zScore: Double, val peakToSidelobe: Double, val scale: Double)

    /** Correlation index d corresponds to lag (lagRadius - d): media time = cue time + lag. */
    private fun findPeak(
        speechDuringCues: DoubleArray,
        cueCoverage: DoubleArray,
        sigma: Double,
        lagRadius: Int,
        minOverlapGrids: Double,
        scale: Double
    ): Peak? {
        val lagCount = 2 * lagRadius + 1
        val z = DoubleArray(lagCount) { Double.NaN }
        var bestIndex = -1
        for (index in 0 until lagCount) {
            val overlap = cueCoverage[index]
            if (overlap < minOverlapGrids) continue
            z[index] = speechDuringCues[index] / (sigma * sqrt(overlap))
            if (bestIndex < 0 || z[index] > z[bestIndex]) bestIndex = index
        }
        if (bestIndex < 0) return null

        val exclusion = (PEAK_EXCLUSION_MS / GRID_MS).toInt()
        var backgroundCount = 0
        var backgroundSum = 0.0
        var backgroundSquares = 0.0
        for (index in 0 until lagCount) {
            if (z[index].isNaN() || abs(index - bestIndex) <= exclusion) continue
            backgroundCount++
            backgroundSum += z[index]
            backgroundSquares += z[index] * z[index]
        }
        if (backgroundCount < MIN_BACKGROUND_LAGS) return null
        val backgroundMean = backgroundSum / backgroundCount
        val backgroundDeviation = sqrt(max(1e-12, backgroundSquares / backgroundCount - backgroundMean * backgroundMean))
        val peakToSidelobe = (z[bestIndex] - backgroundMean) / backgroundDeviation

        // Parabolic interpolation gives sub-grid precision.
        var peakIndex = bestIndex.toDouble()
        if (bestIndex in 1 until lagCount - 1 && !z[bestIndex - 1].isNaN() && !z[bestIndex + 1].isNaN()) {
            val curvature = z[bestIndex - 1] - 2.0 * z[bestIndex] + z[bestIndex + 1]
            if (abs(curvature) > 1e-9) {
                val shift = 0.5 * (z[bestIndex - 1] - z[bestIndex + 1]) / curvature
                if (abs(shift) <= 1.0) peakIndex += shift
            }
        }
        return Peak(lagRadius - peakIndex, z[bestIndex], peakToSidelobe, scale)
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var size = 1
        while (size < value) size = size shl 1
        return size
    }

    /** Iterative radix-2 FFT with precomputed twiddles; one instance per transform size. */
    private class Fft(private val size: Int) {
        private val cosines = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        private val sines = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

        class Spectrum(val real: DoubleArray, val imaginary: DoubleArray)

        fun forward(values: DoubleArray): Spectrum {
            val real = DoubleArray(size)
            System.arraycopy(values, 0, real, 0, min(values.size, size))
            val imaginary = DoubleArray(size)
            transform(real, imaginary, inverse = false)
            return Spectrum(real, imaginary)
        }

        /** Real part of IFFT(conj(X) * Y): sum over g of x[g] * y[g + d], at index d. */
        fun correlate(x: Spectrum, y: Spectrum): DoubleArray {
            val real = DoubleArray(size)
            val imaginary = DoubleArray(size)
            for (index in 0 until size) {
                val xr = x.real[index]
                val xi = x.imaginary[index]
                val yr = y.real[index]
                val yi = y.imaginary[index]
                real[index] = xr * yr + xi * yi
                imaginary[index] = xr * yi - xi * yr
            }
            transform(real, imaginary, inverse = true)
            return real
        }

        private fun transform(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean) {
            var reversed = 0
            for (index in 1 until size) {
                var bit = size shr 1
                while (reversed and bit != 0) {
                    reversed = reversed xor bit
                    bit = bit shr 1
                }
                reversed = reversed or bit
                if (index < reversed) {
                    val r = real[index]; real[index] = real[reversed]; real[reversed] = r
                    val i = imaginary[index]; imaginary[index] = imaginary[reversed]; imaginary[reversed] = i
                }
            }
            var length = 2
            while (length <= size) {
                val half = length / 2
                val stride = size / length
                var start = 0
                while (start < size) {
                    var twiddle = 0
                    for (offset in 0 until half) {
                        val wr = cosines[twiddle]
                        val wi = if (inverse) sines[twiddle] else -sines[twiddle]
                        val even = start + offset
                        val odd = even + half
                        val oddReal = real[odd] * wr - imaginary[odd] * wi
                        val oddImaginary = real[odd] * wi + imaginary[odd] * wr
                        real[odd] = real[even] - oddReal
                        imaginary[odd] = imaginary[even] - oddImaginary
                        real[even] += oddReal
                        imaginary[even] += oddImaginary
                        twiddle += stride
                    }
                    start += length
                }
                length = length shl 1
            }
            if (inverse) {
                val scale = 1.0 / size
                for (index in 0 until size) real[index] *= scale
            }
        }
    }
}
