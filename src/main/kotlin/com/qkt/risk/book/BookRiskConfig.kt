package com.qkt.risk.book

import java.math.BigDecimal

/**
 * Book-risk configuration (from `qkt.config.yaml` `book_risk:`). All sections optional; an absent
 * section disables that control. [capital] is the book basis caps are measured against; null falls
 * back to the run's starting balance / portfolio CAPITAL.
 */
data class BookRiskConfig(
    val capital: BigDecimal? = null,
    val limits: BookLimits? = null,
    val deRisk: DeRisk? = null,
    val allocation: Allocation? = null,
)

/** How the book splits risk across strategies. */
enum class AllocationMethod { FIXED, INVERSE_VOL, ERC, REGIME_WEIGHTED }

/**
 * Dynamic capital allocation across strategies.
 *
 * [FIXED], [INVERSE_VOL], and [ERC] are recomputed every [rebalanceEveryBars] samples from the
 * rolling cross-strategy covariance; weights are expressed as a tilt around 1.0 (FIXED = all 1.0,
 * leaving today's static CAPITAL x WEIGHT untouched). When [targetVol] is set, the whole weight
 * vector is scaled to hit that annualized vol, capped at [maxLeverage] gross.
 *
 * [REGIME_WEIGHTED] reads target fractions supplied by the portfolio's [com.qkt.dsl.portfolio.PortfolioGate];
 * it ignores [targetVol] and [rebalanceEveryBars] because regime changes drive rebalancing.
 */
data class Allocation(
    val method: AllocationMethod = AllocationMethod.FIXED,
    val targetVol: BigDecimal? = null,
    val rebalanceEveryBars: Int = 0,
    val maxLeverage: BigDecimal = BigDecimal("4"),
)

/** Book exposure caps, each expressed as a multiple of book capital (e.g. 3.0 = 3x capital). */
data class BookLimits(
    val maxGrossExposure: BigDecimal? = null,
    val maxNetExposure: BigDecimal? = null,
    val maxSymbolConcentration: BigDecimal? = null,
) {
    init {
        // A cap written in currency ("300000" on 100,000 of capital) reads as 300,000x capital: no cap
        // at all, silently. Nothing trades a book at a hundred times its capital, so refuse it.
        for ((key, value) in listOf(
            "max_gross_exposure" to maxGrossExposure,
            "max_net_exposure" to maxNetExposure,
            "max_symbol_concentration" to maxSymbolConcentration,
        )) {
            require(value == null || value.signum() > 0 && value <= MAX_MULTIPLE) {
                "book_risk.limits.$key is a multiple of book capital, not an amount of money: " +
                    "${value?.toPlainString()} means ${value?.toPlainString()}x capital. " +
                    "For a 300,000 cap on 100,000 of capital write 3.0 (allowed: above 0, up to $MAX_MULTIPLE)."
            }
        }
    }

    private companion object {
        val MAX_MULTIPLE: BigDecimal = BigDecimal(100)
    }
}

/** Graduated drawdown de-risking: an ordered ladder of rungs scaling new risk as the book draws down. */
data class DeRisk(
    val ladder: List<Rung>,
)

/**
 * One de-risk rung: when book drawdown reaches [drawdown] (a fraction, e.g. 0.04 = 4%), new
 * risk-increasing orders are scaled to [factor] of their size (0 = no new risk). [cooldownBars]
 * holds factor 0 for that many samples after the drawdown recovers (only meaningful on a 0 rung).
 */
data class Rung(
    val drawdown: BigDecimal,
    val factor: BigDecimal,
    val cooldownBars: Int? = null,
)
