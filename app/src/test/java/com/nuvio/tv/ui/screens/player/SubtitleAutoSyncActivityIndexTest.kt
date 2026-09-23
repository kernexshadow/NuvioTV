package com.nuvio.tv.ui.screens.player

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleAutoSyncActivityIndexTest {
    @Test fun `indexed counts exactly match per bin scoring across gaps and fractional boundaries`() {
        repeat(20) { seed ->
            val random = Random(seed)
            val times = ((0 until 150).map { 17L + it * 100 } +
                (0 until 330).map { 200_051L + it * 100 }).toLongArray()
            val mask = BooleanArray(times.size) { random.nextBoolean() }
            val spans = SubtitleAutoSyncEngine.mergeSpans(List(150) {
                val start = random.nextLong(-10_000L, 250_000L)
                SubtitleSyncSpan(start, start + random.nextLong(100L, 5_000L))
            }, 0)
            val starts = spans.map { it.startMs }.toLongArray()
            val ends = spans.map { it.endMs }.toLongArray()
            val index = SubtitleAutoSyncActivityIndex(times, mask)
            for (offset in -50_000..50_000 step 250) {
                var tp = 0
                var fp = 0
                var fn = 0
                var tn = 0
                times.forEachIndexed { i, time ->
                    val active = spans.any { time - offset >= it.startMs && time - offset < it.endMs }
                    when {
                        mask[i] && active -> tp++
                        active -> fp++
                        mask[i] -> fn++
                        else -> tn++
                    }
                }
                assertEquals("seed=$seed offset=$offset", SubtitleAutoSyncActivityCounts(tp, fp, fn, tn),
                    index.count(offset, starts, ends))
            }
        }
    }

    @Test fun `empty evidence and exact exclusive ends remain empty`() {
        assertEquals(SubtitleAutoSyncActivityCounts(0, 0, 0, 0),
            SubtitleAutoSyncActivityIndex(longArrayOf(), booleanArrayOf())
                .count(0, longArrayOf(), longArrayOf()))
        assertEquals(SubtitleAutoSyncActivityCounts(0, 0, 1, 1),
            SubtitleAutoSyncActivityIndex(longArrayOf(100L, 200L), booleanArrayOf(true, false))
                .count(0, longArrayOf(0L), longArrayOf(100L)))
    }
}
