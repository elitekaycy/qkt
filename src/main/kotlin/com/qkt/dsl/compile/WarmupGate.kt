package com.qkt.dsl.compile

/**
 * Phase 24: per-stream closed-candle counter used to gate DSL rule firing.
 *
 * A rule does not fire on a given tick until every stream alias it references has
 * been observed [perStream] closed candles. An alias not present in [perStream]
 * (i.e., a stream with no WARMUP declared) is treated as warm from tick zero.
 *
 * The counter is monotonic within a process: engine restart resets it. This matches
 * Phase 24's live-only semantics - historical prefetch lands in Phase 25.
 */
class WarmupGate(
    private val perStream: Map<String, Int>,
) {
    private val counts: MutableMap<String, Int> = mutableMapOf()

    // Warmth is monotonic (counts only grow), so once a set is warm it stays warm — latch it
    // and skip the per-alias walk that otherwise runs on every rule evaluation forever.
    private val warmSets: MutableSet<Set<String>> = HashSet()

    fun onClosedCandle(alias: String) {
        counts[alias] = (counts[alias] ?: 0) + 1
    }

    /**
     * Pre-credit [count] closed bars for [alias] in one call. Used by Phase 25B's
     * deploy path: after the CandleHub is seeded with historical bars, the gate
     * matches the pre-loaded history so the first live candle can fire rules.
     */
    fun recordBars(
        alias: String,
        count: Int,
    ) {
        if (count <= 0) return
        counts[alias] = (counts[alias] ?: 0) + count
    }

    fun isWarm(alias: String): Boolean {
        val required = perStream[alias] ?: return true
        val seen = counts[alias] ?: 0
        return seen >= required
    }

    fun isWarm(aliases: Set<String>): Boolean {
        if (aliases in warmSets) return true
        val warm = aliases.all(::isWarm)
        if (warm) warmSets.add(aliases)
        return warm
    }
}
