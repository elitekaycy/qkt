package com.qkt.derivatives.events

import com.qkt.common.Money
import com.qkt.instrument.EventTerms
import com.qkt.instrument.StrikeType
import java.math.BigDecimal

/** One contract of an event as a metric reads it: its [terms] and its latest YES [bid] and [ask], either absent. */
data class EventQuote(
    val terms: EventTerms,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
) {
    /** The YES mid when both sides are quoted and not crossed, else null. */
    val mid: BigDecimal?
        get() = if (bid != null && ask != null && bid <= ask) bid.add(ask).divide(TWO, Money.CONTEXT) else null

    private companion object {
        val TWO = BigDecimal(2)
    }
}

/** An analytic of one event: a value from every contract of the event in strike order, or null where undefined. */
fun interface EventMetric {
    fun value(contracts: List<EventQuote>): BigDecimal?
}

/**
 * The event `CHAIN:` metrics, a registry: a new metric is one entry here plus its row in
 * `docs/reference/dsl/chain.md`. Each is defined only where it can be computed honestly and is null otherwise:
 * - `implied_median`: the strike where a ladder's YES mids cross 0.5, linear between strikes (ladders of `greater`
 *   strikes);
 * - `implied_mean`: Σ bucket midpoint × YES mid over Σ YES mid (exclusive events of bounded `between` buckets, every
 *   bucket two-sided);
 * - `overround`: Σ YES asks − 1 (exclusive events, every contract with an ask);
 * - `underround`: 1 − Σ YES bids, an absent bid counting 0 (exclusive events, every contract quoted);
 * - `ladder_breaks`: adjacent strikes where the higher strike's YES bid exceeds the lower strike's YES ask (ladders).
 *
 * A ladder is two or more non-exclusive contracts whose strikes are all `greater` or `greater_or_equal` a floor.
 */
object EventChainMetrics {
    private val half = BigDecimal("0.5")
    private val ladderTypes = setOf(StrikeType.GREATER, StrikeType.GREATER_OR_EQUAL)

    private val metrics: Map<String, EventMetric> =
        linkedMapOf(
            "implied_median" to EventMetric(::impliedMedian),
            "implied_mean" to EventMetric(::impliedMean),
            "overround" to EventMetric(::overround),
            "underround" to EventMetric(::underround),
            "ladder_breaks" to EventMetric(::ladderBreaks),
        )

    /** The metric spelled [token], or null. */
    fun of(token: String): EventMetric? = metrics[token]

    /** Every metric spelling, for error messages. */
    val tokens: String get() = metrics.keys.joinToString()

    private fun exclusive(contracts: List<EventQuote>): Boolean =
        contracts.isNotEmpty() && contracts.all { it.terms.exclusive }

    private fun ladder(contracts: List<EventQuote>): Boolean =
        contracts.size >= 2 && contracts.all { !it.terms.exclusive && it.terms.predicate.type in ladderTypes }

    private fun impliedMedian(contracts: List<EventQuote>): BigDecimal? {
        if (!ladder(contracts)) return null
        val points = contracts.mapNotNull { q -> q.mid?.let { requireNotNull(q.terms.predicate.floor) to it } }
        for ((low, high) in points.zipWithNext()) {
            val (k0, m0) = low
            val (k1, m1) = high
            if (m0 < half || m1 > half) continue
            if (m0.compareTo(m1) == 0) return k0
            val fraction = m0.subtract(half).divide(m0.subtract(m1), Money.CONTEXT)
            return k0.add(fraction.multiply(k1.subtract(k0)), Money.CONTEXT)
        }
        return null
    }

    private fun impliedMean(contracts: List<EventQuote>): BigDecimal? {
        if (!exclusive(contracts) || contracts.any { it.terms.predicate.type != StrikeType.BETWEEN }) return null
        val mids = contracts.map { it.mid ?: return null }
        val total = mids.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.signum() <= 0) return null
        val weighted =
            contracts.zip(mids).fold(BigDecimal.ZERO) { acc, (q, p) ->
                val floor = requireNotNull(q.terms.predicate.floor)
                val cap = requireNotNull(q.terms.predicate.cap)
                acc.add(floor.add(cap).multiply(half).multiply(p))
            }
        return weighted.divide(total, Money.CONTEXT)
    }

    private fun overround(contracts: List<EventQuote>): BigDecimal? {
        if (!exclusive(contracts)) return null
        val asks = contracts.map { it.ask ?: return null }
        return asks.fold(BigDecimal.ONE.negate(), BigDecimal::add)
    }

    private fun underround(contracts: List<EventQuote>): BigDecimal? {
        if (!exclusive(contracts) || contracts.any { it.bid == null && it.ask == null }) return null
        return contracts.fold(BigDecimal.ONE) { acc, q -> acc.subtract(q.bid ?: BigDecimal.ZERO) }
    }

    private fun ladderBreaks(contracts: List<EventQuote>): BigDecimal? {
        if (!ladder(contracts)) return null
        val pairs = contracts.zipWithNext().filter { (low, high) -> low.ask != null && high.bid != null }
        if (pairs.isEmpty()) return null
        return BigDecimal(pairs.count { (low, high) -> requireNotNull(high.bid) > requireNotNull(low.ask) })
    }
}
