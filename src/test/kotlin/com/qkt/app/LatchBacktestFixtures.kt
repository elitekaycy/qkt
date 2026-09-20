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
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.LatchCompiler
import com.qkt.dsl.compile.PendingStacks
import com.qkt.dsl.compile.SizingCompiler
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
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal

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
abstract class LatchBacktestFixtures {
    protected val symbol = "BACKTEST:XAUUSD"
    protected val silverSymbol = "BACKTEST:XAGUSD"
    protected val streamAlias = "gold"
    protected val silverAlias = "silver"
    protected val strategyId = "alpha"

    // 1m candle window: closes after 60s, which is well within the 5m arm window
    // when clock.now() is fixed at 0 (arm expires at 300_000ms).
    protected val hubKey = HubKey("BACKTEST", "XAUUSD", "1m")
    protected val silverHubKey = HubKey("BACKTEST", "XAGUSD", "1m")

    // Latch AST: ENTER LIMIT RETRACE 4 BRACKET { SL AGAINST 12, TP WITH 5 }, no explicit sizing
    protected val latchAst =
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
     * Mirrors the stub shape from TradingPipelineStackFixtures.
     */
    protected inner class LatchStubStrategy(
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

    protected data class Harness(
        val pipeline: TradingPipeline,
        val strategyPnL: StrategyPnL,
        val strategyPositions: StrategyPositionTracker,
        val clock: FixedClock,
        val bus: EventBus,
    )

    protected fun harness(
        ast: Latch = latchAst,
        declaredStreams: Map<String, HubKey> = mapOf(streamAlias to hubKey),
        gate: () -> Boolean = { true },
    ): Harness {
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
}
