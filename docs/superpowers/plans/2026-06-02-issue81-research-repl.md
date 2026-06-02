# qkt research REPL — Playback v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `qkt research <strategy.qkt> --data <symbol> --from <d> --to <d>` — an interactive line REPL that loads a strategy + a historical window once, then steps / runs / seeks through a deterministic tick replay, showing an event tape + running PnL/positions/equity, with `reload` to recompile after an edit.

**Architecture:** Extract the construction + tick-advance now inside `Backtest.run()` into a reusable `ReplayEngine` (new `com.qkt.research` package). `Backtest.run()` becomes a thin `ReplayEngine(...).runToEnd()` wrapper, so batch backtest and interactive replay are built and driven by one code path — accuracy by construction. A `ReplaySession` wraps the engine with `step`/`run-to`/`reset`/`reload`; a `ReplayRepl` loop reads commands and renders via `TapeRenderer`; `ResearchCommand` is the CLI entry.

**Tech Stack:** Kotlin, JUnit 5 + AssertJ (no mocks — real types, anonymous `object : Strategy`), the existing `TradingPipeline` / `Backtest` / DSL compiler / `LocalMarketSource`.

**Spec:** `docs/superpowers/specs/2026-06-02-issue81-research-repl-design.md`

**Conventions reminder:** Commit subject only, no body, no footer (`<type>(<scope>): <subject>`, ≤70 chars). Per repo memory, the full `./gradlew build` + ktlint are verified in CI — locally run the focused `--tests` command shown in each task. Every new public type gets KDoc (already inline in the code below).

---

### Task 1: Extract `ReplayEngine`; make `Backtest.run()` delegate

The behavior-preserving core. `ReplayEngine` reproduces `Backtest.run()`'s construction (current `Backtest.kt:93-191`), exposes pull-based advance + a `snapshot()` that reproduces the report build (`Backtest.kt:201-231`), and counts ticks/bars/trades for stepping. `Backtest.run()` then delegates.

**Files:**
- Create: `src/main/kotlin/com/qkt/research/ReplayEngine.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/Backtest.kt:92-245` (replace `run()` body, delete `annualizationFactorFor`, drop now-unused imports)
- Test: `src/test/kotlin/com/qkt/research/ReplayEngineTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/research/ReplayEngineTest.kt`:

```kotlin
package com.qkt.research

import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayEngineTest {
    private fun ticks() = (1..10).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }

    private fun buyThenSell(): Strategy {
        var seen = 0
        return object : Strategy {
            override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
                seen += 1
                if (seen == 2) emit(Signal.Buy("X", Money.of("1")))
                if (seen == 8) emit(Signal.Sell("X", Money.of("1")))
            }
        }
    }

    private fun engine(strategy: Strategy = buyThenSell()) =
        ReplayEngine(
            strategies = listOf("s1" to strategy),
            feed = HistoricalTickFeed(ticks()),
            candleWindow = TimeWindow.ONE_MINUTE,
            cadence = SampleCadence.CANDLE_CLOSE,
        )

    @Test
    fun `run to end produces trades and an equity curve`() {
        val result = engine().runToEnd()
        assertThat(result.global.tradeCount).isGreaterThan(0)
        assertThat(result.global.equityCurve).isNotEmpty()
        assertThat(result.cadence).isEqualTo(SampleCadence.CANDLE_CLOSE)
    }

    @Test
    fun `stepping in increments reaches the same end state as run-to-end`() {
        val whole = engine().runToEnd()

        val stepped = engine()
        stepped.advanceUntil { stepped.barsClosed >= 3 }
        stepped.advanceUntil { stepped.currentTimestamp >= 7 * 60_000L }
        stepped.advanceToEnd()
        val steppedResult = stepped.snapshot()

        assertThat(steppedResult.global.totalPnL).isEqualByComparingTo(whole.global.totalPnL)
        assertThat(steppedResult.global.tradeCount).isEqualTo(whole.global.tradeCount)
        assertThat(stepped.exhausted).isTrue()
    }

    @Test
    fun `bars closed counts candle closes`() {
        val e = engine()
        e.advanceToEnd()
        assertThat(e.barsClosed).isGreaterThan(0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.research.ReplayEngineTest"`
Expected: FAIL — compilation error, `ReplayEngine` is unresolved.

- [ ] **Step 3: Create `ReplayEngine`**

Create `src/main/kotlin/com/qkt/research/ReplayEngine.kt`:

