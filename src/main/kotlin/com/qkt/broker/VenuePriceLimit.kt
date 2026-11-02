package com.qkt.broker

import java.math.BigDecimal

/**
 * How far from its reference a collared order (a gateway's market entry, a live roll's leg) may fill, a fraction.
 * Narrow enough for a venue to accept: Deribit refused a limit 25% from mark, its maximum being about 1.5% away.
 */
val ORDER_COLLAR_FRAC: BigDecimal = BigDecimal("0.01")

private val PRICE_LIMIT = Regex("""price_too_high|price_too_low|\b(110003|110120|110121)\b""")

/**
 * Whether a venue's refusal [reason] says an order's price lay beyond the venue's own limit (Deribit `price_too_high`,
 * `price_too_low`; Bybit 110003, 110120, 110121). The market may be back inside it a moment later, so a collared order
 * counts this as unfilled rather than as a refusal.
 */
fun isVenuePriceLimit(reason: String): Boolean = PRICE_LIMIT.containsMatchIn(reason)
