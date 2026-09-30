package com.qkt.instrument

import java.math.BigDecimal

/**
 * The exchange-listed terms that make an instrument a derivative. Absent on CFDs and spot, which
 * keep every existing behaviour. New derivative kinds are new subtypes, so every consumer's `when`
 * must handle them.
 */
sealed interface DerivativeTerms {
    /** The contract family, venue-qualified: `CME:ES`, `BINANCE_UM:BTCUSDT`. */
    val root: String

    /** Margin per contract, or null when the venue's own margin is authoritative. */
    val margin: MarginTerms?

    /** Exchange and clearing fee per contract per side, in the instrument's currency. */
    val exchangeFeePerContract: BigDecimal

    /** Taker fee as a fraction of notional per side (crypto venues), 0 when fees are per contract. */
    val takerFeeRate: BigDecimal
}

/**
 * A dated futures contract, or a continuous view of its root when [expiryMs] is null. The
 * instrument's `contractSize` is the multiplier and `pointSize` the tick size.
 */
data class FutureTerms(
    override val root: String,
    val expiryMs: Long?,
    override val margin: MarginTerms? = null,
    override val exchangeFeePerContract: BigDecimal = BigDecimal.ZERO,
    override val takerFeeRate: BigDecimal = BigDecimal.ZERO,
) : DerivativeTerms {
    init {
        require(ROOT_FORMAT.matches(root)) { "FutureTerms.root must be VENUE:ROOT: '$root'" }
        require(expiryMs == null || expiryMs > 0) { "FutureTerms.expiryMs must be > 0: $expiryMs" }
        require(exchangeFeePerContract.signum() >= 0) {
            "FutureTerms.exchangeFeePerContract must be >= 0: $exchangeFeePerContract"
        }
        require(takerFeeRate.signum() >= 0 && takerFeeRate < BigDecimal.ONE) {
            "FutureTerms.takerFeeRate must be in [0, 1): $takerFeeRate"
        }
    }

    private companion object {
        val ROOT_FORMAT = Regex("[A-Z0-9_]+:[A-Z0-9_]+")
    }
}
