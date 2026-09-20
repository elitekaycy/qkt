package com.qkt.app.order

import com.qkt.common.Money
import com.qkt.common.Side
import java.math.BigDecimal

// Level arithmetic shared by engine-held stops and the intrabar fill check. `exitSide` is the
// side of the protective order: SELL protects a long (stop below), BUY protects a short.

/** Stop level [distance] away from [entryPrice], on the losing side of the position. */
internal fun initialStopLevel(
    exitSide: Side,
    entryPrice: BigDecimal,
    distance: BigDecimal,
): BigDecimal =
    if (exitSide == Side.SELL) {
        entryPrice.subtract(distance, Money.CONTEXT)
    } else {
        entryPrice.add(distance, Money.CONTEXT)
    }

/** Stop level [profitDistance] away from [entryPrice], on the winning side (a locked-in profit). */
internal fun profitStopLevel(
    exitSide: Side,
    entryPrice: BigDecimal,
    profitDistance: BigDecimal,
): BigDecimal =
    if (exitSide == Side.SELL) {
        entryPrice.add(profitDistance, Money.CONTEXT)
    } else {
        entryPrice.subtract(profitDistance, Money.CONTEXT)
    }

/** True when [candidate] protects more of the position than [current]; stops only tighten. */
internal fun isTighter(
    exitSide: Side,
    candidate: BigDecimal,
    current: BigDecimal,
): Boolean = if (exitSide == Side.SELL) candidate > current else candidate < current

/** Quantity-weighted average of a prior fill average and a new fill slice. */
internal fun blendAvg(
    oldAvg: BigDecimal?,
    oldQty: BigDecimal,
    newPrice: BigDecimal,
    newQty: BigDecimal,
): BigDecimal {
    if (oldAvg == null || oldQty.signum() == 0) return newPrice
    val totalQty = oldQty + newQty
    return (oldAvg * oldQty + newPrice * newQty)
        .divide(totalQty, Money.CONTEXT)
        .setScale(Money.SCALE, Money.ROUNDING)
}

// A stop / stop-limit trigger needs the bar to reach UP to the level for a buy (high >= level),
// or DOWN for a sell (low <= level). Direction-aware, so a gap-open through the level counts.
internal fun stopReached(
    side: Side,
    low: BigDecimal,
    high: BigDecimal,
    level: BigDecimal,
): Boolean = if (side == Side.BUY) high >= level else low <= level

// A limit / if-touched fill needs a dip DOWN to the level for a buy (low <= level), or a rise UP
// for a sell (high >= level).
internal fun limitReached(
    side: Side,
    low: BigDecimal,
    high: BigDecimal,
    level: BigDecimal,
): Boolean = if (side == Side.BUY) low <= level else high >= level