```kotlin
package com.qkt.research

import com.qkt.app.IndicatorWarmer
import com.qkt.app.TradingPipeline
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.EquityCurveCollector
import com.qkt.backtest.EquitySample
import com.qkt.backtest.ReportBuilder
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.TradeRecord
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.RiskRejectedEvent
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.Position
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal
import java.time.Instant

/**
 * The shared replay core: builds the full trading pipeline once and advances ticks
 * through it on demand. Both the batch [com.qkt.backtest.Backtest] and the interactive
 * research session drive this one type, so their results cannot diverge — pacing only
 * decides when we stop pulling ticks, never the tick->ingest order.
 *
 * Construction mirrors `Backtest.run()` exactly (same wiring, same order) so a full
 * [runToEnd] is bit-identical to the previous batch path.
 */
class ReplayEngine(
    private val strategies: List<Pair<String, Strategy>>,
    rules: List<RiskRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    source: MarketSource = NullMarketSource,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    warmupSpec: WarmupSpec = WarmupSpec.None,
    symbols: List<String> = emptyList(),
    cadence: SampleCadence? = null,
    private val startingBalance: BigDecimal = BigDecimal.ZERO,
    instruments: InstrumentRegistry = NoopInstrumentRegistry,
    brokerKind: BrokerKind = BrokerKind.PAPER,
    private val latencyEnabled: Boolean = System.getenv("QKT_LATENCY_TRACKING") == "1",
) : AutoCloseable {
    private val cadence: SampleCadence =
        cadence ?: if (candleWindow != null) SampleCadence.CANDLE_CLOSE else SampleCadence.TICK

    /** Timestamp of the last ingested tick (or [initialTimestamp] before the first). */
    var currentTimestamp: Long = initialTimestamp
        private set

    /** Count of ticks ingested so far. */
    var ticksIngested: Long = 0L
        private set

    /** Count of candle closes seen so far (primary `candleWindow`). */
    var barsClosed: Long = 0L
        private set

    /** True once the feed has been fully drained. */
    var exhausted: Boolean = false
        private set

    private val clock = FixedClock(time = initialTimestamp)
    private val priceTracker = MarketPriceTracker()
    private val positions = PositionTracker()
    private val pnl: PnLCalculator
    private val strategyPnL: StrategyPnL
    private val collector: EquityCurveCollector
    private val pipeline: TradingPipeline
    private val tradeRecords = mutableListOf<TradeRecord>()
    private val rejections = mutableListOf<RiskRejectedEvent>()

    init {
        require(this.cadence != SampleCadence.CANDLE_CLOSE || candleWindow != null) {
            "SampleCadence.CANDLE_CLOSE requires candleWindow"
        }
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        pnl = PnLCalculator(positions, priceTracker, instruments)
        val strategyPositions = StrategyPositionTracker()
        strategyPnL = StrategyPnL(strategyPositions, priceTracker, instruments)
        for ((id, _) in strategies) strategyPnL.setStartingBalance(id, startingBalance)
        val bus = EventBus(clock, sequencer)
        val engine = Engine(bus, priceTracker)
        val candleHub = com.qkt.dsl.compile.CandleHub()

        val dslStrategies =
            strategies.mapNotNull { (_, s) -> s as? com.qkt.dsl.compile.DslCompiledStrategy }
        val brokerSymbols: MutableMap<String, MutableSet<String>> = mutableMapOf()
        for (s in dslStrategies) {
            for (key in s.declaredStreams.values) {
                brokerSymbols.getOrPut(key.broker) { mutableSetOf() }.add(key.qktSymbol)
            }
        }
        val brokerFactory: () -> com.qkt.broker.Broker =
            when (brokerKind) {
                BrokerKind.PAPER -> { -> PaperBroker(bus, clock, priceTracker) }
                BrokerKind.MT5_SIM ->
                    { -> com.qkt.broker.MT5BrokerSimulator(bus, clock, priceTracker, instruments) }
            }
        val broker: com.qkt.broker.Broker =
            if (brokerSymbols.isEmpty()) {
                brokerFactory()
            } else {
                com.qkt.broker.CompositeBroker(
                    routes =
                        brokerSymbols.map { (_, syms) ->
                            com.qkt.marketdata.source.SymbolPattern
                                .exactSet(syms.toSet()) to brokerFactory()
                        },
                    bus = bus,
                )
            }
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        riskState.warmupComplete = true
        val riskEngine = RiskEngine(rules, emptyList(), positions, riskState)

        collector =
            EquityCurveCollector(
                cadence = this.cadence,
                bus = bus,
                pnl = pnl,
                strategyPnL = strategyPnL,
                strategyIds = strategies.map { it.first },
            )

        val holder = arrayOfNulls<TradingPipeline>(1)
        pipeline =
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
                calendar = calendar,
                source = source,
                candleWindow = candleWindow,
                candleHub = candleHub,
                onFilled = { trade, realized, strategyId ->
                    val risk = holder[0]?.orderManager?.riskUsdFor(trade.orderId)
                    tradeRecords.add(TradeRecord(trade, realized, strategyId, risk))
                },
                onRejected = { e -> rejections.add(e) },
                onCandle = { barsClosed++ },
                instruments = instruments,
                latencyEnabled = latencyEnabled,
            )
        holder[0] = pipeline

        if (source !== NullMarketSource && warmupSpec !is WarmupSpec.None && symbols.isNotEmpty()) {
            IndicatorWarmer(source, pipeline).warmup(
                symbols = symbols,
                spec = warmupSpec,
                now = Instant.ofEpochMilli(initialTimestamp),
            )
        }
    }

    /** Pull and ingest ticks until [stop] returns true after a tick, or the feed drains. */
    fun advanceUntil(stop: () -> Boolean) {
        if (exhausted) return
        while (true) {
            val tick = feed.next()
            if (tick == null) {
                exhausted = true
                feed.close()
                break
            }
            currentTimestamp = tick.timestamp
            ticksIngested++
            clock.time = tick.timestamp
            pipeline.ingest(tick)
            if (stop()) break
        }
    }

    /** Advance to the end of the feed. */
    fun advanceToEnd() = advanceUntil { false }

    /** Advance to the end and return the result — the batch-backtest convenience path. */
    fun runToEnd(): BacktestResult {
        advanceToEnd()
        return snapshot()
    }

    /** Trades filled so far. */
    val tradeCount: Int get() = tradeRecords.size

    /** Account equity as of the last ingested tick: starting balance + realized + unrealized. */
    fun equity(): BigDecimal = startingBalance + pnl.realizedTotal() + pnl.unrealizedTotal()

    /** Currently open (non-flat) positions keyed by symbol. */
    fun openPositions(): Map<String, Position> =
        positions.allPositions().filterValues { it.quantity.signum() != 0 }

    /** Build a [BacktestResult] from current state — valid mid-replay or at end. */
    fun snapshot(): BacktestResult {
        val annualizationFactor = annualizationFactorFor(collector.global())
        val globalReport =
            ReportBuilder.buildGlobal(
                trades = tradeRecords,
                equityCurve = collector.global(),
                finalRealized = pnl.realizedTotal(),
                finalUnrealized = pnl.unrealizedTotal(),
                annualizationFactor = annualizationFactor,
            )
        val perStrategy =
            strategies.associate { (id, _) ->
                id to
                    ReportBuilder.buildPerStrategy(
                        strategyId = id,
                        trades = tradeRecords.filter { it.strategyId == id },
                        equityCurve = collector.forStrategy(id),
                        finalRealized = strategyPnL.realizedFor(id),
                        finalUnrealized = strategyPnL.unrealizedTotalFor(id),
                        annualizationFactor = annualizationFactor,
                    )
            }
        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = rejections.toList(),
            finalPositions = positions.allPositions(),
            global = globalReport,
            perStrategy = perStrategy,
            cadence = cadence,
            latencyReport = if (latencyEnabled) pipeline.latency.snapshot() else null,
        )
    }

    override fun close() = feed.close()

    private fun annualizationFactorFor(curve: List<EquitySample>): BigDecimal {
        if (cadence == SampleCadence.CANDLE_CLOSE && candleWindow != null) {
            return calendar.tradingPeriodsPerYear(candleWindow)
        }
        if (curve.size < 2) return BigDecimal("252")
        val spanMs = curve.last().timestamp - curve.first().timestamp
        if (spanMs <= 0L) return BigDecimal("252")
        val avgIntervalMs = BigDecimal(spanMs).divide(BigDecimal(curve.size - 1), Money.CONTEXT)
        val msPerYear = BigDecimal("31557600000")
        return msPerYear.divide(avgIntervalMs, Money.CONTEXT)
    }
}
```

