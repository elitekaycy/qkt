package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchCloseBeyond
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.LatchMarket
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.LatchCompiler
import com.qkt.dsl.compile.PendingStacks
import com.qkt.dsl.compile.SizingCompiler
import com.qkt.engine.Engine
import com.qkt.events.SignalSuppressedEvent
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
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * End-to-end proof that the LATCH flow works through the real pipeline:
 *
 * 1. A candle close arms the latch (ref=2000.00, offset=0.50 → up=2000.50, down=1999.50).
 * 2. A tick crossing the up-wire places a BUY LIMIT RETRACE 4 bracket
 *    (entry=1996.50, TP=2005.50, SL=1988.50).
 * 3. A pullback tick fills the limit entry.
 * 4. A TP tick fills the bracket exit → positive realized PnL.
 *
 * A second case drives ticks that never reach the up-wire and asserts no position opens.
 *
 * The pipeline harness mirrors [StackPnlSanityTest]: real PaperBroker, real OrderManager,
 * real LatchManager — no mocks of the engine layer.
 */
class LatchBacktestTest {
    private val symbol = "BACKTEST:XAUUSD"
    private val silverSymbol = "BACKTEST:XAGUSD"
    private val streamAlias = "gold"
    private val silverAlias = "silver"
    private val strategyId = "alpha"

    // 1m candle window: closes after 60s, which is well within the 5m arm window
    // when clock.now() is fixed at 0 (arm expires at 300_000ms).
    private val hubKey = HubKey("BACKTEST", "XAUUSD", "1m")
    private val silverHubKey = HubKey("BACKTEST", "XAGUSD", "1m")

    // Latch AST: ENTER LIMIT RETRACE 4 BRACKET { SL AGAINST 12, TP WITH 5 }, no explicit sizing
    private val latchAst =
        Latch(
            stream = streamAlias,
            sensor = BreakOffset(reference = null, offset = NumLit(BigDecimal("0.50"))),
            armWindow = DurationAst(300_000L),
            name = null,
            entries =
                listOf(
                    LatchEntry(
                        order = LatchLimit(DirRel(DirSense.AGAINST, NumLit(BigDecimal("4")))),
                        bracket =
                            LatchBracket(
                                stopLoss = DirRel(DirSense.AGAINST, NumLit(BigDecimal("12"))),
                                takeProfit = DirRel(DirSense.WITH, NumLit(BigDecimal("5"))),
                            ),
                        sizing = null,
                        expire = DurationAst(7_200_000L),
                    ),
                ),
        )

