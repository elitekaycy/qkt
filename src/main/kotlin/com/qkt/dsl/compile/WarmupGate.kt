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

    fun onClosedCandle(alias: String) {
        counts.merge(alias, 1, Int::plus)
    }

    fun isWarm(alias: String): Boolean {
        val required = perStream[alias] ?: return true
        val seen = counts[alias] ?: 0
        return seen >= required
    }

    fun isWarm(aliases: Set<String>): Boolean = aliases.all(::isWarm)
}
