# Phase 11e — Multi-stream, multi-timeframe, multi-broker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift the DSL from "one symbol, one timeframe, one broker" to multi-asset shape. Adds a shared `CandleHub` at the pipeline level (JIT-registered, forward-streaming, shared across strategies), rekeys per-strategy state from `symbol` to `alias` so the same symbol at two timeframes maintains independent state, wires `CompositeBroker` into `Backtest` as the single broker seam, ships `forEach` macro expansion, and substitutes the `SYMBOL` placeholder inside `defaults` blocks.

**Architecture:** The hub is owned by `TradingPipeline`; strategies declare their needed `(broker, symbol, timeframe)` keys at compile time and read closed candles through the hub. `CompositeBroker` replaces direct `PaperBroker` construction in `Backtest`; one `PaperBroker` leaf per declared broker prefix. Indicators / aggregates / snapshots rekey from `symbol` to `alias`. `forEach` is a builder-time AST rewrite. `SYMBOL` placeholder is substituted in `mergeDefaults`.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-08-trading-engine-phase11e-design.md`.

**Branch:** `phase11e-multistream` — cut from `main` at start of Task 1.

---

## Design notes

### CandleHub shape

```kotlin
data class HubKey(val broker: String, val symbol: String, val timeframe: String)

class CandleHub(private val window: TimeWindow.Companion = TimeWindow) {
    fun register(key: HubKey, retention: Int)
    fun feed(tick: Tick)
    fun latest(key: HubKey): Candle?
    fun history(key: HubKey, n: Int): Candle?
    fun onClosed(key: HubKey, callback: (Candle) -> Unit)
}
```

- `register` is idempotent. Calling twice with different retentions takes `max`.
- `register` after first `feed` throws — keys are fixed at start.
- `feed` finds every key whose `symbol == tick.symbol` and applies the tick to that key's aggregator. When an aggregator closes a candle, the hub appends to the ring buffer, invokes listeners.
- `latest(key)` is the most recently closed candle (or `null` if none yet).
- `history(key, 0)` == `latest(key)`. `history(key, 1)` is the one before. Returns `null` past the buffer or before any closes.
- `onClosed` registered in compile order; called in registration order.

`CandleAggregator` exists in `com.qkt.candles`; the hub instantiates one per key with the `TimeWindow` parsed from the timeframe string.

### Alias-keyed rekey

| State | Old key | New key |
|---|---|---|
| `IndicatorBinding.streamAlias` | (still alias) | (still alias) — but its **read** uses hub.latest when `candle.symbol != aliasSymbol` |
| `AggregateBinding.ruleSymbol` | symbol | **alias** |
| `AggregateBinding.Bag.bindingsForSymbol(symbol)` | symbol | renamed to `bindingsForAlias(alias)` |
| `SnapshotStore.captureSlot/readSlot/clearSlot/...` | symbol | **alias** |
| `EvalContext.streamSymbols: Map<String, String>` | alias→symbol | replaced by `streams: Map<String, HubKey>` |
| `PositionTransitions.observe(symbol, qty)` | symbol | symbol *(unchanged — positions are per-symbol)* |

### CompositeBroker wiring

```kotlin
// In Backtest.run():
val brokerPrefixes: Set<String> = strategies.flatMap { (_, strat) ->
    (strat as? CompiledStrategy)?.declaredStreams?.values?.map { it.broker } ?: emptyList()
}.toSet().ifEmpty { setOf("BACKTEST") }

val routes: List<Pair<SymbolPattern, Broker>> = brokerPrefixes.map { prefix ->
    val symbolsForBroker = /* derive from declaredStreams */
    val pattern = SymbolPattern.exactSet(symbolsForBroker)
    pattern to PaperBroker(bus, clock, priceTracker)
}
val broker = CompositeBroker(routes = routes, bus = bus)
```

For non-DSL strategies (no `declaredStreams`), Backtest falls back to a single `PaperBroker` (no CompositeBroker wrapping). The two paths are explicit, not implicit.

### forEach expansion

```kotlin
fun StrategyBuilder.forEach(vararg streams: StreamRef, block: ForEachScope.(StreamRef) -> Unit) {
    val scope = ForEachScope(this)
    for (s in streams) scope.block(s)
}

class ForEachScope(private val builder: StrategyBuilder) {
    fun rule(block: RuleBuilder.() -> Unit) {
        // identical to StrategyBuilder.rule — adds expanded rule to builder
        val rb = RuleBuilder()
        rb.block()
        builder.addRule(rb.build())
    }
}
```

The `s: StreamRef` passed into the lambda is a literal stream reference. Inside the lambda, `s.close`, `s.fast`, `position(s)` etc. produce concrete AST referring to that specific alias. After `forEach` runs, the builder holds N independent `WhenThen` rules.

### SYMBOL placeholder

A new sentinel: `Ref(name = "__SYMBOL__")`. Helper in the Kotlin DSL:

```kotlin
val SYMBOL: ExprAst = Ref("__SYMBOL__")
```

`mergeDefaults` recursively walks every default-derived expression, replacing `Ref("__SYMBOL__")` with `Ref(alias)` *if the placeholder appears inside an indicator call's series argument the alias replacement becomes a `StreamFieldRef`* — for now the substitution rule is: when a `Ref("__SYMBOL__")` is the series argument of an `IndicatorCall`, substitute as `StreamFieldRef(alias, "candle")` (whole-stream form) since `ATR(SYMBOL, 14)` is the canonical use.

Outside `defaults`, `Ref("__SYMBOL__")` is rejected by `LetResolver.resolve`. Caught at compile time.

---

## File Structure

### New files

```
src/main/kotlin/com/qkt/dsl/compile/
├── CandleHub.kt                      # the shared hub
├── HubKey.kt                         # data class HubKey
└── ForEachExpansion.kt               # forEach macro builder

