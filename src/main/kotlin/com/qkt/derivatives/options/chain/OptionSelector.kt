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
        val window = minDays != null && maxDays != null
        require(window != (expiryMs != null) && (window || (minDays == null && maxDays == null))) {
            "a leg names a whole days window (minDays and maxDays) or an expiry, not both and not part of a window"
        }
        require(!window || (requireNotNull(minDays) >= 0.0 && minDays <= requireNotNull(maxDays))) {
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
 * the leg's days window that has a usable quote of the leg's right (or the given expiry). Within it,
 * the quote of that right whose Black-76 |delta| is nearest the target wins, ties going to the lower
 * strike. Delta uses the expiry's median `underlying` over all its usable quotes as the forward, and
 * rate 0. Null when nothing qualifies.
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
        val usable = usableQuotes(snapshot, listings, maxQuoteAgeMs)
        val byExpiry = usable.groupBy { listings.getValue(it.contract).expiryMs }
        val ofRight = { expiry: Long ->
            byExpiry[expiry].orEmpty().filter {
                listings.getValue(it.contract).toContract().right ==
                    criteria.right
            }
        }
        val expiry =
            criteria.expiryMs ?: byExpiry.keys.sorted().firstOrNull { e ->
                val days = (e - snapshot.atMs) / DAY_MS
                days >= requireNotNull(criteria.minDays) &&
                    days <= requireNotNull(criteria.maxDays) &&
                    ofRight(e).isNotEmpty()
            } ?: return null
        val candidates = ofRight(expiry).ifEmpty { return null }
        val forward = medianForward(byExpiry.getValue(expiry))
        val years = (expiry - snapshot.atMs) / YEAR_MS
        return candidates
            .map { quote ->
                val contract = listings.getValue(quote.contract).toContract()
                val sigma = requireNotNull(quote.markIv).toDouble() / 100
                Triple(
                    quote,
                    contract.strike,
                    Black76.value(criteria.right, forward, contract.strike.toDouble(), years, 0.0, sigma).delta,
                )
            }.minWithOrNull(compareBy({ abs(abs(it.third) - criteria.delta) }, { it.second }))
            ?.let { (quote, _, delta) -> SelectedOption(quote.contract, expiry, delta, quote) }
    }
}
