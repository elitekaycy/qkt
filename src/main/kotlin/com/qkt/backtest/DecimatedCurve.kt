package com.qkt.backtest

/**
 * A bounded, even-coverage view of an equity curve for charting.
 *
 * Appending one sample per tick over a long run would grow without limit, so this keeps at most
 * [cap] samples by thinning: it retains every `stride`-th sample, and whenever the retained set
 * would exceed [cap] it drops every other retained sample and doubles the stride. The result is a
 * roughly uniform sampling of the whole curve at bounded memory — the chart looks the same, only at
 * lower resolution. Exact metrics are computed separately by [EquityMetrics]; this is display only.
 *
 * The first sample is always retained, and [snapshot] always ends at the most recent sample, so the
 * curve's endpoints are never lost to thinning.
 *
 * e.g. cap 4 fed 1..9 retains samples 1,5,9 (stride 4); snapshot after a 12th sample is 1,5,9,12.
 */
class DecimatedCurve(
    private val cap: Int,
) {
    init {
        require(cap >= 2) { "DecimatedCurve cap must be >= 2, was $cap" }
    }

    private val kept = mutableListOf<EquitySample>()
    private var stride = 1
    private var accepted = 0L
    private var last: EquitySample? = null

    fun accept(sample: EquitySample) {
        accepted++
        last = sample
        if ((accepted - 1) % stride == 0L) {
            kept.add(sample)
            if (kept.size > cap) thin()
        }
    }

    /**
     * The retained samples in time order, always ending at the most recent sample fed. Bounded by
     * `cap + 1` (the trailing sample may be appended on top of the thinned set).
     */
    fun snapshot(): List<EquitySample> {
        val l = last ?: return emptyList()
        if (kept.isEmpty()) return listOf(l)
        if (kept.last().timestamp == l.timestamp) return kept.toList()
        return kept.toMutableList().apply { add(l) }
    }

    /** Halve the retained set (keep every other sample) and double the stride. */
    private fun thin() {
        var write = 0
        for (read in kept.indices) {
            if (read % 2 == 0) {
                kept[write] = kept[read]
                write++
            }
        }
        while (kept.size > write) kept.removeAt(kept.size - 1)
        stride *= 2
    }
}