src/main/kotlin/com/qkt/dsl/kotlin/
└── SymbolPlaceholder.kt              # SYMBOL constant

src/test/kotlin/com/qkt/dsl/compile/
├── CandleHubTest.kt
├── ForEachExpansionTest.kt
├── SymbolPlaceholderTest.kt
├── AliasKeyedSnapshotsTest.kt
├── CrossStreamConditionTest.kt
├── MultiTimeframeEndToEndTest.kt
└── MultiBrokerEndToEndTest.kt
```

### Modified files

```
src/main/kotlin/com/qkt/dsl/compile/
├── EvalContext.kt                    # streams: Map<String, HubKey>; hub field
├── IndicatorBinding.kt               # read through hub when cross-alias
├── AggregateBinding.kt               # ruleSymbol → ruleAlias; bindingsForAlias
├── SnapshotStore.kt                  # symbol → alias
├── ExprCompiler.kt                   # streamSymbols → streams; cross-alias path
├── ActionCompiler.kt                 # streamSymbols → streams
├── ChildPriceResolver.kt             # streamSymbols → streams
├── OrderTypeCompiler.kt              # streamSymbols → streams
├── SizingCompiler.kt                 # streamSymbols → streams
├── DefaultsMerge.kt                  # SYMBOL substitution
├── AstCompiler.kt                    # hub registration; streams: Map<String, HubKey>
└── PositionTransitions.kt            # (unchanged — positions stay symbol-keyed)

src/main/kotlin/com/qkt/dsl/kotlin/
├── StrategyBuilder.kt                # forEach() helper
└── StreamRef.kt                      # gain broker getter

src/main/kotlin/com/qkt/app/
└── TradingPipeline.kt                # CandleHub wiring; per-tick feed

src/main/kotlin/com/qkt/backtest/
└── Backtest.kt                       # CompositeBroker construction

src/main/kotlin/com/qkt/marketdata/source/
└── SymbolPattern.kt                  # add exactSet(Set<String>) helper if missing

src/test/kotlin/com/qkt/dsl/compile/
├── ExprCompilerStateTest.kt          # streamSymbols → streams
├── SizingCompilerTest.kt             # streamSymbols → streams
├── ChildPriceResolverTest.kt         # streamSymbols → streams
├── OrderTypeCompilerTest.kt          # streamSymbols → streams
├── BracketCompileTest.kt             # streamSymbols → streams
├── OcoCompileTest.kt                 # streamSymbols → streams
├── OrderSurfaceEndToEndTest.kt       # streamSymbols → streams
└── DefaultsEndToEndTest.kt           # streamSymbols → streams
```

The rekey ripples through tests that hand-construct `EvalContext`. Boundary lock — refresh as part of Task 2.

---

## Tasks

### Task 1: CandleHub — shared, JIT-registered, forward-streaming

Build the hub class with register/feed/latest/history/onClosed. The hub is the foundational artifact 11e adds; everything else routes through it.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/HubKey.kt`
- Create: `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/CandleHubTest.kt`

- [ ] **Step 1: Cut branch**

```bash
git checkout -b phase11e-multistream
```

- [ ] **Step 2: Define `HubKey`**

```kotlin
package com.qkt.dsl.compile

data class HubKey(
    val broker: String,
    val symbol: String,
    val timeframe: String,
) {
    init {
        require(broker.isNotBlank()) { "HubKey.broker must not be blank" }
        require(symbol.isNotBlank()) { "HubKey.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "HubKey.timeframe must not be blank" }
    }
}
```

- [ ] **Step 3: Write the failing CandleHub test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CandleHubTest {
    private val key1m = HubKey("BYBIT", "BTCUSDT", "1m")
    private val key1h = HubKey("BYBIT", "BTCUSDT", "1h")

    private fun tick(symbol: String, ts: Long, price: String): Tick =
        Tick(symbol, BigDecimal(price), BigDecimal.ONE, ts)

    @Test
    fun `register then feed produces closed candles in history`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5)
        // emit ticks across two minute boundaries (60_000ms each)
        for (t in 0L..120_000L step 30_000L) {
            hub.feed(tick("BTCUSDT", t, "100"))
        }
        assertThat(hub.latest(key1m)).isNotNull
        assertThat(hub.history(key1m, 0)).isNotNull
    }

    @Test
    fun `register max wins when called twice`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5)
        hub.register(key1m, retention = 20)
        assertThat(hub.retention(key1m)).isEqualTo(20)
    }

    @Test
    fun `register after feed throws`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5)
        hub.feed(tick("BTCUSDT", 0L, "100"))
        assertThatThrownBy { hub.register(HubKey("BYBIT", "ETHUSDT", "1m"), retention = 5) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `same symbol two timeframes maintain independent histories`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 100)
        hub.register(key1h, retention = 100)
        // 90 minutes of ticks at 30s cadence
        for (t in 0L..(90L * 60_000L) step 30_000L) {
            hub.feed(tick("BTCUSDT", t, "100"))
        }
        // 1m had ~90 closes; 1h had ~1 close
        assertThat(hub.historySize(key1m)).isGreaterThan(60)
        assertThat(hub.historySize(key1h)).isLessThanOrEqualTo(2)
    }

    @Test
    fun `onClosed listener fires for each closed candle`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 10)
        val received = mutableListOf<Long>()
        hub.onClosed(key1m) { c -> received.add(c.endTimestamp) }
        for (t in 0L..180_000L step 30_000L) {
            hub.feed(tick("BTCUSDT", t, "100"))
        }
        assertThat(received).isNotEmpty
    }
}
```

- [ ] **Step 4: Run failing tests**

```bash
./gradlew test --tests com.qkt.dsl.compile.CandleHubTest
```

Expected: compilation failures (CandleHub doesn't exist).

- [ ] **Step 5: Implement CandleHub**

```kotlin
package com.qkt.dsl.compile

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

