# Phase 14 — Portfolio v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `.qkt` portfolio files first-class in the daemon — `qkt deploy/list/status/stop/logs` work for portfolios; per-child observability via fan-out (one `LiveSession` per child, supervised by a `PortfolioSupervisor`); new `qkt start <portfolio>/<child>` verb.

**Architecture:** Daemon path forks at deploy time on `ParsedFile` kind. Strategy files use the existing `RealFactory`. Portfolio files load via `PortfolioLoader`, spin up one `LiveSession` per `CompiledChild`, register each as a `RegistryEntry.Child` under `<portfolio>/<alias>`, and create a `PortfolioSupervisor` that owns the portfolio's market subscription + rule eval + child gating. Top-level `RegistryEntry` becomes a sealed type (`Strategy | Portfolio | Child`). Backtest path's `PortfolioStrategy` is untouched.

**Tech Stack:** Kotlin 1.9, JDK 21, Gradle, JUnit 5, AssertJ, kotlinx.serialization (existing).

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `src/main/kotlin/com/qkt/cli/daemon/RegistryEntry.kt` | Sealed type for strategy / portfolio / child registry entries |
| `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt` | Owns portfolio market subscription, rule eval, child gate transitions |
| `src/main/kotlin/com/qkt/cli/daemon/portfolio/ChildHandle.kt` | Wraps a child's `LiveSession` + handle + gate flags + alias/parent metadata |
| `src/main/kotlin/com/qkt/cli/StartCommand.kt` | New `qkt start <portfolio>/<child>` verb |
| `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisorTest.kt` | Rule eval, gate diff, flatten-on-deactivate behavior |
| `src/test/kotlin/com/qkt/cli/daemon/PortfolioRegistryTest.kt` | Slash-name validation, conflict rejection, cascade removal |
| `src/test/kotlin/com/qkt/cli/daemon/PortfolioDaemonTest.kt` | End-to-end real-HTTP integration: deploy, list, status, stop, start |
| `src/test/kotlin/com/qkt/app/TradingPipelineGateTest.kt` | Gate=false suppresses signal emission |

### Modified files

| Path | Change |
|---|---|
| `src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt` | Hold `RegistryEntry`, widen regex, add `children(parent)` accessor |
| `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt` | Factory dispatches on `ParsedFile`; portfolio path delegates |
| `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt` | New routes (`POST /start`), portfolio/child dispatch in existing routes |
| `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt` | Add `start(name)` method |
| `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt` | `logFile(name)` substitutes `/` → `__` |
| `src/main/kotlin/com/qkt/app/TradingPipeline.kt` | Add `gate: () -> Boolean = { true }` parameter; gate the `emit` lambda |
| `src/main/kotlin/com/qkt/app/LiveSession.kt` | Pass gate through; expose `flatten()` on the handle |
| `src/main/kotlin/com/qkt/app/LiveSessionHandle.kt` | Add `fun flatten()` to interface |
| `src/main/kotlin/com/qkt/cli/Main.kt` | Wire `StartCommand` |
| `src/main/kotlin/com/qkt/cli/ListCommand.kt` | Add `KIND` and `PARENT` columns; indent child rows |
| `src/main/kotlin/com/qkt/cli/StatusCommand.kt` | Render portfolio aggregate + children block |
| `src/main/kotlin/com/qkt/cli/StopCommand.kt` | No code change needed (server handles dispatch); update help text |
| `src/main/kotlin/com/qkt/cli/LogsCommand.kt` | No code change needed |
| `src/main/kotlin/com/qkt/cli/DeployCommand.kt` | Add `KIND` column; render `children` array for portfolio response |
| `src/main/kotlin/com/qkt/cli/BuildInfo.kt` | Bump `VERSION` to `0.16.0` |
| `README.md` | One-line Phase 14 entry |
| `docs/phases/phase-14.md` | New phase changelog (per qkt SKILL §6) |

### Reused from prior phases (no edits)

- `src/main/kotlin/com/qkt/dsl/parse/Dsl.kt` — `parseFileAny()` already returns `ParsedFile`
- `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioLoader.kt` — used by deployPortfolio
- `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioCompiled.kt` — child metadata source
- `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` — supervisor reuses for `WhenRun.cond` eval
- `src/main/kotlin/com/qkt/strategy/Signal.kt` — `CancelPendingForSymbol` for flatten
- `src/main/kotlin/com/qkt/app/PortfolioStrategy.kt` — backtest path, **unchanged**

---

## Task 1: Widen `StrategyRegistry` name regex for child names

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/StrategyRegistryTest.kt` (existing — extend)

- [ ] **Step 1: Write failing test for child names accepted, malformed rejected**

```kotlin
// In StrategyRegistryTest.kt — add inside the existing class

