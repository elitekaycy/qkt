package com.qkt.dsl.portfolio

import java.math.BigDecimal

/** A market source with no symbols, for the portfolio gate's rule context. */
internal object EmptySource : com.qkt.marketdata.source.MarketSource {
    override val name: String = "PortfolioGate"
    override val capabilities: Set<com.qkt.marketdata.source.MarketSourceCapability> = emptySet()

    override fun supports(symbol: String): Boolean = false
}

/** A position view with no positions, for the portfolio gate's rule context. */
internal object EmptyPositions : com.qkt.positions.StrategyPositionView {
    override fun positionFor(symbol: String): com.qkt.positions.Position? = null

    override fun allPositions(): Map<String, com.qkt.positions.Position> = emptyMap()

    override fun maeFor(symbol: String): BigDecimal? = null
}

/** A PnL view that is zero everywhere, for the portfolio gate's rule context. */
internal object EmptyPnL : com.qkt.pnl.StrategyPnLView {
    override fun realized(): BigDecimal = BigDecimal.ZERO

    override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

    override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

    override fun total(): BigDecimal = BigDecimal.ZERO

    override fun equity(): BigDecimal = BigDecimal.ZERO

    override fun balance(): BigDecimal = BigDecimal.ZERO
}