class CandleHub {
    private data class Slot(
        val aggregator: CandleAggregator,
        val ring: ArrayDeque<Candle>,
        var retention: Int,
        val listeners: MutableList<(Candle) -> Unit>,
    )

    private val slots: MutableMap<HubKey, Slot> = LinkedHashMap()
    private var feedStarted: Boolean = false

    fun register(key: HubKey, retention: Int) {
        require(retention >= 1) { "retention must be >= 1: $retention" }
        check(!feedStarted) { "CandleHub.register called after feed started: $key" }
        val existing = slots[key]
        if (existing != null) {
            existing.retention = maxOf(existing.retention, retention)
            return
        }
        val window = TimeWindow.parse(key.timeframe)
        // CandleAggregator that accumulates and emits closed candles via callback
        val ring = ArrayDeque<Candle>(retention)
        val listeners = mutableListOf<(Candle) -> Unit>()
        val agg = CandleAggregator.standalone(window) { closed ->
            ring.addLast(closed)
            while (ring.size > slots[key]!!.retention) ring.removeFirst()
            listeners.forEach { it(closed) }
        }
        slots[key] = Slot(agg, ring, retention, listeners)
    }

    fun feed(tick: Tick) {
        feedStarted = true
        for ((key, slot) in slots) {
            if (key.symbol == tick.symbol) slot.aggregator.onTick(tick)
        }
    }

    fun latest(key: HubKey): Candle? = slots[key]?.ring?.lastOrNull()

    fun history(key: HubKey, n: Int): Candle? {
        val ring = slots[key]?.ring ?: return null
        if (n < 0 || n >= ring.size) return null
        return ring[ring.size - 1 - n]
    }

    fun onClosed(key: HubKey, callback: (Candle) -> Unit) {
        val slot = slots[key] ?: error("onClosed: unknown key $key")
        slot.listeners.add(callback)
    }

    fun retention(key: HubKey): Int = slots[key]?.retention ?: 0
    fun historySize(key: HubKey): Int = slots[key]?.ring?.size ?: 0
    fun keys(): Set<HubKey> = slots.keys.toSet()
}
```

Note: `CandleAggregator.standalone(window, onClose)` is a new ctor variant — the existing `CandleAggregator` works on the bus; for the hub we need a standalone variant that publishes via callback. This is a small additive constructor on the existing class (or a wrapper).

- [ ] **Step 6: Add `CandleAggregator.standalone(...)` if needed**

Check the existing API:

```bash
grep -n "class CandleAggregator\|fun onTick\|fun aggregate\|init" src/main/kotlin/com/qkt/candles/CandleAggregator.kt
```

If `CandleAggregator` only exposes a bus-based API, add a standalone constructor that takes a `(Candle) -> Unit` callback. Implementation matches the bus version but emits via callback instead of `bus.publish(CandleEvent(...))`.

- [ ] **Step 7: Run tests**

```bash
./gradlew test --tests com.qkt.dsl.compile.CandleHubTest
```

Expected: PASS.

- [ ] **Step 8: ktlint format + commit**

```bash
./gradlew ktlintFormat
git add src/main/kotlin/com/qkt/dsl/compile/HubKey.kt \
        src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt \
        src/test/kotlin/com/qkt/dsl/compile/CandleHubTest.kt \
        src/main/kotlin/com/qkt/candles/CandleAggregator.kt   # if modified
git commit -m "feat(dsl): add shared CandleHub with JIT registration"
```

---

### Task 2: EvalContext — `streams: Map<String, HubKey>` and hub field

Replace the alias-to-symbol map with alias-to-HubKey, and thread the hub. This is the boundary-lock task — touches every test that hand-constructs `EvalContext` and every compiler that destructures it.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/EvalContext.kt`
- Modify: every callsite that constructs `EvalContext` (compilers + tests)

- [ ] **Step 1: Update EvalContext**

```kotlin
package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.strategy.StrategyContext

data class EvalContext(
    val candle: Candle,
    val streams: Map<String, HubKey>,
    val hub: CandleHub,
    val lets: Map<String, Value>,
    val strategyContext: StrategyContext,
    val snapshotStore: SnapshotStore = SnapshotStore(emptyMap()),
)
```

Migration helper for tests: a top-level `testEvalContext(...)` builder that creates a hub-less / single-stream EvalContext for unit-level tests that don't need the hub. The hub is required by the type but tests can pass a fresh `CandleHub()` registered with the relevant key.

- [ ] **Step 2: Update every compiler reading `streamSymbols`**

Mechanical: `ctx.streamSymbols[alias]` → `ctx.streams[alias]?.symbol`. Files:

