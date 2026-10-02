package com.qkt.instrument

import java.math.BigDecimal
import java.time.DayOfWeek

/**
 * Per-instrument venue metadata used to size orders correctly and quantize wire fields.
 *
 * Owned by [InstrumentRegistry]; populated either from the broker's `/symbol_info` cache
 * (live) or from a YAML manifest (backtest). The [contractSize] field is what makes
 * PaperBroker PnL directly comparable to live MT5 PnL — strategies stop needing the
 * `/100`-style sizing workarounds that the engine inherited before this primitive existed.
 *
 * [commissionPerLot] is the broker's commission per 1.0 lot per side, in account currency
 * (e.g. 3.50 = $3.50 per lot per fill). It lets a backtest subtract the same trading cost
 * the live venue bills. Left at zero, fills are commission-free (the pre-cost-model
 * behavior). Live runs leave it zero: the real broker already deducts commission from the
 * account, so the engine must not simulate it on top.
 *
 * [slippagePoints] is adverse execution slip in venue points (each [pointSize] wide), applied by
 * the mt5-sim broker on every fill — e.g. 5 on a 0.001-pointSize symbol slips a fill by 0.005
 * against you. Left at zero, fills have no slippage (the optimistic default); live runs leave it
 * zero because the real venue slips for real.
 *
 * [swapLongPoints] and [swapShortPoints] are signed venue points per lot per rollover:
 * positive values credit the position and negative values debit it. Backtests convert them
 * through `pointSize * contractSize * quantity`; live runs leave them zero because the venue
 * reports actual swap. [swapRolloverHourUtc] and [swapTripleDay] define the deterministic
 * backtest calendar convention.
 *
 * [spreadPoints] and [minSpreadPoints] state this venue's spread for the mt5-sim broker when the
 * backtest's ticks come from another source (a vendor's history priced at its own spread). Set at most
 * one: [spreadPoints] fills at the tick's mid ± half that many points whatever spread the tick carries
 * (e.g. 260 on Dukascopy XAUUSD ticks fills at mid ± 130 points); [minSpreadPoints] only widens, so a
 * tick quoting at least that spread fills at its own bid/ask and a thinner one is widened around its
 * mid. Both also apply to mid-only ticks in place of the broker's synthetic spread. Unset, fills use
 * the tick's own bid/ask. Live runs ignore them: the venue quotes its own spread.
 *
 * [currency] is the quote/settlement currency when stated explicitly; absent, it is inferred from
 * the symbol. [derivative] carries exchange-listed terms (expiry, margin, fees) and is absent for
 * CFDs and spot.
 */
data class InstrumentMeta(
    val qktSymbol: String,
    val contractSize: BigDecimal,
    val volumeStep: BigDecimal,
    val volumeMin: BigDecimal,
    val volumeMax: BigDecimal?,
    val pointSize: BigDecimal,
    val digits: Int,
    val tradeStopsLevelPoints: Int,
    val commissionPerLot: BigDecimal = BigDecimal.ZERO,
    val slippagePoints: Int = 0,
    val swapLongPoints: BigDecimal = BigDecimal.ZERO,
    val swapShortPoints: BigDecimal = BigDecimal.ZERO,
    val swapRolloverHourUtc: Int = 21,
    val swapTripleDay: DayOfWeek = DayOfWeek.WEDNESDAY,
    val spreadPoints: Int? = null,
    val minSpreadPoints: Int? = null,
    val currency: String? = null,
    val derivative: DerivativeTerms? = null,
) {
    init {
        require(qktSymbol.isNotBlank()) { "InstrumentMeta.qktSymbol must not be blank" }
        require(contractSize.signum() > 0) { "InstrumentMeta.contractSize must be > 0: $contractSize" }
        require(volumeStep.signum() > 0) { "InstrumentMeta.volumeStep must be > 0: $volumeStep" }
        require(volumeMin.signum() > 0) { "InstrumentMeta.volumeMin must be > 0: $volumeMin" }
        require(volumeMax == null || volumeMax >= volumeMin) {
            "InstrumentMeta.volumeMax must be >= volumeMin: $volumeMax < $volumeMin"
        }
        require(pointSize.signum() > 0) { "InstrumentMeta.pointSize must be > 0: $pointSize" }
        require(digits >= 0) { "InstrumentMeta.digits must be >= 0: $digits" }
        require(tradeStopsLevelPoints >= 0) {
            "InstrumentMeta.tradeStopsLevelPoints must be >= 0: $tradeStopsLevelPoints"
        }
        require(commissionPerLot.signum() >= 0) {
            "InstrumentMeta.commissionPerLot must be >= 0: $commissionPerLot"
        }
        require(slippagePoints >= 0) {
            "InstrumentMeta.slippagePoints must be >= 0: $slippagePoints"
        }
        require(spreadPoints == null || spreadPoints >= 0) { "InstrumentMeta.spreadPoints must be >= 0: $spreadPoints" }
        require(minSpreadPoints == null || minSpreadPoints >= 0) {
            "InstrumentMeta.minSpreadPoints must be >= 0: $minSpreadPoints"
        }
        require(spreadPoints == null || minSpreadPoints == null) {
            "InstrumentMeta: set spreadPoints or minSpreadPoints, not both ($qktSymbol)"
        }
        require(swapRolloverHourUtc in 0..23) {
            "InstrumentMeta.swapRolloverHourUtc must be in 0..23: $swapRolloverHourUtc"
        }
        require(swapTripleDay.value <= DayOfWeek.FRIDAY.value) {
            "InstrumentMeta.swapTripleDay must be Monday through Friday: $swapTripleDay"
        }
        require(currency == null || CURRENCY_FORMAT.matches(currency)) {
            "InstrumentMeta.currency must be an upper-case code of 3-5 letters: '$currency'"
        }
    }

    private companion object {
        val CURRENCY_FORMAT = Regex("[A-Z]{3,5}")
    }
}
