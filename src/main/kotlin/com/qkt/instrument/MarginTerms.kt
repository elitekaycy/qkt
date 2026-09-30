package com.qkt.instrument

import java.math.BigDecimal

/** How a margin figure scales with a position. */
enum class MarginBasis {
    /** A fixed amount per contract, in the instrument's currency (CME: ES initial 14,000). */
    PER_CONTRACT,

    /** A fraction of notional, `quantity × price × contractSize × rate` (crypto: 0.05 = 20x). */
    NOTIONAL,
}

/**
 * Initial and maintenance margin for one contract of a futures or options instrument. Initial
 * margin gates new exposure; falling below maintenance is a margin call. With
 * [MarginBasis.NOTIONAL] both figures are rates in (0, 1].
 */
data class MarginTerms(
    val initial: BigDecimal,
    val maintenance: BigDecimal,
    val basis: MarginBasis,
) {
    init {
        require(initial.signum() > 0) { "MarginTerms.initial must be > 0: $initial" }
        require(maintenance.signum() > 0) { "MarginTerms.maintenance must be > 0: $maintenance" }
        require(maintenance <= initial) { "MarginTerms.maintenance must be <= initial: $maintenance > $initial" }
        require(basis != MarginBasis.NOTIONAL || initial <= BigDecimal.ONE) {
            "MarginTerms with NOTIONAL basis are rates and must be <= 1: $initial"
        }
    }
}
