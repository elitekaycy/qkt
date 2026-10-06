package com.qkt.pnl

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.instrument.FundingRate
import com.qkt.instrument.FutureTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Deterministic backtest ledger of perpetual funding: at each stored rate's time, every strategy holding
 * that perpetual from before it is charged `quantity × contract size × price × rate`, a long paying a
 * positive rate and a short paid it (nothing when the strategies' holdings net to zero, as a venue charges
 * the account's net position), at the rate's price (the perpetual's last price when it has none).
 * Rates come from the registry's stored series ([com.qkt.instrument.FuturesDirectory.fundingRates]); a
 * perpetual without one accrues nothing, which the backtest refuses unless funding is turned off. Normal
 * ticks only compare the next rate's time; legs are read when one is crossed.
 */
internal class FundingBook(
    instruments: InstrumentRegistry,
    private val strategyPositions: StrategyPositionTracker,
    private val accounting: AccountingEngine,
    private val prices: MarketPriceProvider,
    private val strategyIds: List<String>,
    symbols: Collection<String>,
) {
    private val series: Map<String, List<FundingRate>> =
        symbols
            .distinct()
            .filter { (instruments.lookup(it)?.derivative as? FutureTerms)?.perpetual == true }
            .mapNotNull { s ->
                instruments
                    .futures()
                    ?.fundingRates(s)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { s to it }
            }.toMap()
    private val contractSize: Map<String, BigDecimal> =
        series.keys.associateWith {
            instruments
                .require(
                    it,
                ).contractSize
        }
    private val next = HashMap<String, Int>()
    private val paidByStrategy = HashMap<String, BigDecimal>()
    private val dailyNet = linkedMapOf<LocalDate, BigDecimal>()
    private val dailyNetByStrategy = HashMap<String, MutableMap<LocalDate, BigDecimal>>()
    private var totalPaid: BigDecimal = Money.ZERO

    /** Accrue every stored rate in `(fromExclusiveMs, toInclusiveMs]`, oldest first, through [onAccrued] (account-currency P&L). */
    fun accrueBetween(
        fromExclusiveMs: Long,
        toInclusiveMs: Long,
        onAccrued: (strategyId: String, timestampMs: Long, amount: BigDecimal) -> Unit,
    ) {
        if (series.isEmpty() || toInclusiveMs <= fromExclusiveMs) return
        val due =
            series
                .flatMap { (symbol, rates) ->
                    var i =
                        next.getOrPut(symbol) {
                            rates.indexOfFirst { it.timeMs > fromExclusiveMs }.takeIf { it >= 0 }
                                ?: rates.size
                        }
                    buildList {
                        while (i < rates.size && rates[i].timeMs <= toInclusiveMs) add(symbol to rates[i++])
                    }.also { next[symbol] = i }
                }.sortedBy { it.second.timeMs }
        for ((symbol, rate) in due) accrue(symbol, rate, onAccrued)
    }

    /** Net funding paid across the run: positive a charge, negative a credit. */
    fun totalPaid(): BigDecimal = totalPaid

    /** Net funding paid by [strategyId]. */
    fun totalPaidFor(strategyId: String): BigDecimal = paidByStrategy[strategyId] ?: Money.ZERO

    /** Account-currency funding P&L by UTC date. */
    fun dailyNet(): Map<LocalDate, BigDecimal> = dailyNet.toMap()

    /** Account-currency funding P&L of [strategyId] by UTC date. */
    fun dailyNetFor(strategyId: String): Map<LocalDate, BigDecimal> =
        dailyNetByStrategy[strategyId]?.toMap() ?: emptyMap()

    private fun accrue(
        symbol: String,
        rate: FundingRate,
        onAccrued: (strategyId: String, timestampMs: Long, amount: BigDecimal) -> Unit,
    ) {
        val price = rate.price ?: prices.lastPrice(symbol) ?: return
        val date = LocalDate.ofEpochDay(Math.floorDiv(rate.timeMs, DAY_MS))
        val held = strategyIds.associateWith { heldBefore(it, symbol, rate.timeMs) }.filterValues { it.signum() != 0 }
        // A venue charges the account's net position: strategies netting to nothing pay nothing, as live.
        if (held.values.fold(BigDecimal.ZERO, BigDecimal::add).signum() == 0) return
        for ((strategyId, quantity) in held) {
            val native =
                quantity
                    .multiply(contractSize.getValue(symbol), Money.CONTEXT)
                    .multiply(price, Money.CONTEXT)
                    .multiply(rate.rate, Money.CONTEXT)
                    .negate()
            val amount =
                accounting
                    .convertPnl(
                        symbol,
                        native,
                        rate.timeMs,
                        price,
                    ).account.amount
                    .setScale(Money.SCALE, Money.ROUNDING)
            if (amount.signum() == 0) continue
            onAccrued(strategyId, rate.timeMs, amount)
            totalPaid = totalPaid.subtract(amount)
            paidByStrategy[strategyId] = (paidByStrategy[strategyId] ?: Money.ZERO).subtract(amount)
            dailyNet[date] = (dailyNet[date] ?: Money.ZERO).add(amount)
            dailyNetByStrategy.getOrPut(strategyId) { linkedMapOf() }.merge(date, amount, BigDecimal::add)
        }
    }

    /** [strategyId]'s signed holding of [symbol] from before [timeMs]. */
    private fun heldBefore(
        strategyId: String,
        symbol: String,
        timeMs: Long,
    ): BigDecimal =
        strategyPositions
            .allLegsFor(strategyId)
            .filter { it.symbol == symbol && it.openedAt < timeMs }
            .fold(BigDecimal.ZERO) { q, leg ->
                if (leg.side ==
                    Side.BUY
                ) {
                    q.add(leg.quantity.abs())
                } else {
                    q.subtract(leg.quantity.abs())
                }
            }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
