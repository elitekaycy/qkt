package com.qkt.instrument

import java.math.BigDecimal

/**
 * The static spec shared by every contract of one futures family, declared once under `futures:`
 * in `instruments.yaml`. [multiplier] becomes each contract's `contractSize` and [tickSize] its
 * `pointSize`; fees and margin are in [currency]. [roll] is required for continuous streams of the
 * root and unused by explicit contract streams. [slippageTicks] is the adverse execution slip each
 * contract carries as its `slippagePoints` (ticks are the contract's points), applied when a run
 * uses instrument slippage. [expiryGuardHours] before a contract's expiry only exits are accepted.
 * [perpetual] names the root's perpetual contract without its venue (`BTC_USDC_PERPETUAL`), if it has one.
 */
data class FuturesRoot(
    val root: String,
    val currency: String,
    val multiplier: BigDecimal,
    val tickSize: BigDecimal,
    val volumeStep: BigDecimal,
    val volumeMin: BigDecimal,
    val volumeMax: BigDecimal?,
    val calendar: String?,
    val exchangeFeePerContract: BigDecimal,
    val takerFeeRate: BigDecimal,
    val margin: MarginTerms?,
    val roll: RollPolicy? = null,
    val slippageTicks: Int = 0,
    val expiryGuardHours: Int = 24,
    val perpetual: String? = null,
) {
    /** The venue prefix of [root]: `CME` for `CME:ES`. */
    val venue: String get() = root.substringBefore(':')

    /** The family name of [root]: `ES` for `CME:ES`. */
    val symbol: String get() = root.substringAfter(':')

    /**
     * Metadata for [qktSymbol], a contract of this root expiring at [expiryMs] (null for a continuous
     * view, or for the [perpetual] contract when [isPerpetual]).
     */
    fun metaFor(
        qktSymbol: String,
        expiryMs: Long?,
        isPerpetual: Boolean = false,
    ): InstrumentMeta =
        InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = multiplier,
            volumeStep = volumeStep,
            volumeMin = volumeMin,
            volumeMax = volumeMax,
            pointSize = tickSize,
            digits = tickSize.stripTrailingZeros().scale().coerceAtLeast(0),
            tradeStopsLevelPoints = 0,
            slippagePoints = slippageTicks,
            currency = currency,
            derivative =
                FutureTerms(
                    root,
                    expiryMs,
                    margin,
                    exchangeFeePerContract,
                    takerFeeRate,
                    expiryGuardHours,
                    isPerpetual,
                ),
        )
}
