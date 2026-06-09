package com.qkt.cli.daemon.portfolio

import com.qkt.app.SessionPnl
import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import java.math.BigDecimal

/**
 * A [PnLProvider] over a portfolio book: realized/unrealized are summed across the children's live
 * snapshots. Lets the book reuse the per-session risk machinery (#348) — the book's absolute equity
 * is `CAPITAL + realizedTotal() + unrealizedTotal()`, exactly what `RiskState` expects.
 */
class BookPnLProvider(
    private val children: List<() -> SessionPnl>,
) : PnLProvider {
    override fun realizedTotal(): BigDecimal = children.fold(Money.ZERO) { acc, c -> acc.add(c().realized) }

    override fun unrealizedTotal(): BigDecimal = children.fold(Money.ZERO) { acc, c -> acc.add(c().unrealized) }

    override fun unrealizedFor(symbol: String): BigDecimal = Money.ZERO

    override fun totalPnL(): BigDecimal = realizedTotal().add(unrealizedTotal())
}