- `ExprCompiler.kt` — `compilePositionRef`, `compileStateAccessor`, `compileStreamField`
- `ActionCompiler.kt` — stream resolution paths
- `ChildPriceResolver.kt`
- `OrderTypeCompiler.kt`
- `SizingCompiler.kt`
- `IndicatorBinding.kt` — `update(ctx)` `ctx.streamSymbols[streamAlias]` → `ctx.streams[streamAlias]?.symbol`

- [ ] **Step 3: Update tests that hand-construct EvalContext**

```kotlin
// Before
EvalContext(candle, streamSymbols = mapOf("btc" to "BTCUSDT"), ...)

// After
val hub = CandleHub().apply { register(HubKey("BACKTEST", "BTCUSDT", "1m"), 10) }
EvalContext(candle, streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")), hub = hub, ...)
```

Tests touched (all in `src/test/kotlin/com/qkt/dsl/compile/`):
- `ExprCompilerStateTest.kt`
- `SizingCompilerTest.kt`
- `ChildPriceResolverTest.kt`
- `OrderTypeCompilerTest.kt`
- `BracketCompileTest.kt`
- `OcoCompileTest.kt`
- `OrderSurfaceEndToEndTest.kt`
- `DefaultsEndToEndTest.kt`

The semantics are unchanged for single-stream tests — only the construction shape changes.

- [ ] **Step 4: Run full test suite**

```bash
./gradlew test
```

Expected: all green. If anything fails, the failure is a missed callsite — fix and re-run.

- [ ] **Step 5: ktlint + commit**

```bash
./gradlew ktlintFormat
git add src/main src/test
git commit -m "refactor(dsl): rekey EvalContext from streamSymbols to streams: HubKey"
```

---

### Task 3: Alias-keyed AggregateBinding and SnapshotStore

Rekey aggregates and snapshots from `symbol` to `alias`. Two streams referencing the same symbol with different timeframes get independent state.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AggregateBinding.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/SnapshotStore.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` (compileAggregate, compileRef)
- Create: `src/test/kotlin/com/qkt/dsl/compile/AliasKeyedSnapshotsTest.kt`

- [ ] **Step 1: Rename `AggregateBinding.ruleSymbol` → `ruleAlias`**

```kotlin
class AggregateBinding(
    val seriesEvaluator: CompiledExpr,
    val window: Window,
    val state: AggregateState,
    val ruleAlias: String,
) {
    // ...
    class Bag {
        // ...
        fun bindingsForAlias(alias: String): List<AggregateBinding> = list.filter { it.ruleAlias == alias }
    }
}
```

`AstCompiler` now calls `aggregates.bindingsForAlias(alias)` keyed by stream alias rather than symbol.

- [ ] **Step 2: Rekey SnapshotStore**

Mechanical rename on every method: `symbol: String` → `alias: String`. Internal map keys change shape: `Triple<String, String, SnapshotKind>` and `Pair<String, String>` are still strings, but they now mean alias.

- [ ] **Step 3: Update AstCompiler to thread alias**

Where AstCompiler did `symbol = streamSymbols[streamAlias]`, it now keeps the alias for state keys but uses the `HubKey.symbol` only when matching against `candle.symbol` (e.g. position transitions).

```kotlin
val ruleAlias: String = streamAliasFor(rule.action) ?: streams.keys.first()
val ruleSymbol: String = streams[ruleAlias]!!.symbol
// CompiledRule.ruleAlias = ruleAlias
// CompiledRule.ruleSymbol = ruleSymbol  (used for position transitions)
```

- [ ] **Step 4: Write the failing alias-keyed snapshot test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.SnapshotOpen
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AliasKeyedSnapshotsTest {
    @Test
    fun `same symbol two aliases maintain independent rolling history`() {
        val store = SnapshotStore(mapOf("fast" to 5))
        store.pushRolling("btc", "fast", BigDecimal("100"))
        store.pushRolling("btc_h1", "fast", BigDecimal("200"))
        store.pushRolling("btc", "fast", BigDecimal("110"))
        store.pushRolling("btc_h1", "fast", BigDecimal("210"))

        assertThat(store.readRolling("btc", "fast", 0)).isEqualByComparingTo("110")
        assertThat(store.readRolling("btc_h1", "fast", 0)).isEqualByComparingTo("210")
        assertThat(store.readRolling("btc", "fast", 1)).isEqualByComparingTo("100")
        assertThat(store.readRolling("btc_h1", "fast", 1)).isEqualByComparingTo("200")
    }

    @Test
    fun `same symbol two aliases maintain independent open snapshots`() {
        val store = SnapshotStore(emptyMap())
        store.captureSlot("btc", "fast", SnapshotOpen, BigDecimal("100"))
        store.captureSlot("btc_h1", "fast", SnapshotOpen, BigDecimal("999"))
        assertThat(store.readSlot("btc", "fast", SnapshotOpen)).isEqualByComparingTo("100")
        assertThat(store.readSlot("btc_h1", "fast", SnapshotOpen)).isEqualByComparingTo("999")
    }
}
```

- [ ] **Step 5: Run, expect pass after rekey**

```bash
./gradlew test --tests com.qkt.dsl.compile.AliasKeyedSnapshotsTest
```

- [ ] **Step 6: Run full suite**

```bash
./gradlew build
```

All previously-green tests stay green; new alias-keyed test passes.

- [ ] **Step 7: ktlint + commit**

```bash
./gradlew ktlintFormat
git add src/main src/test
git commit -m "refactor(dsl): alias-key aggregate and snapshot stores"
```

