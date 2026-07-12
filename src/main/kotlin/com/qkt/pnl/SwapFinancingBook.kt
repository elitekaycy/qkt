package com.qkt.pnl

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Deterministic backtest ledger for broker overnight swap.
 *
 * Normal ticks only compare precomputed UTC rollover buckets. Full position legs are read
 * when a configured boundary is crossed, preserving hedged exposure that a net position
 * would hide. Positive instrument points credit PnL; negative points debit it.
 */
internal class SwapFinancingBook(
    instruments: InstrumentRegistry,
    private val strategyPositions: StrategyPositionTracker,
    private val accounting: AccountingEngine,
    private val prices: MarketPriceProvider,
    private val strategyIds: List<String>,
    symbols: Collection<String>,
) {
    private val metadata: Map<String, InstrumentMeta> =
        symbols
            .distinct()
            .mapNotNull { symbol ->
                instruments
                    .lookup(symbol)
                    ?.takeIf {
                        it.swapLongPoints.signum() != 0 || it.swapShortPoints.signum() != 0
                    }?.let { symbol to it }
            }.toMap()
    private val rolloverHours: IntArray =
        metadata.values
            .map {
                it.swapRolloverHourUtc
            }.distinct()
            .sorted()
            .toIntArray()
    private val boundaries: MutableList<Long> = ArrayList(rolloverHours.size)
    private val amountByStrategy: MutableMap<String, BigDecimal> = HashMap()
    private val paidByStrategy: MutableMap<String, BigDecimal> = HashMap()
    private val dailyNet: MutableMap<LocalDate, BigDecimal> = linkedMapOf()
    private val dailyNetByStrategy: MutableMap<String, MutableMap<LocalDate, BigDecimal>> = HashMap()
    private var totalPaid: BigDecimal = Money.ZERO

    /**
     * Accrue every configured rollover in `(fromExclusiveMs, toInclusiveMs]`.
     *
     * [onAccrued] receives account-currency PnL per strategy and boundary. It is called in
     * chronological order after every qualifying leg at that boundary has been aggregated.
     */
    fun accrueBetween(
        fromExclusiveMs: Long,
        toInclusiveMs: Long,
        onAccrued: (strategyId: String, timestampMs: Long, amount: BigDecimal) -> Unit,
    ) {
        if (metadata.isEmpty() || toInclusiveMs <= fromExclusiveMs) return
        collectBoundaries(fromExclusiveMs, toInclusiveMs)
        for (boundary in boundaries) accrueBoundary(boundary, onAccrued)
    }

    /** Net swap paid across the run: positive is a charge, negative is a credit. */
    fun totalPaid(): BigDecimal = totalPaid

    /** Net swap paid by [strategyId]: positive is a charge, negative is a credit. */
    fun totalPaidFor(strategyId: String): BigDecimal = paidByStrategy[strategyId] ?: Money.ZERO

    /** Account-currency financing PnL keyed by UTC rollover date. */
    fun dailyNet(): Map<LocalDate, BigDecimal> = dailyNet.toMap()

    /** Account-currency financing PnL for [strategyId], keyed by UTC rollover date. */
    fun dailyNetFor(strategyId: String): Map<LocalDate, BigDecimal> =
        dailyNetByStrategy[strategyId]?.toMap() ?: emptyMap()

    private fun collectBoundaries(
        fromExclusiveMs: Long,
        toInclusiveMs: Long,
    ) {
        boundaries.clear()
        for (hour in rolloverHours) {
            val hourMs = hour * MILLIS_PER_HOUR
            var day = Math.floorDiv(fromExclusiveMs - hourMs, MILLIS_PER_DAY) + 1L
            val lastDay = Math.floorDiv(toInclusiveMs - hourMs, MILLIS_PER_DAY)
            while (day <= lastDay) {
                boundaries.add(day * MILLIS_PER_DAY + hourMs)
                day += 1L
            }
        }
        boundaries.sort()
    }

    private fun accrueBoundary(
        boundaryMs: Long,
        onAccrued: (strategyId: String, timestampMs: Long, amount: BigDecimal) -> Unit,
    ) {
        val epochDay = Math.floorDiv(boundaryMs, MILLIS_PER_DAY)
        val dayOfWeek = DayOfWeek.of(Math.floorMod(epochDay + THURSDAY_OFFSET, DAYS_PER_WEEK).toInt() + 1)
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) return

        val boundaryHour = Math.floorMod(boundaryMs, MILLIS_PER_DAY).toInt() / MILLIS_PER_HOUR.toInt()
        amountByStrategy.clear()
        for (strategyId in strategyIds) {
            for (leg in strategyPositions.allLegsFor(strategyId)) {
                if (leg.openedAt >= boundaryMs) continue
                val meta = metadata[leg.symbol] ?: continue
                if (meta.swapRolloverHourUtc != boundaryHour) continue
                val points = if (leg.side == Side.BUY) meta.swapLongPoints else meta.swapShortPoints
                if (points.signum() == 0) continue
                val multiplier = if (dayOfWeek == meta.swapTripleDay) THREE else BigDecimal.ONE
                val nativeCash =
                    points
                        .multiply(meta.pointSize, Money.CONTEXT)
                        .multiply(meta.contractSize, Money.CONTEXT)
                        .multiply(leg.quantity.abs(), Money.CONTEXT)
                        .multiply(multiplier, Money.CONTEXT)
                val accountCash =
                    accounting
                        .convertPnl(
                            symbol = leg.symbol,
                            nativeAmount = nativeCash,
                            timestamp = boundaryMs,
                            referencePrice = prices.lastPrice(leg.symbol),
                        ).account.amount
                amountByStrategy[strategyId] =
                    (amountByStrategy[strategyId] ?: Money.ZERO).add(accountCash, Money.CONTEXT)
            }
        }

        val date = LocalDate.ofEpochDay(epochDay)
        for (strategyId in strategyIds) {
            val rawAmount = amountByStrategy[strategyId] ?: continue
            val amount = rawAmount.setScale(Money.SCALE, Money.ROUNDING)
            if (amount.signum() == 0) continue
            onAccrued(strategyId, boundaryMs, amount)
            val paid = amount.negate()
            totalPaid = totalPaid.add(paid).setScale(Money.SCALE, Money.ROUNDING)
            paidByStrategy[strategyId] =
                (paidByStrategy[strategyId] ?: Money.ZERO).add(paid).setScale(Money.SCALE, Money.ROUNDING)
            dailyNet[date] = (dailyNet[date] ?: Money.ZERO).add(amount).setScale(Money.SCALE, Money.ROUNDING)
            val strategyDaily = dailyNetByStrategy.getOrPut(strategyId) { linkedMapOf() }
            strategyDaily[date] =
                (strategyDaily[date] ?: Money.ZERO).add(amount).setScale(Money.SCALE, Money.ROUNDING)
        }
    }

    private companion object {
        const val MILLIS_PER_HOUR = 3_600_000L
        const val MILLIS_PER_DAY = 86_400_000L
        const val DAYS_PER_WEEK = 7L
        const val THURSDAY_OFFSET = 3L
        val THREE: BigDecimal = BigDecimal(3)
    }
}
