package com.qkt.derivatives.futures

import com.qkt.common.Money
import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal

/**
 * A contract price in its continuous series, for a contract at [shift] under [adjustment]. Fails when
 * the series would reach zero or below — panama over a long chain in steep contango can do that
 * (crude in 2020), ratio cannot.
 */
internal fun continuousPrice(
    adjustment: PriceAdjustment,
    shift: BigDecimal,
    raw: BigDecimal,
): BigDecimal {
    val continuous =
        when (adjustment) {
            PriceAdjustment.NONE -> raw
            PriceAdjustment.PANAMA -> raw.add(shift)
            PriceAdjustment.RATIO -> raw.multiply(shift, Money.CONTEXT)
        }
    require(continuous.signum() > 0) {
        "continuous price $continuous (raw $raw) is not positive; anchor the series nearer this contract " +
            "with roll.anchor, or use 'adjust: ratio' (read-only) for this root"
    }
    return continuous
}