---

### Task 4: Cross-stream condition reads via hub

Wire `compileStreamField` to read from `hub.latest(key)` when the requested alias is not the current candle's symbol. Ensures `btc.close > gold.close * 50` evaluates regardless of whose tick is current.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` (`compileStreamField`)
- Modify: `src/main/kotlin/com/qkt/dsl/compile/IndicatorBinding.kt` (`update`)
- Create: `src/test/kotlin/com/qkt/dsl/compile/CrossStreamConditionTest.kt`

- [ ] **Step 1: Update compileStreamField**

```kotlin
private fun compileStreamField(ref: StreamFieldRef): CompiledExpr =
    CompiledExpr { ctx ->
        val key = ctx.streams[ref.stream] ?: error("Unknown stream alias: ${ref.stream}")
        val candle: Candle? =
            if (ctx.candle.symbol == key.symbol) ctx.candle
            else ctx.hub.latest(key)
        if (candle == null) {
            Value.Undefined
        } else {
            Value.Num(
                when (ref.field) {
                    "close", "price" -> candle.close
                    "open" -> candle.open
                    "high" -> candle.high
                    "low" -> candle.low
                    "volume" -> candle.volume
                    else -> error("unreachable")
                },
            )
        }
    }
```

- [ ] **Step 2: Update IndicatorBinding.update**

Indicators bound to a non-current alias should still update on each closed candle of that alias. The cleanest path: indicators don't update via `EvalContext.update` for cross-alias; instead the hub's `onClosed(key, ...)` callback drives indicator updates per alias.

Refactor: `IndicatorBinding.Bag` registers an `onClosed` listener per alias. When alias α closes a candle, the bag updates every indicator bound to α. The `updateAll(ctx)` per-rule-eval pass goes away — indicator updates are event-driven by hub closes.

- [ ] **Step 3: Write cross-stream test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CrossStreamConditionTest {
    @Test
    fun `cross-stream condition reads from hub when current candle is other symbol`() {
        val hub = CandleHub()
        val btcKey = HubKey("BYBIT", "BTCUSDT", "1m")
        val goldKey = HubKey("IB", "XAUUSD", "1m")
        hub.register(btcKey, 5)
        hub.register(goldKey, 5)

        // Feed gold tick that produces a closed candle for gold
        // (assuming TimeWindow.parse("1m") and feeding two ticks 60_000ms apart)

        // Now evaluate "btc.close > 100" on a candle where current symbol is gold.
        val goldCandle = Candle("XAUUSD", BigDecimal("2000"), BigDecimal("2000"),
            BigDecimal("2000"), BigDecimal("2000"), BigDecimal.ZERO, 0L, 60_000L)
        val ec = EvalContext(
            candle = goldCandle,
            streams = mapOf("btc" to btcKey, "gold" to goldKey),
            hub = hub,
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
        // No btc closes yet → cross-stream read returns Undefined
        val expr = CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("100")))
        val v = ExprCompiler().compile(expr).evaluate(ec)
        assertThat(v).isInstanceOf(Value.Undefined::class.java)
    }
}
```

A second test exercises the path *after* btc has a closed candle; assert `Value.Bool(true)`.

- [ ] **Step 4: Run, expect pass**

```bash
./gradlew test --tests com.qkt.dsl.compile.CrossStreamConditionTest
```

- [ ] **Step 5: ktlint + commit**

```bash
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): cross-stream conditions read closed candles via hub"
```

---

### Task 5: Pipeline + Backtest wire CandleHub

`TradingPipeline` constructs and owns the `CandleHub`. Per-tick: pipeline calls `hub.feed(tick)` before any strategy is dispatched. DSL strategies subscribe to `hub.onClosed(key, ...)` for each registered key.

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` (registers keys with hub at compile time)
- Modify: `src/main/kotlin/com/qkt/backtest/Backtest.kt`

- [ ] **Step 1: Add `CandleHub` to TradingPipeline**

```kotlin
class TradingPipeline(
    // ... existing params ...
    val candleHub: CandleHub = CandleHub(),
) {
    // ...
    fun ingest(tick: Tick) {
        candleHub.feed(tick)
        // ... rest of existing ingest body
    }
}
```

- [ ] **Step 2: Expose hub registration from AstCompiler**

`AstCompiler.compile(ast, hub)` becomes the canonical entry point — strategies register their `(broker, symbol, timeframe)` keys with the hub, with retention computed from snapshot plan + max aggregate window. Compile time:

```kotlin
class AstCompiler(private val hub: CandleHub) {
    fun compile(ast: StrategyAst): Strategy {
        val streams: Map<String, HubKey> = ast.streams.associate {
            it.alias to HubKey(it.broker, it.symbol, it.timeframe)
        }
        val retentionByKey: Map<HubKey, Int> = computeRetention(ast)
        for ((key, retention) in retentionByKey) hub.register(key, retention)
        // ... rest of compilation ...
        return CompiledStrategy(streams = streams, hub = hub, ...)
    }
}
```

`computeRetention` is a helper: `max(rolling N for @T-N, max(SinceTPast.n)) + 1`.

- [ ] **Step 3: Pipeline `ingest` feeds the hub before dispatch**

`TradingPipeline.ingest(tick)`:

```kotlin
fun ingest(tick: Tick) {
    candleHub.feed(tick)
    priceTracker.update(tick.symbol, tick.price)
    bus.publish(TickEvent(tick))
    if (candleWindow != null) {
        // legacy single-aggregator path for non-DSL strategies
    }
}
```

