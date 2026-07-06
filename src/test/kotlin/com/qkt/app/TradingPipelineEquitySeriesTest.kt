package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.engine.Engine
import com.qkt.events.SignalEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineEquitySeriesTest {
    @Test
    fun `account equity series can gate an order on a real stream`() {
        val parsed =
            Dsl.parse(
                """
                STRATEGY equity_filter VERSION 1
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                  eq = SERIES ACCOUNT.EQUITY EVERY 1m
                RULES
                  WHEN eq.close >= 0 AND gold.close > 0 THEN BUY gold SIZING 1
                """.trimIndent(),
            ) as ParseResult.Success
        val strategy = AstCompiler().compile(parsed.value)

        val clock = FixedClock(time = 0L)
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val strategyPositions = StrategyPositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        val captured = mutableListOf<Signal>()
        bus.subscribe<SignalEvent> { e -> captured.add(e.signal) }
        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = SequentialIdGenerator(),
                sequencer = sequencer,
                priceTracker = priceTracker,
                positions = positions,
                pnl = pnl,
                strategyPositions = strategyPositions,
                strategyPnL = strategyPnL,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = listOf("equity_filter" to strategy),
                riskEngine = RiskEngine(rules = emptyList(), positions = positions),
                riskState = riskState,
                mode = Mode.BACKTEST,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
            )

        pipeline.ingest(Tick("BACKTEST:XAUUSD", Money.of("100"), 0L))
        pipeline.ingest(Tick("BACKTEST:XAUUSD", Money.of("101"), 60_000L))

        assertThat(captured).anySatisfy { signal ->
            assertThat(signal).isInstanceOf(Signal.Buy::class.java)
        }
    }
}
