package com.qkt.backtest.metrics

/**
 * Bounded-memory estimate of the median of a stream, via the P-square algorithm
 * (Jain & Chlamtac, 1985).
 *
 * Keeps five markers (running quantile estimates at the 0th, 25th, 50th, 75th, and 100th
 * percentiles) and nudges them as values arrive, so it never stores the values themselves.
 * Memory is constant no matter how many samples pass through — the property that lets a
 * multi-million-bar run estimate its median `|return|` without retaining every return.
 *
 * The result is approximate: it converges to the true median but is not exact for any finite
 * stream. It is used only to pick the high/low volatility split point, where an exact median is
 * not needed. Deterministic given the same input order.
 *
 * Computation is in `Double`: the markers are positions on the real line nudged by a parabolic
 * prediction, so `BigDecimal` precision buys nothing here — this is a threshold heuristic, not a
 * reported money value. e.g. feed 1, 2, 3, 4, 5 → [estimate] ≈ 3.
 */
class StreamingMedian {
    private val markerHeights = DoubleArray(MARKER_COUNT)
    private val markerPositions = IntArray(MARKER_COUNT)
    private val desiredPositions = DoubleArray(MARKER_COUNT)
    private val increments = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
    private var seen = 0

    /** Number of values folded in so far. */
    val count: Int get() = seen

    /** Fold the next value into the marker estimates. */
    fun accept(value: Double) {
        if (seen < MARKER_COUNT) {
            markerHeights[seen] = value
            seen++
            if (seen == MARKER_COUNT) {
                markerHeights.sort()
                for (i in 0 until MARKER_COUNT) {
                    markerPositions[i] = i
                    desiredPositions[i] = i.toDouble()
                }
            }
            return
        }

        val cell = locate(value)
        for (i in cell + 1 until MARKER_COUNT) markerPositions[i]++
        for (i in 0 until MARKER_COUNT) desiredPositions[i] += increments[i]
        adjust()
        seen++
    }

    /** Current median estimate, or null before any value is seen. */
    fun estimate(): Double? {
        if (seen == 0) return null
        if (seen < MARKER_COUNT) {
            val copy = markerHeights.copyOf(seen)
            copy.sort()
            val mid = seen / 2
            return if (seen % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) / 2.0
        }
        return markerHeights[MIDDLE_MARKER]
    }

    /** Index of the marker cell the value falls into, extending the min/max markers as needed. */
    private fun locate(value: Double): Int {
        if (value < markerHeights[0]) {
            markerHeights[0] = value
            return 0
        }
        if (value >= markerHeights[MARKER_COUNT - 1]) {
            markerHeights[MARKER_COUNT - 1] = value
            return MARKER_COUNT - 2
        }
        for (i in 1 until MARKER_COUNT) {
            if (value < markerHeights[i]) return i - 1
        }
        return MARKER_COUNT - 2
    }

    /** Move each interior marker toward its desired position, parabolic step with linear fallback. */
    private fun adjust() {
        for (i in 1 until MARKER_COUNT - 1) {
            val delta = desiredPositions[i] - markerPositions[i]
            val gapNext = markerPositions[i + 1] - markerPositions[i]
            val gapPrev = markerPositions[i - 1] - markerPositions[i]
            if ((delta >= 1.0 && gapNext > 1) || (delta <= -1.0 && gapPrev < -1)) {
                val dir = if (delta >= 0) 1 else -1
                val parabolic = parabolic(i, dir)
                markerHeights[i] =
                    if (markerHeights[i - 1] < parabolic && parabolic < markerHeights[i + 1]) {
                        parabolic
                    } else {
                        linear(i, dir)
                    }
                markerPositions[i] += dir
            }
        }
    }

    private fun parabolic(
        i: Int,
        dir: Int,
    ): Double {
        val nPrev = markerPositions[i - 1]
        val n = markerPositions[i]
        val nNext = markerPositions[i + 1]
        val qPrev = markerHeights[i - 1]
        val q = markerHeights[i]
        val qNext = markerHeights[i + 1]
        val term1 = dir.toDouble() / (nNext - nPrev)
        val term2 = (n - nPrev + dir) * (qNext - q) / (nNext - n)
        val term3 = (nNext - n - dir) * (q - qPrev) / (n - nPrev)
        return q + term1 * (term2 + term3)
    }

    private fun linear(
        i: Int,
        dir: Int,
    ): Double {
        val neighbour = markerHeights[i + dir]
        val positionGap = markerPositions[i + dir] - markerPositions[i]
        return markerHeights[i] + dir * (neighbour - markerHeights[i]) / positionGap
    }

    private companion object {
        const val MARKER_COUNT = 5
        const val MIDDLE_MARKER = 2
    }
}
