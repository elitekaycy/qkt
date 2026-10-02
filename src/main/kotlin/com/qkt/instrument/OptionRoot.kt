package com.qkt.instrument

import java.math.BigDecimal

/**
 * The static spec shared by every option contract of one family, declared once under `options:` in
 * `instruments.yaml`: [contractSize] underlying units per contract, premiums in [currency] on
 * [tickSteps], volume in [volumeStep] from [volumeMin], cash settlement against [underlyingIndex].
 * The metadata's `pointSize` is only the base tick: an option's price grid is [OptionTerms.tickSteps].
 *
 * Trading keys: [chains] names the stored chain series a backtest trades on (none: the root is
 * catalogued but not tradeable); a trade-built series needs [markSpread], the half-spread as a
 * fraction of the mark put around a trade mark at most [maxQuoteAgeMinutes] old. Fees: [takerFeeRate]
 * of the underlying per contract and [deliveryFeeRate] of the delivery price at expiry, each capped
 * at [feeCapRate] of the option's value when declared.
 */
data class OptionRoot(
    val root: String,
    val currency: String,
    val contractSize: BigDecimal,
    val tickSteps: TickSteps,
    val volumeStep: BigDecimal,
    val volumeMin: BigDecimal,
    val underlyingIndex: String,
    val exchangeFeePerContract: BigDecimal = BigDecimal.ZERO,
    val takerFeeRate: BigDecimal = BigDecimal.ZERO,
    val margin: MarginTerms? = null,
    val chains: QuoteSource? = null,
    val markSpread: BigDecimal? = null,
    val maxQuoteAgeMinutes: Int = 60,
    val feeCapRate: BigDecimal? = null,
    val deliveryFeeRate: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(ROOT_FORMAT.matches(root)) { "OptionRoot.root must be VENUE:ROOT: '$root'" }
        require(CURRENCY_FORMAT.matches(currency)) { "OptionRoot.currency must be a 3-5 letter code: '$currency'" }
        require(contractSize.signum() > 0) { "OptionRoot.contractSize must be > 0: $contractSize" }
        require(volumeStep.signum() > 0) { "OptionRoot.volumeStep must be > 0: $volumeStep" }
        require(volumeMin >= volumeStep) { "OptionRoot.volumeMin must be >= volumeStep: $volumeMin < $volumeStep" }
        require(underlyingIndex.isNotBlank()) { "OptionRoot.underlyingIndex must not be blank" }
        require(markSpread == null || (markSpread.signum() > 0 && markSpread < BigDecimal.ONE)) {
            "OptionRoot.markSpread must be a fraction above 0 and below 1: $markSpread"
        }
        require(chains != QuoteSource.TRADE || markSpread != null) {
            "OptionRoot.markSpread is required to trade on a trade-built chain (chains: trade)"
        }
        require(maxQuoteAgeMinutes > 0) { "OptionRoot.maxQuoteAgeMinutes must be > 0: $maxQuoteAgeMinutes" }
        require(feeCapRate == null || (feeCapRate.signum() > 0 && feeCapRate <= BigDecimal.ONE)) {
            "OptionRoot.feeCapRate must be a fraction above 0 and at most 1: $feeCapRate"
        }
        require(deliveryFeeRate.signum() >= 0) { "OptionRoot.deliveryFeeRate must be >= 0: $deliveryFeeRate" }
        require(takerFeeRate.signum() >= 0) { "OptionRoot.takerFeeRate must be >= 0: $takerFeeRate" }
    }

    /** The venue prefix of [root]: `DERIBIT` for `DERIBIT:BTC_USDC`. */
    val venue: String get() = root.substringBefore(':')

    /** Metadata for [qktSymbol], the root's [contract]. */
    fun metaFor(
        qktSymbol: String,
        contract: OptionContract,
    ): InstrumentMeta =
        InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = contractSize,
            volumeStep = volumeStep,
            volumeMin = volumeMin,
            volumeMax = null,
            pointSize = tickSteps.base,
            digits =
                tickSteps.base
                    .stripTrailingZeros()
                    .scale()
                    .coerceAtLeast(0),
            tradeStopsLevelPoints = 0,
            currency = currency,
            derivative =
                OptionTerms(
                    root = root,
                    underlyingIndex = underlyingIndex,
                    strike = contract.strike,
                    right = contract.right,
                    expiryMs = contract.expiryMs,
                    tickSteps = tickSteps,
                    margin = margin,
                    exchangeFeePerContract = exchangeFeePerContract,
                    takerFeeRate = takerFeeRate,
                ),
        )

    private companion object {
        val ROOT_FORMAT = Regex("[A-Z0-9_]+:[A-Z0-9_]+")
        val CURRENCY_FORMAT = Regex("[A-Z]{3,5}")
    }
}
