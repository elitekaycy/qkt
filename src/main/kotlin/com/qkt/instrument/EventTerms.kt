package com.qkt.instrument

import java.math.BigDecimal

/**
 * A binary event contract: it pays [payout] per contract when its question resolves YES and 0 when NO, so its price
 * lives in [0, payout] and reads as a probability. The instrument's `contractSize` is 1 and `pointSize` its finest
 * tick.
 *
 * [root] is the family, `VENUE:SERIES` (`KALSHI:KXINXU`), and [event] one instance of it (one close time, one game).
 * The question reads [predicate]. Trading opens at [openAtMs] and stops by [closeAtMs] (earlier when [canCloseEarly]);
 * the result is expected at [expiryMs]. [occursAtMs] is when the thing asked about happens (a game's start, a window's
 * end), when known. [occasion] is the venue's key shared by families about the same occasion (one game's winner,
 * spread and total). [exclusive] says the event's contracts partition its outcomes, so exactly one resolves YES.
 * [frequency] is the series' cadence as the venue gateway states it (`1h`, `1d`, `one_off`), [title] the contract's
 * human label, [fee] its per-order fee shape and [outcome] the settled value once known.
 *
 * The [DerivativeTerms] members are fixed: no [margin] (an event's premium is its whole risk), and no per-contract or
 * notional fee (the fee lives in [fee]).
 */
data class EventTerms(
    override val root: String,
    val event: String,
    val predicate: StrikePredicate,
    val openAtMs: Long,
    val closeAtMs: Long,
    val expiryMs: Long,
    val occursAtMs: Long? = null,
    val occasion: String? = null,
    val canCloseEarly: Boolean = false,
    val exclusive: Boolean = false,
    val frequency: String? = null,
    val title: String? = null,
    val payout: BigDecimal = BigDecimal.ONE,
    val fee: ProbabilityFee? = null,
    val outcome: BigDecimal? = null,
) : DerivativeTerms {
    override val margin: MarginTerms? get() = null

    override val exchangeFeePerContract: BigDecimal get() = BigDecimal.ZERO

    override val takerFeeRate: BigDecimal get() = BigDecimal.ZERO

    init {
        require(ROOT_FORMAT.matches(root)) { "EventTerms.root must be VENUE:SERIES: '$root'" }
        require(event.isNotBlank()) { "EventTerms.event must not be blank ($root)" }
        require(payout.signum() > 0) { "EventTerms.payout must be > 0: $payout" }
        require(closeAtMs >= openAtMs) { "EventTerms.closeAtMs must be >= openAtMs: $closeAtMs < $openAtMs ($event)" }
        require(outcome == null || (outcome.signum() >= 0 && outcome <= payout)) {
            "EventTerms.outcome must be in [0, $payout]: $outcome ($event)"
        }
    }

    private companion object {
        val ROOT_FORMAT = Regex("[A-Z0-9_]+:[A-Za-z0-9_]+")
    }
}
