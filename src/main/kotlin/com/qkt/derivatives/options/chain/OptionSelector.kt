package com.qkt.derivatives.options.chain

import com.qkt.derivatives.options.pricing.Black76
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRight
import kotlin.math.abs

/**
 * What one structure leg asks for: a [right] whose |delta| is nearest [delta], in the nearest expiry
 * whose days to expiry fall in [minDays]..[maxDays], or at exactly [expiryMs] (`SAME EXPIRY`).
 */
data class LegCriteria(
    val right: OptionRight,
    val delta: Double,
    val minDays: Double? = null,
    val maxDays: Double? = null,
    val expiryMs: Long? = null,
) {
    init {
        require(delta > 0.0 && delta < 1.0) { "a leg's target delta is between 0 and 1: $delta" }
        require((expiryMs != null) != (minDays != null && maxDays != null)) {
            "a leg names an expiry or a days-to-expiry window, not both"
        }
        require(minDays == null || maxDays == null || (minDays >= 0.0 && minDays <= maxDays)) {
            "days window $minDays..$maxDays"
        }
    }
}

/** The contract a leg selects: its venue name, expiry and Black-76 delta, and the quote it came from. */
data class SelectedOption(
    val contract: String,
    val expiryMs: Long,
    val delta: Double,
    val quote: ChainQuote,
)

/**
 * The one deterministic leg selector (spec §6.4), over a single snapshot. Only catalogued, unexpired
 * quotes with a positive mark IV no older than the quote age count. The expiry is the nearest one in
 * the leg's days window (or the given expiry). Within it, the quote of the right whose Black-76
 * |delta| is nearest the target wins, ties going to the lower strike. Delta uses that expiry's median
 * `underlying` as the forward and rate 0. Null when nothing qualifies.
 */
object OptionSelector {
    private const val DAY_MS = 86_400_000.0
    private const val YEAR_MS = 365 * DAY_MS

    /** The contract [criteria] selects in [snapshot], with terms from [listings]; null when none qualifies. */
    fun select(
        snapshot: ChainSnapshot,
        listings: Map<String, OptionListing>,
        criteria: LegCriteria,
        maxQuoteAgeMs: Long,
    ): SelectedOption? {
        val usable =
            snapshot.quotes.filter { q ->
                val listing = listings[q.contract]
                listing != null &&
                    listing.expiryMs > snapshot.atMs &&
                    (q.markIv?.signum() ?: 0) > 0 &&
                    q.markAgeMs <= maxQuoteAgeMs
            }
        val byExpiry = usable.groupBy { listings.getValue(it.contract).expiryMs }
        val expiry =
            criteria.expiryMs ?: byExpiry.keys.sorted().firstOrNull { e ->
                val days = (e - snapshot.atMs) / DAY_MS
                days >= requireNotNull(criteria.minDays) && days <= requireNotNull(criteria.maxDays)
            } ?: return null
        val quotes = byExpiry[expiry] ?: return null
        val forward =
            quotes.map { it.underlying.toDouble() }.sorted().let { m ->
                (m[(m.size - 1) / 2] + m[m.size / 2]) /
                    2
            }
        val years = (expiry - snapshot.atMs) / YEAR_MS
        return quotes
            .map { it to listings.getValue(it.contract).toContract() }
            .filter { (_, contract) -> contract.right == criteria.right }
            .map { (quote, contract) ->
                val delta =
                    Black76
                        .value(
                            criteria.right,
                            forward,
                            contract.strike.toDouble(),
                            years,
                            0.0,
                            requireNotNull(quote.markIv).toDouble() / 100,
                        ).delta
                Triple(quote, contract.strike, delta)
            }.minWithOrNull(compareBy({ abs(abs(it.third) - criteria.delta) }, { it.second }))
            ?.let { (quote, _, delta) -> SelectedOption(quote.contract, expiry, delta, quote) }
    }
}
