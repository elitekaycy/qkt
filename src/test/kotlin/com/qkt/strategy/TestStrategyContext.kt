package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.pnl.StrategyPnLView
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.risk.NoOpRiskView
import com.qkt.risk.RiskView
import java.math.BigDecimal

private val emptySource =
    object : MarketSource {
        override val name = "Empty"
        override val capabilities = emptySet<MarketSourceCapability>()

        override fun supports(symbol: String): Boolean = false
    }

private val emptyPositions =
    object : StrategyPositionView {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions(): Map<String, Position> = emptyMap()
    }

private val emptyPnL =
    object : StrategyPnLView {
        override fun realized(): BigDecimal = BigDecimal.ZERO

        override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

        override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

        override fun total(): BigDecimal = BigDecimal.ZERO
    }

fun testStrategyContext(
    strategyId: String = "test",
    mode: Mode = Mode.BACKTEST,
    clock: Clock = FixedClock(time = 0L),
    calendar: TradingCalendar = TradingCalendar.crypto(),
    source: MarketSource = emptySource,
    positions: StrategyPositionView = emptyPositions,
    pnl: StrategyPnLView = emptyPnL,
    risk: RiskView = NoOpRiskView(),
): StrategyContext =
    StrategyContext(
        strategyId = strategyId,
        mode = mode,
        clock = clock,
        calendar = calendar,
        source = source,
        positions = positions,
        pnl = pnl,
        risk = risk,
    )
