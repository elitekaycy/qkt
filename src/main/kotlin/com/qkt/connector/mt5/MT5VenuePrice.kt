package com.qkt.connector.mt5

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * A price as the gateway sends it, without float noise. The gateway is Python: a price it computes is a double,
 * printed with up to 17 significant digits (`4127.5830000000005` for 4127.583, live 2026-10-20). Venue prices carry
 * far fewer, so rounding to 15 significant digits drops the noise and never touches a real price.
 */
internal fun venuePrice(raw: String?): BigDecimal? {
    val parsed = raw?.toBigDecimalOrNull() ?: return null
    if (parsed.precision() <= SIGNIFICANT) return parsed
    val rounded = parsed.round(MathContext(SIGNIFICANT, RoundingMode.HALF_EVEN)).stripTrailingZeros()
    return if (rounded.scale() < 0) rounded.setScale(0) else rounded
}

private const val SIGNIFICANT = 15
