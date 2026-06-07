package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.pnl.NoOpTradeHistoryView
import com.qkt.pnl.StrategyPnLView
import com.qkt.pnl.TradeHistoryView
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

        override fun legsFor(symbol: String): List<com.qkt.positions.PositionLeg> = emptyList()
    }

private val emptyPnL =
    object : StrategyPnLView {
        override fun realized(): BigDecimal = BigDecimal.ZERO

        override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

        override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

        override fun total(): BigDecimal = BigDecimal.ZERO

        override fun equity(): BigDecimal = BigDecimal.ZERO

        override fun balance(): BigDecimal = BigDecimal.ZERO
    }

/**
 * Default registry for tests — every symbol resolves to a unit-contract instrument
 * (contractSize = 1) so SIZING RISK math degenerates to the pre-Phase-30 shape.
 * Tests that exercise contract-size behavior pass a real registry explicitly.
 */
private object UnitContractRegistry : InstrumentRegistry {
    override fun lookup(qktSymbol: String): InstrumentMeta? =
        InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = BigDecimal.ONE,
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
        )
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
    instruments: InstrumentRegistry = UnitContractRegistry,
    tradeHistory: TradeHistoryView = NoOpTradeHistoryView(),
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
        instruments = instruments,
        tradeHistory = tradeHistory,
    )
