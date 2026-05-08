package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class StrategyPnL(
    private val strategyPositions: StrategyPositionTracker,
    private val prices: MarketPriceProvider,
) {
    private val realizedByStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()
    private val startingBalanceByStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()

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
        realizedByStrategy[strategyId] = current.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun realizedFor(strategyId: String): BigDecimal = realizedByStrategy[strategyId] ?: Money.ZERO

    fun unrealizedFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal {
        val pos = strategyPositions.positionFor(strategyId, symbol) ?: return Money.ZERO
        val price = prices.lastPrice(symbol) ?: return Money.ZERO
        return price
            .subtract(pos.avgEntryPrice)
            .multiply(pos.quantity)
            .setScale(Money.SCALE, Money.ROUNDING)
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
        startingBalanceFor(strategyId)
            .add(totalFor(strategyId))
            .setScale(Money.SCALE, Money.ROUNDING)

    fun balanceFor(strategyId: String): BigDecimal =
        startingBalanceFor(strategyId)
            .add(realizedFor(strategyId))
            .setScale(Money.SCALE, Money.ROUNDING)
}