For DSL strategies that subscribe via `hub.onClosed`, candle-driven rule eval is event-fired by the hub callback instead of by `bus.subscribe<CandleEvent>`. Bridge: `CompiledStrategy.onCandle(...)` receives `CandleEvent` from the legacy aggregator if present; in the new path, the hub's `onClosed` invokes `CompiledStrategy.evaluate(alias, candle, ctx)` directly.

This is the most invasive change in 11e. Care: keep the legacy `Strategy.onCandle(candle, ctx, emit)` path alive for hand-written non-DSL strategies; DSL strategies bypass it.

- [ ] **Step 4: Backtest passes the hub through**

```kotlin
val candleHub = CandleHub()
val pipeline = TradingPipeline(
    // ... existing args ...
    candleHub = candleHub,
)
```

- [ ] **Step 5: Run full suite**

```bash
./gradlew build
```

All existing tests should still pass — the hub plumbing is additive; legacy paths still work.

- [ ] **Step 6: ktlint + commit**

```bash
./gradlew ktlintFormat
git add src/main
git commit -m "feat(dsl): wire CandleHub into TradingPipeline and Backtest"
```

---

### Task 6: Multi-timeframe end-to-end test

A strategy declaring `btc 1m` and `btc_h1 1h` runs against a deterministic tick fixture and produces predictable trades.

**Files:**
- Create: `src/test/kotlin/com/qkt/dsl/compile/MultiTimeframeEndToEndTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.dsl.kotlin.strategy
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt
import com.qkt.dsl.kotlin.and
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MultiTimeframeEndToEndTest {
    @Test
    fun `btc 1m and btc_h1 1h coexist with independent indicator histories`() {
        val ast = strategy(name = "mtf", version = 1) {
            val btc    = stream("btc",    broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
            val btc_h1 = stream("btc_h1", broker = "BACKTEST", symbol = "BTCUSDT", every = "1h")

            rule {
                whenever(btc.close gt BigDecimal("105").bd and (btc_h1.close gt BigDecimal("100").bd))
                then { buy(btc, sizing = qty(BigDecimal.ONE)) }
            }
        }
        // Fixture: 90 minutes of ticks. btc_h1 closes at the 60-minute mark.
        // Build deterministic ticks with prices crossing 105 after the first hour.
        val ticks: List<Tick> = (0..(90 * 60)).map { sec ->
            Tick("BTCUSDT", BigDecimal(100 + (sec / 60)), BigDecimal.ONE, sec * 1_000L)
        }
        val backtest = Backtest(
            strategies = listOf("mtf" to AstCompiler(/*hub built inside*/).compile(ast)),
            ticks = ticks,
            candleWindow = TimeWindow.parse("1m"),
            initialTimestamp = 0L,
        )
        val result = backtest.run()
        assertThat(result.trades).isNotEmpty
        // Specific count check based on how the rule fires:
        // first eligible 1m close after the 1h boundary
    }
}
```

(Specific assertions tuned during execution against the deterministic fixture.)

- [ ] **Step 2: Run**

```bash
./gradlew test --tests com.qkt.dsl.compile.MultiTimeframeEndToEndTest
```

- [ ] **Step 3: ktlint + commit**

```bash
./gradlew ktlintFormat
git add src/test
git commit -m "test(dsl): multi-timeframe e2e with btc 1m and btc_h1 1h"
```

---

### Task 7: forEach macro

Builder-time AST rewrite. `forEach(a, b, c) { s → rule(...) }` produces N independent `WhenThen` rules.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/ForEachExpansion.kt` *(if any standalone helpers)*
- Modify: `src/main/kotlin/com/qkt/dsl/kotlin/StrategyBuilder.kt`
- Create: `src/test/kotlin/com/qkt/dsl/compile/ForEachExpansionTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.kotlin.strategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForEachExpansionTest {
    @Test
    fun `forEach expands to one rule per stream`() {
        val ast = strategy("fe", 1) {
            val a = stream("a", "BACKTEST", "AAA", "1m")
            val b = stream("b", "BACKTEST", "BBB", "1m")
            val c = stream("c", "BACKTEST", "CCC", "1m")
            forEach(a, b, c) { s ->
                rule {
                    whenever(s.close gt 100.bd)
                    then { buy(s) }
                }
            }
        }
        assertThat(ast.rules).hasSize(3)
        val streams = ast.rules.map { (it as WhenThen).action }.map { (it as Buy).stream }
        assertThat(streams).containsExactly("a", "b", "c")
    }

    @Test
    fun `forEach result equals hand-expanded rules`() {
        val expanded = strategy("fe", 1) {
            val a = stream("a", "BACKTEST", "AAA", "1m")
            val b = stream("b", "BACKTEST", "BBB", "1m")
            forEach(a, b) { s ->
                rule { whenever(s.close gt 100.bd); then { buy(s) } }
            }
        }
        val handwritten = strategy("fe", 1) {
            val a = stream("a", "BACKTEST", "AAA", "1m")
            val b = stream("b", "BACKTEST", "BBB", "1m")
            rule { whenever(a.close gt 100.bd); then { buy(a) } }
            rule { whenever(b.close gt 100.bd); then { buy(b) } }
        }
        assertThat(expanded.rules).isEqualTo(handwritten.rules)
    }
}
```

- [ ] **Step 2: Implement forEach**

In `StrategyBuilder`:

```kotlin
fun forEach(vararg streams: StreamRef, block: ForEachScope.(StreamRef) -> Unit) {
    val scope = ForEachScope(this)
    for (s in streams) scope.block(s)
}

