package com.qkt.research

import com.qkt.backtest.MetricsWindow
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
import com.qkt.pnl.DividendBook
import com.qkt.pnl.FundingBook
import com.qkt.pnl.SwapFinancingBook
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A replay's financing over [books], for [strategyIds] trading [symbols]: overnight swap ([SwapFinancingBook]),
 * perpetual funding ([FundingBook]) and cash dividends ([DividendBook], at each ex-date's session start on
 * [calendar]), accrued together in time order so the replay's clock never steps back between them.
 */
internal class ReplayFinancing(
    instruments: InstrumentRegistry,
    books: ReplayBooks,
    strategyIds: List<String>,
    symbols: List<String>,
    calendar: TradingCalendar = TradingCalendar.crypto(),
) {
    /** What an accrual pays for. */
    private enum class Kind { SWAP, FUNDING, DIVIDEND }

    private val swap =
        SwapFinancingBook(
            instruments,
            books.strategyPositions,
            books.accounting,
            books.priceTracker,
            strategyIds,
            symbols,
        )
    private val funding =
        FundingBook(instruments, books.strategyPositions, books.accounting, books.priceTracker, strategyIds, symbols)
    private val dividends =
        DividendBook(
            instruments,
            calendar,
            books.strategyPositions,
            books.accounting,
            books.priceTracker,
            strategyIds,
            symbols,
        )

    /** Every accrual so far, oldest first, so a sub-window can total its own (#1451). One per day and strategy. */
    private val accruals = ArrayList<Accrual>()

    /** Accrues swap, funding and dividends in `(fromExclusiveMs, toInclusiveMs]`, each through [apply] at its own time, oldest first. */
    fun accrueBetween(
        fromExclusiveMs: Long,
        toInclusiveMs: Long,
        apply: (strategyId: String, timestampMs: Long, amount: BigDecimal) -> Unit,
    ) {
        val due = ArrayList<Triple<String, Long, BigDecimal>>()

        fun collect(kind: Kind) =
            { id: String, at: Long, amount: BigDecimal ->
                due += Triple(id, at, amount)
                accruals += Accrual(at, amount, kind)
            }
        swap.accrueBetween(fromExclusiveMs, toInclusiveMs, collect(Kind.SWAP))
        funding.accrueBetween(fromExclusiveMs, toInclusiveMs, collect(Kind.FUNDING))
        dividends.accrueBetween(fromExclusiveMs, toInclusiveMs, collect(Kind.DIVIDEND))
        due.sortedBy { it.second }.forEach { (id, at, amount) -> apply(id, at, amount) }
    }

    /** Net swap paid ([strategyId]'s, or the run's when null): positive a charge. */
    fun swapPaid(strategyId: String? = null): BigDecimal = strategyId?.let(swap::totalPaidFor) ?: swap.totalPaid()

    /** Net perpetual funding paid ([strategyId]'s, or the run's when null): positive a charge. */
    fun fundingPaid(strategyId: String? = null): BigDecimal =
        strategyId?.let(funding::totalPaidFor) ?: funding.totalPaid()

    /** Net dividends paid ([strategyId]'s, or the run's when null): positive when shorts paid more than longs got. */
    fun dividendsPaid(strategyId: String? = null): BigDecimal =
        strategyId?.let(dividends::totalPaidFor) ?: dividends.totalPaid()

    /** Swap, funding and dividend P&L by UTC date ([strategyId]'s, or the run's when null). */
    fun dailyNet(strategyId: String? = null): Map<LocalDate, BigDecimal> {
        val books =
            listOf(
                strategyId?.let(swap::dailyNetFor) ?: swap.dailyNet(),
                strategyId?.let(funding::dailyNetFor) ?: funding.dailyNet(),
                strategyId?.let(dividends::dailyNetFor) ?: dividends.dailyNet(),
            )
        return books.flatMap { it.keys }.distinct().sorted().associateWith { day ->
            books.fold(BigDecimal.ZERO) { acc, daily -> acc.add(daily[day] ?: BigDecimal.ZERO) }
        }
    }

    /** Net swap paid on accruals stamped inside [window]: positive a charge. */
    fun swapPaid(window: MetricsWindow): BigDecimal = paidIn(window, Kind.SWAP)

    /** Net perpetual funding paid on accruals stamped inside [window]: positive a charge. */
    fun fundingPaid(window: MetricsWindow): BigDecimal = paidIn(window, Kind.FUNDING)

    /** Net dividends paid on accruals stamped inside [window]. */
    fun dividendsPaid(window: MetricsWindow): BigDecimal = paidIn(window, Kind.DIVIDEND)

    /** Swap, funding and dividend P&L by UTC date, for the accruals stamped inside [window]. */
    fun dailyNet(window: MetricsWindow): Map<LocalDate, BigDecimal> =
        accruals
            .filter { window.contains(it.atMs) }
            .groupBy { LocalDate.ofEpochDay(Math.floorDiv(it.atMs, DAY_MS)) }
            .mapValues { (_, day) -> day.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.amount) } }
            .toSortedMap()

    private fun paidIn(
        window: MetricsWindow,
        kind: Kind,
    ): BigDecimal =
        accruals
            .filter { it.kind == kind && window.contains(it.atMs) }
            .fold(BigDecimal.ZERO) { acc, a -> acc.subtract(a.amount) }

    /** One strategy's swap, funding or dividend P&L at [atMs], account currency. */
    private class Accrual(
        val atMs: Long,
        val amount: BigDecimal,
        val kind: Kind,
    )

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
