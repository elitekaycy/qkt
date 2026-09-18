package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Account-level net position per symbol, folded across every strategy's legs in [legBooks].
 * [StrategyPositionTracker] calls [reindex] for one symbol after every mutation of that symbol's
 * legs, so account reads are index lookups and the account never disagrees with the ledger it
 * is derived from. [AccountPositionView] is the read-only face of this index.
 */
internal class AccountNetIndex(
    private val legBooks: StrategyLegBooks,
) {
    private val accountBySymbol: MutableMap<String, Position> = ConcurrentHashMap()

    fun positionFor(symbol: String): Position? = accountBySymbol[symbol]

    fun positions(): Map<String, Position> = accountBySymbol.toMap()

    fun symbols(): Set<String> = accountBySymbol.keys

    fun forEachLeg(
        symbol: String,
        action: (PositionLeg) -> Unit,
    ) {
        for (books in legBooks.strategyBooks()) {
            val book = books[symbol] ?: continue
            book.forEach(action)
        }
    }

    /** Rebuild [symbol]'s account net from the current legs of every strategy. */
    fun reindex(symbol: String) {
        // Accumulate from a scale-0 zero so the net keeps the legs' own quantity scale, exactly
        // as the strategy net view does — report columns print 0.01, not 0.01000000.
        var netQty = BigDecimal.ZERO
        var earliest = Long.MAX_VALUE
        var any = false
        for (books in legBooks.strategyBooks()) {
            val book = books[symbol] ?: continue
            book.forEach { leg ->
                any = true
                netQty = if (leg.side == Side.BUY) netQty.add(leg.quantity) else netQty.subtract(leg.quantity)
                if (leg.openedAt < earliest) earliest = leg.openedAt
            }
        }
        if (!any) {
            accountBySymbol.remove(symbol)
            return
        }
        if (netQty.signum() == 0) {
            accountBySymbol[symbol] = Position(symbol, Money.ZERO, Money.ZERO, openedAt = earliest)
            return
        }
        val netSide = if (netQty.signum() > 0) Side.BUY else Side.SELL
        var notional = Money.ZERO
        var qty = Money.ZERO
        for (books in legBooks.strategyBooks()) {
            val book = books[symbol] ?: continue
            book.forEach { leg ->
                if (leg.side != netSide) return@forEach
                notional = notional.add(leg.entryPrice.multiply(leg.quantity))
                qty = qty.add(leg.quantity)
            }
        }
        val avg = notional.divide(qty, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
        accountBySymbol[symbol] = Position(symbol, netQty, avg, openedAt = earliest)
    }
}