@QktDsl
class ForEachScope(private val builder: StrategyBuilder) {
    fun rule(block: RuleBuilder.() -> Unit) {
        val rb = RuleBuilder()
        rb.block()
        builder.addRule(rb.build())
    }
}
```

- [ ] **Step 3: Run, ktlint, commit**

```bash
./gradlew test --tests com.qkt.dsl.compile.ForEachExpansionTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): forEach macro expands per stream at builder time"
```

---

### Task 8: SYMBOL placeholder substitution

Bare `SYMBOL` inside `defaults` substitutes per-rule at merge time.

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/kotlin/SymbolPlaceholder.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/DefaultsMerge.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/LetResolver.kt` *(reject placeholder outside defaults)*
- Create: `src/test/kotlin/com/qkt/dsl/compile/SymbolPlaceholderTest.kt`

- [ ] **Step 1: Define the placeholder constant**

```kotlin
package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.Ref

const val SYMBOL_PLACEHOLDER_NAME = "__SYMBOL__"
val SYMBOL: com.qkt.dsl.ast.ExprAst = Ref(SYMBOL_PLACEHOLDER_NAME)
```

- [ ] **Step 2: Substitution helper**

In `DefaultsMerge.kt`:

```kotlin
internal fun substituteSymbol(expr: ExprAst, alias: String): ExprAst = when (expr) {
    is Ref -> if (expr.name == SYMBOL_PLACEHOLDER_NAME) StreamFieldRef(alias, "candle") else expr
    is BinaryOp -> expr.copy(lhs = substituteSymbol(expr.lhs, alias), rhs = substituteSymbol(expr.rhs, alias))
    is CmpOp -> expr.copy(lhs = substituteSymbol(expr.lhs, alias), rhs = substituteSymbol(expr.rhs, alias))
    is UnaryOp -> expr.copy(arg = substituteSymbol(expr.arg, alias))
    is IndicatorCall -> expr.copy(args = expr.args.map { substituteSymbol(it, alias) })
    is FuncCall -> expr.copy(args = expr.args.map { substituteSymbol(it, alias) })
    is Between -> expr.copy(v = substituteSymbol(expr.v, alias), lo = substituteSymbol(expr.lo, alias), hi = substituteSymbol(expr.hi, alias))
    // ... other ExprAst variants pass-through if no children
    else -> expr
}
```

`mergeDefaults` calls `substituteSymbol(expr, ruleAlias)` on every default-derived expression before installing it on the action.

- [ ] **Step 3: LetResolver rejects placeholder outside defaults**

```kotlin
class LetResolver(...) {
    fun resolve(expr: ExprAst): ExprAst = when (expr) {
        is Ref -> {
            if (expr.name == SYMBOL_PLACEHOLDER_NAME)
                error("SYMBOL placeholder is only valid inside DEFAULTS block")
            // ... existing logic
        }
        // ... existing branches
    }
}
```

- [ ] **Step 4: Write tests**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.kotlin.SYMBOL
import com.qkt.dsl.kotlin.strategy
import com.qkt.dsl.kotlin.atr
import com.qkt.dsl.kotlin.childBy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SymbolPlaceholderTest {
    @Test
    fun `SYMBOL in defaults stop loss substitutes per rule alias`() {
        val ast = strategy("sp", 1) {
            val btc  = stream("btc",  "BYBIT", "BTCUSDT", "1m")
            val gold = stream("gold", "IB",    "XAUUSD",  "1m")
            defaults {
                stopLoss = childBy(atr(SYMBOL, 14))
            }
            rule { whenever(btc.close gt 100.bd); then { buy(btc) } }
            rule { whenever(gold.close gt 1000.bd); then { buy(gold) } }
        }
        // After AST compilation, defaults are merged per rule. Inspect the resulting
        // bracket on each Buy: the ATR call's series arg is the rule's stream.
        // (Detailed structural check.)
    }

    @Test
    fun `SYMBOL outside defaults is rejected`() {
        // ... assertThatThrownBy on a strategy that uses SYMBOL in a rule whenever ...
    }
}
```

- [ ] **Step 5: Run, ktlint, commit**

```bash
./gradlew test --tests com.qkt.dsl.compile.SymbolPlaceholderTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(dsl): SYMBOL placeholder substitutes per rule alias in DEFAULTS"
```

---

### Task 9: CompositeBroker wired into Backtest

Backtest builds a `CompositeBroker` from the strategy's declared brokers. One `PaperBroker` per prefix; routing by symbol.

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/Backtest.kt`
- Modify: `src/main/kotlin/com/qkt/marketdata/source/SymbolPattern.kt` *(add `exactSet` if missing)*
- Create: `src/test/kotlin/com/qkt/dsl/compile/MultiBrokerEndToEndTest.kt`

- [ ] **Step 1: Add `SymbolPattern.exactSet(...)` helper if needed**

Check existing API:

```bash
grep -n "fun matches\|class SymbolPattern\|object SymbolPattern" src/main/kotlin/com/qkt/marketdata/source/SymbolPattern.kt
```

Add if missing:

```kotlin
fun SymbolPattern.Companion.exactSet(symbols: Set<String>): SymbolPattern =
    SymbolPattern { sym -> sym in symbols }
```

- [ ] **Step 2: Expose declared streams from CompiledStrategy**

