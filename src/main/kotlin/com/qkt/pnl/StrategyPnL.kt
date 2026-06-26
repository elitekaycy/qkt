package com.qkt.pnl

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Money
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class StrategyPnL(
    private val strategyPositions: StrategyPositionTracker,
    private val prices: MarketPriceProvider,
    private val instruments: InstrumentRegistry = NoopInstrumentRegistry,
    private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
    private val accounting: AccountingEngine = AccountingEngine(),
    private val markTimestamp: () -> Long = { 0L },
    /**
     * Live account equity from the broker, or null when unavailable. When it returns a value
     * (a live MT5 session whose gateway exposes account equity), [equityFor] uses it directly so
     * sizing and drawdown track the *real* account — commissions, swaps, and deposits the engine
     * didn't originate are all reflected. Default returns null, so backtest and paper keep the
     * derived `startingBalance + realized + unrealized` and stay deterministic. Account-level, so
     * the caller only wires it for a single-strategy session (where account == strategy).
     */
    private val brokerEquity: () -> BigDecimal? = { null },
) {
    private val realizedByStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()
    private val startingBalanceByStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    /**
     * Rehydrate the lifetime realized PnL a previous session persisted. Call once at
     * boot, before any fills: equity then continues from where the last session ended
     * instead of snapping back to the starting balance on every restart.
     */
    fun restore(strategyId: String) {
        if (strategyId.isBlank()) return
        val persisted = runCatching { persistor.loadPnl(strategyId) }.getOrNull() ?: return
        realizedByStrategy[strategyId] = persisted.realized.setScale(Money.SCALE, Money.ROUNDING)
    }

    fun setStartingBalance(
        strategyId: String,
        balance: BigDecimal,
    ) {
        if (strategyId.isBlank()) return
        startingBalanceByStrategy[strategyId] = balance.setScale(Money.SCALE, Money.ROUNDING)
    }

    fun startingBalanceFor(strategyId: String): BigDecimal = startingBalanceByStrategy[strategyId] ?: Money.ZERO

    fun recordRealized(
        strategyId: String,
        realized: BigDecimal,
    ) {
        if (strategyId.isBlank()) return
        val current = realizedByStrategy[strategyId] ?: Money.ZERO
        val updated = current.add(realized).setScale(Money.SCALE, Money.ROUNDING)
        realizedByStrategy[strategyId] = updated
        runCatching { persistor.savePnl(strategyId, com.qkt.persistence.PersistedPnl(realized = updated)) }
    }

    fun realizedFor(strategyId: String): BigDecimal = realizedByStrategy[strategyId] ?: Money.ZERO

    /**
     * Open PnL on [symbol], summed PER LEG: each open leg contributes
     * `signedQty x (mark - legEntry) x contractSize`. The netted position view cannot be
     * the basis here — a hedged pair (equal long and short legs) nets to quantity zero
     * while both legs are open at the broker, so a straddle locked in at a $300 spread
     * loss would read as zero and equity, daily-loss, and drawdown halts would all be
     * blind to it until the legs individually close.
     */
    fun unrealizedFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal {
        val price = prices.lastPrice(symbol) ?: return Money.ZERO
        val cs = instruments.lookup(symbol)?.contractSize ?: BigDecimal.ONE
        val legs = strategyPositions.legBookFor(strategyId, symbol)?.all().orEmpty()
        val native =
            if (legs.isEmpty()) {
                val pos = strategyPositions.positionFor(strategyId, symbol) ?: return Money.ZERO
                price
                    .subtract(pos.avgEntryPrice)
                    .multiply(pos.quantity)
                    .multiply(cs)
                    .setScale(Money.SCALE, Money.ROUNDING)
            } else {
                var sum = Money.ZERO
                for (leg in legs) {
                    val signedQty = if (leg.side == com.qkt.common.Side.BUY) leg.quantity else leg.quantity.negate()
                    sum = sum.add(price.subtract(leg.entryPrice).multiply(signedQty).multiply(cs))
                }
                sum.setScale(Money.SCALE, Money.ROUNDING)
            }
        return accounting
            .convertPnl(
                symbol = symbol,
                nativeAmount = native,
                timestamp = markTimestamp(),
                referencePrice = price,
            ).account.amount
    }

    fun unrealizedTotalFor(strategyId: String): BigDecimal =
        strategyPositions
            .positionsFor(strategyId)
            .keys
            .map { unrealizedFor(strategyId, it) }
            .fold(Money.ZERO) { acc, v -> acc.add(v) }
            .setScale(Money.SCALE, Money.ROUNDING)

    fun totalFor(strategyId: String): BigDecimal =
        realizedFor(strategyId)
            .add(unrealizedTotalFor(strategyId))
            .setScale(Money.SCALE, Money.ROUNDING)

    fun equityFor(strategyId: String): BigDecimal =
        brokerEquity()?.setScale(Money.SCALE, Money.ROUNDING)
            ?: startingBalanceFor(strategyId)
                .add(totalFor(strategyId))
                .setScale(Money.SCALE, Money.ROUNDING)

    fun balanceFor(strategyId: String): BigDecimal =
        startingBalanceFor(strategyId)
            .add(realizedFor(strategyId))
            .setScale(Money.SCALE, Money.ROUNDING)
}
