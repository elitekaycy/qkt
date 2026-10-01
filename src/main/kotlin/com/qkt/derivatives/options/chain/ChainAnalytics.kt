package com.qkt.derivatives.options.chain

import com.qkt.derivatives.options.pricing.Black76
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRight
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Implied-volatility analytics of one chain snapshot at a tenor, from quotes with a mark IV no older
 * than the quote age. Per expiry (years T > 0 on a 365-day year): the forward is the median
 * `underlying`, ATM IV the mean mark IV at the listed strike nearest the forward (lower on a tie), and
 * the 25-delta skew the put IV at delta −0.25 less the call IV at +0.25, each interpolated linearly in
 * Black-76 delta between the bracketing quotes. Across expiries ATM IV interpolates total variance
 * `IV²·T` and skew interpolates linearly in T; a tenor outside the listed expiries, or without the
 * pieces it needs, has no value. Nothing is extrapolated.
 */
object ChainAnalytics {
    private const val YEAR_MS = 365.0 * 24 * 3600 * 1000
    private const val WING = 0.25

    /** [metric] at [tenorDays] in [snapshot], with contract terms from [listings]; null when not defined. */
    fun value(
        metric: ChainMetric,
        tenorDays: Int,
        snapshot: ChainSnapshot,
        listings: Map<String, OptionListing>,
        maxQuoteAgeMs: Long,
    ): Double? {
        require(tenorDays > 0) { "chain tenor must be > 0 days: $tenorDays" }
        val smiles = smiles(snapshot, listings, maxQuoteAgeMs)
        val points =
            smiles.mapNotNull { s ->
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
        points.firstOrNull { it.first == tau }?.let { return it.second }
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
            .filter { it.markIv != null && it.markAgeMs <= maxQuoteAgeMs && it.contract in listings }
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
        val rate: Double,
    ) {
        companion object {
            fun of(
                quote: ChainQuote,
                listing: OptionListing,
            ): Leg {
                val contract = listing.toContract()
                val iv = requireNotNull(quote.markIv).toDouble()
                return Leg(
                    contract.strike.toDouble(),
                    contract.right,
                    iv,
                    quote.underlying.toDouble(),
                    quote.rate?.toDouble() ?: 0.0,
                )
            }
        }
    }

    private class Smile(
        val years: Double,
        val legs: List<Leg>,
    ) {
        private val forward = legs.map { it.underlying }.sorted().let { m -> (m[(m.size - 1) / 2] + m[m.size / 2]) / 2 }

        fun atmIv(): Double {
            val strike = legs.map { it.strike }.distinct().minWith(compareBy({ abs(it - forward) }, { it }))
            return legs.filter { it.strike == strike }.map { it.iv }.average()
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
                    .map { Black76.value(right, forward, it.strike, years, it.rate, it.iv / 100).delta to it.iv }
                    .sortedBy { it.first }
            val (lo, hi) =
                points.zipWithNext().firstOrNull { (a, b) -> a.first <= target && target <= b.first }
                    ?: return null
            if (hi.first == lo.first) return lo.second
            return lo.second + (hi.second - lo.second) * (target - lo.first) / (hi.first - lo.first)
        }
    }
}
