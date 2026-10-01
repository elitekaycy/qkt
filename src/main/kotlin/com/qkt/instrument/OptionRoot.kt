package com.qkt.instrument

import java.math.BigDecimal

/**
 * The static spec shared by every option contract of one family, declared once under `options:` in
 * `instruments.yaml`: [contractSize] underlying units per contract, premiums in [currency] on
 * [tickSteps], volume in [volumeStep] from [volumeMin], cash settlement against [underlyingIndex].
 * The metadata's `pointSize` is only the base tick: an option's price grid is [OptionTerms.tickSteps].
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
) {
    init {
        require(ROOT_FORMAT.matches(root)) { "OptionRoot.root must be VENUE:ROOT: '$root'" }
        require(CURRENCY_FORMAT.matches(currency)) { "OptionRoot.currency must be a 3-5 letter code: '$currency'" }
        require(contractSize.signum() > 0) { "OptionRoot.contractSize must be > 0: $contractSize" }
        require(volumeStep.signum() > 0) { "OptionRoot.volumeStep must be > 0: $volumeStep" }
        require(volumeMin >= volumeStep) { "OptionRoot.volumeMin must be >= volumeStep: $volumeMin < $volumeStep" }
        require(underlyingIndex.isNotBlank()) { "OptionRoot.underlyingIndex must not be blank" }
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