`CompiledStrategy` gains a `declaredStreams: Map<String, HubKey>` property. `Backtest` reads it to build the route table.

- [ ] **Step 3: Refactor Backtest broker construction**

```kotlin
fun run(): BacktestResult {
    // ... existing setup ...

    val candleHub = CandleHub()
    // Compile strategies first so they can register hub keys
    val compiled: List<Pair<String, Strategy>> = strategies // already compiled by caller

    val declaredStreams: Map<String, HubKey> =
        compiled.flatMap { (_, s) -> (s as? CompiledStrategy)?.declaredStreams?.values ?: emptyList() }
            .associateBy { it.symbol }

    val broker: Broker = if (declaredStreams.isEmpty()) {
        PaperBroker(bus, clock, priceTracker)
    } else {
        val routes: List<Pair<SymbolPattern, Broker>> =
            declaredStreams.values.groupBy { it.broker }.map { (brokerName, keys) ->
                SymbolPattern.exactSet(keys.map { it.symbol }.toSet()) to PaperBroker(bus, clock, priceTracker)
            }
        CompositeBroker(routes = routes, bus = bus)
    }
    // ... rest of run() unchanged ...
}
```

- [ ] **Step 4: Multi-broker end-to-end test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.dsl.kotlin.strategy
import com.qkt.dsl.kotlin.gt
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MultiBrokerEndToEndTest {
    @Test
    fun `three brokers route correctly via CompositeBroker`() {
        val ast = strategy("mb", 1) {
            val btc  = stream("btc",  "BYBIT", "BTCUSDT", "1m")
            val gold = stream("gold", "IB",    "XAUUSD",  "1m")
            val aapl = stream("aapl", "ALPACA","AAPL",    "1m")
            forEach(btc, gold, aapl) { s ->
                rule { whenever(s.close gt 0.bd); then { buy(s) } }
            }
        }
        val ticks: List<Tick> = listOf(
            Tick("BTCUSDT", BigDecimal("100"), BigDecimal.ONE, 0L),
            Tick("XAUUSD", BigDecimal("2000"), BigDecimal.ONE, 30_000L),
            Tick("AAPL", BigDecimal("150"), BigDecimal.ONE, 60_000L),
            Tick("BTCUSDT", BigDecimal("100"), BigDecimal.ONE, 65_000L),
            Tick("XAUUSD", BigDecimal("2000"), BigDecimal.ONE, 90_000L),
            Tick("AAPL", BigDecimal("150"), BigDecimal.ONE, 120_000L),
        )
        val hub = CandleHub()
        val compiled = AstCompiler(hub).compile(ast)
        val result = Backtest(
            strategies = listOf("mb" to compiled),
            ticks = ticks,
            candleWindow = TimeWindow.parse("1m"),
        ).run()
        assertThat(result.trades.map { it.trade.symbol }).contains("BTCUSDT", "XAUUSD", "AAPL")
    }
}
```

- [ ] **Step 5: Run + commit**

```bash
./gradlew test --tests com.qkt.dsl.compile.MultiBrokerEndToEndTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(backtest): wire CompositeBroker as single broker seam"
```

---

### Task 10: Phase 11e changelog

User-facing changelog under `docs/phases/`. Per qkt convention: summary, what's new, migration notes, usage cookbook with worked examples, testing patterns, known limitations, references.

**Files:**
- Create: `docs/phases/phase-11e-multistream.md`

- [ ] **Step 1: Write the changelog**

Sections (fill in during execution, with the actual examples that worked in Tasks 6 and 9):

1. Summary (3 sentences).
2. What's new (bullet list of every new public type / capability).
3. Migration from 11d2 (rekey notes for any external callers).
4. Usage cookbook:
   - Multi-stream single-broker (`btc 1m` + `btc_h1 1h`).
   - Multi-broker (BYBIT BTC + IB XAUUSD + ALPACA AAPL).
   - `forEach` cross-asset entry rule.
   - `defaults { ... SYMBOL ... }`.
   - Cross-stream condition (`btc.close > gold.close * 50`).
   - Cross-timeframe (`btc.fast` + `btc_h1.fast` in one rule).
5. Testing patterns (CandleHub, hand-constructed `EvalContext`, multi-broker e2e).
6. Known limitations:
   - Live multi-broker integration deferred.
   - Hub does not service range queries (use `MarketSource`).
   - `GTD` TIF, `CANCEL`/`CANCEL_ALL`, `ACCOUNT.drawdown` still deferred.
   - One-strategy-per-file unchanged.
7. References (spec, plan, merge commit SHA).

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-11e-multistream.md
git commit -m "docs: phase 11e changelog"
```

---

## Self-review checklist

After all tasks complete:

- [ ] `./gradlew build` green.
- [ ] All commit messages match `<type>(<scope>): <subject>` per qkt SKILL.md §3.
- [ ] No `Co-Authored-By` footers, no AI attributions, no emoji.
- [ ] No `// removed` comments left from refactor.
- [ ] No leftover TODO without a tracking issue.
- [ ] Phase 11e changelog `Merge commit:` line filled in after merge.
- [ ] Spec and plan committed alongside code.

---

## Merge

After all tasks pass:

```bash
git checkout main
git merge --no-ff phase11e-multistream -m "merge: phase 11e multistream and multibroker"
./gradlew build   # verify
# Update changelog with merge SHA
git add docs/phases/phase-11e-multistream.md
git commit -m "docs: link phase 11e changelog to merge commit"
git branch -d phase11e-multistream
```