- [ ] **Step 4: Run the new test to verify it passes**

Run: `./gradlew test --tests "com.qkt.research.ReplayEngineTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Make `Backtest.run()` delegate**

In `src/main/kotlin/com/qkt/backtest/Backtest.kt`, replace the entire `run()` method (lines 92-232) **and** the private `annualizationFactorFor` (lines 234-245) with just this `run()`:

```kotlin
    fun run(): BacktestResult =
        com.qkt.research.ReplayEngine(
            strategies = strategies,
            rules = rules,
            feed = feed,
            candleWindow = candleWindow,
            initialTimestamp = initialTimestamp,
            source = source,
            calendar = calendar,
            warmupSpec = warmupSpec,
            symbols = symbols,
            cadence = cadence,
            startingBalance = startingBalance,
            instruments = instruments,
            brokerKind = brokerKind,
            latencyEnabled = latencyEnabled,
        ).runToEnd()
```

Then delete every import in `Backtest.kt` that this slimmed file no longer references. After the edit, `Backtest` only needs the symbols used by its constructors + `fromStore`/`fromSource`. Remove these now-unused imports: `IndicatorWarmer`, `TradingPipeline`, `PaperBroker`, `EventBus`, `FixedClock` (still used by `fromStore`/`fromSource`? yes — keep `FixedClock`), `Money`, `MonotonicSequenceGenerator`, `SequentialIdGenerator`, `Engine`, `RiskRejectedEvent`, `MarketPriceTracker`, `PnLCalculator`, `StrategyPnL`, `PositionTracker`, `StrategyPositionTracker`, `RiskEngine`, `RiskState`, `Mode`. Keep: `TimeWindow`, `TimeRange`, `TradingCalendar`, `WarmupSpec`, `Strategy`, `RiskRule`, `HistoricalTickFeed`, `MergingTickFeed`, `SequenceTickFeed`, `Tick`, `TickFeed`, `LocalMarketSource`, `MarketRequest`, `MarketSource`, `MarketSourceCapability`, `NullMarketSource`, `DataStore`, `FixedClock`, `BigDecimal`, `Instant`. (`EquityCurveCollector`, `EquitySample`, `ReportBuilder`, `TradeRecord`, `SampleCadence` live in the same `com.qkt.backtest` package — no import needed; `SampleCadence` is still referenced by the constructor signature.)

If unsure which imports are dead, the compile error from Step 6 lists each unused import — remove exactly those.

- [ ] **Step 6: Run the Backtest suite to prove behavior is preserved**

Run: `./gradlew test --tests "com.qkt.backtest.*" --tests "com.qkt.research.*" --tests "com.qkt.parity.*"`
Expected: PASS — every existing backtest + parity test green unchanged, plus the new `ReplayEngineTest`. This is the guardrail proving the extraction changed no behavior.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/research/ReplayEngine.kt \
        src/main/kotlin/com/qkt/backtest/Backtest.kt \
        src/test/kotlin/com/qkt/research/ReplayEngineTest.kt
git commit -m "refactor(research): add ReplayEngine, delegate Backtest run"
```

---

### Task 2: Buffer the replay tape

Add an in-order event buffer (signals emitted, fills, rejections) the session drains after each advance.

**Files:**
- Create: `src/main/kotlin/com/qkt/research/TapeEvent.kt`
- Modify: `src/main/kotlin/com/qkt/research/ReplayEngine.kt` (subscribe signals, append in callbacks, add `drainTape()`)
- Test: `src/test/kotlin/com/qkt/research/ReplayEngineTapeTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/research/ReplayEngineTapeTest.kt`:

```kotlin
package com.qkt.research

import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayEngineTapeTest {
    @Test
    fun `tape records a signal then its fill, and drains empty`() {
        var seen = 0
        val strategy =
            object : Strategy {
                override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
                    seen += 1
                    if (seen == 2) emit(Signal.Buy("X", Money.of("1")))
                }
            }
        val e =
            ReplayEngine(
                strategies = listOf("s1" to strategy),
                feed = HistoricalTickFeed((1..6).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }),
                candleWindow = TimeWindow.ONE_MINUTE,
                cadence = SampleCadence.CANDLE_CLOSE,
            )
        e.advanceToEnd()

        val tape = e.drainTape()
        assertThat(tape).anyMatch { it is TapeEvent.SignalEmitted }
        assertThat(tape).anyMatch { it is TapeEvent.Filled }
        assertThat(e.drainTape()).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.research.ReplayEngineTapeTest"`
Expected: FAIL — `TapeEvent` and `drainTape` unresolved.

- [ ] **Step 3: Create `TapeEvent`**

Create `src/main/kotlin/com/qkt/research/TapeEvent.kt`:

```kotlin
package com.qkt.research

import com.qkt.execution.Trade
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * A single line in the replay tape, captured in the order it occurred during ingest.
 * Rendered by [TapeRenderer]; carries the engine timestamp at which it fired.
 */
sealed interface TapeEvent {
    /** Engine clock (epoch millis) when this event occurred. */
    val timestamp: Long

    /** A strategy emitted a trading intent (before risk/broker). */
    data class SignalEmitted(override val timestamp: Long, val signal: Signal) : TapeEvent

    /** A broker fill landed. [realized] is the realized P&L of this fill. */
    data class Filled(
        override val timestamp: Long,
        val trade: Trade,
        val realized: BigDecimal,
        val strategyId: String,
    ) : TapeEvent

    /** The risk engine vetoed an order. [reason] is the rule label; [symbol] the order's symbol. */
    data class Rejected(override val timestamp: Long, val symbol: String, val reason: String) : TapeEvent
}
```

- [ ] **Step 4: Wire the buffer into `ReplayEngine`**

In `src/main/kotlin/com/qkt/research/ReplayEngine.kt`, add this field next to `tradeRecords`/`rejections`:

```kotlin
    private val tape = mutableListOf<TapeEvent>()
```

Change the `onFilled` and `onRejected` lambdas in the `TradingPipeline(...)` call to also append to the tape:

