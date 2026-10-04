package com.qkt.research

import com.qkt.instrument.InstrumentRegistry
import com.qkt.pnl.FundingBook
import com.qkt.pnl.SwapFinancingBook
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A replay's financing over [books], for [strategyIds] trading [symbols]: overnight swap
 * ([SwapFinancingBook]) and perpetual funding ([FundingBook]), accrued together in time order so the
 * replay's clock never steps back between them.
 */
internal class ReplayFinancing(
    instruments: InstrumentRegistry,
    books: ReplayBooks,
    strategyIds: List<String>,
    symbols: List<String>,
) {
    private val swap =
        SwapFinancingBook(instruments, books.strategyPositions, books.accounting, books.priceTracker, strategyIds, symbols)
    private val funding =
        FundingBook(instruments, books.strategyPositions, books.accounting, books.priceTracker, strategyIds, symbols)

    /** Accrues swap and funding in `(fromExclusiveMs, toInclusiveMs]`, each through [apply] at its own time, oldest first. */
    fun accrueBetween(
        fromExclusiveMs: Long,
        toInclusiveMs: Long,
        apply: (strategyId: String, timestampMs: Long, amount: BigDecimal) -> Unit,
    ) {
        val due = ArrayList<Triple<String, Long, BigDecimal>>()
        swap.accrueBetween(fromExclusiveMs, toInclusiveMs) { id, at, amount -> due += Triple(id, at, amount) }
        funding.accrueBetween(fromExclusiveMs, toInclusiveMs) { id, at, amount -> due += Triple(id, at, amount) }
        due.sortedBy { it.second }.forEach { (id, at, amount) -> apply(id, at, amount) }
    }

    /** Net swap paid ([strategyId]'s, or the run's when null): positive a charge. */
    fun swapPaid(strategyId: String? = null): BigDecimal = strategyId?.let(swap::totalPaidFor) ?: swap.totalPaid()

    /** Net perpetual funding paid ([strategyId]'s, or the run's when null): positive a charge. */
    fun fundingPaid(strategyId: String? = null): BigDecimal = strategyId?.let(funding::totalPaidFor) ?: funding.totalPaid()

    /** Swap and funding P&L by UTC date ([strategyId]'s, or the run's when null). */
    fun dailyNet(strategyId: String? = null): Map<LocalDate, BigDecimal> {
        val swapDaily = strategyId?.let(swap::dailyNetFor) ?: swap.dailyNet()
        val fundingDaily = strategyId?.let(funding::dailyNetFor) ?: funding.dailyNet()
        return (swapDaily.keys + fundingDaily.keys).sorted().associateWith {
            (swapDaily[it] ?: BigDecimal.ZERO).add(fundingDaily[it] ?: BigDecimal.ZERO)
        }
    }
}
