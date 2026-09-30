package com.qkt.derivatives.futures

import com.qkt.common.Money
import com.qkt.instrument.PriceAdjustment
import java.math.BigDecimal

/**
 * A contract price in its continuous series, for a contract at [shift] under [adjustment]. Fails when
 * the series would reach zero or below — panama over a long, falling chain can do that, ratio cannot.
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
        "continuous price $continuous (raw $raw) is not positive; use 'adjust: ratio' for this root"
    }
    return continuous
}