```kotlin
                onFilled = { trade, realized, strategyId ->
                    val risk = holder[0]?.orderManager?.riskUsdFor(trade.orderId)
                    tradeRecords.add(TradeRecord(trade, realized, strategyId, risk))
                    tape.add(TapeEvent.Filled(currentTimestamp, trade, realized, strategyId))
                },
                onRejected = { e ->
                    rejections.add(e)
                    tape.add(TapeEvent.Rejected(currentTimestamp, e.request.symbol, e.reason))
                },
```

At the very end of the `init` block (after `holder[0] = pipeline` and the warmup `if`), subscribe to signal events:

```kotlin
        bus.subscribe<com.qkt.events.SignalEvent> { e ->
            tape.add(TapeEvent.SignalEmitted(currentTimestamp, e.signal))
        }
```

Add the drain method next to `snapshot()`:

```kotlin
    /** Returns tape events accumulated since the last drain, then clears the buffer. */
    fun drainTape(): List<TapeEvent> {
        val out = tape.toList()
        tape.clear()
        return out
    }
```

- [ ] **Step 5: Run the tape test to verify it passes**

Run: `./gradlew test --tests "com.qkt.research.ReplayEngineTapeTest" --tests "com.qkt.backtest.*"`
Expected: PASS — tape test green and the backtest suite still green (the no-op signal subscriber + tape appends have no effect on results).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/research/TapeEvent.kt \
        src/main/kotlin/com/qkt/research/ReplayEngine.kt \
        src/test/kotlin/com/qkt/research/ReplayEngineTapeTest.kt
git commit -m "feat(research): buffer replay tape events"
```

---

### Task 3: `ReplayCommand` parser

Pure string -> command. No IO.

**Files:**
- Create: `src/main/kotlin/com/qkt/research/ReplayCommand.kt`
- Test: `src/test/kotlin/com/qkt/research/ReplayCommandTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/research/ReplayCommandTest.kt`:

```kotlin
package com.qkt.research

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplayCommandTest {
    @Test
    fun `parses every command form`() {
        assertThat(ReplayCommand.parse("run")).isEqualTo(ReplayCommand.Run)
        assertThat(ReplayCommand.parse("step 5")).isEqualTo(ReplayCommand.StepBars(5))
        assertThat(ReplayCommand.parse("step 2d")).isEqualTo(ReplayCommand.StepDuration(2 * 86_400_000L))
        assertThat(ReplayCommand.parse("step 30m")).isEqualTo(ReplayCommand.StepDuration(30 * 60_000L))
        assertThat(ReplayCommand.parse("run-to next-trade")).isEqualTo(ReplayCommand.RunToNextTrade)
        assertThat(ReplayCommand.parse("run-to 2024-01-15"))
            .isEqualTo(ReplayCommand.RunToTime(1_705_276_800_000L))
        assertThat(ReplayCommand.parse("reset")).isEqualTo(ReplayCommand.Reset)
        assertThat(ReplayCommand.parse("reload")).isEqualTo(ReplayCommand.Reload)
        assertThat(ReplayCommand.parse("show")).isEqualTo(ReplayCommand.Show)
        assertThat(ReplayCommand.parse("quit")).isEqualTo(ReplayCommand.Quit)
    }

    @Test
    fun `unknown input is reported, not crashed`() {
        assertThat(ReplayCommand.parse("frobnicate")).isEqualTo(ReplayCommand.Unknown("frobnicate"))
        assertThat(ReplayCommand.parse("step abc")).isEqualTo(ReplayCommand.Unknown("step abc"))
        assertThat(ReplayCommand.parse("")).isEqualTo(ReplayCommand.Unknown(""))
    }
}
```

(`2024-01-15` at UTC start-of-day is epoch millis `1705276800000`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.research.ReplayCommandTest"`
Expected: FAIL — `ReplayCommand` unresolved.

- [ ] **Step 3: Implement `ReplayCommand`**

Create `src/main/kotlin/com/qkt/research/ReplayCommand.kt`:

```kotlin
package com.qkt.research

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A parsed research-REPL command. [parse] maps a typed line to one of these; anything
 * unrecognised becomes [Unknown] so the loop can report it instead of crashing.
 */
sealed interface ReplayCommand {
    /** Advance to the end of the feed. */
    data object Run : ReplayCommand

    /** Advance [n] bars (primary timeframe), or [n] ticks when the strategy has no timeframe. */
    data class StepBars(val n: Int) : ReplayCommand

    /** Advance by a wall-clock span of [millis]. */
    data class StepDuration(val millis: Long) : ReplayCommand

    /** Advance until the engine clock reaches [epochMillis] (reset-and-forward if in the past). */
    data class RunToTime(val epochMillis: Long) : ReplayCommand

    /** Advance until the next fill. */
    data object RunToNextTrade : ReplayCommand

    /** Restart the replay from the first tick, same strategy. */
    data object Reset : ReplayCommand

    /** Re-read and recompile the strategy file, then reset. */
    data object Reload : ReplayCommand

    /** Print the current footer without advancing. */
    data object Show : ReplayCommand

    /** Exit the session. */
    data object Quit : ReplayCommand

    /** Unrecognised input; [input] is the trimmed line. */
    data class Unknown(val input: String) : ReplayCommand

    companion object {
        /** Parse one REPL line. Never throws — bad input returns [Unknown]. */
        fun parse(line: String): ReplayCommand {
            val t = line.trim()
            if (t.isEmpty()) return Unknown("")
            val parts = t.split(Regex("\\s+"))
            return when (parts[0]) {
                "run" -> Run
                "run-to" -> parseRunTo(t, parts.drop(1))
                "step" -> parseStep(t, parts.getOrNull(1))
                "reset" -> Reset
                "reload" -> Reload
                "show" -> Show
                "quit", "exit", "q" -> Quit
                else -> Unknown(t)
            }
        }

        private fun parseRunTo(raw: String, args: List<String>): ReplayCommand {
            val arg = args.firstOrNull() ?: return Unknown(raw)
            if (arg == "next-trade") return RunToNextTrade
            val millis = parseInstantMillis(arg) ?: return Unknown(raw)
            return RunToTime(millis)
        }

        private fun parseStep(raw: String, arg: String?): ReplayCommand {
            if (arg == null) return Unknown(raw)
            arg.toIntOrNull()?.let { return StepBars(it) }
            val m = Regex("^(\\d+)([smhd])$").matchEntire(arg) ?: return Unknown(raw)
            val n = m.groupValues[1].toLong()
            val unitMs =
                when (m.groupValues[2]) {
                    "s" -> 1_000L
                    "m" -> 60_000L
                    "h" -> 3_600_000L
                    "d" -> 86_400_000L
                    else -> return Unknown(raw)
                }
            return StepDuration(n * unitMs)
        }

        private fun parseInstantMillis(s: String): Long? =
            try {
                if (s.contains('T')) {
                    Instant.parse(if (s.endsWith("Z")) s else "${s}Z").toEpochMilli()
                } else {
                    LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
            } catch (e: java.time.format.DateTimeParseException) {
                null
            }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.qkt.research.ReplayCommandTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/research/ReplayCommand.kt \
        src/test/kotlin/com/qkt/research/ReplayCommandTest.kt
git commit -m "feat(research): add ReplayCommand parser"
```

