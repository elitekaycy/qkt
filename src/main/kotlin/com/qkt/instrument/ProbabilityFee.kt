package com.qkt.instrument

import java.math.BigDecimal

/**
 * An event contract's per-order fee: rate × contracts × p × (1 − p), rounded up to [rounding]. [takerRate] applies
 * when the order takes liquidity, [makerRate] when it rested (null: no maker fee). Rates are fractions in [0, 1).
 */
data class ProbabilityFee(
    val takerRate: BigDecimal,
    val makerRate: BigDecimal?,
    val rounding: BigDecimal,
) {
    init {
        require(takerRate.isRate()) { "ProbabilityFee.takerRate must be in [0, 1): $takerRate" }
        require(makerRate == null || makerRate.isRate()) { "ProbabilityFee.makerRate must be in [0, 1): $makerRate" }
        require(rounding.signum() > 0) { "ProbabilityFee.rounding must be > 0: $rounding" }
    }

    private fun BigDecimal.isRate(): Boolean = signum() >= 0 && this < BigDecimal.ONE
}
