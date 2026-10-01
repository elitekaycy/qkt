package com.qkt.derivatives.options

import com.qkt.common.Money
import com.qkt.instrument.OptionRight
import java.math.BigDecimal

/** What a European option pays at expiry, per unit of the underlying. */
object OptionPayoff {
    /** The intrinsic value of a [right] struck at [strike] with the underlying settling at [settle]: `max(S − K, 0)` or `max(K − S, 0)`. */
    fun intrinsic(
        right: OptionRight,
        strike: BigDecimal,
        settle: BigDecimal,
    ): BigDecimal =
        when (right) {
            OptionRight.CALL -> settle.subtract(strike)
            OptionRight.PUT -> strike.subtract(settle)
        }.max(BigDecimal.ZERO).setScale(Money.SCALE, Money.ROUNDING)
}
