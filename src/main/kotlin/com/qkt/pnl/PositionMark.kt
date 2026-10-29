package com.qkt.pnl

import java.math.BigDecimal

/**
 * One open position valued now: the [mark] it could be closed at (bid for a long, ask for a short) and
 * its [unrealized] P&L in account currency, null when the quote currency has no rate to the account's.
 */
data class PositionMark(
    val mark: BigDecimal,
    val unrealized: BigDecimal?,
)

/**
 * Values a position qkt did not book itself, such as a venue ticket that states no mark, by the rules qkt
 * values its own: e.g. a long 0.001 BTC_USDC_PERPETUAL at 79994.8 marked at a 80100.0 bid is worth
 * `(80100.0 - 79994.8) x 0.001 x contractSize` in USDC, converted to the account currency.
 */
fun interface PositionMarks {
    /** [symbol]'s position of [signedQuantity] (negative: short) entered at [entryPrice]; null with no mark yet. */
    fun markOf(
        symbol: String,
        signedQuantity: BigDecimal,
        entryPrice: BigDecimal,
    ): PositionMark?
}