---

### Task 4: `ReplaySession`

Holds the cached ticks + current `ReplayEngine` + strategy file; maps commands to engine advances; `reset` rebuilds the engine, `reload` recompiles the file.

**Files:**
- Create: `src/main/kotlin/com/qkt/research/ReplaySession.kt`
- Test: `src/test/kotlin/com/qkt/research/ReplaySessionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/research/ReplaySessionTest.kt`:

```kotlin
package com.qkt.research

import com.qkt.common.Money
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class ReplaySessionTest {
    private fun ticks() = (1..10).map { Tick("BACKTEST:BTCUSDT", Money.of((100 + it).toString()), it * 60_000L) }

    private fun writeStrategy(dir: Path, threshold: String): Path {
        val p = dir.resolve("s.qkt")
        Files.writeString(
            p,
            """
            STRATEGY sample VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > $threshold
                THEN BUY btc SIZING 1
            """.trimIndent(),
        )
        return p
    }

    private fun session(path: Path) =
        ReplaySession(
            ticks = ticks(),
            strategyPath = path,
            startingBalance = BigDecimal("10000"),
            instruments = NoopInstrumentRegistry,
        )

    @Test
    fun `run advances to end and reset returns to the start`(@TempDir dir: Path) {
        val s = session(writeStrategy(dir, "100"))
        val ran = s.dispatch(ReplayCommand.Run)
        assertThat(ran.footer.exhausted).isTrue()

        val reset = s.dispatch(ReplayCommand.Reset)
        assertThat(reset.footer.exhausted).isFalse()
        assertThat(reset.footer.barsClosed).isZero()
        assertThat(reset.footer.tradeCount).isZero()
    }

    @Test
    fun `reload picks up the edited strategy`(@TempDir dir: Path) {
        val path = writeStrategy(dir, "100") // close (>100) always true -> trades
        val s = session(path)
        val before = s.dispatch(ReplayCommand.Run)
        assertThat(before.footer.tradeCount).isGreaterThan(0)

        Files.writeString(
            path,
            """
            STRATEGY sample VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > 100000
                THEN BUY btc SIZING 1
            """.trimIndent(),
        )
        val reloaded = s.dispatch(ReplayCommand.Reload)
        assertThat(reloaded.reloadErrors).isEmpty()
        val after = s.dispatch(ReplayCommand.Run)
        assertThat(after.footer.tradeCount).isZero()
    }

    @Test
    fun `reload surfaces parse errors and keeps the old strategy`(@TempDir dir: Path) {
        val path = writeStrategy(dir, "100")
        val s = session(path)
        Files.writeString(path, "STRATEGY broken VERSION") // truncated -> parse error
        val reloaded = s.dispatch(ReplayCommand.Reload)
        assertThat(reloaded.reloadErrors).isNotEmpty()

        // old strategy still works
        val after = s.dispatch(ReplayCommand.Run)
        assertThat(after.footer.tradeCount).isGreaterThan(0)
    }

    @Test
    fun `run-to a past time resets and runs forward`(@TempDir dir: Path) {
        val s = session(writeStrategy(dir, "100"))
        s.dispatch(ReplayCommand.Run) // at end
        val back = s.dispatch(ReplayCommand.RunToTime(3 * 60_000L))
        assertThat(back.notice).contains("reset")
        assertThat(back.footer.timestamp).isEqualTo(3 * 60_000L)
        assertThat(back.footer.exhausted).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.research.ReplaySessionTest"`
Expected: FAIL — `ReplaySession` unresolved.

- [ ] **Step 3: Implement `ReplaySession`**

Create `src/main/kotlin/com/qkt/research/ReplaySession.kt`:

