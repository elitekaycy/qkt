package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.engine.Engine
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SequenceBacktestTest {
    private val symbol = "BACKTEST:XAUUSD"
    private val strategyId = "alpha"

    private data class Harness(
        val pipeline: TradingPipeline,
        val strategyPositions: StrategyPositionTracker,
    )

    private fun compile(source: String): DslCompiledStrategy {
        val parsed = Dsl.parse(source) as ParseResult.Success
        return AstCompiler().compile(parsed.value) as DslCompiledStrategy
    }

    private fun harness(strategy: DslCompiledStrategy): Harness {
        val clock = FixedClock(time = 0L)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = ids,
                sequencer = sequencer,
                priceTracker = priceTracker,
                positions = positions,
                pnl = pnl,
                strategyPositions = strategyPositions,
                strategyPnL = strategyPnL,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = listOf(strategyId to strategy),
                riskEngine = riskEngine,
                riskState = riskState,
                mode = Mode.BACKTEST,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = null,
            )
        return Harness(pipeline, strategyPositions)
    }

    @Test
    fun `sequence completes through TradingPipeline candle hub and fires rule with snapshots`() {
        val strategy =
            compile(
                """
                STRATEGY seq VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                SEQUENCE sweep ON gold {
                    STAGE swept: gold.low < 99
                    STAGE reclaimed WITHIN 2m: gold.close > 100
                }
                RULES
                    WHEN SEQUENCE.sweep.complete AND SEQUENCE.sweep.swept.price < 99
                    THEN BUY gold SIZING 1
                """.trimIndent(),
            )
        val h = harness(strategy)

        h.pipeline.ingest(Tick(symbol, BigDecimal("100.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("98.50"), 10_000L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("98.50"), 60_001L))

        assertThat(h.strategyPositions.positionFor(strategyId, symbol)).isNull()

        h.pipeline.ingest(Tick(symbol, BigDecimal("101.00"), 70_000L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("101.00"), 120_001L))

        val position = h.strategyPositions.positionFor(strategyId, symbol)
        assertThat(position).isNotNull
        assertThat(position!!.quantity).isGreaterThan(BigDecimal.ZERO)
    }
}
