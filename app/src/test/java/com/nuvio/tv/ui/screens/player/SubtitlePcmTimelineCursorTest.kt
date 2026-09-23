package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePcmTimelineCursorTest {
    @Test
    fun `anchored cursor ignores a large renderer timestamp origin`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = 1_238_885L)

        val range = cursor.map(
            rawPresentationTimeUs = 90_373_000_000_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        assertEquals(1_238_885L, range.startMs)
        assertEquals(1_239_885L, range.endMs)
    }

    @Test
    fun `anchored cursor advances by decoded frames instead of raw timestamp jumps`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = 10_000L)
        cursor.map(
            rawPresentationTimeUs = 8_000_000_000L,
            frameCount = 24_000,
            sampleRate = 48_000
        )

        val second = cursor.map(
            rawPresentationTimeUs = 99_000_000_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        assertEquals(10_500L, second.startMs)
        assertEquals(11_500L, second.endMs)
    }

    @Test
    fun `anchored cursor preserves a plausible forward pts gap`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = 10_000L)
        cursor.map(
            rawPresentationTimeUs = 8_000_000_000L,
            frameCount = 24_000,
            sampleRate = 48_000
        )

        val second = cursor.map(
            rawPresentationTimeUs = 8_002_500_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        assertEquals(12_500L, second.startMs)
        assertEquals(13_500L, second.endMs)
    }

    @Test
    fun `reset returns an anchored cursor to requested probe position`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = 25_000L)
        cursor.map(
            rawPresentationTimeUs = 5_000_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        cursor.reset()
        val afterReset = cursor.map(
            rawPresentationTimeUs = 25_000_000_000L,
            frameCount = 4_800,
            sampleRate = 48_000
        )

        assertEquals(25_000L, afterReset.startMs)
        assertEquals(25_100L, afterReset.endMs)
    }

    @Test
    fun `unanchored cursor preserves renderer timeline`() {
        val cursor = SubtitlePcmTimelineCursor(anchorMs = null)

        val range = cursor.map(
            rawPresentationTimeUs = 12_345_000L,
            frameCount = 48_000,
            sampleRate = 48_000
        )

        assertEquals(12_345L, range.startMs)
        assertEquals(13_345L, range.endMs)
    }

    @Test
    fun `surround downmix uses centre only and excludes lfe`() {
        val downmixer = SubtitleDialogueDownmixer()
        feed(downmixer, 16_384) { index -> doubleArrayOf(0.4, 0.4, speech(index), 0.9, 0.3, 0.3) }

        val mono = downmixer.downmix(doubleArrayOf(0.4, -0.4, 0.5, 0.9, 0.3, -0.3))

        assertEquals(0.5, mono, 1e-9)
    }

    @Test
    fun `surround downmix falls back to fronts when centre is empty`() {
        val downmixer = SubtitleDialogueDownmixer()
        feed(downmixer, 16_384) { index -> doubleArrayOf(speech(index), speech(index), 0.0, 0.0, 0.0, 0.0) }

        val mono = downmixer.downmix(doubleArrayOf(0.6, 0.6, 0.0, 0.0, 0.0, 0.0))

        assertTrue(mono > 0.3)
    }

    @Test
    fun `silent opening does not declare the centre empty`() {
        val downmixer = SubtitleDialogueDownmixer()
        feed(downmixer, 16_384) { doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }

        assertEquals(0.5, downmixer.downmix(doubleArrayOf(0.0, 0.0, 0.5, 0.0, 0.0, 0.0)), 1e-9)
    }

    @Test
    fun `stereo downmix averages in phase channels`() {
        val downmixer = SubtitleDialogueDownmixer()
        feed(downmixer, 16_384) { index -> doubleArrayOf(speech(index), speech(index)) }

        assertEquals(0.5, downmixer.downmix(doubleArrayOf(0.6, 0.4)), 1e-9)
    }

    @Test
    fun `anti phase stereo track does not cancel speech energy`() {
        val downmixer = SubtitleDialogueDownmixer()
        feed(downmixer, 16_384) { index -> doubleArrayOf(speech(index), -speech(index)) }

        val mono = downmixer.downmix(doubleArrayOf(0.8, -0.8))

        assertTrue(kotlin.math.abs(mono) > 0.50)
    }

    private fun speech(index: Int): Double = 0.5 * kotlin.math.sin(index * 0.07)

    private fun feed(downmixer: SubtitleDialogueDownmixer, frames: Int, frame: (Int) -> DoubleArray) {
        repeat(frames) { downmixer.downmix(frame(it)) }
    }
}