```kotlin
package com.qkt.research

import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseError
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant

/** Current replay position + running totals, rendered as the REPL footer. */
data class Footer(
    val timestamp: Long,
    val barsClosed: Long,
    val tradeCount: Int,
    val equity: BigDecimal,
    val openPositions: Map<String, Position>,
    val exhausted: Boolean,
)

/** What one dispatched command produced: new tape lines, the footer, and any notice/errors. */
data class StepResult(
    val tape: List<TapeEvent>,
    val footer: Footer,
    val notice: String? = null,
    val reloadErrors: List<ParseError> = emptyList(),
    val quit: Boolean = false,
)

/**
 * An interactive replay over a fixed historical window. Holds the ticks in memory once;
 * [ReplayCommand.Reset] rebuilds the engine from those cached ticks and
 * [ReplayCommand.Reload] re-reads + recompiles the strategy file before resetting.
 *
 * e.g. `dispatch(StepBars(3))` advances three primary-timeframe bars and returns the
 * fills/signals seen during those bars plus the new equity/position footer.
 */
class ReplaySession(
    private val ticks: List<Tick>,
    private val strategyPath: Path,
    private val startingBalance: BigDecimal,
    private val instruments: InstrumentRegistry,
) {
    private var ast: StrategyAst = parseOrThrow()
    private var engine: ReplayEngine = buildEngine()

    private val candleWindow: TimeWindow?
        get() = ast.streams.firstOrNull()?.timeframe?.let { TimeWindow.parse(it) }

    private fun parseOrThrow(): StrategyAst =
        when (val r = Dsl.parseFile(strategyPath)) {
            is ParseResult.Success -> r.value
            is ParseResult.Failure ->
                error("cannot compile $strategyPath: ${r.errors.joinToString { "${it.line}:${it.col} ${it.message}" }}")
        }

    private fun buildEngine(): ReplayEngine {
        val strategy = AstCompiler().compile(ast)
        val from = ticks.firstOrNull()?.timestamp ?: 0L
        val window = candleWindow
        return ReplayEngine(
            strategies = listOf(ast.name to strategy),
            feed = HistoricalTickFeed(ticks),
            candleWindow = window,
            initialTimestamp = from,
            symbols = ast.streams.map { it.symbol }.distinct(),
            cadence = if (window != null) SampleCadence.CANDLE_CLOSE else SampleCadence.TICK,
            startingBalance = startingBalance,
            instruments = instruments,
        )
    }

    private fun reset() {
        engine = buildEngine()
    }

    /** Execute one command and return its result. Never throws on user input. */
    fun dispatch(cmd: ReplayCommand): StepResult =
        when (cmd) {
            is ReplayCommand.Run -> {
                engine.advanceToEnd()
                advanced()
            }
            is ReplayCommand.StepBars -> {
                if (candleWindow != null) {
                    val target = engine.barsClosed + cmd.n
                    engine.advanceUntil { engine.barsClosed >= target }
                } else {
                    val target = engine.ticksIngested + cmd.n
                    engine.advanceUntil { engine.ticksIngested >= target }
                }
                advanced()
            }
            is ReplayCommand.StepDuration -> {
                val target = engine.currentTimestamp + cmd.millis
                engine.advanceUntil { engine.currentTimestamp >= target }
                advanced()
            }
            is ReplayCommand.RunToTime -> {
                if (cmd.epochMillis <= engine.currentTimestamp) {
                    reset()
                    engine.advanceUntil { engine.currentTimestamp >= cmd.epochMillis }
                    advanced(notice = "reset and ran forward to ${Instant.ofEpochMilli(cmd.epochMillis)}")
                } else {
                    engine.advanceUntil { engine.currentTimestamp >= cmd.epochMillis }
                    advanced()
                }
            }
            is ReplayCommand.RunToNextTrade -> {
                val target = engine.tradeCount + 1
                engine.advanceUntil { engine.tradeCount >= target }
                advanced()
            }
            is ReplayCommand.Reset -> {
                reset()
                advanced(notice = "replay reset to start")
            }
            is ReplayCommand.Reload -> reload()
            is ReplayCommand.Show -> StepResult(tape = emptyList(), footer = footer())
            is ReplayCommand.Quit -> StepResult(tape = emptyList(), footer = footer(), quit = true)
            is ReplayCommand.Unknown ->
                StepResult(
                    tape = emptyList(),
                    footer = footer(),
                    notice =
                        "unknown command: '${cmd.input}' (try: run, step N, step 1d, " +
                            "run-to <time>, run-to next-trade, reset, reload, show, quit)",
                )
        }

    private fun reload(): StepResult =
        when (val r = Dsl.parseFile(strategyPath)) {
            is ParseResult.Success -> {
                ast = r.value
                reset()
                advanced(notice = "recompiled ${strategyPath.fileName}, replay reset to start")
            }
            is ParseResult.Failure ->
                StepResult(tape = emptyList(), footer = footer(), reloadErrors = r.errors)
        }

    private fun advanced(notice: String? = null) =
        StepResult(tape = engine.drainTape(), footer = footer(), notice = notice)

    private fun footer() =
        Footer(
            timestamp = engine.currentTimestamp,
            barsClosed = engine.barsClosed,
            tradeCount = engine.tradeCount,
            equity = engine.equity(),
            openPositions = engine.openPositions(),
            exhausted = engine.exhausted,
        )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.qkt.research.ReplaySessionTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/research/ReplaySession.kt \
        src/test/kotlin/com/qkt/research/ReplaySessionTest.kt
git commit -m "feat(research): add ReplaySession"
```

---

### Task 5: `TapeRenderer`

Format tape events + footer + reload diagnostics into the lines the REPL prints. Pure string formatting.

**Files:**
- Create: `src/main/kotlin/com/qkt/research/TapeRenderer.kt`
- Test: `src/test/kotlin/com/qkt/research/TapeRendererTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/research/TapeRendererTest.kt`:

```kotlin
package com.qkt.research

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TapeRendererTest {
    private val footer =
        Footer(
            timestamp = 60_000L,
            barsClosed = 1,
            tradeCount = 1,
            equity = BigDecimal("10015.00"),
            openPositions = emptyMap(),
            exhausted = false,
        )

    @Test
    fun `renders a fill line and a footer`() {
        val fill =
            TapeEvent.Filled(
                timestamp = 60_000L,
                trade = Trade("o1", "X", Money.of("100"), Money.of("1"), Side.BUY, 60_000L),
                realized = Money.of("0"),
                strategyId = "s1",
            )
        val out = TapeRenderer.render(StepResult(tape = listOf(fill), footer = footer))
        assertThat(out).contains("FILL")
        assertThat(out).contains("BUY")
        assertThat(out).contains("equity 10015.00")
    }

    @Test
    fun `renders reload errors`() {
        val r =
            StepResult(
                tape = emptyList(),
                footer = footer,
                reloadErrors = listOf(com.qkt.dsl.parse.ParseError(2, 5, "unexpected token")),
            )
        val out = TapeRenderer.render(r)
        assertThat(out).contains("2:5")
        assertThat(out).contains("unexpected token")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.research.TapeRendererTest"`
Expected: FAIL — `TapeRenderer` unresolved.

- [ ] **Step 3: Implement `TapeRenderer`**

Create `src/main/kotlin/com/qkt/research/TapeRenderer.kt`:

