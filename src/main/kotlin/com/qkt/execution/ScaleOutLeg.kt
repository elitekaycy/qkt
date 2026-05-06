package com.qkt.execution

import java.math.BigDecimal

data class ScaleOutLeg(
    val priceTarget: BigDecimal,
    val fraction: BigDecimal,
) {
    init {
        require(priceTarget.signum() > 0) { "priceTarget must be > 0: $priceTarget" }
        require(fraction.signum() > 0 && fraction <= BigDecimal.ONE) {
            "fraction must be in (0, 1]: $fraction"
        }
    }
}
