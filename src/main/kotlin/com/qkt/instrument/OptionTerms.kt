package com.qkt.instrument

import java.math.BigDecimal

/** How an option may be exercised; qkt models European exercise only (E18). */
enum class OptionStyle { EUROPEAN, }

/** How an option settles at expiry; qkt models cash settlement against an index. */
enum class OptionSettlement { CASH, }

/**
 * A listed option contract: the right to buy ([OptionRight.CALL]) or sell the underlying measured by
 * [underlyingIndex] at [strike] on [expiryMs], settled in cash against that index. Premiums move on
 * [tickSteps]; the instrument's `contractSize` is the underlying quantity per contract.
 */
data class OptionTerms(
    override val root: String,
    val underlyingIndex: String,
    val strike: BigDecimal,
    val right: OptionRight,
    val expiryMs: Long,
    val tickSteps: TickSteps,
    val style: OptionStyle = OptionStyle.EUROPEAN,
    val settlement: OptionSettlement = OptionSettlement.CASH,
    override val margin: MarginTerms? = null,
    override val exchangeFeePerContract: BigDecimal = BigDecimal.ZERO,
    override val takerFeeRate: BigDecimal = BigDecimal.ZERO,
) : DerivativeTerms {
    init {
        require(ROOT_FORMAT.matches(root)) { "OptionTerms.root must be VENUE:ROOT: '$root'" }
        require(underlyingIndex.isNotBlank()) { "OptionTerms.underlyingIndex must not be blank" }
        require(strike.signum() > 0) { "OptionTerms.strike must be > 0: $strike" }
        require(expiryMs > 0) { "OptionTerms.expiryMs must be > 0: $expiryMs" }
        require(exchangeFeePerContract.signum() >= 0) {
            "OptionTerms.exchangeFeePerContract must be >= 0: $exchangeFeePerContract"
        }
        require(takerFeeRate.signum() >= 0 && takerFeeRate < BigDecimal.ONE) {
            "OptionTerms.takerFeeRate must be in [0, 1): $takerFeeRate"
        }
    }

    private companion object {
        val ROOT_FORMAT = Regex("[A-Z0-9_]+:[A-Z0-9_]+")
    }
}

/** One catalogued option contract of a root: its code (no venue), strike, right and expiry. */
data class OptionContract(
    val symbol: String,
    val strike: BigDecimal,
    val right: OptionRight,
    val expiryMs: Long,
) {
    init {
        require(symbol.isNotBlank()) { "OptionContract.symbol must not be blank" }
        require(strike.signum() > 0) { "OptionContract.strike must be > 0: $strike" }
        require(expiryMs > 0) { "OptionContract.expiryMs must be > 0: $expiryMs" }
    }
}