```kotlin
package com.qkt.research

import java.time.Instant

/**
 * Formats a [StepResult] into the text the REPL prints: one line per tape event,
 * any notice or reload diagnostics, then a one-line footer. Pure — returns a string,
 * does no IO.
 */
object TapeRenderer {
    /** Render the full block for one dispatched command (may be empty-tape + footer only). */
    fun render(result: StepResult): String {
        val lines = mutableListOf<String>()
        for (e in result.tape) lines.add(renderEvent(e))
        result.notice?.let { lines.add(it) }
        for (err in result.reloadErrors) lines.add("  ${err.line}:${err.col} — ${err.message}")
        lines.add(renderFooter(result.footer))
        return lines.joinToString("\n")
    }

    private fun renderEvent(e: TapeEvent): String =
        when (e) {
            is TapeEvent.SignalEmitted -> "  ${ts(e.timestamp)}  SIGNAL ${e.signal::class.simpleName}"
            is TapeEvent.Filled ->
                "  ${ts(e.timestamp)}  FILL ${e.trade.side} ${e.trade.quantity} @${e.trade.price}  pnl ${e.realized}"
            is TapeEvent.Rejected -> "  ${ts(e.timestamp)}  REJECT ${e.symbol} (${e.reason})"
        }

    private fun renderFooter(f: Footer): String {
        val pos =
            if (f.openPositions.isEmpty()) {
                "flat"
            } else {
                f.openPositions.entries.joinToString(" ") { (sym, p) -> "$sym ${p.quantity}" }
            }
        val end = if (f.exhausted) " [end]" else ""
        return "  ${ts(f.timestamp)}  bars ${f.barsClosed}  trades ${f.tradeCount}  " +
            "pos $pos  equity ${f.equity}$end"
    }

    private fun ts(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.qkt.research.TapeRendererTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/research/TapeRenderer.kt \
        src/test/kotlin/com/qkt/research/TapeRendererTest.kt
git commit -m "feat(research): add TapeRenderer"
```

---

### Task 6: `ReplayRepl` loop + `ResearchCommand` + CLI wiring

