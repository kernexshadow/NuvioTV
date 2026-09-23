package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class SubtitleAnalysisAudioSinkTest {
    private class Consumer : SubtitlePcmConsumer {
        val times = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()
        override fun configure(format: Format) = Unit
        override fun acceptPcm(buffer: ByteBuffer, presentationTimeUs: Long) {
            times += presentationTimeUs
            sizes += buffer.remaining()
        }
        override fun onDiscontinuity() = Unit
    }
    private val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT).setSampleRate(16_000).setChannelCount(1).build()

    @Test fun `timestamps subtract renderer offset without inventing seek alignment`() {
        val consumer = Consumer()
        val gate = SubtitleAnalysisReadGate()
        val sink = SubtitleAnalysisAudioSink(consumer, gate)
        sink.configure(pcm, 0, null)
        sink.setOutputStreamOffsetUs(1_000_000_000L)
        sink.play()
        gate.begin(10_000, 15_000)
        gate.enable()
        sink.flush()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(640), 1_010_300_000L, 1))
        assertEquals(listOf(10_300_000L), consumer.times)
        assertEquals(1_010_320_000L, sink.getCurrentPositionUs(false))
    }

    @Test fun `checkpoint blocks further consumption and extends without flush or seek`() {
        val consumer = Consumer()
        val gate = SubtitleAnalysisReadGate()
        val sink = SubtitleAnalysisAudioSink(consumer, gate)
        sink.configure(pcm, 0, null)
        sink.play()
        gate.begin(0, 15_000)
        gate.enable()
        sink.flush()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(480_000), 0, 1))
        assertTrue(gate.atCheckpoint())
        val next = ByteBuffer.allocate(640)
        assertFalse(sink.handleBuffer(next, 15_000_000, 1))
        assertEquals(0, next.position())
        gate.extend(30_000)
        assertTrue(sink.handleBuffer(next, 15_000_000, 1))
        assertEquals(2, consumer.times.size)
        assertEquals(15_020_000L, sink.getCurrentPositionUs(false))
    }

    @Test fun `old decoder buffers are blocked until seek flush and track selection`() {
        val gate = SubtitleAnalysisReadGate()
        val sink = SubtitleAnalysisAudioSink(Consumer(), gate)
        sink.configure(pcm, 0, null)
        sink.play()
        gate.begin(10_000, 15_000)
        val buffer = ByteBuffer.allocate(640)
        assertFalse(sink.handleBuffer(buffer, 10_000_000, 1))
        gate.enable()
        assertFalse(sink.handleBuffer(buffer, 10_000_000, 1))
        sink.flush()
        assertTrue(sink.handleBuffer(buffer, 10_000_000, 1))
        gate.close()
        assertFalse(sink.handleBuffer(ByteBuffer.allocate(640), 10_020_000, 1))
    }

    @Test fun `seek preroll is discarded rather than counted as new evidence`() {
        val consumer = Consumer()
        val gate = SubtitleAnalysisReadGate()
        val sink = SubtitleAnalysisAudioSink(consumer, gate)
        sink.configure(pcm, 0, null)
        sink.play()
        gate.begin(10_000, 15_000)
        gate.enable()
        sink.flush()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(640), 9_980_000, 1))
        assertTrue(consumer.times.isEmpty())
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(640), 9_990_000, 1))
        assertEquals(listOf(10_000_000L), consumer.times)
        assertEquals(listOf(320), consumer.sizes)
    }

    @Test fun `analysis never enables encoded passthrough or playback pacing`() {
        val sink = SubtitleAnalysisAudioSink(Consumer(), SubtitleAnalysisReadGate())
        assertFalse(sink.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()))
        assertTrue(sink.supportsFormat(pcm))
        sink.setPlaybackParameters(PlaybackParameters(8f))
        assertEquals(1f, sink.getPlaybackParameters().speed, 0f)
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        sink.playToEndOfStream()
        assertTrue(sink.isEnded())
        sink.flush()
        assertFalse(sink.isEnded())
    }

    @Test fun `buffering holds decoder output until renderer starts its clock`() {
        val consumer = Consumer()
        val gate = SubtitleAnalysisReadGate()
        val sink = SubtitleAnalysisAudioSink(consumer, gate)
        sink.configure(pcm, 0, null)
        gate.begin(0, 15_000)
        gate.enable()
        sink.flush()
        val output = ByteBuffer.allocate(64_000)
        // ExoPlayer calls render while buffering. Refusing the buffer leaves it in the renderer,
        // which reports ready and can transition to STARTED instead of draining with a frozen clock.
        repeat(10) { assertFalse(sink.handleBuffer(output, 0, 1)) }
        assertEquals(0, output.position())
        assertTrue(consumer.times.isEmpty())
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
        assertFalse(gate.atCheckpoint())
        sink.play()
        assertTrue(sink.handleBuffer(output, 0, 1))
        assertEquals(2_000_000L, sink.getCurrentPositionUs(false))
        assertEquals(listOf(0L), consumer.times)
    }

    @Test fun `rebuffer pauses consumption then resumes the same buffer without changing timestamps`() {
        val consumer = Consumer()
        val gate = SubtitleAnalysisReadGate()
        val sink = SubtitleAnalysisAudioSink(consumer, gate)
        sink.configure(pcm, 0, null)
        gate.begin(0, 15_000)
        gate.enable()
        sink.flush()
        sink.play()
        assertTrue(sink.handleBuffer(ByteBuffer.allocate(640), 0, 1))
        sink.pause()
        val output = ByteBuffer.allocate(640)
        assertFalse(sink.handleBuffer(output, 20_000, 1))
        assertEquals(0, output.position())
        assertEquals(20_000L, sink.getCurrentPositionUs(false))
        sink.play()
        assertTrue(sink.handleBuffer(output, 20_000, 1))
        assertEquals(listOf(0L, 20_000L), consumer.times)
        assertEquals(40_000L, sink.getCurrentPositionUs(false))
    }

    @Test fun `reset requires a new play before consuming another stream`() {
        val gate = SubtitleAnalysisReadGate()
        val sink = SubtitleAnalysisAudioSink(Consumer(), gate)
        sink.play()
        sink.reset()
        sink.configure(pcm, 0, null)
        gate.begin(100_000, 15_000)
        gate.enable()
        sink.flush()
        val output = ByteBuffer.allocate(640)
        assertFalse(sink.handleBuffer(output, 100_000_000, 1))
        sink.play()
        assertTrue(sink.handleBuffer(output, 100_000_000, 1))
    }
}
