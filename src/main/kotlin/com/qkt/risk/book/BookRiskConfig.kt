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
enum class AllocationMethod { FIXED, INVERSE_VOL, ERC }

/**
 * Dynamic capital allocation across strategies, recomputed every [rebalanceEveryBars] samples from
 * the rolling cross-strategy covariance. [method] picks the weighting; weights are expressed as a
 * tilt around 1.0 (FIXED = all 1.0, leaving today's static CAPITAL x WEIGHT untouched). When
 * [targetVol] is set, the whole weight vector is scaled to hit that annualized vol, capped at
 * [maxLeverage] gross.
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
)

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
