package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RuleDecisionEvent
import com.qkt.events.SignalEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineGateTest {
    private fun newPipeline(
        strategies: List<Pair<String, Strategy>>,
        gate: () -> Boolean,
    ): Pair<TradingPipeline, EventBus> {
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
                strategies = strategies,
                riskEngine = riskEngine,
                riskState = riskState,
                mode = Mode.BACKTEST,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = TimeWindow.ONE_MINUTE,
                gate = gate,
            )
        return pipeline to bus
    }

    @Test
    fun `gate false suppresses signal emission`() {
        val captured = mutableListOf<Signal>()
        val gate = AtomicBoolean(false)
        val emittingStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("BTCUSDT", BigDecimal.ONE))
                }
            }
        val (pipeline, bus) = newPipeline(listOf("s1" to emittingStrategy)) { gate.get() }
        bus.subscribe<SignalEvent> { e -> captured.add(e.signal) }

        pipeline.ingest(Tick("BTCUSDT", Money.of("100"), 1_000L))
        assertThat(captured).isEmpty()

        gate.set(true)
        pipeline.ingest(Tick("BTCUSDT", Money.of("101"), 2_000L))
        assertThat(captured).hasSize(1)
        assertThat(captured.first()).isInstanceOf(Signal.Buy::class.java)
    }

    @Test
    fun `gate false publishes a suppression event so the drop is auditable`() {
        val suppressed = mutableListOf<com.qkt.events.SignalSuppressedEvent>()
        val emittingStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("BTCUSDT", BigDecimal.ONE))
                }
            }
        val (pipeline, bus) = newPipeline(listOf("s1" to emittingStrategy)) { false }
        bus.subscribe<com.qkt.events.SignalSuppressedEvent> { e -> suppressed.add(e) }

        pipeline.ingest(Tick("BTCUSDT", Money.of("100"), 1_000L))

        assertThat(suppressed).hasSize(1)
        assertThat(suppressed.first().strategyId).isEqualTo("s1")
        assertThat(suppressed.first().reason).contains("gate")
    }

    @Test
    fun `dsl causality is published before synchronous broker side effects`() {
        val parsed =
            Dsl.parse(
                """
                STRATEGY causal VERSION 1
                SYMBOLS
                  asset = PAPER:BTCUSDT EVERY 1m
                RULES
                  WHEN asset.close > 0 THEN BUY asset SIZING 1
                """.trimIndent(),
            ) as ParseResult.Success
        val strategy = AstCompiler().compile(parsed.value)
        val (pipeline, bus) = newPipeline(listOf("causal" to strategy)) { true }
        val eventTypes = mutableListOf<Class<out com.qkt.events.Event>>()
        bus.subscribeAllFirst { event -> eventTypes.add(event.javaClass) }

        pipeline.ingest(Tick("PAPER:BTCUSDT", Money.of("100"), 0L))
        pipeline.ingest(Tick("PAPER:BTCUSDT", Money.of("101"), 60_000L))

        assertThat(eventTypes)
            .contains(
                RuleDecisionEvent::class.java,
                SignalEvent::class.java,
                DecisionOrderLinkedEvent::class.java,
                OrderEvent::class.java,
                BrokerEvent.OrderFilled::class.java,
                FillAccountedEvent::class.java,
            )
        assertThat(eventTypes.indexOf(RuleDecisionEvent::class.java))
            .isLessThan(eventTypes.indexOf(SignalEvent::class.java))
        assertThat(eventTypes.indexOf(DecisionOrderLinkedEvent::class.java))
            .isLessThan(eventTypes.indexOf(OrderEvent::class.java))
        assertThat(eventTypes.indexOf(OrderEvent::class.java))
            .isLessThan(eventTypes.indexOf(BrokerEvent.OrderFilled::class.java))
        assertThat(eventTypes.indexOf(BrokerEvent.OrderFilled::class.java))
            .isLessThan(eventTypes.indexOf(FillAccountedEvent::class.java))
    }
}
