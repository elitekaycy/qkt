package com.qkt.pnl

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.QuoteCurrencyGuard
import com.qkt.instrument.exDateStartMs
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Deterministic backtest ledger of cash dividends ([CorporateAction.Dividend]): at the start of each dividend's
 * ex-date session ([calendar]), every strategy holding the share from before then is credited
 * `quantity x contract size x amount` when long and debited it when short, as a CFD broker books it and as a share's
 * owner is entitled to it. A dividend in another currency than the instrument is quoted in is refused at setup rather
 * than converted at a guessed rate. Normal ticks only compare the next ex-date's instant.
 */
internal class DividendBook(
    instruments: InstrumentRegistry,
    private val calendar: TradingCalendar,
    private val strategyPositions: StrategyPositionTracker,
    private val accounting: AccountingEngine,
    private val prices: MarketPriceProvider,
    private val strategyIds: List<String>,
    symbols: Collection<String>,
) {
    private class Due(
        val symbol: String,
        val atMs: Long,
        val dividend: CorporateAction.Dividend,
    )

    private val schedule: List<Due> =
        symbols
            .distinct()
            .flatMap { symbol ->
                val dividends =
                    instruments
                        .corporateActions()
                        ?.actions(
                            symbol,
                        ).orEmpty()
                        .filterIsInstance<CorporateAction.Dividend>()
                if (dividends.isNotEmpty()) {
                    val quote = instruments.lookup(symbol)?.currency ?: QuoteCurrencyGuard.quoteOf(symbol)
                    dividends.forEach {
                        require(quote == null || it.currency == quote) {
                            "$symbol dividend on ${it.exDate} is in ${it.currency}, the instrument in $quote"
                        }
                    }
                }
                dividends.map { Due(symbol, calendar.exDateStartMs(symbol, it.exDate), it) }
            }.sortedBy { it.atMs }
    private val contractSize: Map<String, BigDecimal> =
        schedule.map { it.symbol }.distinct().associateWith { instruments.require(it).contractSize }
    private var next = -1
    private val paidByStrategy = HashMap<String, BigDecimal>()
    private val dailyNet = linkedMapOf<LocalDate, BigDecimal>()
    private val dailyNetByStrategy = HashMap<String, MutableMap<LocalDate, BigDecimal>>()
    private var totalPaid: BigDecimal = Money.ZERO

    /** Books every dividend whose ex-date session starts in `(fromExclusiveMs, toInclusiveMs]`, oldest first. */
    fun accrueBetween(
        fromExclusiveMs: Long,
        toInclusiveMs: Long,
        onAccrued: (strategyId: String, timestampMs: Long, amount: BigDecimal) -> Unit,
    ) {
        if (schedule.isEmpty() || toInclusiveMs <= fromExclusiveMs) return
        if (next < 0) next = schedule.indexOfFirst { it.atMs > fromExclusiveMs }.takeIf { it >= 0 } ?: schedule.size
        while (next < schedule.size && schedule[next].atMs <= toInclusiveMs) accrue(schedule[next++], onAccrued)
    }

    /** Net dividends paid across the run: negative when holders were credited more than shorts paid. */
    fun totalPaid(): BigDecimal = totalPaid

    /** Net dividends paid by [strategyId]. */
    fun totalPaidFor(strategyId: String): BigDecimal = paidByStrategy[strategyId] ?: Money.ZERO

    /** Account-currency dividend P&L by UTC date. */
    fun dailyNet(): Map<LocalDate, BigDecimal> = dailyNet.toMap()

    /** Account-currency dividend P&L of [strategyId] by UTC date. */
    fun dailyNetFor(strategyId: String): Map<LocalDate, BigDecimal> =
        dailyNetByStrategy[strategyId]?.toMap() ?: emptyMap()

    private fun accrue(
        due: Due,
        onAccrued: (strategyId: String, timestampMs: Long, amount: BigDecimal) -> Unit,
    ) {
        val date = LocalDate.ofEpochDay(Math.floorDiv(due.atMs, DAY_MS))
        for (strategyId in strategyIds) {
            val held = heldBefore(strategyId, due.symbol, due.atMs)
            if (held.signum() == 0) continue
            // A holding was filled at a price, so there is one; conversion of a non-account currency reads it.
            val price = requireNotNull(prices.lastPrice(due.symbol)) { "no price for ${due.symbol} at its dividend" }
            val native =
                held
                    .multiply(contractSize.getValue(due.symbol), Money.CONTEXT)
                    .multiply(due.dividend.amount, Money.CONTEXT)
            val amount =
                accounting
                    .convertPnl(due.symbol, native, due.atMs, price)
                    .account.amount
                    .setScale(Money.SCALE, Money.ROUNDING)
            if (amount.signum() == 0) continue
            onAccrued(strategyId, due.atMs, amount)
            totalPaid = totalPaid.subtract(amount)
            paidByStrategy[strategyId] = (paidByStrategy[strategyId] ?: Money.ZERO).subtract(amount)
            dailyNet[date] = (dailyNet[date] ?: Money.ZERO).add(amount)
            dailyNetByStrategy.getOrPut(strategyId) { linkedMapOf() }.merge(date, amount, BigDecimal::add)
        }
    }

    /** [strategyId]'s signed holding of [symbol] opened before [timeMs]. */
    private fun heldBefore(
        strategyId: String,
        symbol: String,
        timeMs: Long,
    ): BigDecimal =
        strategyPositions
            .allLegsFor(strategyId)
            .filter { it.symbol == symbol && it.openedAt < timeMs }
            .fold(BigDecimal.ZERO) { q, leg ->
                if (leg.side == Side.BUY) q.add(leg.quantity.abs()) else q.subtract(leg.quantity.abs())
            }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