The read-eval-print loop (over injectable streams, so it's testable) and the CLI entry that loads data and starts it.

**Files:**
- Create: `src/main/kotlin/com/qkt/research/ReplayRepl.kt`
- Create: `src/main/kotlin/com/qkt/cli/ResearchCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt:10-25` (add `research` case) and `Main.kt:56-60` (help text)
- Test: `src/test/kotlin/com/qkt/research/ReplayReplTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/research/ReplayReplTest.kt`:

```kotlin
package com.qkt.research

import com.qkt.common.Money
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.StringReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class ReplayReplTest {
    @Test
    fun `drives a scripted session and renders tape + footer`(@TempDir dir: Path) {
        val path = dir.resolve("s.qkt")
        Files.writeString(
            path,
            """
            STRATEGY sample VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > 100
                THEN BUY btc SIZING 1
            """.trimIndent(),
        )
        val ticks = (1..6).map { Tick("BACKTEST:BTCUSDT", Money.of((100 + it).toString()), it * 60_000L) }
        val session =
            ReplaySession(
                ticks = ticks,
                strategyPath = path,
                startingBalance = BigDecimal("10000"),
                instruments = NoopInstrumentRegistry,
            )
        val input = BufferedReader(StringReader("run\nshow\nquit\n"))
        val out = StringBuilder()

        ReplayRepl(session).run(input, out)

        val text = out.toString()
        assertThat(text).contains("> ") // prompt was shown
        assertThat(text).contains("[end]") // run reached end of feed
        assertThat(text).contains("bars") // footer rendered
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.research.ReplayReplTest"`
Expected: FAIL — `ReplayRepl` unresolved.

- [ ] **Step 3: Implement `ReplayRepl`**

Create `src/main/kotlin/com/qkt/research/ReplayRepl.kt`:

```kotlin
package com.qkt.research

import java.io.BufferedReader

/**
 * The read-eval-print loop: prints a prompt, reads a line, dispatches it to the
 * [ReplaySession], renders the result, and repeats until `quit` or end-of-input.
 * Streams are injected so the loop is testable without real stdin/stdout.
 */
class ReplayRepl(
    private val session: ReplaySession,
) {
    /** Run until the user quits or [input] is exhausted. Writes prompts + rendered output to [out]. */
    fun run(input: BufferedReader, out: Appendable) {
        while (true) {
            out.append("> ")
            val line = input.readLine() ?: break
            val cmd = ReplayCommand.parse(line)
            val result = session.dispatch(cmd)
            out.append(TapeRenderer.render(result)).append("\n")
            if (result.quit) break
        }
    }
}
```

- [ ] **Step 4: Run the loop test to verify it passes**

Run: `./gradlew test --tests "com.qkt.research.ReplayReplTest"`
Expected: PASS.

- [ ] **Step 5: Implement `ResearchCommand`**

Create `src/main/kotlin/com/qkt/cli/ResearchCommand.kt`:

```kotlin
package com.qkt.cli

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.research.ReplayRepl
import com.qkt.research.ReplaySession
import com.qkt.common.FixedClock
import com.qkt.common.TimeRange
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** `qkt research <strategy.qkt> --from <d> --to <d>` — interactive playback REPL (#81). */
class ResearchCommand(
    private val args: Args,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        val ast =
            when (val parsed = Dsl.parseFile(path)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure -> {
                    for (e in parsed.errors) System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                    return ExitCodes.USER_ERROR
                }
            }

        val from = parseInstant(args.requireOption("from"))
        val to = parseInstant(args.requireOption("to"))
        val dataRoot = args.option("data-root") ?: "./data"
        val startingBalance = args.option("starting-balance")?.let(::BigDecimal) ?: BigDecimal("10000")
        val symbols = ast.streams.map { it.symbol }.distinct()

        val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = null)
        val source = LocalMarketSource(store, FixedClock(time = to.toEpochMilli()), barStore = LocalBarStore())
        val range = TimeRange(from, to)
        val perSymbolFeeds = symbols.map { SequenceTickFeed(source.ticks(it, range)) }
        val feed = if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
        val ticks =
            buildList {
                feed.use { f ->
                    while (true) {
                        val t = f.next() ?: break
                        add(t)
                    }
                }
            }
        if (ticks.isEmpty()) {
            System.err.println("qkt: error: no ticks for $symbols in [$from, $to] under $dataRoot")
            return ExitCodes.USER_ERROR
        }

        val session =
            ReplaySession(
                ticks = ticks,
                strategyPath = path,
                startingBalance = startingBalance,
                instruments = NoopInstrumentRegistry,
            )
        println("loaded ${ticks.size} ticks for $symbols  [$from .. $to]")
        ReplayRepl(session).run(BufferedReader(InputStreamReader(System.`in`)), System.out)
        return ExitCodes.SUCCESS
    }

    private fun parseInstant(s: String): Instant =
        if (s.contains('T')) {
            Instant.parse(if (s.endsWith("Z")) s else "${s}Z")
        } else {
            LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
}
```

`Appendable` accepts `System.out` (a `PrintStream`) — no adapter needed.

- [ ] **Step 6: Wire `research` into `Main.kt`**

In `src/main/kotlin/com/qkt/cli/Main.kt`, add a case to the `when (args.subcommand)` block (after the `backtest` line):

```kotlin
            "research" -> ResearchCommand(args).run()
```

And in `printHelp()`'s `STRATEGY AUTHORING` section, add a line after the `backtest` line:

```
            research <file> ...     interactive playback REPL over historical data
```

- [ ] **Step 7: Run the research suite + Main to verify nothing broke**

Run: `./gradlew test --tests "com.qkt.research.*" --tests "com.qkt.cli.*"`
Expected: PASS — all research tests plus the existing CLI tests green.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/research/ReplayRepl.kt \
        src/main/kotlin/com/qkt/cli/ResearchCommand.kt \
        src/main/kotlin/com/qkt/cli/Main.kt \
        src/test/kotlin/com/qkt/research/ReplayReplTest.kt
git commit -m "feat(cli): add research REPL command"
```

---

### Task 7: Docs + skill scope

User-facing usage page, and register the new `research` commit scope per the living-document protocol.

**Files:**
- Create: `docs/guide/research-repl.md`
- Modify: `mkdocs.yml` (add the page to nav — find the existing `nav:` block and the `Guide`/authoring section)
- Modify: `.claude/skills/qkt/SKILL.md` (add `research` to the allowed scopes list in §3)

- [ ] **Step 1: Write the usage guide**

Create `docs/guide/research-repl.md`:

```markdown
# Research REPL (`qkt research`)

Interactive playback of a strategy over a historical window. Load once, then step / run /
seek through a tick replay and watch trades, positions, and equity update — without
re-running a full backtest on every edit.

## Start a session

    qkt research strategy.qkt --from 2024-01-01 --to 2024-02-01 --data-root ./data

Optional: `--starting-balance 10000`. The data window is read from the local store under
`--data-root` (the same store `qkt fetch` / `qkt backtest` use).

## Commands

| Command | Effect |
|---|---|
| `run` | Advance to the end of the window. |
| `step N` | Advance N bars on the strategy's primary timeframe. |
| `step 1d` / `step 30m` / `step 2h` | Advance a wall-clock duration. |
| `run-to 2024-01-15` | Advance to a timestamp (a past time resets and runs forward). |
| `run-to next-trade` | Advance to the next fill. |
| `reset` | Restart from the first tick, same strategy. |
| `reload` | Re-read + recompile the file (after you edit it), then reset. |
| `show` | Print the current footer without advancing. |
| `quit` | Exit. |

## The tweak loop

Edit the `.qkt` in your editor, then type `reload`. A parse error keeps the previous
strategy loaded and prints the diagnostics, so a bad edit never drops your session.

## Determinism

A full `run` is bit-identical to `qkt backtest` over the same window — the REPL and the
batch backtest share one replay engine; stepping only changes when the replay pauses.

## Not yet supported

Per-bar rule introspection, in-session parameter overrides (`set`), portfolio files, and
backward seek are tracked as follow-ups under the #81 epic.
```

- [ ] **Step 2: Add the page to `mkdocs.yml` nav**

Open `mkdocs.yml`, find the `nav:` section containing the strategy-authoring guides, and add under the appropriate group:

```yaml
      - Research REPL: guide/research-repl.md
```

(Match the exact indentation of sibling entries in that file.)

- [ ] **Step 3: Register the `research` scope in the skill**

In `.claude/skills/qkt/SKILL.md`, §3 "Allowed scopes", change the source-scope line to include `research`:

Find:

```
`common`, `marketdata`, `execution`, `strategy`, `broker`, `engine`, `risk`, `backtesting`, `dsl`, `app`.
```

Replace with:

```
`common`, `marketdata`, `execution`, `strategy`, `broker`, `engine`, `risk`, `backtesting`, `dsl`, `app`, `research`.
```

- [ ] **Step 4: Verify docs build is not broken (link check)**

Run: `grep -n "research-repl" mkdocs.yml`
Expected: one line — the nav entry you added. (Full `mkdocs build --strict` runs in CI.)

- [ ] **Step 5: Commit**

```bash
git add docs/guide/research-repl.md mkdocs.yml .claude/skills/qkt/SKILL.md
git commit -m "docs: research REPL usage guide and research scope"
```

---

## Post-implementation (not tasks — handle on PR/merge)

- Open the PR into `dev` per the qkt PR template; reference issue #81 and this plan + spec.
- On merge: flip the backlog line for #70/#81 area as appropriate, and (with elitekaycy's say-so) convert #81 into an epic and open child issues for Watch mode, Expression REPL, and DSL parameters.

---

## Self-Review

**Spec coverage (against `2026-06-02-issue81-research-repl-design.md`):**
- §2 goals: `qkt research` entry (Task 6), load-once + step/run/seek (Tasks 1,4,6), tape + footer (Tasks 2,4,5), edit+`reload` (Task 4), bit-identical to backtest (Task 1 delegation + backtest suite guardrail). Covered.
- §4 shared core / `Backtest.run()` delegates: Task 1. Covered.
- §5 components: ReplayEngine (T1), ReplaySession (T4), ReplayCommand (T3), TapeRenderer (T5), ResearchCommand (T6). `ReplayRepl` added (T6) as the loop the spec folded into ResearchCommand — same responsibility, split for testability. Covered.
- §6 command surface: all verbs parsed (T3) + dispatched (T4). Covered.
- §8 determinism: Task 1 stepping-equals-end test + backtest suite. Covered.
- §9 error handling: file-not-found + initial parse error (T6), reload keeps old strategy (T4 test), unknown command (T3/T4), run-to past (T4). Covered.
- §10 testing: every task is TDD; backtest suite re-run in T1/T2. Covered.

**Placeholder scan:** No TBD/TODO. Every code step shows complete code; every run step shows an exact command + expected result. The one judgment step (removing dead imports in T1·S5) is bounded by the compiler error listing.

**Type consistency:** `ReplayEngine` members (`advanceUntil`, `advanceToEnd`, `runToEnd`, `snapshot`, `drainTape`, `barsClosed`, `ticksIngested`, `currentTimestamp`, `tradeCount`, `exhausted`, `equity()`, `openPositions()`) are used consistently across T2/T4. `StepResult`/`Footer` fields match between T4 (producer) and T5 (renderer). `TapeEvent` variants (`SignalEmitted`/`Filled`/`Rejected`) match between T2 (producer) and T5 (renderer). `ReplayCommand` variants match between T3 (parser) and T4 (dispatch). Consistent.
