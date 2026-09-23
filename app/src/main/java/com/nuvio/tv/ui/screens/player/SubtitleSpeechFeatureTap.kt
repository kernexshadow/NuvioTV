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

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One contiguous run of audio features. Frame k starts at media time
 * `startUs + k * frameDurationUs`; the duration comes from the exact sample count per frame.
 * [band] is the per-frame RMS of the 300-3400 Hz speech band, [broadband] the unfiltered RMS.
 */
internal class SubtitleSpeechFeatureSegment(
    val startUs: Long,
    val frameDurationUs: Double,
    val band: FloatArray,
    val broadband: FloatArray
) {
    val frameCount: Int get() = band.size
    val durationMs: Double get() = frameCount * frameDurationUs / 1_000.0
    val startMs: Double get() = startUs / 1_000.0
}

internal enum class SubtitleSpeechTapInput {
    /** No audio format has been configured since the content was bound. */
    UNKNOWN,
    PCM,
    /** Bitstream passthrough/offload: decoded samples never reach the app. */
    ENCODED
}

/**
 * Reduces the PCM the main player is already decoding for playout to 32 ms speech features.
 *
 * Auto Sync used to open a second player and re-download stream samples, which for a remux means
 * transferring the interleaved video too. This tap costs no network and no extra decode: it sits
 * in [PlaybackSpeedAwareAudioSink] and sees each decoded byte once, with its media timestamp.
 *
 * [acceptPcm] runs on ExoPlayer's playback thread and is allocation-free in steady state apart
 * from occasional array growth. Readers take copies through [snapshot].
 */
