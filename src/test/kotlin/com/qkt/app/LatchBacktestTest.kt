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
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.LatchCompiler
import com.qkt.dsl.compile.PendingStacks
import com.qkt.dsl.compile.SizingCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.engine.Engine
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
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
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
    private val streamAlias = "gold"
    private val strategyId = "alpha"

    // 1m candle window: closes after 60s, which is well within the 5m arm window
    // when clock.now() is fixed at 0 (arm expires at 300_000ms).
    private val hubKey = HubKey("BACKTEST", "XAUUSD", "1m")

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

    // Single unbracketed limit 0.20 below the anchor, expiring 100s after it is placed.
    // Drives the EXPIRE-in-backtest proof: the pending limit must be swept against the
    // sim clock before a pullback can fill it.
    private val expireLatchAst =
        Latch(
            stream = streamAlias,
            sensor = BreakOffset(reference = null, offset = NumLit(BigDecimal("0.50"))),
            armWindow = DurationAst(3_600_000L),
            name = null,
            entries =
                listOf(
                    LatchEntry(
                        order = LatchLimit(DirRel(DirSense.AGAINST, NumLit(BigDecimal("0.20")))),
                        bracket = null,
                        sizing = null,
                        expire = DurationAst(100_000L),
                    ),
                ),
        )

    /**
     * Stub DSL strategy that arms [ast] once on the first candle close.
     * Mirrors the stub shape from TradingPipelineStackTest.
     */
    private inner class LatchStubStrategy(
        private val ast: Latch = latchAst,
    ) : DslCompiledStrategy {
        override val declaredStreams: Map<String, HubKey> = mapOf(streamAlias to hubKey)
        override val retentionByKey: Map<HubKey, Int> = mapOf(hubKey to 1)
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
                        streams = mapOf(streamAlias to hubKey),
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
        val clock: FixedClock,
        val positions: PositionTracker,
    )

    private fun harness(makeStrategy: () -> Strategy = { LatchStubStrategy() }): Harness {
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
        val strategy = makeStrategy()
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
        return Harness(pipeline, strategyPnL, clock, positions)
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
    fun `DSL latch with LET-named distances arms and trades end-to-end through AstCompiler`() {
        // Regression for the LET-in-action wiring: the stub strategy compiles the latch
        // directly, bypassing AstCompiler. A real AstCompiler-compiled strategy with
        // LET-named distances would throw "LATCH distances must be compile-time constants"
        // at the wire cross unless the action is LET-resolved at compile time. Same price
        // path as the literal-distance test (off=0.50, near=4, sl=12, tp=5).
        val dsl =
            """
            STRATEGY let_latch VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            LET wire = 0.50, near = 4, sl = 12, tp = 5
            RULES
                WHEN POSITION.gold = 0
                THEN LATCH gold OFFSET wire ARM 5m {
                    ENTER LIMIT RETRACE near BRACKET { STOP LOSS AGAINST sl, TAKE PROFIT WITH tp } SIZING 1
                }
            """.trimIndent()
        val ast = (Dsl.parse(dsl) as ParseResult.Success).value
        val h = harness { AstCompiler().compile(ast) }

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L)) // close candle → arm
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_002L)) // cross up-wire → place limit
        h.pipeline.ingest(Tick(symbol, BigDecimal("1996.40"), 60_003L)) // pullback fills entry
        h.pipeline.ingest(Tick(symbol, BigDecimal("2005.60"), 60_004L)) // TP exits

        val realized = h.strategyPnL.realizedFor(strategyId)
        assertThat(realized)
            .withFailMessage("expected positive realized PnL from a LET-named latch, got $realized")
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
    fun `latch limit fills on a pullback when ingested before its EXPIRE deadline`() {
        // Positive control for the EXPIRE test below: with the sim clock still inside
        // the entry's 100s window, the pending limit is live and a pullback fills it.
        val h = harness { LatchStubStrategy(expireLatchAst) }

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L)) // close candle → arm
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_002L)) // cross up-wire → place BUY LIMIT 2000.30
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.25"), 60_003L)) // pullback ≤ 2000.30 fills it

        assertThat(h.positions.positionFor(symbol)?.quantity)
            .withFailMessage("expected the limit to fill into a long position before expiry")
            .isEqualByComparingTo(BigDecimal.ONE)
    }

    @Test
    fun `latch limit is swept by EXPIRE against the sim clock before a pullback can fill it`() {
        // Regression for the latch clock bug: the entry builder stamped expiresAt from
        // SystemClock (wall-clock ms) instead of the runtime sim clock, so the GTD sweep —
        // which keys on the sim clock — never reached the deadline in backtest and the
        // stale deep leg lingered. Same price path as the control above; only the clock moves.
        val h = harness { LatchStubStrategy(expireLatchAst) }

        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 0L))
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.00"), 60_001L)) // close candle → arm (clock.now()=0)
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.60"), 60_002L)) // cross → rest BUY LIMIT 2000.30

        // Advance the sim clock past the entry's 100s deadline, then deliver a tick above
        // the limit (no fill) — exactly the deep-leg case: price drifts away while the order
        // sits unfilled. The GTD sweep must cancel the stale resting limit on this tick.
        h.clock.time = 100_001L
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.55"), 60_003L)) // no fill; sweep cancels the expired limit
        h.pipeline.ingest(Tick(symbol, BigDecimal("2000.25"), 60_004L)) // pullback finds nothing left to fill

        assertThat(h.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO)
            .withFailMessage("expected EXPIRE to cancel the resting limit, leaving no position")
            .isEqualByComparingTo(BigDecimal.ZERO)
    }
}
