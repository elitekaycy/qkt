package com.qkt.pnl

import java.math.BigDecimal

interface StrategyPnLView {
    fun realized(): BigDecimal

    fun unrealizedFor(symbol: String): BigDecimal

    fun unrealizedTotal(): BigDecimal

    fun total(): BigDecimal

    fun equity(): BigDecimal

    fun balance(): BigDecimal
}

internal class StrategyPnLViewImpl(
    private val pnl: StrategyPnL,
    private val strategyId: String,
) : StrategyPnLView {
    override fun realized(): BigDecimal = pnl.realizedFor(strategyId)

    override fun unrealizedFor(symbol: String): BigDecimal = pnl.unrealizedFor(strategyId, symbol)

    override fun unrealizedTotal(): BigDecimal = pnl.unrealizedTotalFor(strategyId)

    override fun total(): BigDecimal = pnl.totalFor(strategyId)

    override fun equity(): BigDecimal = pnl.equityFor(strategyId)

    override fun balance(): BigDecimal = pnl.balanceFor(strategyId)
}
