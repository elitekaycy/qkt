package com.qkt.marketdata

/**
 * One symbol's observed feed for [MarketDataGate]: arrival cadence (last seen time and the
 * smoothed inter-tick gap), the age-bounded price ring the outlier band is computed over,
 * the rebaseline candidate cluster, and the per-condition alert latches. Mutated only on
 * the gate's calling thread; every method is allocation-free.
 */
internal class SymbolFeedState {
    var lastSeenMs: Long = 0L
    var ewmaGapMs: Double = 0.0

    // Primitive rings of the last WINDOW_CAPACITY prices and their arrival times, oldest
    // at [windowHead] — the boxed ArrayDeque<Double> allocated a wrapper per tick on the
    // live hot path. Entries are evicted by AGE, not by count: see outlierWindowMs.
    val window = DoubleArray(WINDOW_CAPACITY)
    val windowAtMs = LongArray(WINDOW_CAPACITY)
    var windowHead = 0
    var windowSize = 0
    var staleAlerted = false
    var pausedAlerted = false
    var lastSkewMs = 0L
    var skewAlerted = false
    var closedAlerted = false
    var rejectedOutlierRun = 0
    var rebaselineCandidate = 0.0
    var rebaselineCandidateCount = 0

    fun push(
        price: Double,
        atMs: Long,
    ) {
        if (windowSize < WINDOW_CAPACITY) {
            val slot = (windowHead + windowSize) % WINDOW_CAPACITY
            window[slot] = price
            windowAtMs[slot] = atMs
            windowSize++
        } else {
            window[windowHead] = price
            windowAtMs[windowHead] = atMs
            windowHead = (windowHead + 1) % WINDOW_CAPACITY
        }
    }

    fun resetAt(
        price: Double,
        atMs: Long,
    ) {
        windowHead = 0
        windowSize = 0
        repeat(MIN_WINDOW_FOR_OUTLIER) { push(price, atMs) }
        clearRejectedOutliers()
    }

    fun clearRejectedOutliers() {
        rejectedOutlierRun = 0
        rebaselineCandidate = 0.0
        rebaselineCandidateCount = 0
    }

    fun recordRebaselineCandidate(price: Double): Boolean {
        val tolerance = maxOf(kotlin.math.abs(rebaselineCandidate), 1.0) * REBASELINE_CLUSTER_TOLERANCE
        if (rebaselineCandidateCount == 0 || kotlin.math.abs(price - rebaselineCandidate) > tolerance) {
            rebaselineCandidate = price
            rebaselineCandidateCount = 1
        } else {
            rebaselineCandidateCount++
            rebaselineCandidate +=
                (price - rebaselineCandidate) / rebaselineCandidateCount
        }
        return rebaselineCandidateCount >= REBASELINE_TICK_COUNT
    }

    fun touch(now: Long) {
        if (lastSeenMs > 0L) {
            val gap = (now - lastSeenMs).toDouble()
            ewmaGapMs =
                if (ewmaGapMs == 0.0) gap else EWMA_ALPHA * gap + (1 - EWMA_ALPHA) * ewmaGapMs
        }
        lastSeenMs = now
    }

    fun isOutlier(
        price: Double,
        nowMs: Long,
        outlierWindowMs: Long,
        outlierSigma: Double,
    ): Boolean {
        val stored = windowSize
        if (stored < MIN_WINDOW_FOR_OUTLIER) return false
        val prices = window
        val at = windowAtMs
        val head = windowHead
        // The ring is in arrival order, so the first entry inside the age window starts the
        // sample. Bounding by AGE rather than by count keeps the comparison band's meaning
        // fixed as the feed's tick rate changes: a fixed 64-entry window spanned about a
        // minute under one-quote-per-poll and about twelve seconds once range polling
        // delivered every tick, tightening the band precisely during fast moves.
        val cutoff = nowMs - outlierWindowMs
        var first = 0
        while (first < stored && at[(head + first) % WINDOW_CAPACITY] < cutoff) first++
        val n = stored - first
        if (n < MIN_WINDOW_FOR_OUTLIER) return false
        // Two passes in oldest-to-newest order, matching the deque version's summation order
        // exactly so the double math is unchanged.
        var sum = 0.0
        for (k in first until stored) sum += prices[(head + k) % WINDOW_CAPACITY]
        val mean = sum / n
        var ssd = 0.0
        for (k in first until stored) {
            val d = prices[(head + k) % WINDOW_CAPACITY] - mean
            ssd += d * d
        }
        val variance = ssd / n
        val sigma = kotlin.math.sqrt(variance)
        // A flat window (sigma ~ 0) cannot judge deviation meaningfully — use a small
        // relative floor so a constant-price series doesn't flag the first real move.
        val effectiveSigma = maxOf(sigma, mean.coerceAtLeast(1.0) * MIN_RELATIVE_SIGMA)
        return kotlin.math.abs(price - mean) > outlierSigma * effectiveSigma
    }

    companion object {
        /** Ring capacity — bounds memory; [MarketDataGate.DEFAULT_OUTLIER_WINDOW_MS] bounds the sample itself. */
        private const val WINDOW_CAPACITY = 512
        private const val MIN_WINDOW_FOR_OUTLIER = 16
        private const val EWMA_ALPHA = 0.1
        private const val MIN_RELATIVE_SIGMA = 0.002
        private const val REBASELINE_CLUSTER_TOLERANCE = 0.002
        const val REBASELINE_TICK_COUNT = 3
    }
}
