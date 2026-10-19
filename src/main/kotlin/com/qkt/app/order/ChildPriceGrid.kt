package com.qkt.app.order

import com.qkt.common.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Puts a bracket child's price on the instrument's price grid, so a stop or target computed as
 * `fill ± distance` is a price the venue accepts. A fill at a half-point mid (an MT5 quote's last
 * is the bid/ask mid) or a distance written as a percentage, an RR multiple or an ATR expression
 * otherwise yields a level between two ticks, which a venue rejects or rounds its own way.
 *
 * The rule: the nearest tick, and an exact tie goes toward [entry]. Toward the entry is the
 * conservative side for both legs: a stop never risks more than the distance written, and a target
 * never promises more. A level that would land on or past the entry is pushed one tick away from it
 * instead, so a sub-tick distance still leaves the exit on the side it was written for.
 *
 * Null [tick] (no instrument metadata for the symbol) leaves [price] as it is.
 */
internal fun snapToTick(
    price: BigDecimal,
    tick: BigDecimal?,
    entry: BigDecimal,
): BigDecimal {
    if (tick == null || tick.signum() <= 0 || price.compareTo(entry) == 0) return price
    val above = price > entry
    val units = price.divide(tick, TICK_UNITS_SCALE, RoundingMode.HALF_EVEN)
    // An exact half rounds toward the entry: down for a level above it, up for one below. HALF_DOWN
    // and HALF_UP are relative to zero, so a negative price (a spread) flips which one that is.
    val towardEntry = if (above == (units.signum() >= 0)) RoundingMode.HALF_DOWN else RoundingMode.HALF_UP
    val nearest = units.setScale(0, towardEntry)
    val entryUnits = entry.divide(tick, TICK_UNITS_SCALE, RoundingMode.HALF_EVEN)
    // A level that rounding put on or past the entry stays one tick on its own side of it.
    val snappedUnits =
        when {
            above && nearest <= entryUnits -> entryUnits.setScale(0, RoundingMode.FLOOR) + BigDecimal.ONE
            !above && nearest >= entryUnits -> entryUnits.setScale(0, RoundingMode.CEILING) - BigDecimal.ONE
            else -> nearest
        }
    return snappedUnits.multiply(tick).setScale(Money.SCALE, Money.ROUNDING)
}

private const val TICK_UNITS_SCALE = 12
