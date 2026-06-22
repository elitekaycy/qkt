package com.qkt.backtest

/**
 * Bounded, even-coverage view of any time-ordered series for charting — the generic form of
 * [DecimatedCurve]. Keeps at most [cap] samples by retaining every `stride`-th one and halving +
 * doubling the stride whenever the kept set would exceed [cap]. The first sample is always kept and
 * [snapshot] always ends at the most recent sample, so endpoints survive thinning.
 *
 * e.g. cap 4 fed 1..9 keeps 1,5,9; after a 12th sample, snapshot is 1,5,9,12.
 */
class DecimatedSeries<T>(
    private val cap: Int,
) {
    init {
        require(cap >= 2) { "DecimatedSeries cap must be >= 2, was $cap" }
    }

    private val kept = mutableListOf<T>()
    private var stride = 1
    private var accepted = 0L
    private var last: T? = null

    fun accept(sample: T) {
        accepted++
        last = sample
        if ((accepted - 1) % stride == 0L) {
            kept.add(sample)
            if (kept.size > cap) thin()
        }
    }

    /** Retained samples in order, always ending at the most recent fed. Bounded by `cap + 1`. */
    fun snapshot(): List<T> {
        val l = last ?: return emptyList()
        if (kept.isEmpty()) return listOf(l)
        if (kept.last() === l) return kept.toList()
        return kept.toMutableList().apply { add(l) }
    }

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
