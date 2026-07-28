package com.qkt.cli.daemon.portfolio

import com.qkt.pnl.BookBalanceView
import java.math.BigDecimal

/**
 * Live book balance for one deployed portfolio: declared CAPITAL plus the lifetime
 * realized PnL of every child, read on demand from each child session's PnL snapshot
 * (which restores across restarts). Children capture this at construction; the deployer
 * binds the realized sources once every child exists, before the supervisor activates
 * any gate — so no signal can size against an unbound book.
 *
 * e.g. CAPITAL 50000 with children at +800 and -300 realized → balance() = 50500.
 */
class PortfolioBookBalance(
    private val capital: BigDecimal,
) : BookBalanceView {
    @Volatile
    private var realizedSources: List<() -> BigDecimal> = emptyList()

    /** Late-bind one realized-PnL reader per child; called once after all children exist. */
    fun bind(sources: List<() -> BigDecimal>) {
        realizedSources = sources
    }

    override fun balance(): BigDecimal = realizedSources.fold(capital) { acc, source -> acc.add(source()) }
}
