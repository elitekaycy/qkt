package com.qkt.derivatives.options.chain

import com.qkt.derivatives.options.pricing.Black76
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRight
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Implied-volatility analytics of one chain snapshot at a tenor, from catalogued quotes with a
 * positive mark IV no older than the quote age. Per expiry (years T > 0 on a 365-day year):
 * - the forward F is the median `underlying`;
 * - ATM IV interpolates the mean IV per strike linearly in strike between the nearest strikes at or
 *   below and at or above F, both within [MAX_MONEYNESS] of F (otherwise none);
 * - the 25-delta skew is the put IV at Black-76 delta −0.25 less the call IV at +0.25, each
 *   interpolated linearly in delta between bracketing quotes both within [MAX_DELTA_GAP] of the
 *   target (at rate 0: the venue's rate field is not used).
 *
 * Across expiries ATM IV interpolates total variance `IV²·T` and skew interpolates linearly in T. A
 * tenor outside the expiries that carry the value has none: nothing is extrapolated or read from a
 * distant strike.
 */
object ChainAnalytics {
    private const val YEAR_MS = 365.0 * 24 * 3600 * 1000
    private const val WING = 0.25

    /** How far from the forward, as a fraction of it, the strikes an ATM IV is read from may be. */
    const val MAX_MONEYNESS = 0.10

    /** How far in delta from ±0.25 the quotes a 25-delta IV is read from may be. */
    const val MAX_DELTA_GAP = 0.15

    /** [metric] at [tenorDays] in [snapshot], with contract terms from [listings]; null when not defined. */
    fun value(
        metric: ChainMetric,
        tenorDays: Int,
        snapshot: ChainSnapshot,
        listings: Map<String, OptionListing>,
        maxQuoteAgeMs: Long,
    ): Double? {
        require(tenorDays > 0) { "chain tenor must be > 0 days: $tenorDays" }
        val points =
            smiles(snapshot, listings, maxQuoteAgeMs).mapNotNull { s ->
                val v = if (metric == ChainMetric.ATM_IV) s.atmIv() else s.skew()
                v?.let { s.years to it }
            }
        return interpolate(points, tenorDays / 365.0, variance = metric == ChainMetric.ATM_IV)
    }

    private fun interpolate(
        points: List<Pair<Double, Double>>,
        tau: Double,
        variance: Boolean,
    ): Double? {
        val (lo, hi) = points.zipWithNext().firstOrNull { (a, b) -> a.first <= tau && tau <= b.first } ?: return null
        val weight = (tau - lo.first) / (hi.first - lo.first)
        if (!variance) return lo.second + (hi.second - lo.second) * weight
        val w1 = lo.second * lo.second * lo.first
        val w2 = hi.second * hi.second * hi.first
        return sqrt((w1 + (w2 - w1) * weight) / tau)
    }

    private fun smiles(
        snapshot: ChainSnapshot,
        listings: Map<String, OptionListing>,
        maxQuoteAgeMs: Long,
    ): List<Smile> =
        snapshot.quotes
            .filter { q -> (q.markIv?.signum() ?: 0) > 0 && q.markAgeMs <= maxQuoteAgeMs && q.contract in listings }
            .groupBy { listings.getValue(it.contract).expiryMs }
            .toSortedMap()
            .mapNotNull { (expiry, quotes) ->
                val years = (expiry - snapshot.atMs) / YEAR_MS
                if (years <= 0.0) null else Smile(years, quotes.map { Leg.of(it, listings.getValue(it.contract)) })
            }

    private class Leg(
        val strike: Double,
        val right: OptionRight,
        val iv: Double,
        val underlying: Double,
    ) {
        companion object {
            fun of(
                quote: ChainQuote,
                listing: OptionListing,
            ): Leg {
                val contract = listing.toContract()
                return Leg(
                    contract.strike.toDouble(),
                    contract.right,
                    requireNotNull(quote.markIv).toDouble(),
                    quote.underlying.toDouble(),
                )
            }
        }
    }

    private class Smile(
        val years: Double,
        val legs: List<Leg>,
    ) {
        private val forward = legs.map { it.underlying }.sorted().let { m -> (m[(m.size - 1) / 2] + m[m.size / 2]) / 2 }

        fun atmIv(): Double? {
            val ivByStrike = legs.groupBy { it.strike }.mapValues { (_, at) -> at.map { it.iv }.average() }
            val below = ivByStrike.keys.filter { it <= forward }.maxOrNull() ?: return null
            val above = ivByStrike.keys.filter { it >= forward }.minOrNull() ?: return null
            val band = MAX_MONEYNESS * forward
            if (forward - below > band || above - forward > band) return null
            val lo = ivByStrike.getValue(below)
            if (above == below) return lo
            return lo + (ivByStrike.getValue(above) - lo) * (forward - below) / (above - below)
        }

        fun skew(): Double? {
            val put = wing(OptionRight.PUT, -WING) ?: return null
            val call = wing(OptionRight.CALL, WING) ?: return null
            return put - call
        }

        private fun wing(
            right: OptionRight,
            target: Double,
        ): Double? {
            val points =
                legs
                    .filter { it.right == right }
                    .map { Black76.value(right, forward, it.strike, years, 0.0, it.iv / 100).delta to it.iv }
                    .sortedBy { it.first }
            val near = { d: Double -> abs(d - target) <= MAX_DELTA_GAP }
            val (lo, hi) =
                points.zipWithNext().firstOrNull { (a, b) ->
                    a.first <= target &&
                        target <= b.first &&
                        near(a.first) &&
                        near(b.first)
                }
                    ?: return null
            if (hi.first == lo.first) return lo.second
            return lo.second + (hi.second - lo.second) * (target - lo.first) / (hi.first - lo.first)
        }
    }
}
