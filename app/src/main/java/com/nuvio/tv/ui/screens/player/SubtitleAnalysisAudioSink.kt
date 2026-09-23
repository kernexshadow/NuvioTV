package com.nuvio.tv.ui.screens.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

internal interface SubtitlePcmConsumer {
    fun configure(format: Format)
    fun acceptPcm(buffer: ByteBuffer, presentationTimeUs: Long)
    fun onDiscontinuity()
}

/** Application-thread controls; the decoder cannot run past a checkpoint while it is scored. */
internal class SubtitleAnalysisReadGate {
    private var enabled = false
    private var awaitingFlush = true
    private var consumedUs = 0L
    private var limitUs = 0L
    var minimumMediaTimeUs: Long = 0L
        private set

    @Synchronized fun begin(startMs: Long, durationMs: Long) {
        enabled = false
        awaitingFlush = true
        consumedUs = 0L
        limitUs = durationMs * 1_000L
        minimumMediaTimeUs = startMs * 1_000L
    }
    @Synchronized fun enable() { enabled = true }
    @Synchronized fun close() { enabled = false }
    @Synchronized fun flushed() { awaitingFlush = false }
    @Synchronized fun extend(durationMs: Long) { limitUs = durationMs * 1_000L }
    // Keeping consumption under this lock makes close/begin a barrier against old decoder data.
    @Synchronized fun consume(block: () -> Long): Boolean {
        if (!enabled || awaitingFlush || consumedUs >= limitUs) return false
        consumedUs += block()
        return true
    }
    @Synchronized fun atCheckpoint(): Boolean = !awaitingFlush && consumedUs >= limitUs
    @Synchronized fun describe(): String =
        "enabled=$enabled awaitingFlush=$awaitingFlush consumedUs=$consumedUs limitUs=$limitUs"
}

/**
 * PCM-only analysis output. There is no AudioTrack, Sonic/time stretching or wall-clock pacing.
 * Renderer position stays in renderer time; ONLY the VAD input subtracts the stream offset.
 * A checkpoint applies backpressure to the renderer instead of seeking/reopening the source.
 */
internal class SubtitleAnalysisAudioSink(
    private val consumer: SubtitlePcmConsumer,
    private val gate: SubtitleAnalysisReadGate
) : AudioSink {
    private var listener: AudioSink.Listener? = null
    private var format: Format? = null
    private var streamOffsetUs = 0L
    private var positionUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var ended = false
    private var playing = false
    private var discontinuity = true
    private var attributes = AudioAttributes.DEFAULT

    override fun setListener(listener: AudioSink.Listener) { this.listener = listener }
    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW && bytesPerSample(format.pcmEncoding) > 0
    override fun getFormatSupport(format: Format): Int = if (supportsFormat(format)) {
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    } else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (!supportsFormat(inputFormat) || inputFormat.sampleRate <= 0 || inputFormat.channelCount <= 0) {
            throw AudioSink.ConfigurationException("Unsupported analysis PCM format", inputFormat)
        }
        // Analyse the decoder's original interleaved channels, before any playback channel map.
        format = inputFormat
        consumer.configure(inputFormat)
        ended = false
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!buffer.hasRemaining()) return true
        // Media3 renders while BUFFERING, but its audio renderer only advances the media clock
        // while STARTED. Draining here used to empty the decoder with the clock frozen, leaving
        // LoadControl seeing already-consumed audio as buffered and refusing further loading.
        // Keep the output buffer in the renderer until play(): that also makes isReady() true.
        // This is lifecycle backpressure, not real-time pacing; once started we drain immediately.
        if (!playing) return false
        val pcm = format ?: return false
        if (presentationTimeUs == C.TIME_UNSET) return false
        return gate.consume {
            val frameBytes = bytesPerSample(pcm.pcmEncoding) * pcm.channelCount
            val frames = buffer.remaining() / frameBytes
            val durationUs = frames.toLong() * 1_000_000L / pcm.sampleRate
            val mediaTimeUs = presentationTimeUs - streamOffsetUs
            // Decoder preroll must not be relabelled as the requested seek time.
            val skipFrames = (((gate.minimumMediaTimeUs - mediaTimeUs).coerceAtLeast(0L) *
                pcm.sampleRate + 999_999L) / 1_000_000L).coerceAtMost(frames.toLong()).toInt()
            val input = buffer.duplicate()
            input.position(input.position() + skipFrames * frameBytes)
            if (input.hasRemaining()) {
                consumer.acceptPcm(input, mediaTimeUs + skipFrames.toLong() * 1_000_000L / pcm.sampleRate)
            }
            positionUs = presentationTimeUs + durationUs
            buffer.position(buffer.limit())
            if (discontinuity) {
                discontinuity = false
                listener?.onPositionDiscontinuity()
            }
            (frames - skipFrames).toLong() * 1_000_000L / pcm.sampleRate
        }
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { streamOffsetUs = outputStreamOffsetUs }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = positionUs
    override fun playToEndOfStream() { ended = true }
    override fun isEnded(): Boolean = ended
    override fun hasPendingData(): Boolean = false
    override fun handleDiscontinuity() {
        discontinuity = true
        consumer.onDiscontinuity()
    }
    override fun flush() {
        positionUs = AudioSink.CURRENT_POSITION_NOT_SET
        ended = false
        handleDiscontinuity()
        gate.flushed()
    }
    override fun reset() {
        flush()
        playing = false
        format = null
        streamOffsetUs = 0L
    }
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) { attributes = audioAttributes }
    override fun getAudioAttributes(): AudioAttributes = attributes
    override fun setAudioSessionId(audioSessionId: Int) = Unit
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit
    override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
    override fun enableTunnelingV21() = Unit
    override fun disableTunneling() = Unit
    override fun setVolume(volume: Float) = Unit

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
}
