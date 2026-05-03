package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.PositionProvider
import java.math.BigDecimal

interface PnLProvider {
    fun realizedTotal(): BigDecimal

    fun unrealizedFor(symbol: String): BigDecimal

    fun unrealizedTotal(): BigDecimal

    fun totalPnL(): BigDecimal
}

class PnLCalculator(
    private val positions: PositionProvider,
    private val prices: MarketPriceProvider,
) : PnLProvider {
    private var realizedTotal: BigDecimal = Money.ZERO

    fun recordRealized(realized: BigDecimal) {
        realizedTotal = realizedTotal.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun realizedTotal(): BigDecimal = realizedTotal

    override fun unrealizedFor(symbol: String): BigDecimal {
        val pos = positions.positionFor(symbol) ?: return Money.ZERO
        val price = prices.lastPrice(symbol) ?: return Money.ZERO
        return price
            .subtract(pos.avgEntryPrice)
            .multiply(pos.quantity)
            .setScale(Money.SCALE, Money.ROUNDING)
    }

    override fun unrealizedTotal(): BigDecimal =
        positions
            .allPositions()
            .keys
            .map { unrealizedFor(it) }
            .fold(Money.ZERO) { acc, v -> acc.add(v) }
            .setScale(Money.SCALE, Money.ROUNDING)

    override fun totalPnL(): BigDecimal = realizedTotal().add(unrealizedTotal()).setScale(Money.SCALE, Money.ROUNDING)
}