internal class SubtitleSpeechFeatureTap(
    private val uptimeMs: () -> Long = SystemClock::uptimeMillis
) : SubtitlePcmConsumer {
    internal companion object {
        const val FRAME_MS = 32.0
        /** About 60 minutes of history; the oldest segments are dropped whole. */
        const val MAX_TOTAL_FRAMES = 112_500
        /** Segments close after ~10 minutes so array growth never copies a whole film. */
        const val ROLLOVER_FRAMES = 18_750
        /** Timestamp jitter tolerated before a buffer is treated as a new, separate run. */
        const val CONTINUITY_TOLERANCE_US = 40_000L
        private const val SPEECH_BAND_LOW_HZ = 300.0
        private const val SPEECH_BAND_HIGH_HZ = 3_400.0
    }

    private val lock = Any()
    private val closed = ArrayDeque<SegmentBuilder>()
    private var current: SegmentBuilder? = null
    private var boundContentKey: String? = null
    private var input = SubtitleSpeechTapInput.UNKNOWN

    @Volatile
    private var lastPcmAtUptimeMs = Long.MIN_VALUE

    private var sampleRate = 0
    private var channelCount = 0
    private var pcmEncoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var frameSamples = DoubleArray(0)
    private val downmixer = SubtitleDialogueDownmixer()

    /** Starts a new history when the content changes; rebinding the same content keeps it. */
    fun bindContent(contentKey: String) = synchronized(lock) {
        if (boundContentKey == contentKey) return@synchronized
        boundContentKey = contentKey
        closed.clear()
        current = null
        input = SubtitleSpeechTapInput.UNKNOWN
        lastPcmAtUptimeMs = Long.MIN_VALUE
    }

    fun isBoundTo(contentKey: String): Boolean = synchronized(lock) { boundContentKey == contentKey }

    fun inputState(): SubtitleSpeechTapInput = synchronized(lock) { input }

    fun hasRecentPcm(windowMs: Long): Boolean {
        val last = lastPcmAtUptimeMs
        return last != Long.MIN_VALUE && uptimeMs() - last < windowMs
    }

    /** Captured audio, without copying feature arrays. */
    fun capturedDurationMs(): Double = synchronized(lock) {
        closed.sumOf { it.durationMs() } + (current?.durationMs() ?: 0.0)
    }

    /** Copies of all captured segments, oldest capture first. */
    fun snapshot(): List<SubtitleSpeechFeatureSegment> = synchronized(lock) {
        buildList {
            closed.forEach { add(it.toSegment()) }
            current?.takeIf { it.frameCount > 0 }?.let { add(it.toSegment()) }
        }
    }

    override fun configure(format: Format) = synchronized(lock) {
        val encodingBytes = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        val isPcm = format.sampleMimeType == MimeTypes.AUDIO_RAW && encodingBytes > 0 &&
            format.sampleRate > 0 && format.channelCount > 0
        closeCurrentLocked()
        if (!isPcm) {
            input = SubtitleSpeechTapInput.ENCODED
            bytesPerSample = 0
            return@synchronized
        }
        input = SubtitleSpeechTapInput.PCM
        if (sampleRate != format.sampleRate || channelCount != format.channelCount ||
            pcmEncoding != format.pcmEncoding
        ) {
            sampleRate = format.sampleRate
            channelCount = format.channelCount
            pcmEncoding = format.pcmEncoding
            frameSamples = DoubleArray(channelCount)
            downmixer.reset()
        }
        bytesPerSample = encodingBytes
    }

    override fun acceptPcm(buffer: ByteBuffer, presentationTimeUs: Long) = synchronized(lock) {
        if (input != SubtitleSpeechTapInput.PCM || bytesPerSample == 0) return@synchronized
        if (presentationTimeUs == C.TIME_UNSET || presentationTimeUs < 0L) return@synchronized
        val bytesPerFrame = bytesPerSample * channelCount
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val sampleFrames = source.remaining() / bytesPerFrame
        if (sampleFrames <= 0) return@synchronized
        lastPcmAtUptimeMs = uptimeMs()

        var segment: SegmentBuilder = current
            ?.takeIf { abs(it.nextSampleTimeUs() - presentationTimeUs) <= CONTINUITY_TOLERANCE_US }
            ?: run {
                closeCurrentLocked()
                SegmentBuilder(presentationTimeUs, sampleRate).also { current = it }
            }
        repeat(sampleFrames) {
            for (channel in 0 until channelCount) frameSamples[channel] = readSample(source)
            if (segment.accept(downmixer.downmix(frameSamples)) && segment.frameCount >= ROLLOVER_FRAMES) {
                closed.addLast(segment)
                segment = segment.continuation()
                current = segment
                trimLocked()
            }
        }
    }

    override fun onDiscontinuity() = synchronized(lock) { closeCurrentLocked() }

    private fun closeCurrentLocked() {
        current?.takeIf { it.frameCount > 0 }?.let(closed::addLast)
        current = null
        trimLocked()
    }

    private fun trimLocked() {
        var total = closed.sumOf { it.frameCount } + (current?.frameCount ?: 0)
        while (total > MAX_TOTAL_FRAMES && closed.isNotEmpty()) {
            total -= closed.removeFirst().frameCount
        }
    }

    private fun readSample(source: ByteBuffer): Double = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> source.short / 32_768.0
        C.ENCODING_PCM_FLOAT -> source.float.toDouble()
        C.ENCODING_PCM_24BIT -> {
            val raw = (source.get().toInt() and 0xff) or
                ((source.get().toInt() and 0xff) shl 8) or
                (source.get().toInt() shl 16)
            raw / 8_388_608.0
        }
        C.ENCODING_PCM_32BIT -> source.int / 2_147_483_648.0
        else -> 0.0
    }

    private class SegmentBuilder(
        private val startUs: Long,
        private val sampleRate: Int
    ) {
        private val samplesPerFrame = (sampleRate * FRAME_MS / 1_000.0).toInt().coerceAtLeast(1)
        private val frameDurationUs = samplesPerFrame * 1_000_000.0 / sampleRate
        private val highPass = Biquad.highPass(SPEECH_BAND_LOW_HZ, sampleRate)
        private val lowPass = Biquad.lowPass(SPEECH_BAND_HIGH_HZ, sampleRate)
        private var band = FloatArray(1_024)
        private var broadband = FloatArray(1_024)
        private var bandEnergy = 0.0
        private var broadbandEnergy = 0.0
        private var pendingSamples = 0
        var frameCount = 0
            private set

        fun durationMs(): Double = frameCount * frameDurationUs / 1_000.0

        fun nextSampleTimeUs(): Long =
            startUs + ((frameCount.toLong() * samplesPerFrame + pendingSamples) * 1_000_000L) / sampleRate

        /** Returns true when this sample completed a feature frame. */
        fun accept(mono: Double): Boolean {
            val speech = lowPass.process(highPass.process(mono))
            bandEnergy += speech * speech
            broadbandEnergy += mono * mono
            if (++pendingSamples < samplesPerFrame) return false
            if (frameCount == band.size) {
                band = band.copyOf(band.size * 2)
                broadband = broadband.copyOf(broadband.size * 2)
            }
            band[frameCount] = sqrt(bandEnergy / pendingSamples).toFloat()
            broadband[frameCount] = sqrt(broadbandEnergy / pendingSamples).toFloat()
            frameCount++
            bandEnergy = 0.0
            broadbandEnergy = 0.0
            pendingSamples = 0
            return true
        }

        /** The next segment, anchored by exact sample count rather than a new timestamp. */
        fun continuation(): SegmentBuilder = SegmentBuilder(nextSampleTimeUs(), sampleRate)

        fun toSegment() = SubtitleSpeechFeatureSegment(
            startUs = startUs,
            frameDurationUs = frameDurationUs,
            band = band.copyOf(frameCount),
            broadband = broadband.copyOf(frameCount)
        )
    }

    /** Second-order Butterworth section (RBJ cookbook), transposed direct form II. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            private const val Q = 0.7071067811865476

            fun lowPass(cutoffHz: Double, sampleRate: Int): Biquad {
                val (cosW, alpha) = coefficients(cutoffHz, sampleRate)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = (1.0 - cosW) / 2.0 / a0,
                    b1 = (1.0 - cosW) / a0,
                    b2 = (1.0 - cosW) / 2.0 / a0,
                    a1 = -2.0 * cosW / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }

            fun highPass(cutoffHz: Double, sampleRate: Int): Biquad {
                val (cosW, alpha) = coefficients(cutoffHz, sampleRate)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = (1.0 + cosW) / 2.0 / a0,
                    b1 = -(1.0 + cosW) / a0,
                    b2 = (1.0 + cosW) / 2.0 / a0,
                    a1 = -2.0 * cosW / a0,
                    a2 = (1.0 - alpha) / a0
                )
            }

            private fun coefficients(cutoffHz: Double, sampleRate: Int): Pair<Double, Double> {
                // Keep the cutoff well below Nyquist for low-rate streams.
                val omega = 2.0 * PI * min(cutoffHz, sampleRate / 2.5) / sampleRate
                return cos(omega) to sin(omega) / (2.0 * Q)
            }
        }
    }
}