    /**
     * Stub DSL strategy that arms the latch once on the first candle close.
     * Mirrors the stub shape from TradingPipelineStackTest.
     */
    private inner class LatchStubStrategy(
        private val ast: Latch,
        override val declaredStreams: Map<String, HubKey> = mapOf(streamAlias to hubKey),
    ) : DslCompiledStrategy {
        override val retentionByKey: Map<HubKey, Int> = declaredStreams.values.associateWith { 1 }
        override val pendingStacks: PendingStacks = PendingStacks()

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            hub.onClosed(hubKey, ctx.strategyId) { candle ->
                val exprCompiler = ExprCompiler()
                val sizingCompiler = SizingCompiler(exprCompiler)
                val ids = SequentialIdGenerator(prefix = "latch-e2e-")
                val compiler = LatchCompiler(exprCompiler, sizingCompiler, ids)
                val compiled = compiler.compile(ast, ctx.strategyId)
                val ec =
                    EvalContext(
                        candle = candle,
                        streams = declaredStreams,
                        lets = emptyMap(),
                        strategyContext = ctx,
                    )
                emit(Signal.ArmLatch(compiled, ec))
            }
        }

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}
    }

    private data class Harness(
        val pipeline: TradingPipeline,
        val strategyPnL: StrategyPnL,
        val strategyPositions: StrategyPositionTracker,
        val clock: FixedClock,
        val bus: EventBus,
    )

    private fun harness(
        ast: Latch = latchAst,
        declaredStreams: Map<String, HubKey> = mapOf(streamAlias to hubKey),
        gate: () -> Boolean = { true },
    ): Harness {
        val clock = FixedClock(time = 0L)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        val strategy = LatchStubStrategy(ast, declaredStreams)
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
                gate = gate,
            )
        return Harness(pipeline, strategyPnL, strategyPositions, clock, bus)
    }

    @Test
    fun `latch arms on candle close, up-wire cross places limit, pullback fills it, TP exits with positive PnL`() {
        val h = harness()
        // Tick 1: open a 1-min candle at 2000.00 (t=0, window [0, 60000))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))

        // Tick 2: cross into the next 1-min window (t=60001) → closes the first candle (close=2000.00)
        //         → hub fires → latch arms with up=2000.50, down=1999.50
        //         clock.now()=0 → expires at 300_000ms; ticks 3-5 are well under that
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))

        // Tick 3: price crosses up-wire 2000.50 → LatchManager fires → places BUY LIMIT at 1996.50
        //         bracket: TP=2005.50, SL=1988.50
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_002L))

        // Tick 4: pullback to 1996.40 ≤ 1996.50 (BUY LIMIT fills) → position opens at ~1996.40
        h.pipeline.ingest(Tick(symbol, BigDecimal("1996.40"), 60_003L))

        // Tick 5: rally to 2005.60 ≥ 2005.50 (TP fills) → position closes at ~2005.50
        h.pipeline.ingest(Tick(symbol, BigDecimal("2005.60"), 60_004L))

        // PnL: (2005.50 - 1996.40) * qty=1 ≈ +9.10 — just assert it's positive
        val realized = h.strategyPnL.realizedFor(strategyId)
        assertThat(realized)
            .withFailMessage("expected positive realized PnL after TP fill, got $realized")
            .isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `no wire cross means no position opened`() {
        val h = harness()

        // Tick 1: open candle at 2000.00
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))

        // Tick 2: crosses 1-min candle boundary → arms latch (up=2000.50, down=1999.50)
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))

        // Ticks 3-5: price moves but never reaches 2000.50 or drops to 1999.50
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.10"), 60_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.20"), 60_003L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.30"), 60_004L))

        val realized = h.strategyPnL.realizedFor(strategyId)
        assertThat(realized)
            .withFailMessage("expected zero realized PnL with no wire cross, got $realized")
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `latch fire routes through the strategy gate and suppression event`() {
        val enabled = AtomicBoolean(true)
        val h = harness(gate = enabled::get)
        val suppressed = mutableListOf<SignalSuppressedEvent>()
        h.bus.subscribe<SignalSuppressedEvent> { suppressed.add(it) }

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))
        enabled.set(false)
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("1996.40"), 60_003L))

        assertThat(h.strategyPositions.positionFor(strategyId, symbol)).isNull()
        assertThat(suppressed).hasSize(1)
        assertThat(suppressed.single().signal).isInstanceOf(Signal.Submit::class.java)
        assertThat(suppressed.single().strategyId).isEqualTo(strategyId)
    }

    @Test
    fun `CLOSE_BEYOND fakeout spike does not fire until a candle closes beyond the wire`() {
        val ast =
            Latch(
                stream = streamAlias,
                sensor = BreakOffset(reference = null, offset = NumLit(BigDecimal("0.50"))),
                armWindow = DurationAst(300_000L),
                name = null,
                entries = listOf(LatchEntry(order = LatchMarket)),
                confirm = LatchCloseBeyond,
            )
        val h = harness(ast)

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))

        h.pipeline.ingest(Tick(symbol, BigDecimal("2001.00"), 60_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.40"), 60_003L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.40"), 120_001L))
        assertThat(h.strategyPositions.positionFor(strategyId, symbol)).isNull()

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 120_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 180_001L))
        assertThat(h.strategyPositions.positionFor(strategyId, symbol)).isNotNull
    }

    @Test
    fun `gold CLOSE_BEYOND can enter silver from silver price snapshot`() {
        val ast =
            Latch(
                stream = streamAlias,
                sensor = BreakOffset(reference = null, offset = NumLit(BigDecimal("0.50"))),
                armWindow = DurationAst(300_000L),
                name = null,
                entries = listOf(LatchEntry(order = LatchMarket, stream = silverAlias)),
                confirm = LatchCloseBeyond,
            )
        val h = harness(ast, mapOf(streamAlias to hubKey, silverAlias to silverHubKey))

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L))
        h.pipeline.ingest(Tick(silverSymbol, BigDecimal("30.00"), 60_002L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_003L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 120_001L))

        val silver = h.strategyPositions.positionFor(strategyId, silverSymbol)
        assertThat(silver).isNotNull
        assertThat(silver!!.quantity).isEqualByComparingTo("1")
        assertThat(silver.avgEntryPrice).isEqualByComparingTo("30.00")
    }
}