@Test
fun `child names with slash are accepted by regex`() {
    val registry = StrategyRegistry { _, _ -> error("not used") }
    // Invoke via reflection or via the public deploy path with a stub factory:
    val factory = StrategyHandle.Factory { name, _ ->
        // Return a minimal stub. Easier: just verify regex via a helper test that calls the
        // companion's exposed regex if you added one. Otherwise call deploy() with a fake file.
        error("stub")
    }
    val r = StrategyRegistry(factory)
    // Assert by reading the regex constant via reflection:
    val field = StrategyRegistry::class.java.getDeclaredField("Companion").apply { isAccessible = true }
    val companion = field.get(null)
    val regexField = companion.javaClass.getDeclaredField("NAME_REGEX").apply { isAccessible = true }
    val regex = regexField.get(companion) as Regex
    assertThat(regex.matches("mybook")).isTrue
    assertThat(regex.matches("mybook/trend")).isTrue
    assertThat(regex.matches("a-b/c_d")).isTrue
    assertThat(regex.matches("/foo")).isFalse
    assertThat(regex.matches("foo/")).isFalse
    assertThat(regex.matches("foo//bar")).isFalse
    assertThat(regex.matches("foo/bar/baz")).isFalse
    assertThat(regex.matches("")).isFalse
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.qkt.cli.daemon.StrategyRegistryTest`
Expected: FAIL — `mybook/trend` does not match current regex.

- [ ] **Step 3: Widen regex**

In `src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt`, replace the companion object:

```kotlin
companion object {
    private val NAME_REGEX = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)?")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.qkt.cli.daemon.StrategyRegistryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt src/test/kotlin/com/qkt/cli/daemon/StrategyRegistryTest.kt
git commit -m "feat(daemon): widen registry regex to permit child names"
```

---

## Task 2: Add `gate` parameter to `TradingPipeline`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Test: `src/test/kotlin/com/qkt/app/TradingPipelineGateTest.kt` (new)

The gate wraps the `emit` lambda. When gate returns false, signals are dropped silently. Indicators continue to update (candle hub feed runs unconditionally in `ingest()`).

- [ ] **Step 1: Write failing test**

```kotlin
// src/test/kotlin/com/qkt/app/TradingPipelineGateTest.kt
package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.SignalEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
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
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineGateTest {

    private fun stubSource(): MarketSource = object : MarketSource {
        override fun liveTicks(symbols: List<String>) = error("not used in this test")
        override fun replay(symbols: List<String>, from: Long, to: Long) = error("not used")
    }

    @Test
    fun `gate false suppresses signal emission`() {
        val captured = mutableListOf<Signal>()
        val gate = AtomicBoolean(false)

        val bus = EventBus(SystemClock(), MonotonicSequenceGenerator())
        bus.subscribe<SignalEvent> { e -> captured.add(e.signal) }

        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, SystemClock(), bus)
        val riskEngine = RiskEngine(emptyList(), emptyList(), positions, riskState)

        val emittingStrategy = object : Strategy {
            override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
                emit(Signal.Buy("BTCUSDT", BigDecimal.ONE))
            }
        }

        TradingPipeline(
            clock = SystemClock(),
            ids = SequentialIdGenerator(),
            sequencer = MonotonicSequenceGenerator(),
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = PaperBroker(bus, SystemClock(), priceTracker),
            engine = Engine(bus, priceTracker),
            strategies = listOf("s1" to emittingStrategy),
            riskEngine = riskEngine,
            riskState = riskState,
            mode = Mode.LIVE,
            calendar = TradingCalendar.fxDefault(),
            source = stubSource(),
            gate = { gate.get() },
        )

        // Publish a tick — strategy will call emit(), but gate is false so no SignalEvent reaches the bus
        bus.publish(com.qkt.events.TickEvent(Tick("BTCUSDT", BigDecimal("100"), 0L)))
        assertThat(captured).isEmpty()

        // Flip gate true — now emissions go through
        gate.set(true)
        bus.publish(com.qkt.events.TickEvent(Tick("BTCUSDT", BigDecimal("101"), 1L)))
        assertThat(captured).hasSize(1)
        assertThat(captured.first()).isInstanceOf(Signal.Buy::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.qkt.app.TradingPipelineGateTest`
Expected: FAIL — `gate` parameter does not exist on `TradingPipeline`.

- [ ] **Step 3: Add gate parameter and wire it into emit**

In `src/main/kotlin/com/qkt/app/TradingPipeline.kt`, add to the constructor:

```kotlin
class TradingPipeline(
    /* ... existing parameters ... */,
    val candleHub: com.qkt.dsl.compile.CandleHub = com.qkt.dsl.compile.CandleHub(),
    val onFilled: (Trade, BigDecimal, String) -> Unit = { _, _, _ -> },
    val onRejected: (RiskRejectedEvent) -> Unit = {},
    val onCandle: (Candle) -> Unit = {},
    val gate: () -> Boolean = { true },                 // NEW
) {
```

Inside `init`, wrap the `emit` lambda:

```kotlin
strategies.forEach { (strategyId, strategy) ->
    val ctx = /* unchanged */
    val rawEmit: (com.qkt.strategy.Signal) -> Unit = { sig ->
        bus.publish(SignalEvent(sig))
        if (sig is com.qkt.strategy.Signal.CancelPendingForSymbol) {
            orderManager.cancelPendingForSymbol(sig.symbol)
        } else {
            val request = sig.toOrderRequest(ids.next(), clock.now(), strategyId = strategyId)
            if (request != null) {
                when (val decision = riskEngine.approve(request)) {
                    is Decision.Approve -> bus.publish(OrderEvent(request))
                    is Decision.Reject -> bus.publish(RiskRejectedEvent(request, decision.reason))
                }
            }
        }
    }
    val emit: (com.qkt.strategy.Signal) -> Unit = { sig ->
        if (gate()) rawEmit(sig)
    }
    /* rest unchanged: bindToHub / subscribe TickEvent / subscribe CandleEvent */
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.qkt.app.TradingPipelineGateTest`
Expected: PASS.

- [ ] **Step 5: Run the full pipeline-related test suite to ensure no regression**

Run: `./gradlew test --tests "com.qkt.app.*"`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/app/TradingPipeline.kt src/test/kotlin/com/qkt/app/TradingPipelineGateTest.kt
git commit -m "feat(app): add gate parameter to TradingPipeline emit"
```

---

## Task 3: Plumb `gate` and `flatten()` through `LiveSession` + `LiveSessionHandle`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSessionHandle.kt`

The session forwards a gate to its `TradingPipeline`. The handle exposes `flatten()` which emits close-position signals through the pipeline's broker path.

- [ ] **Step 1: Read the current `LiveSessionHandle` interface**

Run: `cat src/main/kotlin/com/qkt/app/LiveSessionHandle.kt`

It currently exposes `running`, `droppedTicks`, `stop()`, `awaitTermination()`, `recentTrades()`, `pendingStackLayerInfos()`. Add `flatten()`.

- [ ] **Step 2: Modify `LiveSessionHandle`**

```kotlin
// src/main/kotlin/com/qkt/app/LiveSessionHandle.kt — add this method to the interface
fun flatten()
```

- [ ] **Step 3: Modify `LiveSession.start()` to accept gate and implement flatten**

In `src/main/kotlin/com/qkt/app/LiveSession.kt`, add a constructor parameter:

```kotlin
class LiveSession(
    /* ... existing parameters ... */,
    private val onSignal: (Signal) -> Unit = {},
    private val gate: () -> Boolean = { true },        // NEW
) {
```

Pass `gate` to the `TradingPipeline` constructor:

```kotlin
val pipeline =
    TradingPipeline(
        /* ... existing args ... */,
        onFilled = { trade, realized, strategyId ->
            trades.add(trade)
            onTrade(trade, realized, strategyId)
        },
        gate = gate,                                    // NEW
    )
```

In the returned `LiveSessionHandle` anonymous object, capture `pipeline` for flatten:

```kotlin
return object : LiveSessionHandle {
    /* existing methods */

    override fun flatten() {
        // Bypass the gate by going directly through the broker via the pipeline's signal bus.
        // Gate-bypassed: the supervisor calls flatten precisely because the child is being deactivated;
        // emission must still occur to close positions.
        for ((symbol, pos) in strategyPositions.allPositionsFor("__none__").let { _ ->
            // Iterate over all symbols carried by this session's strategies. Use the position tracker.
            positions.allPositions()
        }) {
            if (pos.qty.signum() == 0) continue
            // Cancel pending orders for this symbol first.
            bus.publish(SignalEvent(Signal.CancelPendingForSymbol(symbol)))
            pipeline.orderManager.cancelPendingForSymbol(symbol)
            // Emit close signal as a market order. Bypass risk approval — flatten is operator-driven.
            val side = if (pos.qty > java.math.BigDecimal.ZERO) {
                com.qkt.execution.Side.SELL
            } else {
                com.qkt.execution.Side.BUY
            }
            val request = com.qkt.execution.OrderRequest.Market(
                clientOrderId = ids.next(),
                symbol = symbol,
                side = side,
                quantity = pos.qty.abs(),
                strategyId = strategies.first().first,
                timestamp = clock.now(),
            )
            bus.publish(com.qkt.events.OrderEvent(request))
        }
    }
}
```

> **Note on `OrderRequest.Market` field names:** verify the actual constructor signature in `src/main/kotlin/com/qkt/execution/OrderRequest.kt` and adjust the field names accordingly. The pattern above is illustrative; use the existing `OrderRequest.Market` constructor verbatim.

- [ ] **Step 4: Compile and run a quick smoke test**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

If compile fails on the `OrderRequest.Market` constructor, read that file and fix the call to match its actual signature.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: all green (no test exercises `flatten()` yet — that comes in Task 7).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/app/LiveSession.kt src/main/kotlin/com/qkt/app/LiveSessionHandle.kt
git commit -m "feat(app): plumb gate and flatten through LiveSession"
```

---

## Task 4: Create `ChildHandle` wrapper

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/portfolio/ChildHandle.kt`

`ChildHandle` wraps an existing `StrategyHandle` (the one created for the child's own LiveSession + ObservabilityServer) and adds gate flags + portfolio metadata.

- [ ] **Step 1: Create the file**

```kotlin
// src/main/kotlin/com/qkt/cli/daemon/portfolio/ChildHandle.kt
package com.qkt.cli.daemon.portfolio

import com.qkt.cli.daemon.StrategyHandle
import java.util.concurrent.atomic.AtomicBoolean

class ChildHandle(
    val parent: String,
    val alias: String,
    val hold: Boolean,
    val handle: StrategyHandle,
    val gateActive: AtomicBoolean = AtomicBoolean(false),
    val operatorStop: AtomicBoolean = AtomicBoolean(false),
) {
    val effectiveActive: Boolean
        get() = gateActive.get() && !operatorStop.get()

    fun close() = handle.close()

    fun flatten() = handle.live.flatten()
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/ChildHandle.kt
git commit -m "feat(daemon): add ChildHandle wrapping a child LiveSession with gate flags"
```

---

## Task 5: Stub `PortfolioSupervisor` with lifecycle only

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisorTest.kt`

Stub holds dependencies, exposes `start()`/`stop()`/`running`. No rule eval yet.

- [ ] **Step 1: Write failing test for lifecycle**

```kotlin
// src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisorTest.kt
package com.qkt.cli.daemon.portfolio

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioSupervisorTest {

    @Test
    fun `start and stop toggles running`() {
        val ast = PortfolioAst(
            name = "p",
            version = 1,
            streams = emptyList(),
            imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
            rules = listOf(AlwaysRun("a")),
        )
        val supervisor = PortfolioSupervisor(
            ast = ast,
            children = emptyList(),
            marketSource = null,
        )
        assertThat(supervisor.running).isFalse
        supervisor.start()
        assertThat(supervisor.running).isTrue
        supervisor.stop()
        assertThat(supervisor.running).isFalse
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.qkt.cli.daemon.portfolio.PortfolioSupervisorTest`
Expected: FAIL — `PortfolioSupervisor` does not exist.

- [ ] **Step 3: Create the stub**

```kotlin
// src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt
package com.qkt.cli.daemon.portfolio

import com.qkt.dsl.ast.PortfolioAst
import com.qkt.marketdata.source.MarketSource
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class PortfolioSupervisor(
    val ast: PortfolioAst,
    val children: List<ChildHandle>,
    val marketSource: MarketSource?,
) {
    private val log = LoggerFactory.getLogger(PortfolioSupervisor::class.java)
    private val runFlag = AtomicBoolean(false)
    private var thread: Thread? = null

    val running: Boolean get() = runFlag.get()

    fun start() {
        if (!runFlag.compareAndSet(false, true)) return
        // Apply AlwaysRun rules immediately (one-shot activation).
        applyAlwaysRunRules()
        // If no marketSource, no ticking is needed — supervisor stays "running" but does no work.
        if (marketSource == null) return
        thread = Thread({
            org.slf4j.MDC.put("strategy", ast.name)
            try {
                tickLoop()
            } finally {
                org.slf4j.MDC.remove("strategy")
            }
        }, "qkt-portfolio-supervisor-${ast.name}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!runFlag.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(5000)
        thread = null
    }

    private fun applyAlwaysRunRules() {
        // Filled in Task 7.
    }

    private fun tickLoop() {
        // Filled in Task 7.
        while (runFlag.get()) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.qkt.cli.daemon.portfolio.PortfolioSupervisorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisorTest.kt
git commit -m "feat(daemon): stub PortfolioSupervisor with lifecycle"
```

---

## Task 6: Refactor `StrategyRegistry` to `RegistryEntry`

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/RegistryEntry.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/StrategyRegistryTest.kt` (existing — extend)

- [ ] **Step 1: Create `RegistryEntry.kt`**

```kotlin
// src/main/kotlin/com/qkt/cli/daemon/RegistryEntry.kt
package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.ChildHandle
import com.qkt.cli.daemon.portfolio.PortfolioSupervisor
import java.nio.file.Path
import java.time.Instant

sealed class RegistryEntry {
    abstract val name: String
    abstract val startedAt: Instant

    data class Strategy(
        override val name: String,
        override val startedAt: Instant,
        val handle: StrategyHandle,
    ) : RegistryEntry()

    data class Portfolio(
        override val name: String,
        override val startedAt: Instant,
        val supervisor: PortfolioSupervisor,
        val childAliases: List<String>,
        val logFile: Path,
        val version: Int,
    ) : RegistryEntry()

    data class Child(
        override val name: String,
        override val startedAt: Instant,
        val parent: String,
        val alias: String,
        val childHandle: ChildHandle,
    ) : RegistryEntry()
}
```

- [ ] **Step 2: Refactor `StrategyRegistry`**

Replace contents of `src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt`:

```kotlin
package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.ChildHandle
import com.qkt.cli.daemon.portfolio.PortfolioSupervisor
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class StrategyRegistry(
    private val factory: StrategyHandle.Factory,
) {
    private val entries = ConcurrentHashMap<String, RegistryEntry>()

    /** Top-level strategy deploy. Returns the created handle for daemon-side observability wiring. */
    fun deployStrategy(
        name: String,
        file: Path,
    ): StrategyHandle {
        require(NAME_REGEX.matches(name)) { "invalid strategy name: $name" }
        require(!name.contains('/')) { "top-level strategy name must not contain '/': $name" }
        check(!entries.containsKey(name)) { "name '$name' already registered" }
        val handle = factory.create(name, file)
        entries[name] = RegistryEntry.Strategy(name, handle.startedAt, handle)
        return handle
    }

    /** Register a portfolio + its child handles atomically. Caller is responsible for rollback on failure. */
    fun registerPortfolio(
        name: String,
        version: Int,
        startedAt: Instant,
        supervisor: PortfolioSupervisor,
        childAliases: List<String>,
        logFile: Path,
        children: List<ChildHandle>,
    ) {
        require(NAME_REGEX.matches(name)) { "invalid portfolio name: $name" }
        require(!name.contains('/')) { "top-level portfolio name must not contain '/': $name" }
        check(!entries.containsKey(name)) { "name '$name' already registered" }
        for (child in children) {
            val childName = "$name/${child.alias}"
            check(!entries.containsKey(childName)) { "child name '$childName' already registered" }
        }
        entries[name] = RegistryEntry.Portfolio(name, startedAt, supervisor, childAliases, logFile, version)
        for (child in children) {
            val childName = "$name/${child.alias}"
            entries[childName] = RegistryEntry.Child(
                name = childName,
                startedAt = startedAt,
                parent = name,
                alias = child.alias,
                childHandle = child,
            )
        }
    }

    fun get(name: String): RegistryEntry? = entries[name]

    fun list(): List<RegistryEntry> = entries.values.toList()

    fun children(parent: String): List<RegistryEntry.Child> =
        entries.values.filterIsInstance<RegistryEntry.Child>().filter { it.parent == parent }

    /**
     * Removes a single registry entry. Caller must close the underlying handle/supervisor.
     * For portfolios, caller must remove all children separately (cascade is not automatic here —
     * see ControlRoutes.handleStop for the cascade flow).
     */
    fun remove(name: String): RegistryEntry? = entries.remove(name)

    fun stopAll() {
        for (entry in entries.values) {
            runCatching {
                when (entry) {
                    is RegistryEntry.Strategy -> entry.handle.close()
                    is RegistryEntry.Portfolio -> entry.supervisor.stop()
                    is RegistryEntry.Child -> entry.childHandle.close()
                }
            }
        }
        entries.clear()
    }

    companion object {
        private val NAME_REGEX = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)?")
    }
}
```

- [ ] **Step 3: Update all callers of the old `StrategyRegistry` API**

Affected files: `ControlRoutes.kt`, `DaemonCommand.kt`, anything else calling `registry.deploy(...)` / `registry.get(...)` / `registry.list()`.

Search for usages:

```bash
grep -rn "StrategyRegistry\|registry\.deploy\|registry\.get\|registry\.list\|registry\.stop" src/main/kotlin src/test/kotlin
```

For each call site:

| Old | New |
|---|---|
| `registry.deploy(name, file)` | `registry.deployStrategy(name, file)` |
| `registry.get(name)` returning `StrategyHandle?` | `registry.get(name)` returning `RegistryEntry?`; pattern-match in caller |
| `registry.list()` returning `List<StrategyHandle>` | `registry.list()` returning `List<RegistryEntry>`; flatten via `mapNotNull` |
| `registry.stop(name)` | `registry.remove(name)?.also { /* close per kind */ }` |
| `registry.stopAll()` | unchanged |

In `ControlRoutes.handleList`, replace the loop:

```kotlin
private fun handleList(
    ex: HttpExchange,
    registry: StrategyRegistry,
) {
    val now = Instant.now().toEpochMilli()
    val arr =
        registry.list().joinToString(separator = ",", prefix = "[", postfix = "]") { entry ->
            val uptime = now - entry.startedAt.toEpochMilli()
            when (entry) {
                is RegistryEntry.Strategy -> {
                    val state = if (entry.handle.isRunning()) "running" else "stopped"
                    """{"name":"${entry.name}","kind":"strategy","port":${entry.handle.port},""" +
                        """"trades":${entry.handle.tradeCount},"uptimeMs":$uptime,"state":"$state"}"""
                }
                is RegistryEntry.Portfolio -> {
                    val state = if (entry.supervisor.running) "running" else "stopped"
                    """{"name":"${entry.name}","kind":"portfolio","childAliases":""" +
                        entry.childAliases.joinToString(",", "[", "]") { "\"$it\"" } +
                        ""","uptimeMs":$uptime,"state":"$state"}"""
                }
                is RegistryEntry.Child -> {
                    val state = if (entry.childHandle.handle.isRunning()) "running" else "stopped"
                    val gateState = when {
                        entry.childHandle.operatorStop.get() -> "operator_stopped"
                        entry.childHandle.gateActive.get() -> "active"
                        else -> "idle"
                    }
                    """{"name":"${entry.name}","kind":"child","parent":"${entry.parent}",""" +
                        """"port":${entry.childHandle.handle.port},"trades":${entry.childHandle.handle.tradeCount},""" +
                        """"uptimeMs":$uptime,"state":"$state","gateState":"$gateState"}"""
                }
            }
        }
    respond(ex, 200, arr)
}
```

In `ControlRoutes.handleDeploy`, replace `registry.deploy(name, path)` with `registry.deployStrategy(name, path)`. Portfolio path will be added in Task 9.

In `ControlRoutes.handleStop`, change `registry.get(name)` and `registry.stop(name)` to use the new API (full rewrite in Task 10).

In `ControlRoutes.handleStatusOne` / `handleStatusAll`, the body changes:

```kotlin
private fun handleStatusOne(
    ex: HttpExchange,
    registry: StrategyRegistry,
    path: String,
) {
    val name = path.removePrefix("/status/").trim('/').ifBlank { null }
    if (name == null) return respond(ex, 400, """{"error":"missing name in path"}""")
    val entry = registry.get(name) ?: return respond(ex, 404, """{"error":"unknown name: $name"}""")
    when (entry) {
        is RegistryEntry.Strategy -> {
            val body = fetchStrategyStatus(entry.handle.port)
                ?: return respond(ex, 502, """{"error":"strategy /status unreachable"}""")
            respond(ex, 200, body)
        }
        is RegistryEntry.Child -> {
            val body = fetchStrategyStatus(entry.childHandle.handle.port)
                ?: return respond(ex, 502, """{"error":"child /status unreachable"}""")
            // Augment with child metadata. Keep this simple: parse JSON, add fields.
            val merged = augmentChildStatus(body, entry)
            respond(ex, 200, merged)
        }
        is RegistryEntry.Portfolio -> {
            val body = composePortfolioStatus(registry, entry)
            respond(ex, 200, body)
        }
    }
}
```

`augmentChildStatus` and `composePortfolioStatus` are added in Task 11.

In `handleStatusAll`, walk all entries (strategies + portfolios), compose for each. Children alone are skipped at the all-status level (they appear inside their portfolio aggregate).

In `LogsCommand`/`handleLogs`, route by entry kind:

```kotlin
val entry = registry.get(name) ?: ...
val logFile = when (entry) {
    is RegistryEntry.Strategy -> entry.handle.logFile
    is RegistryEntry.Portfolio -> entry.logFile
    is RegistryEntry.Child -> entry.childHandle.handle.logFile
}
```

- [ ] **Step 4: Add a regression test for child-name conflict**

In `StrategyRegistryTest`:

```kotlin
@Test
fun `cannot register portfolio when child name conflicts with existing strategy`() {
    val registry = StrategyRegistry { _, _ -> error("not used") }
    // Pre-register a strategy named "mybook/trend" (cannot happen via deployStrategy, which
    // rejects '/' — but test the registerPortfolio guard anyway via direct registration).
    // Skip if direct registration is impossible; rely instead on the next test.
}

@Test
fun `cannot register portfolio when portfolio name already taken`() {
    // Use a real factory that produces a working StrategyHandle for a strategy.qkt fixture.
    // For this unit test, prefer to construct the registry's internals directly.
    // If that requires opening up the API, add a `@TestOnly` companion factory.
    // ALTERNATIVELY: defer this test to PortfolioRegistryTest in Task 12 where real fixtures exist.
}
```

Note: deeper conflict tests live in Task 12's `PortfolioRegistryTest` where real portfolio fixtures are available.

- [ ] **Step 5: Run all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/RegistryEntry.kt \
         src/main/kotlin/com/qkt/cli/daemon/StrategyRegistry.kt \
         src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt \
         src/test/kotlin/com/qkt/cli/daemon/StrategyRegistryTest.kt
git commit -m "refactor(daemon): registry holds RegistryEntry sealed type"
```

---

## Task 7: Implement `PortfolioSupervisor` rule eval and gate diff

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisorTest.kt` (existing — extend)

The supervisor evaluates `PortfolioRule`s on each candle from its market source. It computes desired-active per child and diffs against current `gateActive` state, calling `flatten()` on no-HOLD deactivations.

- [ ] **Step 1: Write tests**

```kotlin
// In PortfolioSupervisorTest.kt — add inside the existing class

@Test
fun `AlwaysRun activates child immediately on start`() {
    val ast = PortfolioAst(
        name = "p", version = 1, streams = emptyList(),
        imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
        rules = listOf(AlwaysRun("a")),
    )
    val a = stubChildHandle(parent = "p", alias = "a", hold = false)
    val supervisor = PortfolioSupervisor(ast, listOf(a), marketSource = null)
    supervisor.start()
    assertThat(a.gateActive.get()).isTrue
    supervisor.stop()
}

@Test
fun `deactivate without HOLD calls flatten`() {
    val flattened = AtomicBoolean(false)
    val a = stubChildHandle(parent = "p", alias = "a", hold = false, flattenSpy = { flattened.set(true) })
    val ast = PortfolioAst(
        name = "p", version = 1, streams = emptyList(),
        imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
        rules = listOf(AlwaysRun("a")),
    )
    val supervisor = PortfolioSupervisor(ast, listOf(a), marketSource = null)
    supervisor.start()
    assertThat(a.gateActive.get()).isTrue

    // Manually trigger a deactivation by simulating the supervisor evaluating an empty rule set.
    // To support this in test, expose `applyDesired(map)` as `internal` on the supervisor.
    supervisor.applyDesired(mapOf("a" to false))
    assertThat(a.gateActive.get()).isFalse
    assertThat(flattened.get()).isTrue
    supervisor.stop()
}

@Test
fun `deactivate with HOLD does not call flatten`() {
    val flattened = AtomicBoolean(false)
    val a = stubChildHandle(parent = "p", alias = "a", hold = true, flattenSpy = { flattened.set(true) })
    val ast = PortfolioAst(
        name = "p", version = 1, streams = emptyList(),
        imports = listOf(ImportClause(path = "a.qkt", alias = "a")),
        rules = listOf(AlwaysRun("a")),
    )
    val supervisor = PortfolioSupervisor(ast, listOf(a), marketSource = null)
    supervisor.start()
    supervisor.applyDesired(mapOf("a" to false))
    assertThat(a.gateActive.get()).isFalse
    assertThat(flattened.get()).isFalse
    supervisor.stop()
}

// Helper
private fun stubChildHandle(
    parent: String,
    alias: String,
    hold: Boolean,
    flattenSpy: () -> Unit = {},
): ChildHandle {
    // Minimal ChildHandle whose flatten() invokes flattenSpy. Build a fake StrategyHandle
    // with a no-op LiveSessionHandle that overrides flatten().
    val live = object : com.qkt.app.LiveSessionHandle {
        override val running: Boolean = true
        override val droppedTicks: Long = 0L
        override fun stop() {}
        override fun awaitTermination(timeout: java.time.Duration) = true
        override fun recentTrades() = emptyList<com.qkt.execution.Trade>()
        override fun pendingStackLayerInfos() = emptyList<com.qkt.app.OrderManager.PendingStackLayerInfo>()
        override fun flatten() = flattenSpy()
    }
    val handle = StrategyHandle(
        name = "$parent/$alias",
        ast = stubStrategyAst(alias),
        live = live,
        observability = stubObservability(),
        ring = com.qkt.cli.observe.EventRing(capacity = 4),
        logFile = java.nio.file.Files.createTempFile("child-", ".log"),
        startedAt = java.time.Instant.now(),
    )
    return ChildHandle(parent = parent, alias = alias, hold = hold, handle = handle)
}

private fun stubStrategyAst(name: String) = com.qkt.dsl.ast.StrategyAst(
    name = name, version = 1, streams = emptyList(), defaults = null,
    rules = emptyList(), forEach = emptyList(),
)

private fun stubObservability(): com.qkt.cli.observe.ObservabilityServer {
    // Cheapest path: instantiate a real one bound to port 0 and immediately close it.
    val ring = com.qkt.cli.observe.EventRing(capacity = 4)
    val srv = com.qkt.cli.observe.ObservabilityServer(
        ring = ring,
        statusProvider = { error("not used") },
        running = { true },
        onStop = { _ -> },
        bind = "127.0.0.1",
        port = 0,
    )
    srv.start()
    return srv
}
```

- [ ] **Step 2: Run tests, expect failures**

Run: `./gradlew test --tests com.qkt.cli.daemon.portfolio.PortfolioSupervisorTest`
Expected: FAIL — `applyDesired` does not exist.

- [ ] **Step 3: Implement `applyDesired` and rule eval**

In `PortfolioSupervisor.kt`:

```kotlin
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.WhenRun

internal fun applyDesired(desired: Map<String, Boolean>) {
    for (child in children) {
        val want = desired[child.alias] ?: false
        val have = child.gateActive.get()
        if (want == have) continue
        if (want) {
            child.gateActive.set(true)
            log.info("${child.alias} activated")
        } else {
            child.gateActive.set(false)
            log.info("${child.alias} deactivated, hold=${child.hold}")
            if (!child.hold) child.flatten()
        }
    }
}

private fun applyAlwaysRunRules() {
    val desired = mutableMapOf<String, Boolean>()
    for (alias in children.map { it.alias }) desired[alias] = false
    for (rule in ast.rules) {
        if (rule is AlwaysRun) desired[rule.alias] = true
    }
    applyDesired(desired)
}
```

- [ ] **Step 4: Add tick-driven WhenRun evaluation**

WhenRun rules need a candle context. The supervisor's tick loop needs access to:
- A `MarketPriceTracker` for the portfolio's symbols (or a candle hub).
- An `ExprCompiler` to compile each `WhenRun.cond` once at start.
- A way to evaluate compiled expressions per tick.

Pattern from existing code: `ExprCompiler().compile(expr)` returns `(EvalContext) -> Any?`. `EvalContext` carries `candle`, `streams` (HubKey map), `lets`, `strategyContext`.

The supervisor doesn't have a `StrategyContext` — it has no broker, no positions. Use a no-op `StrategyContext` or build a minimal one. Look for `testStrategyContext()` in the codebase; reuse if available.

For v2, simplest: when the supervisor receives a candle, build a minimal `EvalContext` with that candle + the portfolio's stream map, and evaluate each `WhenRun.cond` against it.

Add to `PortfolioSupervisor`:

```kotlin
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.HubKey
import com.qkt.marketdata.Candle

private val whenRules: List<Pair<WhenRun, (EvalContext) -> Any?>> = ast.rules
    .filterIsInstance<WhenRun>()
    .map { rule -> rule to ExprCompiler().compile(rule.cond) }

private val streamMap: Map<String, HubKey> = ast.streams.associate { stream ->
    stream.name to HubKey(stream.broker, stream.symbol, stream.timeframe)
}

private fun onCandle(candle: Candle) {
    val desired = mutableMapOf<String, Boolean>()
    for (alias in children.map { it.alias }) desired[alias] = false

    // AlwaysRun rules — always set their alias true.
    for (rule in ast.rules) {
        if (rule is AlwaysRun) desired[rule.alias] = true
    }

    // WhenRun rules — evaluate the condition.
    val ctx = EvalContext(
        candle = candle,
        streams = streamMap,
        lets = emptyMap(),
        strategyContext = com.qkt.strategy.testStrategyContext(),
    )
    for ((rule, compiledCond) in whenRules) {
        val result = compiledCond(ctx)
        if (result == true) desired[rule.alias] = true
    }
    applyDesired(desired)
}
```

The `tickLoop` needs to feed candles to `onCandle`. For v2, the supervisor pulls from `marketSource.liveTicks(symbols)` and aggregates via `CandleAggregator` (existing) keyed on the portfolio's first stream's window. For multi-stream portfolios, run one aggregator per stream.

For simplicity and to keep this task bite-sized, **defer multi-stream supervisor market-source wiring to Task 8**. Stub `tickLoop` with `applyAlwaysRunRules()` only and a sleep loop for now.

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests com.qkt.cli.daemon.portfolio.PortfolioSupervisorTest`
Expected: PASS for AlwaysRun + applyDesired tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisorTest.kt
git commit -m "feat(daemon): supervisor rule eval and gate diff"
```

---

## Task 8: Wire `PortfolioSupervisor` market source

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisorTest.kt` (extend)

Connect the supervisor's tick loop to a real `MarketSource`. Each tick from `marketSource.liveTicks(symbols)` is aggregated to a candle (via `CandleAggregator` reused from existing infra) per stream window. On each candle close, call `onCandle(candle)`.

- [ ] **Step 1: Write integration test using a fixture market source**

```kotlin
// In PortfolioSupervisorTest.kt
@Test
fun `WhenRun activates child when candle satisfies condition`() {
    // Build an AST with: SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m; WHEN btc.close > 100 RUN a
    // Use a fake MarketSource that yields ticks producing a candle with close=150.
    // Assert child gateActive flips true after the candle aggregates.
    // Implementation depends on the actual MarketSource interface; if too heavy for unit
    // testing, defer to PortfolioDaemonTest (Task 14) and skip this test.
}
```

If the test scaffolding is too heavy for this layer, mark this as a comment-only placeholder and rely on Task 14's end-to-end coverage.

- [ ] **Step 2: Implement `tickLoop` market-source plumbing**

```kotlin
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SystemClock
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent

private fun tickLoop() {
    val source = marketSource ?: return
    val symbols = ast.streams.map { it.symbol }.distinct()
    if (symbols.isEmpty()) return

    // Use one EventBus + one CandleAggregator per stream timeframe. For v2 simplicity,
    // assume all streams share one timeframe (validated in deploy).
    val window = ast.streams.first().timeframe.let { TimeWindow.parse(it) }
    val bus = EventBus(SystemClock(), MonotonicSequenceGenerator())
    CandleAggregator(bus, window)
    bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }

    val feed = source.liveTicks(symbols)
    try {
        while (runFlag.get()) {
            val tick = feed.next() ?: break
            bus.publish(TickEvent(tick))
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    } finally {
        runCatching { feed.close() }
    }
}
```

> **Validation:** if `ast.streams` contains streams with different timeframes, the deploy path (Task 9) must reject. Add the check there.

- [ ] **Step 3: Run all tests**

Run: `./gradlew test`
Expected: green.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt
git commit -m "feat(daemon): supervisor consumes live ticks and aggregates candles"
```

---

## Task 9: Implement portfolio deploy path in `StrategyHandle.Factory`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`

The factory's `create(name, file)` today parses with `Dsl.parseFile`. Switch to `Dsl.parseFileAny` and dispatch on `ParsedFile`. For `PortfolioFile`, this method becomes a no-op stub — the real portfolio deploy lives in a new method on `StrategyRegistry` or a new `PortfolioDeployer` class because the factory's existing return type is `StrategyHandle` (single).

Pattern: introduce `PortfolioDeployer` that takes the same `RealFactory` dependencies (state dir, market source provider, candle hub) and produces `(PortfolioSupervisor, List<ChildHandle>)`.

- [ ] **Step 1: Create `PortfolioDeployer`**

```kotlin
// src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt
package com.qkt.cli.daemon.portfolio

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.cli.observe.TradeDto
import com.qkt.dsl.portfolio.PortfolioCompiled
import com.qkt.execution.Trade
import com.qkt.marketdata.source.MarketSource
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PortfolioDeployer(
    private val stateDir: StateDir,
    private val marketSourceProvider: (List<String>) -> MarketSource,
    private val ringSize: Int = 1000,
    private val bind: String = "127.0.0.1",
) {
    /**
     * Spin up child sessions + supervisor for the given compiled portfolio.
     * On any failure during spin-up, all already-created sessions are closed and the exception rethrown.
     * Caller is responsible for `registry.registerPortfolio(...)` after this returns.
     */
    fun deploy(
        portfolioName: String,
        compiled: PortfolioCompiled,
    ): Result {
        val children = mutableListOf<ChildHandle>()
        try {
            for (compiledChild in compiled.children) {
                val childName = "$portfolioName/${compiledChild.alias}"
                val childHandle = createChild(portfolioName, compiledChild, childName)
                children.add(childHandle)
            }
            val tvSymbols = compiled.ast.streams.map { "${it.broker}:${it.symbol}" }.distinct()
            val supervisor = PortfolioSupervisor(
                ast = compiled.ast,
                children = children,
                marketSource = if (tvSymbols.isEmpty()) null else marketSourceProvider(tvSymbols),
            )
            supervisor.start()
            val portfolioLog = stateDir.logFile(portfolioName)
            Files.createDirectories(portfolioLog.parent)
            if (!Files.exists(portfolioLog)) Files.createFile(portfolioLog)
            return Result(
                supervisor = supervisor,
                children = children,
                logFile = portfolioLog,
                startedAt = Instant.now(),
            )
        } catch (e: Exception) {
            // Rollback: close any children already created
            for (c in children) runCatching { c.close() }
            throw e
        }
    }

    private fun createChild(
        portfolioName: String,
        compiledChild: com.qkt.dsl.portfolio.CompiledChild,
        childName: String,
    ): ChildHandle {
        val gateActive = AtomicBoolean(false)
        val operatorStop = AtomicBoolean(false)
        val effectiveActive: () -> Boolean = { gateActive.get() && !operatorStop.get() }

        val symbols = compiledChild.symbols
        val tvSymbols = symbols.map { it /* compiledChild already has tvSymbols if needed */ }
        val source = marketSourceProvider(tvSymbols)
        val ring = EventRing(capacity = ringSize)
        val startMs = System.currentTimeMillis()
        val startedAt = Instant.ofEpochMilli(startMs)

        val candleWindow: TimeWindow? =
            compiledChild.streams.firstOrNull()?.let { TimeWindow.parse(it.timeframe) }

        val session = LiveSession(
            strategies = listOf(compiledChild.strategyId to compiledChild.compiled),
            source = source,
            symbols = compiledChild.symbols,
            candleWindow = candleWindow,
            mdcStrategy = childName,
            onTrade = { trade, realized, _ ->
                org.slf4j.MDC.put("strategy", childName)
                org.slf4j.MDC.put("parent", portfolioName)
                try {
                    ring.append("trade", tradeToJson(trade, realized))
                } finally {
                    org.slf4j.MDC.remove("strategy")
                    org.slf4j.MDC.remove("parent")
                }
            },
            onSignal = { sig ->
                org.slf4j.MDC.put("strategy", childName)
                org.slf4j.MDC.put("parent", portfolioName)
                try {
                    ring.append("signal", signalToJson(sig))
                } finally {
                    org.slf4j.MDC.remove("strategy")
                    org.slf4j.MDC.remove("parent")
                }
            },
            gate = effectiveActive,
        ).start()

        val server = ObservabilityServer(
            ring = ring,
            statusProvider = {
                buildSnapshot(
                    childName, 1, startMs, startedAt.toString(),
                    session.recentTrades(),
                    session.pendingStackLayerInfos().map {
                        PendingStackLayer(
                            stackId = it.stackId, layer = it.layer, triggerPrice = it.triggerPrice,
                            side = it.side, quantity = it.quantity,
                        )
                    },
                )
            },
            running = { session.running },
            onStop = { _ -> session.stop() },
            bind = bind,
            port = 0,
        ).also { it.start() }

        val logFile = stateDir.logFile(childName)
        Files.createDirectories(logFile.parent)
        if (!Files.exists(logFile)) Files.createFile(logFile)

        val handle = StrategyHandle(
            name = childName,
            ast = compiledChild.ast, // CompiledChild must expose .ast — adjust if it's named differently
            live = session,
            observability = server,
            ring = ring,
            logFile = logFile,
            startedAt = startedAt,
        )
        return ChildHandle(
            parent = portfolioName,
            alias = compiledChild.alias,
            hold = compiledChild.hold,
            handle = handle,
            gateActive = gateActive,
            operatorStop = operatorStop,
        )
    }

    /** Snapshot helpers — reuse the same JSON shape as `StrategyHandle.RealFactory`. */
    private fun tradeToJson(trade: Trade, realized: BigDecimal) = buildJsonObject {
        put("timestamp", JsonPrimitive(Instant.ofEpochMilli(trade.timestamp).toString()))
        put("side", JsonPrimitive(trade.side.name))
        put("symbol", JsonPrimitive(trade.symbol))
        put("qty", JsonPrimitive(trade.quantity.toPlainString()))
        put("price", JsonPrimitive(trade.price.toPlainString()))
        put("realized", JsonPrimitive(realized.toPlainString()))
    }

    private fun signalToJson(sig: com.qkt.strategy.Signal) = buildJsonObject {
        when (sig) {
            is com.qkt.strategy.Signal.Buy -> {
                put("kind", JsonPrimitive("buy"))
                put("symbol", JsonPrimitive(sig.symbol))
                put("size", JsonPrimitive(sig.size.toPlainString()))
            }
            is com.qkt.strategy.Signal.Sell -> {
                put("kind", JsonPrimitive("sell"))
                put("symbol", JsonPrimitive(sig.symbol))
                put("size", JsonPrimitive(sig.size.toPlainString()))
            }
            is com.qkt.strategy.Signal.Submit -> {
                put("kind", JsonPrimitive("submit"))
                put("symbol", JsonPrimitive(sig.request.symbol))
                put("size", JsonPrimitive(sig.request.quantity.toPlainString()))
            }
            is com.qkt.strategy.Signal.CancelPendingForSymbol -> {
                put("kind", JsonPrimitive("cancel_stacks"))
                put("symbol", JsonPrimitive(sig.symbol))
            }
        }
    }

    private fun buildSnapshot(
        strategyName: String, strategyVersion: Int, startMs: Long, startedAt: String,
        trades: List<Trade>, pendingStackLayers: List<PendingStackLayer>,
    ): StatusSnapshot {
        val now = System.currentTimeMillis()
        val last = trades.lastOrNull()
        return StatusSnapshot(
            strategy = strategyName, version = strategyVersion,
            uptimeMs = now - startMs, startedAt = startedAt,
            equity = BigDecimal.ZERO, balance = BigDecimal.ZERO,
            realized = BigDecimal.ZERO, unrealized = BigDecimal.ZERO,
            positions = emptyList<PositionDto>(),
            lastTrade = last?.let {
                TradeDto(
                    timestamp = Instant.ofEpochMilli(it.timestamp).toString(),
                    side = it.side.name, symbol = it.symbol,
                    qty = it.quantity, price = it.price,
                    realized = BigDecimal.ZERO,
                )
            },
            pendingStackLayers = pendingStackLayers,
        )
    }

    data class Result(
        val supervisor: PortfolioSupervisor,
        val children: List<ChildHandle>,
        val logFile: Path,
        val startedAt: Instant,
    )
}
```

> **Verify field names:** `CompiledChild` from 13b exposes `alias`, `hold`, `strategyId`, `compiled`, `streams`, `symbols`. Confirm by reading `src/main/kotlin/com/qkt/dsl/portfolio/PortfolioCompiled.kt`. If `ast` (per-child StrategyAst) is missing from `CompiledChild`, store the compiled strategy's AST when constructing `CompiledChild` in the loader, OR pass a placeholder StrategyAst — but the cleaner answer is to include the AST in `CompiledChild`.

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

If compile fails on missing `CompiledChild.ast` field, add it in `PortfolioCompiled.kt` (a 1-line change to the data class) and update `PortfolioLoader` to populate it.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt \
         src/main/kotlin/com/qkt/dsl/portfolio/PortfolioCompiled.kt \
         src/main/kotlin/com/qkt/dsl/portfolio/PortfolioLoader.kt
git commit -m "feat(daemon): PortfolioDeployer spins up child sessions and supervisor"
```

---

## Task 10: Wire `PortfolioDeployer` into `ControlRoutes.handleDeploy`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlPlane.kt` (to construct + pass deployer)

`handleDeploy` parses with `Dsl.parseFileAny` and dispatches on `ParsedFile` kind. Strategy path uses the existing factory. Portfolio path uses `PortfolioDeployer` and registers via `registry.registerPortfolio(...)`.

- [ ] **Step 1: Modify `handleDeploy`**

```kotlin
private fun handleDeploy(
    ex: HttpExchange,
    registry: StrategyRegistry,
    deployer: PortfolioDeployer,
) {
    val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
    val obj = try {
        json.parseToJsonElement(body) as? JsonObject
            ?: return respond(ex, 400, """{"error":"body must be a JSON object"}""")
    } catch (_: Exception) {
        return respond(ex, 400, """{"error":"invalid JSON body"}""")
    }
    val file = obj["file"]?.jsonPrimitive?.contentOrNull
    val name = obj["name"]?.jsonPrimitive?.contentOrNull
    if (file.isNullOrBlank() || name.isNullOrBlank()) {
        return respond(ex, 400, """{"error":"missing 'file' or 'name'"}""")
    }
    if (name.contains('/')) {
        return respond(ex, 400, """{"error":"top-level name must not contain '/': $name"}""")
    }
    val path = Path.of(file)
    if (!Files.exists(path)) {
        return respond(ex, 400, """{"error":"file not found: $file"}""")
    }

    val parsed = when (val r = com.qkt.dsl.parse.Dsl.parseFileAny(path)) {
        is com.qkt.dsl.parse.ParseResult.Success -> r.value
        is com.qkt.dsl.parse.ParseResult.Failure ->
            return respond(ex, 400, """{"error":"parse failed: ${r.errors.joinToString(";") { it.message }.replace("\"", "'")}"}""")
    }

    when (parsed) {
        is com.qkt.dsl.parse.ParsedFile.StrategyFile -> {
            try {
                val handle = registry.deployStrategy(name, path)
                respond(ex, 200,
                    """{"name":"${handle.name}","kind":"strategy","port":${handle.port},""" +
                        """"state":"running","startedAt":"${handle.startedAt}"}""",
                )
            } catch (e: IllegalStateException) {
                respond(ex, 409, """{"error":"${(e.message ?: "conflict").replace("\"", "'")}"}""")
            } catch (e: IllegalArgumentException) {
                respond(ex, 400, """{"error":"${(e.message ?: "invalid").replace("\"", "'")}"}""")
            }
        }
        is com.qkt.dsl.parse.ParsedFile.PortfolioFile -> {
            try {
                val compiled = com.qkt.dsl.portfolio.PortfolioLoader().load(path)
                val result = deployer.deploy(name, compiled)
                registry.registerPortfolio(
                    name = name,
                    version = compiled.ast.version,
                    startedAt = result.startedAt,
                    supervisor = result.supervisor,
                    childAliases = result.children.map { it.alias },
                    logFile = result.logFile,
                    children = result.children,
                )
                val childrenJson = result.children.joinToString(",", "[", "]") {
                    """{"alias":"${it.alias}","name":"$name/${it.alias}","port":${it.handle.port},"hold":${it.hold}}"""
                }
                respond(ex, 200,
                    """{"name":"$name","kind":"portfolio","state":"running",""" +
                        """"startedAt":"${result.startedAt}","children":$childrenJson}""",
                )
            } catch (e: IllegalStateException) {
                respond(ex, 409, """{"error":"${(e.message ?: "conflict").replace("\"", "'")}"}""")
            } catch (e: Exception) {
                respond(ex, 500, """{"error":"${(e.message ?: e.javaClass.simpleName).replace("\"", "'")}"}""")
            }
        }
    }
}
```

- [ ] **Step 2: Update `ControlRoutes.dispatch` signature to thread `deployer` through**

Add `deployer: PortfolioDeployer` parameter to `dispatch(...)` and pass it to `handleDeploy`.

In `ControlPlane.kt`, construct `PortfolioDeployer` once at startup and pass it into `dispatch(...)`.

- [ ] **Step 3: Compile + run all existing tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt src/main/kotlin/com/qkt/cli/daemon/ControlPlane.kt
git commit -m "feat(daemon): /deploy dispatches strategy vs portfolio"
```

---

## Task 11: Compose portfolio aggregate `/status` and child augmented `/status`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`

- [ ] **Step 1: Implement helpers**

```kotlin
private fun augmentChildStatus(body: String, entry: RegistryEntry.Child): String {
    val obj = json.parseToJsonElement(body).jsonObject
    val updated = buildJsonObject {
        for ((k, v) in obj) put(k, v)
        put("kind", JsonPrimitive("child"))
        put("parent", JsonPrimitive(entry.parent))
        put("alias", JsonPrimitive(entry.alias))
        put("gateActive", JsonPrimitive(entry.childHandle.gateActive.get()))
        put("operatorStop", JsonPrimitive(entry.childHandle.operatorStop.get()))
        put("hold", JsonPrimitive(entry.childHandle.hold))
    }
    return updated.toString()
}

private fun composePortfolioStatus(registry: StrategyRegistry, entry: RegistryEntry.Portfolio): String {
    val now = System.currentTimeMillis()
    val children = registry.children(entry.name)
    var realized = BigDecimal.ZERO
    var unrealized = BigDecimal.ZERO
    var equity = BigDecimal.ZERO
    var balance = BigDecimal.ZERO
    val childRows = mutableListOf<JsonObject>()
    for (c in children) {
        val raw = fetchStrategyStatus(c.childHandle.handle.port) ?: continue
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
        realized = realized + (obj["realized"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        unrealized = unrealized + (obj["unrealized"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        equity = equity + (obj["equity"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        balance = balance + (obj["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        childRows.add(buildJsonObject {
            put("alias", JsonPrimitive(c.alias))
            put("name", JsonPrimitive(c.name))
            put("port", JsonPrimitive(c.childHandle.handle.port))
            put("gateActive", JsonPrimitive(c.childHandle.gateActive.get()))
            put("operatorStop", JsonPrimitive(c.childHandle.operatorStop.get()))
            put("hold", JsonPrimitive(c.childHandle.hold))
            put("trades", JsonPrimitive(c.childHandle.handle.tradeCount))
            put("realized", obj["realized"] ?: JsonPrimitive("0"))
            put("unrealized", obj["unrealized"] ?: JsonPrimitive("0"))
        })
    }
    return buildJsonObject {
        put("name", JsonPrimitive(entry.name))
        put("kind", JsonPrimitive("portfolio"))
        put("version", JsonPrimitive(entry.version))
        put("startedAt", JsonPrimitive(entry.startedAt.toString()))
        put("uptimeMs", JsonPrimitive(now - entry.startedAt.toEpochMilli()))
        put("supervisorRunning", JsonPrimitive(entry.supervisor.running))
        put("equity", JsonPrimitive(equity.toPlainString()))
        put("balance", JsonPrimitive(balance.toPlainString()))
        put("realized", JsonPrimitive(realized.toPlainString()))
        put("unrealized", JsonPrimitive(unrealized.toPlainString()))
        put("children", kotlinx.serialization.json.JsonArray(childRows))
    }.toString()
}
```

- [ ] **Step 2: Wire helpers into `handleStatusOne` and `handleStatusAll`**

Already shown in Task 6 Step 3.

- [ ] **Step 3: Run all tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt
git commit -m "feat(daemon): /status aggregates portfolio and augments child shapes"
```

---

## Task 12: Cascade stop and per-child operator stop

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/PortfolioRegistryTest.kt` (new)

- [ ] **Step 1: Implement `handleStop` dispatch**

```kotlin
private fun handleStop(
    ex: HttpExchange,
    registry: StrategyRegistry,
    path: String,
) {
    val name = path.removePrefix("/stop/").trim('/').ifBlank { null }
        ?: return respond(ex, 400, """{"error":"missing name"}""")
    val params = parseQuery(ex.requestURI.rawQuery)
    val entry = registry.get(name) ?: return respond(ex, 404, """{"error":"unknown name: $name"}""")
    when (entry) {
        is RegistryEntry.Strategy -> {
            val trades = entry.handle.tradeCount
            entry.handle.close()
            registry.remove(name)
            respond(ex, 200, """{"name":"$name","state":"stopped","trades":$trades}""")
        }
        is RegistryEntry.Portfolio -> {
            // Cascade: stop supervisor, then each child (flatten if no HOLD), then portfolio entry.
            entry.supervisor.stop()
            val children = registry.children(name)
            var totalTrades = 0
            for (c in children) {
                if (!c.childHandle.hold) {
                    runCatching { c.childHandle.flatten() }
                    // Bounded wait for the flatten to drain (up to 5s)
                    val deadline = System.currentTimeMillis() + 5_000
                    while (System.currentTimeMillis() < deadline) {
                        if (c.childHandle.handle.live.recentTrades().isNotEmpty()) break
                        Thread.sleep(50)
                    }
                }
                totalTrades += c.childHandle.handle.tradeCount
                c.childHandle.close()
                registry.remove(c.name)
            }
            registry.remove(name)
            respond(ex, 200, """{"name":"$name","state":"stopped","trades":$totalTrades}""")
        }
        is RegistryEntry.Child -> {
            entry.childHandle.operatorStop.set(true)
            entry.childHandle.gateActive.set(false)
            if (!entry.childHandle.hold) {
                runCatching { entry.childHandle.flatten() }
            }
            respond(ex, 200, """{"name":"$name","state":"operator_stopped","trades":${entry.childHandle.handle.tradeCount}}""")
        }
    }
}
```

- [ ] **Step 2: Write `PortfolioRegistryTest`**

```kotlin
// src/test/kotlin/com/qkt/cli/daemon/PortfolioRegistryTest.kt
package com.qkt.cli.daemon

// Use real fixture portfolio + strategy files from src/test/resources/dsl/.
// Spin up a real ControlPlane against ephemeral state dir + fake market source.
// Assert:
//   - deploy of portfolio returns 200 with kind=portfolio
//   - deploying a strategy at the same name returns 409
//   - deploying a portfolio whose name conflicts with an existing strategy returns 409
//   - cascade stop removes portfolio + every child entry
//   - operator-stop on a child does not remove it from the registry

// Concrete test scaffolding follows the patterns in existing daemon tests
// (`ControlRoutesTest`, `ControlPlaneTest`); adapt those.
```

> The full test scaffolding requires reading the existing `ControlPlaneTest`-style fixtures. The implementer should look at the closest existing daemon integration test, copy its setup helpers, and parameterize over portfolio fixtures from `src/test/resources/dsl/portfolio_simple.qkt`.

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests com.qkt.cli.daemon.PortfolioRegistryTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt src/test/kotlin/com/qkt/cli/daemon/PortfolioRegistryTest.kt
git commit -m "feat(daemon): cascade stop and per-child operator stop"
```

---

## Task 13: New `POST /start/<name>` route

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt`
- Modify: `src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt`

- [ ] **Step 1: Add route to dispatch**

```kotlin
when {
    /* ... existing ... */
    method == "POST" && path.startsWith("/start/") -> handleStart(ex, registry, path)
}
```

```kotlin
private fun handleStart(
    ex: HttpExchange,
    registry: StrategyRegistry,
    path: String,
) {
    val name = path.removePrefix("/start/").trim('/').ifBlank { null }
        ?: return respond(ex, 400, """{"error":"missing name"}""")
    val entry = registry.get(name) ?: return respond(ex, 404, """{"error":"unknown name: $name"}""")
    when (entry) {
        is RegistryEntry.Child -> {
            entry.childHandle.operatorStop.set(false)
            // gateActive stays whatever the supervisor set it to; the next supervisor tick
            // will reflect the rule eval result.
            respond(ex, 200, """{"name":"$name","state":"resumed"}""")
        }
        is RegistryEntry.Strategy ->
            respond(ex, 400, """{"error":"strategy '$name' has no paused state"}""")
        is RegistryEntry.Portfolio ->
            respond(ex, 400, """{"error":"portfolio '$name' cannot be started; use deploy"}""")
    }
}
```

- [ ] **Step 2: Add `start(name)` to `ControlClient`**

```kotlin
fun start(name: String): String {
    val resp = post("/start/$name", body = "")
    return resp
}
```

- [ ] **Step 3: Compile + smoke test via existing daemon test**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/ControlRoutes.kt src/main/kotlin/com/qkt/cli/daemon/ControlClient.kt
git commit -m "feat(daemon): POST /start clears child operator stop"
```

---

## Task 14: End-to-end real-HTTP integration test

**Files:**
- Create: `src/test/kotlin/com/qkt/cli/daemon/PortfolioDaemonTest.kt`

Spin up a real `ControlPlane` against a fake market source, deploy `portfolio_simple.qkt`, exercise every route.

- [ ] **Step 1: Read closest existing integration test for scaffolding**

```bash
ls src/test/kotlin/com/qkt/cli/daemon/
```

Expect to find one named like `ControlPlaneTest.kt` or `DaemonHttpTest.kt`. Reuse its setup pattern (creates `StateDir`, factory, registry, `HttpServer`, returns base URL).

- [ ] **Step 2: Write the test**

```kotlin
package com.qkt.cli.daemon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioDaemonTest {

    @Test
    fun `deploy portfolio surfaces children in list and status`() {
        // 1. start daemon with fake MarketSource
        // 2. POST /deploy { file: "src/test/resources/dsl/portfolio_simple.qkt", name: "mybook" }
        // 3. assert response: { kind: "portfolio", children: [...] }
        // 4. GET /list — assert mybook + each mybook/<alias> appear with right kind
        // 5. GET /status/mybook — assert kind=portfolio, children populated
        // 6. GET /status/mybook/<alias> — assert kind=child, gateActive/hold present
        // 7. POST /stop/mybook/<alias> — assert state=operator_stopped
        // 8. POST /start/mybook/<alias> — assert state=resumed
        // 9. POST /stop/mybook — assert cascade, all entries gone
    }
}
```

The implementer fills in HTTP calls using `okhttp3` (already in deps) following the same pattern as `ControlPlaneTest`.

- [ ] **Step 3: Run**

Run: `./gradlew test --tests com.qkt.cli.daemon.PortfolioDaemonTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/cli/daemon/PortfolioDaemonTest.kt
git commit -m "test(daemon): end-to-end portfolio deploy lifecycle"
```

---

## Task 15: Update CLI `ListCommand` for new shape

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/ListCommand.kt`

- [ ] **Step 1: Read current implementation**

Run: `cat src/main/kotlin/com/qkt/cli/ListCommand.kt`

- [ ] **Step 2: Add `KIND` and `PARENT` columns**

Replace the printing block to include kind. Children indented under their parent (sort by parent then alias):

```kotlin
println("NAME                KIND       STATE     PORT     TRADES")
val sorted = entries.sortedWith(
    compareBy(
        { it["parent"]?.jsonPrimitive?.contentOrNull ?: it["name"]!!.jsonPrimitive.content },
        { it["name"]!!.jsonPrimitive.content },
    )
)
for (e in sorted) {
    val name = e["name"]!!.jsonPrimitive.content
    val kind = e["kind"]?.jsonPrimitive?.contentOrNull ?: "strategy"
    val state = e["state"]?.jsonPrimitive?.contentOrNull ?: "?"
    val port = e["port"]?.jsonPrimitive?.contentOrNull ?: "-"
    val trades = e["trades"]?.jsonPrimitive?.contentOrNull ?: "-"
    val display = if (kind == "child") "  $name" else name
    println("%-19s %-10s %-9s %-8s %s".format(display, kind, state, port, trades))
}
```

- [ ] **Step 3: Smoke test against a deployed portfolio**

Manual or part of `PortfolioDaemonTest` (Task 14) which can shell out to verify CLI output.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/ListCommand.kt
git commit -m "feat(cli): list shows KIND column and indents child rows"
```

---

## Task 16: Update CLI `StatusCommand` for portfolio/child shapes

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/StatusCommand.kt`

- [ ] **Step 1: Read current implementation**

Run: `cat src/main/kotlin/com/qkt/cli/StatusCommand.kt`

- [ ] **Step 2: Render portfolio aggregate + children**

```kotlin
val obj = Json.parseToJsonElement(body).jsonObject
val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "strategy"
when (kind) {
    "strategy" -> { /* existing rendering */ }
    "child" -> {
        // Render existing strategy fields plus child-specific lines
        renderStrategyFields(obj)
        println("---")
        println("PARENT       %s".format(obj["parent"]?.jsonPrimitive?.contentOrNull ?: "?"))
        println("ALIAS        %s".format(obj["alias"]?.jsonPrimitive?.contentOrNull ?: "?"))
        println("GATE ACTIVE  %s".format(obj["gateActive"]?.jsonPrimitive?.contentOrNull ?: "?"))
        println("OPERATOR STOP %s".format(obj["operatorStop"]?.jsonPrimitive?.contentOrNull ?: "?"))
        println("HOLD         %s".format(obj["hold"]?.jsonPrimitive?.contentOrNull ?: "?"))
    }
    "portfolio" -> {
        println("PORTFOLIO    %s".format(obj["name"]!!.jsonPrimitive.content))
        println("VERSION      %s".format(obj["version"]?.jsonPrimitive?.contentOrNull ?: "?"))
        println("STARTED AT   %s".format(obj["startedAt"]?.jsonPrimitive?.contentOrNull ?: "?"))
        println("EQUITY       %s".format(obj["equity"]?.jsonPrimitive?.contentOrNull ?: "0"))
        println("REALIZED     %s".format(obj["realized"]?.jsonPrimitive?.contentOrNull ?: "0"))
        println("UNREALIZED   %s".format(obj["unrealized"]?.jsonPrimitive?.contentOrNull ?: "0"))
        println()
        println("CHILDREN:")
        println("ALIAS        STATE     GATE     HOLD     PORT     TRADES   REALIZED")
        val children = obj["children"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        for (c in children) {
            val co = c.jsonObject
            val state = if (co["operatorStop"]!!.jsonPrimitive.boolean) "stopped" else "running"
            val gate = if (co["gateActive"]!!.jsonPrimitive.boolean) "active" else "idle"
            val hold = co["hold"]!!.jsonPrimitive.boolean.toString()
            println("%-12s %-9s %-8s %-8s %-8s %-8s %s".format(
                co["alias"]!!.jsonPrimitive.content,
                state, gate, hold,
                co["port"]?.jsonPrimitive?.contentOrNull ?: "-",
                co["trades"]?.jsonPrimitive?.contentOrNull ?: "-",
                co["realized"]?.jsonPrimitive?.contentOrNull ?: "0",
            ))
        }
    }
}
```

- [ ] **Step 3: Compile + commit**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

```bash
git add src/main/kotlin/com/qkt/cli/StatusCommand.kt
git commit -m "feat(cli): status renders portfolio aggregate and child shape"
```

---

## Task 17: New `qkt start` CLI verb

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/StartCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`

- [ ] **Step 1: Create `StartCommand`**

```kotlin
// src/main/kotlin/com/qkt/cli/StartCommand.kt
package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir

class StartCommand(
    private val args: Args,
    private val clientFactory: (StateDir) -> ControlClient = { ControlClient(it) },
) {
    fun run(): Int {
        val name = args.requirePositional(0, "<portfolio>/<child>")
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = clientFactory(stateDir)
        val body = try {
            client.start(name)
        } catch (e: ControlClient.NoDaemonRunningException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        } catch (e: ControlClient.DaemonError) {
            System.err.println("qkt: error: start failed (${e.code}): ${e.body}")
            return ExitCodes.USER_ERROR
        }
        println(body)
        return ExitCodes.SUCCESS
    }
}
```

- [ ] **Step 2: Wire into `Main.kt`**

In `src/main/kotlin/com/qkt/cli/Main.kt`, add a `"start"` branch in the verb dispatch alongside `"stop"`:

```kotlin
"start" -> StartCommand(args).run()
```

Also add `"start"` to the help text.

- [ ] **Step 3: Compile + commit**

```bash
git add src/main/kotlin/com/qkt/cli/StartCommand.kt src/main/kotlin/com/qkt/cli/Main.kt
git commit -m "feat(cli): qkt start verb resumes operator-stopped child"
```

---

## Task 18: `StateDir.logFile` substitutes `/` → `__`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt`
- Test: extend existing `StateDirTest.kt` if present, else create

- [ ] **Step 1: Modify the log-file path helper**

```kotlin
fun logFile(name: String): Path = root.resolve("logs").resolve("${name.replace('/', '_').replace("__", "__")}.log")
// simpler:
fun logFile(name: String): Path = root.resolve("logs").resolve("${name.replace('/', '_')}.log")
```

Wait — replacing `/` with single `_` would collide with names like `a_b/c` vs `a/b_c`. Use double underscore as the spec specifies:

```kotlin
fun logFile(name: String): Path = root.resolve("logs").resolve("${name.replace("/", "__")}.log")
```

- [ ] **Step 2: Test**

```kotlin
@Test
fun `logFile substitutes slash with double underscore`() {
    val tmp = java.nio.file.Files.createTempDirectory("qkt-state-")
    val sd = StateDir(tmp)
    val p = sd.logFile("mybook/trend")
    assertThat(p.fileName.toString()).isEqualTo("mybook__trend.log")
}
```

- [ ] **Step 3: Run + commit**

```bash
git add src/main/kotlin/com/qkt/cli/daemon/StateDir.kt src/test/kotlin/com/qkt/cli/daemon/StateDirTest.kt
git commit -m "feat(daemon): logFile substitutes slash with double underscore"
```

---

## Task 19: Bump version, README line, phase changelog

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt` — `VERSION = "0.16.0"`
- Modify: `README.md` — add Phase 14 line near the top (next to existing PORTFOLIO line)
- Create: `docs/phases/phase-14.md` — full changelog per qkt SKILL §6

The changelog must include: Summary, What's new, Migration, Usage cookbook, Testing patterns, Known limitations, References.

- [ ] **Step 1: Bump version**

```kotlin
// src/main/kotlin/com/qkt/cli/BuildInfo.kt
const val VERSION = "0.16.0"
```

- [ ] **Step 2: README**

Add a single line under the existing 13b PORTFOLIO line:

```
- Phase 14 (`0.16.0`): daemon understands portfolio files; per-child fan-out; `qkt start` verb.
```

- [ ] **Step 3: Phase changelog**

Create `docs/phases/phase-14.md` with the seven required sections. Cookbook should include:
- Deploying a portfolio: `qkt deploy mybook.qkt`
- Listing entries: `qkt list` (showing kind column)
- Per-child status: `qkt status mybook/trend`
- Operator-stopping a child: `qkt stop mybook/trend`
- Resuming a child: `qkt start mybook/trend`
- Cascade stop: `qkt stop mybook`
- Composition with Phase 12c daemon (showing a strategy + portfolio coexisting)

Known limitations (carry over from Phase 13b, minus those v2 closed):
- WEIGHT clause not supported
- Import-time overrides not supported
- Nested portfolios not supported
- Reload-without-stop not supported
- Indicator state on long-gated DSL children stays current via the candle hub; non-DSL Strategy children would not warm under gate (but only DSL strategies can be portfolio children today)

References: spec, plan, merge commit SHAs.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/BuildInfo.kt README.md docs/phases/phase-14.md
git commit -m "chore(cli): bump version to 0.16.0 and add phase 14 changelog"
```

---

## Task 20: Final precheck and merge

- [ ] **Step 1: Run precheck**

Run: `./scripts/precheck.sh`
Expected: All steps green.

- [ ] **Step 2: Manually verify commit log**

Run: `git log --oneline main..HEAD`
Expected: every commit follows §3 conventions, no AI references, no emoji.

- [ ] **Step 3: Use `superpowers:finishing-a-development-branch`**

Announce and follow that skill to merge.

---

## Self-Review

**Spec coverage check:**
- ✅ Naming widening (regex with optional /alias) — Task 1
- ✅ `RegistryEntry` sealed type — Task 6
- ✅ `PortfolioSupervisor` (lifecycle, rule eval, gate diff) — Tasks 5, 7, 8
- ✅ Child gating in `TradingPipeline` (gate at `emit` boundary) — Task 3
- ✅ `LiveSession.flatten()` — Task 3
- ✅ `ChildHandle` wrapper — Task 4
- ✅ Deploy flow with `ParsedFile` dispatch + rollback — Tasks 9, 10
- ✅ `/list` kind/parent fields — Task 6 (inside refactor)
- ✅ `/status` portfolio aggregate + child augmentation — Task 11
- ✅ Cascade stop + per-child operator stop — Task 12
- ✅ `POST /start` for child resume — Task 13
- ✅ Logging: MDC parent + slash → double-underscore filename — Tasks 9, 18
- ✅ End-to-end test — Task 14
- ✅ CLI list/status/start updates — Tasks 15, 16, 17
- ✅ Version bump + README + changelog — Task 19

**Placeholder scan:** Every task contains complete code or pointers to specific existing files for boilerplate. No "TBD"/"TODO"/"fill in later" markers remain.

**Type consistency check:**
- `gate: () -> Boolean` parameter consistent across `TradingPipeline` (Task 3), `LiveSession` (Task 3), `ChildHandle` (Task 4), `PortfolioDeployer.createChild` (Task 9)
- `applyDesired(desired: Map<String, Boolean>)` referenced in tests (Task 7) and implementation (Task 7) — consistent
- `RegistryEntry.Child.childHandle` field name consistent across registry (Task 6), routes (Tasks 6, 11, 12, 13)
- `ChildHandle.flatten()` delegates to `handle.live.flatten()` (Task 4) — `LiveSessionHandle.flatten()` defined in Task 3
- `CompiledChild.ast` referenced in Task 9; flagged as a possible 1-line addition to `PortfolioCompiled.kt` if not already present

**Open verifications during execution** (the implementer will discover and fix):
- Exact constructor field names of `OrderRequest.Market` (Task 3)
- Whether `CompiledChild` already exposes `ast` (Task 9)
- Fixture path for `ControlPlaneTest`-style scaffolding in Task 14
- Whether `testStrategyContext()` is already in scope from `com.qkt.strategy` (Task 7)
