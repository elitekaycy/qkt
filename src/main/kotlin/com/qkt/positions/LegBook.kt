package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Container of [PositionLeg]s for a single (strategy, symbol).
 *
 * Replaces the singular [Position] model when a strategy uses `STACK_AT` clauses to open
 * independent stack legs alongside the primary. The [netView] derives a [Position] from
 * the legs so the existing DSL `POSITION.<stream>` accessor (which returns a scalar)
 * continues to work unchanged.
 *
 * Single-PRIMARY invariant: at most one leg with [LegRole.PRIMARY] per book. Multiple
 * STACK legs are allowed. The PRIMARY may close before its STACK children — the stacks
 * survive and continue to be tracked.
 */
class LegBook(
    val symbol: String,
) {
    private val legs: MutableMap<String, PositionLeg> = ConcurrentHashMap()

    fun add(leg: PositionLeg) {
        require(leg.symbol == symbol) {
            "LegBook[$symbol] cannot hold a leg for ${leg.symbol}"
        }
        if (leg.role == LegRole.PRIMARY) {
            require(legs.values.none { it.role == LegRole.PRIMARY }) {
                "LegBook[$symbol] already has a PRIMARY leg; close it before adding another"
            }
        }
        legs[leg.legId] = leg
    }

    fun close(legId: String): PositionLeg? = legs.remove(legId)

    fun all(): List<PositionLeg> = legs.values.toList()

    fun primary(): PositionLeg? = legs.values.firstOrNull { it.role == LegRole.PRIMARY }

    fun stacks(): List<PositionLeg> = legs.values.filter { it.role == LegRole.STACK }

    fun isEmpty(): Boolean = legs.isEmpty()

    fun size(): Int = legs.size

    /** Signed net quantity across all legs. Positive = net long, negative = net short. */
    fun netQuantity(): BigDecimal {
        var sum = BigDecimal.ZERO
        for (leg in legs.values) {
            sum = if (leg.side == Side.BUY) sum.add(leg.quantity) else sum.subtract(leg.quantity)
        }
        return sum
    }

    /**
     * Derived [Position] view — net quantity + weighted average entry price across all legs.
     * Returns `null` if the book is empty.
     *
     * The averaging treats opposite-direction legs by signed contribution: a BUY 0.2 @ 1.10
     * paired with a SELL 0.1 @ 1.20 yields netQty = +0.1 and avgEntry computed from the
     * remaining net exposure. For most strategies this matches the singular-position model.
     */
    fun netView(): Position? {
        if (legs.isEmpty()) return null
        val netQty = netQuantity()
        if (netQty.signum() == 0) {
            // Legs net to flat (e.g. equal-and-opposite). Return a zero-quantity Position
            // for callers that still want the symbol context.
            val earliest = legs.values.minOf { it.openedAt }
            return Position(symbol, BigDecimal.ZERO, BigDecimal.ZERO, openedAt = earliest)
        }
        // Notional-weighted average price across same-direction legs (drop opposite-side legs
        // for the entry-price calc since they've already realized in the cancel-out math).
        val netSide = if (netQty.signum() > 0) Side.BUY else Side.SELL
        var notional = BigDecimal.ZERO
        var qty = BigDecimal.ZERO
        for (leg in legs.values) {
            if (leg.side != netSide) continue
            notional = notional.add(leg.entryPrice.multiply(leg.quantity))
            qty = qty.add(leg.quantity)
        }
        val avg =
            if (qty.signum() > 0) {
                notional.divide(qty, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            } else {
                BigDecimal.ZERO
            }
        val earliest = legs.values.minOf { it.openedAt }
        return Position(symbol, netQty, avg, openedAt = earliest)
    }
}
