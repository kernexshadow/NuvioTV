package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class SubtitleSpeechFeatureTapTest {
    private var nowMs = 1_000L
    private val tap = SubtitleSpeechFeatureTap(uptimeMs = { nowMs }).apply {
        bindContent("stream")
        configure(pcmFormat())
    }

    @Test
    fun `continuous buffers form one segment anchored at the first timestamp`() {
        feed(startUs = 10_000_000L, seconds = 2.0)

        val segments = tap.snapshot()
        assertEquals(1, segments.size)
        assertEquals(10_000_000L, segments[0].startUs)
        assertEquals(62, segments[0].frameCount) // 2 s / 32 ms
        assertTrue(tap.hasRecentPcm(1_000L))
    }

    @Test
    fun `a timestamp jump starts a new segment`() {
        feed(startUs = 0L, seconds = 1.0)
        feed(startUs = 60_000_000L, seconds = 1.0)

        val segments = tap.snapshot()
        assertEquals(2, segments.size)
        assertEquals(60_000_000L, segments[1].startUs)
    }

    @Test
    fun `passthrough input is reported and never analysed`() {
        tap.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).setSampleRate(48_000).build())
        feed(startUs = 0L, seconds = 1.0)

        assertEquals(SubtitleSpeechTapInput.ENCODED, tap.inputState())
        assertTrue(tap.snapshot().isEmpty())
    }

    @Test
    fun `rebinding keeps history only for the same content`() {
        feed(startUs = 0L, seconds = 1.0)
        tap.bindContent("stream")
        assertEquals(1, tap.snapshot().size)

        tap.bindContent("another stream")
        assertTrue(tap.snapshot().isEmpty())
        assertFalse(tap.isBoundTo("stream"))
    }

    @Test
    fun `speech band tone keeps its energy while a bass tone is filtered out`() {
        feed(startUs = 0L, seconds = 1.0, frequencyHz = 1_000.0)
        feed(startUs = 5_000_000L, seconds = 1.0, frequencyHz = 60.0)

        val (voice, bass) = tap.snapshot()
        val voiceShare = voice.band[20] / voice.broadband[20]
        val bassShare = bass.band[20] / bass.broadband[20]
        assertTrue("voice=$voiceShare", voiceShare > 0.8f)
        assertTrue("bass=$bassShare", bassShare < 0.2f)
    }

    private fun pcmFormat() = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(48_000)
        .setChannelCount(2)
        .build()

    /** Stereo 16-bit sine in 20 ms buffers, each with its exact timestamp. */
    private fun feed(startUs: Long, seconds: Double, frequencyHz: Double = 440.0) {
        val bufferFrames = 960
        val totalFrames = (48_000 * seconds).toInt()
        var frame = 0
        while (frame < totalFrames) {
            val buffer = ByteBuffer.allocate(bufferFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(bufferFrames) { index ->
                val sample = (sin(2.0 * PI * frequencyHz * (frame + index) / 48_000.0) * 12_000).toInt().toShort()
                buffer.putShort(sample).putShort(sample)
            }
            buffer.flip()
            tap.acceptPcm(buffer, startUs + frame * 1_000_000L / 48_000L)
            frame += bufferFrames
        }
    }
}
