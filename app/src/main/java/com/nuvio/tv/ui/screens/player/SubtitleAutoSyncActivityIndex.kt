package com.nuvio.tv.ui.screens.player

internal data class SubtitleAutoSyncActivityCounts(
    val truePositive: Int,
    val falsePositive: Int,
    val falseNegative: Int,
    val trueNegative: Int
)

/** Exact equivalent of the per-bin activity comparison, using prefix sums at cue boundaries. */
internal class SubtitleAutoSyncActivityIndex(
    private val sampleTimes: LongArray,
    speechMask: BooleanArray
) {
    private val speechPrefix = IntArray(sampleTimes.size + 1)
    private val segments: List<IntRange>

    init {
        require(sampleTimes.size == speechMask.size)
        speechMask.forEachIndexed { index, speech ->
            speechPrefix[index + 1] = speechPrefix[index] + if (speech) 1 else 0
        }
        segments = buildList {
            if (sampleTimes.isNotEmpty()) {
                var start = 0
                for (index in 1 until sampleTimes.size) {
                    if (sampleTimes[index] - sampleTimes[index - 1] > 100L) {
                        add(start until index)
                        start = index
                    }
                }
                add(start until sampleTimes.size)
            }
        }
    }

    /** Subtitle intervals must be sorted and non-overlapping, as produced by mergeSpans. */
    fun count(offsetMs: Int, subtitleStarts: LongArray, subtitleEnds: LongArray): SubtitleAutoSyncActivityCounts {
        var active = 0
        var truePositive = 0
        for (segment in segments) {
            val first = segment.first
            val end = segment.last + 1
            val firstTime = sampleTimes[first] - offsetMs
            val lastTime = sampleTimes[end - 1] - offsetMs
            var cue = upperBound(subtitleEnds, firstTime)
            while (cue < subtitleStarts.size && subtitleStarts[cue] <= lastTime) {
                val from = lowerBound(sampleTimes, subtitleStarts[cue] + offsetMs, first, end)
                val until = lowerBound(sampleTimes, subtitleEnds[cue] + offsetMs, from, end)
                active += until - from
                truePositive += speechPrefix[until] - speechPrefix[from]
                cue++
            }
        }
        val speech = speechPrefix.last()
        return SubtitleAutoSyncActivityCounts(truePositive, active - truePositive,
            speech - truePositive, sampleTimes.size - speech - active + truePositive)
    }

    private fun lowerBound(values: LongArray, target: Long, from: Int, until: Int): Int {
        var low = from
        var high = until
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    private fun upperBound(values: LongArray, target: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] <= target) low = middle + 1 else high = middle
        }
        return low
    }
}
