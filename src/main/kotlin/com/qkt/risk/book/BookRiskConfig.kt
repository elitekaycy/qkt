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
)

/** Book exposure caps, each expressed as a multiple of book capital (e.g. 3.0 = 3x capital). */
data class BookLimits(
    val maxGrossExposure: BigDecimal? = null,
    val maxNetExposure: BigDecimal? = null,
    val maxSymbolConcentration: BigDecimal? = null,
)
